package org.dcsa.bkg.service.impl;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.dcsa.bkg.model.transferobjects.BookingCancellationRequestTO;
import org.dcsa.bkg.service.BKGService;
import org.dcsa.core.events.edocumentation.model.mapper.*;
import org.dcsa.core.events.edocumentation.model.transferobject.*;
import org.dcsa.core.events.edocumentation.repository.*;
import org.dcsa.core.events.model.*;
import org.dcsa.core.events.model.enums.DocumentTypeCode;
import org.dcsa.core.events.model.enums.EventClassifierCode;
import org.dcsa.core.events.model.enums.ShipmentEventTypeCode;
import org.dcsa.core.events.model.enums.TransportEventTypeCode;
import org.dcsa.core.events.model.mapper.LocationMapper;
import org.dcsa.core.events.model.mapper.RequestedEquipmentMapper;
import org.dcsa.core.events.model.transferobjects.*;
import org.dcsa.core.events.repository.*;
import org.dcsa.core.events.service.*;
import org.dcsa.core.exception.ConcreteRequestErrorMessageException;
import org.dcsa.core.exception.CreateException;
import org.dcsa.core.exception.NotFoundException;
import org.dcsa.core.exception.UpdateException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class BKGServiceImpl implements BKGService {

  // repositories
  private final BookingRepository bookingRepository;
  private final LocationRepository locationRepository;
  private final CommodityRepository commodityRepository;
  private final ValueAddedServiceRequestRepository valueAddedServiceRequestRepository;
  private final ReferenceRepository referenceRepository;
  private final RequestedEquipmentRepository requestedEquipmentRepository;
  private final DocumentPartyRepository documentPartyRepository;
  private final DocumentPartyService documentPartyService;
  private final PartyService partyService;
  private final PartyContactDetailsRepository partyContactDetailsRepository;
  private final ShipmentLocationRepository shipmentLocationRepository;
  private final DisplayedAddressRepository displayedAddressRepository;
  private final ShipmentCutOffTimeRepository shipmentCutOffTimeRepository;
  private final ShipmentRepository shipmentRepository;
  private final VesselRepository vesselRepository;
  private final ShipmentCarrierClausesRepository shipmentCarrierClausesRepository;
  private final CarrierClauseRepository carrierClauseRepository;
  private final ChargeRepository chargeRepository;
  private final TransportRepository transportRepository;
  private final ShipmentTransportRepository shipmentTransportRepository;
  private final TransportCallRepository transportCallRepository;
  private final TransportEventRepository transportEventRepository;
  private final ModeOfTransportRepository modeOfTransportRepository;
  private final VoyageRepository voyageRepository;
  private final RequestedEquipmentEquipmentRepository requestedEquipmentEquipmentRepository;

  // mappers
  private final BookingMapper bookingMapper;
  private final LocationMapper locationMapper;
  private final CommodityMapper commodityMapper;
  private final ShipmentMapper shipmentMapper;
  private final CarrierClauseMapper carrierClauseMapper;
  private final ConfirmedEquipmentMapper confirmedEquipmentMapper;
  private final ChargeMapper chargeMapper;
  private final TransportMapper transportMapper;
  private final RequestedEquipmentMapper requestedEquipmentMapper;

  // services
  private final ShipmentEventService shipmentEventService;
  private final LocationService locationService;
  private final AddressService addressService;

  @Override
  @Transactional
  public Mono<BookingResponseTO> createBooking(final BookingTO bookingRequest) {

    String bookingRequestError = validateBookingRequest(bookingRequest);
    if (!bookingRequestError.isEmpty()) {
      return Mono.error(new CreateException(bookingRequestError));
    }

    OffsetDateTime now = OffsetDateTime.now();
    Booking requestedBooking = bookingMapper.dtoToBooking(bookingRequest);
    // CarrierBookingRequestReference is not allowed to be set by request
    requestedBooking.setCarrierBookingRequestReference(null);
    requestedBooking.setDocumentStatus(ShipmentEventTypeCode.RECE);
    requestedBooking.setBookingRequestDateTime(now);
    requestedBooking.setUpdatedDateTime(now);

    return bookingRepository
        .save(requestedBooking)
        .flatMap(
            bookingRefreshed ->
                // have to refresh booking as the default values created for columns in booking (for
                // example, in this case
                // `carrier_booking_request_reference`) are not being updated on the model on save.
                // Also prefer setting the uuid value at db level and not application level to avoid
                // collisions
                bookingRepository.findById(bookingRefreshed.getId()))
        .flatMap(
            booking -> {
              final UUID bookingID = booking.getId();

              return Mono.zip(
                      findVesselAndUpdateBooking(
                              bookingRequest.getVesselName(),
                              bookingRequest.getVesselIMONumber(),
                              bookingID)
                          .flatMap(
                              v -> {
                                BookingTO bookingTO = bookingToDTOWithNullLocations(booking);
                                bookingTO.setVesselName(v.getVesselName());
                                bookingTO.setVesselIMONumber(v.getVesselIMONumber());
                                return Mono.just(bookingTO);
                              }),
                      optionalInMono(
                          locationService.createLocationByTO(
                              bookingRequest.getInvoicePayableAt(),
                              invPayAT ->
                                  bookingRepository.setInvoicePayableAtFor(invPayAT, bookingID))),
                      optionalInMono(
                          locationService.createLocationByTO(
                              bookingRequest.getPlaceOfIssue(),
                              placeOfIss ->
                                  bookingRepository.setPlaceOfIssueIDFor(placeOfIss, bookingID))),
                      createCommoditiesByBookingIDAndTOs(
                          bookingID, bookingRequest.getCommodities()),
                      createValueAddedServiceRequestsByBookingIDAndTOs(
                          bookingID, bookingRequest.getValueAddedServiceRequests()),
                      createReferencesByBookingIDAndTOs(bookingID, bookingRequest.getReferences()),
                      createRequestedEquipmentsByBookingIDAndTOs(
                          bookingID, bookingRequest.getRequestedEquipments()),
                      optionalInMono(
                          documentPartyService.createDocumentPartiesByBookingID(
                              bookingID, bookingRequest.getDocumentParties())))
                  .zipWith(
                      createShipmentLocationsByBookingIDAndTOs(
                          bookingID, bookingRequest.getShipmentLocations()));
            })
        .flatMap(
            t -> {
              BookingTO bookingTO = t.getT1().getT1();
              Optional<LocationTO> invoicePayableAtOpt = t.getT1().getT2();
              Optional<LocationTO> placeOfIssueOpt = t.getT1().getT3();
              Optional<List<CommodityTO>> commoditiesOpt = t.getT1().getT4();
              Optional<List<ValueAddedServiceRequestTO>> valueAddedServiceRequestsOpt =
                  t.getT1().getT5();
              Optional<List<ReferenceTO>> referencesOpt = t.getT1().getT6();
              List<RequestedEquipmentTO> requestedEquipmentsOpt = t.getT1().getT7();
              Optional<List<DocumentPartyTO>> documentPartiesOpt = t.getT1().getT8();
              Optional<List<ShipmentLocationTO>> shipmentLocationsOpt = t.getT2();

              // populate the booking DTO
              invoicePayableAtOpt.ifPresent(bookingTO::setInvoicePayableAt);
              placeOfIssueOpt.ifPresent(bookingTO::setPlaceOfIssue);
              commoditiesOpt.ifPresent(bookingTO::setCommodities);
              valueAddedServiceRequestsOpt.ifPresent(bookingTO::setValueAddedServiceRequests);
              referencesOpt.ifPresent(bookingTO::setReferences);
              bookingTO.setRequestedEquipments(requestedEquipmentsOpt);
              documentPartiesOpt.ifPresent(bookingTO::setDocumentParties);
              shipmentLocationsOpt.ifPresent(bookingTO::setShipmentLocations);

              return Mono.just(bookingTO);
            })
        .flatMap(bTO -> createShipmentEventFromBookingTO(bTO).thenReturn(bTO))
        .flatMap(bTO -> Mono.just(bookingMapper.dtoToBookingResponseTO(bTO)));
  }

  private BookingTO bookingToDTOWithNullLocations(Booking booking) {
    BookingTO bookingTO = bookingMapper.bookingToDTO(booking);
    // the mapper creates a new instance of location even if value of invoicePayableAt is null in
    // booking hence we set it to null if it's a null object
    bookingTO.setInvoicePayableAt(null);
    bookingTO.setPlaceOfIssue(null);
    return bookingTO;
  }

  // Booking should not create a vessel, only use existing ones.
  // If the requested vessel does not exist or the values don't match
  // an error should be thrown
  private Mono<Vessel> findVesselAndUpdateBooking(
      String vesselName, String vesselIMONumber, UUID bookingID) {

    Vessel vessel = new Vessel();
    vessel.setVesselName(vesselName);
    vessel.setVesselIMONumber(vesselIMONumber);

    if (!StringUtils.isEmpty(vesselIMONumber)) {
      return vesselRepository
          .findByVesselIMONumberOrEmpty(vesselIMONumber)
          .flatMap(
              v -> {
                if (!vesselName.equals(v.getVesselName())) {
                  return Mono.error(
                      new CreateException(
                          "Provided vessel name does not match vessel name of existing vesselIMONumber."));
                }
                return Mono.just(v);
              })
          .flatMap(v -> bookingRepository.setVesselIDFor(v.getId(), bookingID).thenReturn(v));
    } else if (!StringUtils.isEmpty(vesselName)) {
      return vesselRepository
          .findByVesselNameOrEmpty(vesselName)
          .collectList()
          .flatMap(
              vs -> {
                if (vs.size() > 1) {
                  return Mono.error(
                      new CreateException(
                          "Unable to identify unique vessel, please provide a vesselIMONumber."));
                }
                return Mono.just(vs.get(0));
              })
          .flatMap(v -> bookingRepository.setVesselIDFor(v.getId(), bookingID).thenReturn(v));
    } else {
      return Mono.just(new Vessel());
    }
  }

  /**
   * TODO This is a hack. But I preferred to simply remove duplicate code and not re-writing it at
   * the same time - https://dcsa.atlassian.net/browse/DDT-975 should address this.
   */
  private <T> Mono<Optional<T>> optionalInMono(Mono<T> original) {
    return original.map(Optional::of).switchIfEmpty(Mono.just(Optional.empty()));
  }

  private Mono<Optional<List<CommodityTO>>> createCommoditiesByBookingIDAndTOs(
      UUID bookingID, List<CommodityTO> commodities) {

    if (Objects.isNull(commodities) || commodities.isEmpty()) {
      return Mono.just(Optional.of(Collections.emptyList()));
    }

    Stream<Commodity> commodityStream =
        commodities.stream()
            .map(
                c -> {
                  Commodity commodity = commodityMapper.dtoToCommodity(c);
                  commodity.setBookingID(bookingID);
                  return commodity;
                });

    return commodityRepository
        .saveAll(Flux.fromStream(commodityStream))
        .map(commodityMapper::commodityToDTO)
        .collectList()
        .map(Optional::of);
  }

  private Mono<Optional<List<ValueAddedServiceRequestTO>>>
      createValueAddedServiceRequestsByBookingIDAndTOs(
          UUID bookingID, List<ValueAddedServiceRequestTO> valueAddedServiceRequests) {

    if (Objects.isNull(valueAddedServiceRequests) || valueAddedServiceRequests.isEmpty()) {
      return Mono.just(Optional.of(Collections.emptyList()));
    }

    Stream<ValueAddedServiceRequest> vasrStream =
        valueAddedServiceRequests.stream()
            .map(
                vasr -> {
                  ValueAddedServiceRequest valueAddedServiceRequest =
                      new ValueAddedServiceRequest();
                  valueAddedServiceRequest.setBookingID(bookingID);
                  valueAddedServiceRequest.setValueAddedServiceCode(
                      vasr.getValueAddedServiceCode());
                  return valueAddedServiceRequest;
                });

    return valueAddedServiceRequestRepository
        .saveAll(Flux.fromStream(vasrStream))
        .map(
            savedVasr -> {
              ValueAddedServiceRequestTO valueAddedServiceRequestTO =
                  new ValueAddedServiceRequestTO();
              valueAddedServiceRequestTO.setValueAddedServiceCode(
                  savedVasr.getValueAddedServiceCode());
              return valueAddedServiceRequestTO;
            })
        .collectList()
        .map(Optional::of);
  }

  private Mono<Optional<List<ReferenceTO>>> createReferencesByBookingIDAndTOs(
      UUID bookingID, List<ReferenceTO> references) {

    if (Objects.isNull(references) || references.isEmpty()) {
      return Mono.just(Optional.of(Collections.emptyList()));
    }

    Stream<Reference> referenceStream =
        references.stream()
            .map(
                r -> {
                  Reference reference = new Reference();
                  reference.setBookingID(bookingID);
                  reference.setReferenceType(r.getReferenceType());
                  reference.setReferenceValue(r.getReferenceValue());
                  return reference;
                });

    return referenceRepository
        .saveAll(Flux.fromStream(referenceStream))
        .map(
            r -> {
              ReferenceTO referenceTO = new ReferenceTO();
              referenceTO.setReferenceType(r.getReferenceType());
              referenceTO.setReferenceValue(r.getReferenceValue());
              return referenceTO;
            })
        .collectList()
        .map(Optional::of);
  }

  private Mono<List<RequestedEquipmentTO>> createRequestedEquipmentsByBookingIDAndTOs(
      UUID bookingID, List<RequestedEquipmentTO> requestedEquipments) {

    if (Objects.isNull(requestedEquipments) || requestedEquipments.isEmpty()) {
      return Mono.justOrEmpty(Collections.emptyList());
    }

    return Flux.fromIterable(requestedEquipments)
        .filter(this::isValidRequestedEquipmentTO)
        .switchIfEmpty(
            Mono.error(
                ConcreteRequestErrorMessageException.invalidParameter(
                    "Requested Equipment Units cannot be lower than quantity of Equipment References.")))
        .flatMap(
            requestedEquipmentTO ->
                saveRequestedEquipmentAndEquipmentReferences(bookingID, requestedEquipmentTO))
        .collectList();
  }

  private Mono<RequestedEquipmentTO> saveRequestedEquipmentAndEquipmentReferences(
      UUID bookingID, RequestedEquipmentTO requestedEquipmentTO) {
    List<String> equipmentReferences = requestedEquipmentTO.getEquipmentReferences();
    return Mono.just(
            requestedEquipmentMapper.dtoToRequestedEquipmentWithBookingId(
                requestedEquipmentTO, bookingID))
        .flatMap(requestedEquipmentRepository::save)
        .filter(requestedEquipment -> Objects.nonNull(equipmentReferences))
        .flatMap(
            requestedEquipment ->
                mapAndSaveRequestedEquipmentEquipment(requestedEquipment, equipmentReferences))
        .thenReturn(requestedEquipmentTO);
  }

  private Mono<List<RequestedEquipmentEquipment>> mapAndSaveRequestedEquipmentEquipment(
      RequestedEquipment requestedEquipment, List<String> equipmentReferences) {
    return Flux.fromIterable(equipmentReferences)
        .flatMap(
            equipmentReference ->
                mapRequestedEquipmentToRequestedEquipmentEquipment(
                    requestedEquipment, equipmentReference))
        .collectList();
  }

  private Mono<RequestedEquipmentEquipment> mapRequestedEquipmentToRequestedEquipmentEquipment(
      RequestedEquipment requestedEquipment, String equipmentReference) {
    RequestedEquipmentEquipment requestedEquipmentEquipment = new RequestedEquipmentEquipment();
    requestedEquipmentEquipment.setRequestedEquipmentId(requestedEquipment.getId());
    requestedEquipmentEquipment.setEquipmentReference(equipmentReference);
    return requestedEquipmentEquipmentRepository.save(requestedEquipmentEquipment);
  }

  private Boolean isValidRequestedEquipmentTO(RequestedEquipmentTO requestedEquipmentTO) {
    Boolean isValid = true;
    if (Objects.nonNull(requestedEquipmentTO.getEquipmentReferences())) {
      isValid =
          requestedEquipmentTO.getEquipmentReferences().size()
              <= requestedEquipmentTO.getRequestedEquipmentUnits();
    }
    return isValid;
  }

  private Mono<Optional<List<ShipmentLocationTO>>> createShipmentLocationsByBookingIDAndTOs(
      final UUID bookingID, List<ShipmentLocationTO> shipmentLocations) {

    if (Objects.isNull(shipmentLocations) || shipmentLocations.isEmpty()) {
      return Mono.just(Optional.of(Collections.emptyList()));
    }

    return Flux.fromStream(shipmentLocations.stream())
        .flatMap(
            slTO -> {
              ShipmentLocation shipmentLocation = new ShipmentLocation();
              shipmentLocation.setBookingID(bookingID);
              shipmentLocation.setShipmentLocationTypeCode(slTO.getShipmentLocationTypeCode());
              shipmentLocation.setDisplayedName(slTO.getDisplayedName());
              shipmentLocation.setEventDateTime(slTO.getEventDateTime());

              Location location = locationMapper.dtoToLocation(slTO.getLocation());

              if (Objects.isNull(slTO.getLocation().getAddress())) {
                return locationRepository
                    .save(location)
                    .map(
                        l -> {
                          LocationTO lTO = locationMapper.locationToDTO(l);
                          shipmentLocation.setLocationID(l.getId());
                          return Tuples.of(lTO, shipmentLocation);
                        });
              } else {
                return addressService
                    .ensureResolvable(slTO.getLocation().getAddress())
                    .flatMap(
                        a -> {
                          location.setAddressID(a.getId());
                          return locationRepository
                              .save(location)
                              .map(
                                  l -> {
                                    LocationTO lTO = locationMapper.locationToDTO(l);
                                    lTO.setAddress(a);
                                    shipmentLocation.setLocationID(l.getId());
                                    return Tuples.of(lTO, shipmentLocation);
                                  });
                        });
              }
            })
        .flatMap(
            t ->
                shipmentLocationRepository
                    .save(t.getT2())
                    .map(
                        savedSl -> {
                          ShipmentLocationTO shipmentLocationTO = new ShipmentLocationTO();
                          shipmentLocationTO.setLocation(t.getT1());
                          shipmentLocationTO.setShipmentLocationTypeCode(
                              savedSl.getShipmentLocationTypeCode());
                          shipmentLocationTO.setDisplayedName(savedSl.getDisplayedName());
                          shipmentLocationTO.setEventDateTime(savedSl.getEventDateTime());
                          return shipmentLocationTO;
                        }))
        .collectList()
        .map(Optional::of);
  }

  @Override
  @Transactional
  public Mono<BookingResponseTO> updateBookingByReferenceCarrierBookingRequestReference(
      String carrierBookingRequestReference, BookingTO bookingRequest) {

    String bookingRequestError = validateBookingRequest(bookingRequest);
    if (!bookingRequestError.isEmpty()) {
      return Mono.error(new CreateException(bookingRequestError));
    }

    return bookingRepository
        .findByCarrierBookingRequestReference(carrierBookingRequestReference)
        .flatMap(checkUpdateBookingStatus)
        .flatMap(
            b -> {
              // update booking with new booking request
              Booking booking = bookingMapper.dtoToBooking(bookingRequest);
              booking.setId(b.getId());
              booking.setDocumentStatus(b.getDocumentStatus());
              booking.setBookingRequestDateTime(b.getBookingRequestDateTime());
              booking.setUpdatedDateTime(OffsetDateTime.now());
              return bookingRepository.save(booking).thenReturn(b);
            })
        .flatMap(
            b ->
                // resolve entities linked to booking
                Mono.zip(
                        findVesselAndUpdateBooking(
                                bookingRequest.getVesselName(),
                                bookingRequest.getVesselIMONumber(),
                                b.getId())
                            .flatMap(
                                v -> {
                                  BookingTO bookingTO = bookingToDTOWithNullLocations(b);
                                  bookingTO.setVesselName(v.getVesselName());
                                  bookingTO.setVesselIMONumber(v.getVesselIMONumber());
                                  return Mono.just(bookingTO);
                                }),
                        optionalInMono(
                            locationService.resolveLocationByTO(
                                b.getInvoicePayableAt(),
                                bookingRequest.getInvoicePayableAt(),
                                invPayAT ->
                                    bookingRepository.setInvoicePayableAtFor(invPayAT, b.getId()))),
                        optionalInMono(
                            locationService.resolveLocationByTO(
                                b.getPlaceOfIssueID(),
                                bookingRequest.getPlaceOfIssue(),
                                placeOfIss ->
                                    bookingRepository.setPlaceOfIssueIDFor(placeOfIss, b.getId()))),
                        resolveCommoditiesForBookingID(bookingRequest.getCommodities(), b.getId()),
                        resolveValueAddedServiceReqForBookingID(
                            bookingRequest.getValueAddedServiceRequests(), b.getId()),
                        resolveReferencesForBookingID(bookingRequest.getReferences(), b.getId()),
                        resolveReqEqForBookingID(
                            bookingRequest.getRequestedEquipments(), b.getId()),
                        resolveDocumentPartiesForBookingID(
                            bookingRequest.getDocumentParties(), b.getId()))
                    .zipWith(
                        resolveShipmentLocationsForBookingID(
                            bookingRequest.getShipmentLocations(), b.getId())))
        .flatMap(
            t -> {
              BookingTO bookingTO = t.getT1().getT1();
              Optional<LocationTO> invoicePayableAtOpt = t.getT1().getT2();
              Optional<LocationTO> placeOfIssueOpt = t.getT1().getT3();
              Optional<List<CommodityTO>> commoditiesOpt = t.getT1().getT4();
              Optional<List<ValueAddedServiceRequestTO>> valueAddedServiceRequestsOpt =
                  t.getT1().getT5();
              Optional<List<ReferenceTO>> referencesOpt = t.getT1().getT6();
              List<RequestedEquipmentTO> requestedEquipmentsOpt = t.getT1().getT7();
              Optional<List<DocumentPartyTO>> documentPartiesOpt = t.getT1().getT8();
              Optional<List<ShipmentLocationTO>> shipmentLocationsOpt = t.getT2();

              // populate the booking DTO
              invoicePayableAtOpt.ifPresent(bookingTO::setInvoicePayableAt);
              placeOfIssueOpt.ifPresent(bookingTO::setPlaceOfIssue);
              commoditiesOpt.ifPresent(bookingTO::setCommodities);
              valueAddedServiceRequestsOpt.ifPresent(bookingTO::setValueAddedServiceRequests);
              referencesOpt.ifPresent(bookingTO::setReferences);
              bookingTO.setRequestedEquipments(requestedEquipmentsOpt);
              documentPartiesOpt.ifPresent(bookingTO::setDocumentParties);
              shipmentLocationsOpt.ifPresent(bookingTO::setShipmentLocations);

              return Mono.just(bookingTO);
            })
        .flatMap(bTO -> createShipmentEventFromBookingTO(bTO).thenReturn(bTO))
        .switchIfEmpty(
            Mono.defer(
                () ->
                    Mono.error(
                        new UpdateException(
                            "No booking found for given carrierBookingRequestReference."))))
        .flatMap(bTO -> Mono.just(bookingMapper.dtoToBookingResponseTO(bTO)));
  }

  private Mono<Optional<List<CommodityTO>>> resolveCommoditiesForBookingID(
      List<CommodityTO> commodities, UUID bookingID) {

    return commodityRepository
        .deleteByBookingID(bookingID)
        .then(createCommoditiesByBookingIDAndTOs(bookingID, commodities));
  }

  private Mono<Optional<List<ValueAddedServiceRequestTO>>> resolveValueAddedServiceReqForBookingID(
      List<ValueAddedServiceRequestTO> valAddedSerReqs, UUID bookingID) {

    return valueAddedServiceRequestRepository
        .deleteByBookingID(bookingID)
        .then(createValueAddedServiceRequestsByBookingIDAndTOs(bookingID, valAddedSerReqs));
  }

  private Mono<Optional<List<ReferenceTO>>> resolveReferencesForBookingID(
      List<ReferenceTO> references, UUID bookingID) {

    return referenceRepository
        .deleteByBookingID(bookingID)
        .then(createReferencesByBookingIDAndTOs(bookingID, references));
  }

  private Mono<List<RequestedEquipmentTO>> resolveReqEqForBookingID(
      List<RequestedEquipmentTO> requestedEquipments, UUID bookingID) {

    return requestedEquipmentRepository
        .deleteByBookingID(bookingID)
        .then(requestedEquipmentEquipmentRepository.deleteByBookingId(bookingID))
        .then(createRequestedEquipmentsByBookingIDAndTOs(bookingID, requestedEquipments));
  }

  private Mono<Optional<List<DocumentPartyTO>>> resolveDocumentPartiesForBookingID(
      List<DocumentPartyTO> documentPartyTOs, UUID bookingID) {

    // this will create orphan parties
    return documentPartyRepository
        .deleteByBookingID(bookingID)
        .then(
            optionalInMono(
                documentPartyService.createDocumentPartiesByBookingID(
                    bookingID, documentPartyTOs)));
  }

  private Mono<Optional<List<ShipmentLocationTO>>> resolveShipmentLocationsForBookingID(
      List<ShipmentLocationTO> shipmentLocationTOs, UUID bookingID) {

    // this will create orphan locations
    return shipmentLocationRepository
        .deleteByBookingID(bookingID)
        .then(createShipmentLocationsByBookingIDAndTOs(bookingID, shipmentLocationTOs));
  }

  @Override
  public Mono<BookingTO> getBookingByCarrierBookingRequestReference(
      String carrierBookingRequestReference) {
    return bookingRepository
        .findByCarrierBookingRequestReference(carrierBookingRequestReference)
        .map(b -> Tuples.of(b.getId(), bookingMapper.bookingToDTO(b), b))
        .switchIfEmpty(
            Mono.error(
                new NotFoundException(
                    "No booking found with carrier booking request reference: "
                        + carrierBookingRequestReference)))
        .doOnSuccess(
            t -> {
              // the mapper creates a new instance of location even if value of invoicePayableAt
              // is null in booking hence we set it to null if it's a null object
              if (t.getT2().getInvoicePayableAt().getId() == null) {
                t.getT2().setInvoicePayableAt(null);
              }
              if (t.getT2().getPlaceOfIssue().getId() == null) {
                t.getT2().setPlaceOfIssue(null);
              }
            })
        .flatMap(
            t -> {
              BookingTO bookingTO = t.getT2();

              String invoicePayableAtLocID =
                  Objects.isNull(bookingTO.getInvoicePayableAt())
                      ? null
                      : bookingTO.getInvoicePayableAt().getId();

              String placeOfIssueLocID =
                  Objects.isNull(bookingTO.getPlaceOfIssue())
                      ? null
                      : bookingTO.getPlaceOfIssue().getId();

              return Mono.zip(
                      fetchLocationTupleByID(invoicePayableAtLocID, placeOfIssueLocID),
                      fetchVesselByVesselID(t.getT3().getVesselId()),
                      fetchCommoditiesByBookingID(t.getT1()),
                      fetchValueAddedServiceRequestsByBookingID(t.getT1()),
                      fetchReferencesByBookingID(t.getT1()),
                      fetchRequestedEquipmentsByBookingID(t.getT1()),
                      fetchDocumentPartiesByBookingID(t.getT1()),
                      fetchShipmentLocationsByBookingID(t.getT1()))
                  .doOnSuccess(
                      deepObjs -> {
                        Optional<LocationTO> locationToOpt1 = deepObjs.getT1().getT1();
                        Optional<LocationTO> locationToOpt2 = deepObjs.getT1().getT2();
                        Optional<Vessel> vesselOptional = deepObjs.getT2();
                        Optional<List<CommodityTO>> commoditiesToOpt = deepObjs.getT3();
                        Optional<List<ValueAddedServiceRequestTO>> valueAddedServiceRequestsToOpt =
                            deepObjs.getT4();
                        Optional<List<ReferenceTO>> referenceTOsOpt = deepObjs.getT5();
                        Optional<List<RequestedEquipmentTO>> requestedEquipmentsToOpt =
                            deepObjs.getT6();
                        Optional<List<DocumentPartyTO>> documentPartiesToOpt = deepObjs.getT7();
                        Optional<List<ShipmentLocationTO>> shipmentLocationsToOpt =
                            deepObjs.getT8();

                        locationToOpt1.ifPresent(bookingTO::setInvoicePayableAt);
                        locationToOpt2.ifPresent(bookingTO::setPlaceOfIssue);
                        vesselOptional.ifPresent(
                            x -> {
                              bookingTO.setVesselName(x.getVesselName());

                              bookingTO.setVesselIMONumber(x.getVesselIMONumber());
                            });
                        commoditiesToOpt.ifPresent(bookingTO::setCommodities);
                        valueAddedServiceRequestsToOpt.ifPresent(
                            bookingTO::setValueAddedServiceRequests);
                        referenceTOsOpt.ifPresent(bookingTO::setReferences);
                        requestedEquipmentsToOpt.ifPresent(bookingTO::setRequestedEquipments);
                        documentPartiesToOpt.ifPresent(bookingTO::setDocumentParties);
                        shipmentLocationsToOpt.ifPresent(bookingTO::setShipmentLocations);
                      })
                  .thenReturn(bookingTO);
            });
  }

  @Override
  public Mono<ShipmentTO> getShipmentByCarrierBookingReference(
      String carrierBookingRequestReference) {
    return shipmentRepository
        .findByCarrierBookingReference(carrierBookingRequestReference)
        .switchIfEmpty(
            Mono.error(
                new NotFoundException(
                    "No booking found with carrier booking reference: "
                        + carrierBookingRequestReference)))
        .map(b -> Tuples.of(b, shipmentMapper.shipmentToDTO(b)))
        .flatMap(
            t -> {
              ShipmentTO shipmentTO = t.getT2();
              return Mono.zip(
                      fetchShipmentCutOffTimeByBookingID(t.getT1().getShipmentID()),
                      fetchShipmentLocationsByBookingID(t.getT1().getBookingID()),
                      fetchCarrierClausesByShipmentID(t.getT1().getShipmentID()),
                      fetchConfirmedEquipmentByByBookingID(t.getT1().getBookingID()),
                      fetchChargesByShipmentID(t.getT1().getShipmentID()),
                      fetchBookingByBookingID(t.getT1().getBookingID()),
                      fetchTransports(t.getT1().getShipmentID()))
                  .flatMap(
                      deepObjs -> {
                        Optional<List<ShipmentCutOffTimeTO>> shipmentCutOffTimeTOpt =
                            deepObjs.getT1();
                        Optional<List<ShipmentLocationTO>> shipmentLocationsToOpt =
                            deepObjs.getT2();
                        Optional<List<CarrierClauseTO>> carrierClauseToOpt = deepObjs.getT3();
                        Optional<List<ConfirmedEquipmentTO>> confirmedEquipmentTOOpt =
                            deepObjs.getT4();
                        Optional<List<ChargeTO>> chargesToOpt = deepObjs.getT5();
                        Optional<BookingTO> bookingToOpt = deepObjs.getT6();
                        Optional<List<TransportTO>> transportsToOpt = deepObjs.getT7();

                        shipmentCutOffTimeTOpt.ifPresent(shipmentTO::setShipmentCutOffTimes);
                        shipmentLocationsToOpt.ifPresent(shipmentTO::setShipmentLocations);
                        carrierClauseToOpt.ifPresent(shipmentTO::setCarrierClauses);
                        confirmedEquipmentTOOpt.ifPresent(shipmentTO::setConfirmedEquipments);
                        chargesToOpt.ifPresent(shipmentTO::setCharges);
                        bookingToOpt.ifPresent(shipmentTO::setBooking);
                        transportsToOpt.ifPresent(shipmentTO::setTransports);
                        return Mono.just(shipmentTO);
                      })
                  .thenReturn(shipmentTO);
            });
  }

  private Mono<Tuple2<Optional<LocationTO>, Optional<LocationTO>>> fetchLocationTupleByID(
      String invoicePayableAtLocID, String placeOfIssueLocID) {
    return Mono.zip(
            optionalInMono(locationService.fetchLocationDeepObjByID(invoicePayableAtLocID)),
            optionalInMono(locationService.fetchLocationDeepObjByID(placeOfIssueLocID)))
        .map(deepObjs -> Tuples.of(deepObjs.getT1(), deepObjs.getT2()));
  }

  private Mono<Optional<LocationTO>> fetchLocationByTransportCallId(String id) {
    if (id == null) return Mono.just(Optional.empty());
    return transportCallRepository
        .findById(id)
        .flatMap(
            transportCall ->
                optionalInMono(
                    locationService.fetchLocationDeepObjByID(transportCall.getLocationID())));
  }

  private Mono<Optional<List<CarrierClauseTO>>> fetchCarrierClausesByShipmentID(UUID shipmentID) {
    if (shipmentID == null) return Mono.just(Optional.empty());
    return shipmentCarrierClausesRepository
        .findAllByShipmentID(shipmentID)
        .flatMap(
            shipmentCarrierClause ->
                carrierClauseRepository.findById(shipmentCarrierClause.getCarrierClauseID()))
        .flatMap(x -> Mono.just(carrierClauseMapper.carrierClauseToDTO(x)))
        .collectList()
        .map(Optional::of);
  }

  private Mono<Optional<List<CommodityTO>>> fetchCommoditiesByBookingID(UUID bookingID) {
    return commodityRepository
        .findByBookingID(bookingID)
        .map(commodityMapper::commodityToDTO)
        .collectList()
        .map(Optional::of);
  }

  private Mono<Optional<List<ChargeTO>>> fetchChargesByShipmentID(UUID shipmentID) {
    return chargeRepository
        .findAllByShipmentID(shipmentID)
        .map(chargeMapper::chargeToDTO)
        .collectList()
        .map(Optional::of);
  }

  private Mono<Optional<BookingTO>> fetchBookingByBookingID(UUID bookingID) {
    if (bookingID == null) return Mono.just(Optional.empty());
    return bookingRepository
        .findById(bookingID)
        .flatMap(
            booking ->
                Mono.zip(
                        optionalInMono(
                            locationService.fetchLocationDeepObjByID(
                                booking.getInvoicePayableAt())),
                        optionalInMono(
                            locationService.fetchLocationDeepObjByID(booking.getPlaceOfIssueID())),
                        fetchCommoditiesByBookingID(booking.getId()),
                        fetchValueAddedServiceRequestsByBookingID(booking.getId()),
                        fetchReferencesByBookingID(booking.getId()),
                        fetchRequestedEquipmentsByBookingID(booking.getId()),
                        fetchDocumentPartiesByBookingID(booking.getId()),
                        fetchShipmentLocationsByBookingID(booking.getId()))
                    .flatMap(
                        t2 -> {
                          BookingTO bookingTO = bookingMapper.bookingToDTO(booking);
                          t2.getT1().ifPresent(bookingTO::setInvoicePayableAt);
                          t2.getT2().ifPresent(bookingTO::setPlaceOfIssue);
                          t2.getT3().ifPresent(bookingTO::setCommodities);
                          t2.getT4().ifPresent(bookingTO::setValueAddedServiceRequests);
                          t2.getT5().ifPresent(bookingTO::setReferences);
                          t2.getT6().ifPresent(bookingTO::setRequestedEquipments);
                          t2.getT7().ifPresent(bookingTO::setDocumentParties);
                          t2.getT8().ifPresent(bookingTO::setShipmentLocations);
                          return Mono.just(bookingTO);
                        }))
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty());
  }

  private Mono<Optional<List<ShipmentCutOffTimeTO>>> fetchShipmentCutOffTimeByBookingID(
      UUID shipmentID) {
    return shipmentCutOffTimeRepository
        .findAllByShipmentID(shipmentID)
        .map(shipmentMapper::shipmentCutOffTimeToDTO)
        .collectList()
        .map(Optional::of);
  }

  private Mono<Optional<List<ValueAddedServiceRequestTO>>>
      fetchValueAddedServiceRequestsByBookingID(UUID bookingID) {
    return valueAddedServiceRequestRepository
        .findByBookingID(bookingID)
        .map(
            vasr -> {
              ValueAddedServiceRequestTO vTo = new ValueAddedServiceRequestTO();
              vTo.setValueAddedServiceCode(vasr.getValueAddedServiceCode());
              return vTo;
            })
        .collectList()
        .map(Optional::of);
  }

  private Mono<Optional<List<ReferenceTO>>> fetchReferencesByBookingID(UUID bookingID) {
    return referenceRepository
        .findByBookingID(bookingID)
        .map(
            r -> {
              ReferenceTO referenceTO = new ReferenceTO();
              referenceTO.setReferenceType(r.getReferenceType());
              referenceTO.setReferenceValue(r.getReferenceValue());
              return referenceTO;
            })
        .collectList()
        .map(Optional::of);
  }

  private Mono<Optional<List<RequestedEquipmentTO>>> fetchRequestedEquipmentsByBookingID(
      UUID bookingId) {
    return requestedEquipmentRepository
        .findByBookingID(bookingId)
        .map(
            re -> {
              RequestedEquipmentTO requestedEquipmentTO = new RequestedEquipmentTO();
              requestedEquipmentTO.setRequestedEquipmentUnits(re.getRequestedEquipmentUnits());
              requestedEquipmentTO.setRequestedEquipmentSizetype(
                  re.getRequestedEquipmentSizetype());
              requestedEquipmentTO.setShipperOwned(re.getIsShipperOwned());
              return requestedEquipmentTO;
            })
        .collectList()
        .map(Optional::of);
  }

  private Mono<Optional<List<DocumentPartyTO>>> fetchDocumentPartiesByBookingID(UUID bookingId) {
    if (bookingId == null) return Mono.empty();
    return documentPartyRepository
        .findByBookingID(bookingId)
        .flatMap(
            dp ->
                Mono.zip(
                        optionalInMono(partyService.findTOById(dp.getPartyID())),
                        fetchDisplayAddressByDocumentID(dp.getId()))
                    .flatMap(
                        t -> {
                          Optional<PartyTO> partyToOpt = t.getT1();
                          Optional<List<String>> displayAddressOpt = t.getT2();

                          DocumentPartyTO documentPartyTO = new DocumentPartyTO();
                          partyToOpt.ifPresent(documentPartyTO::setParty);
                          documentPartyTO.setPartyFunction(dp.getPartyFunction());
                          documentPartyTO.setIsToBeNotified(dp.getIsToBeNotified());
                          displayAddressOpt.ifPresent(documentPartyTO::setDisplayedAddress);
                          return Mono.just(documentPartyTO);
                        }))
        .collectList()
        .map(Optional::of);
  }

  private Mono<Optional<List<PartyContactDetailsTO>>> fetchPartyContactDetailsByPartyID(
      String partyID) {
    return partyContactDetailsRepository
        .findByPartyID(partyID)
        .map(pcd -> new PartyContactDetailsTO(pcd.getName(), pcd.getEmail(), pcd.getPhone()))
        .collectList()
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty());
  }

  private Mono<Optional<List<String>>> fetchDisplayAddressByDocumentID(UUID documentPartyID) {
    return displayedAddressRepository
        .findByDocumentPartyIDOrderByAddressLineNumber(documentPartyID)
        .map(DisplayedAddress::getAddressLine)
        .collectList()
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty());
  }

  private Mono<Optional<List<ShipmentLocationTO>>> fetchShipmentLocationsByBookingID(
      UUID bookingID) {
    if (bookingID == null) return Mono.just(Optional.empty());
    return shipmentLocationRepository
        .findByBookingID(bookingID)
        .flatMap(
            sl ->
                optionalInMono(locationService.fetchLocationDeepObjByID(sl.getLocationID()))
                    .flatMap(
                        lopt -> {
                          ShipmentLocationTO shipmentLocationTO =
                              shipmentMapper.shipmentLocationToDTO(sl);
                          lopt.ifPresent(shipmentLocationTO::setLocation);
                          return Mono.just(shipmentLocationTO);
                        }))
        .collectList()
        .map(Optional::of);
  }

  private Mono<Optional<List<ConfirmedEquipmentTO>>> fetchConfirmedEquipmentByByBookingID(
      UUID bookingID) {
    return requestedEquipmentRepository
        .findByBookingID(bookingID)
        .map(confirmedEquipmentMapper::requestedEquipmentToDto)
        .collectList()
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty());
  }

  private Mono<Optional<Tuple2<TransportEvent, TransportEvent>>> fetchTransportEventByTransportId(
      UUID transportId) {
    return transportRepository
        .findById(transportId)
        .flatMap(
            x ->
                Mono.zip(
                        transportEventRepository
                            .findFirstByTransportCallIDAndEventTypeCodeAndEventClassifierCodeOrderByEventDateTimeDesc(
                                x.getLoadTransportCallID(),
                                TransportEventTypeCode.ARRI,
                                EventClassifierCode.PLN),
                        transportEventRepository
                            .findFirstByTransportCallIDAndEventTypeCodeAndEventClassifierCodeOrderByEventDateTimeDesc(
                                x.getDischargeTransportCallID(),
                                TransportEventTypeCode.DEPA,
                                EventClassifierCode.PLN))
                    .flatMap(y -> Mono.just(Tuples.of(y.getT1(), y.getT2()))))
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty());
  }

  private Mono<Optional<Vessel>> fetchVesselByTransportCallId(String transportCallId) {

    if (transportCallId == null) return Mono.just(Optional.empty());
    return transportCallRepository
        .findById(transportCallId)
        .flatMap(
            x -> {
              if (x.getVesselID() == null) {
                return Mono.empty();
              }
              return vesselRepository.findById(x.getVesselID());
            })
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty());
  }

  private Mono<TransportCall> fetchTransportCallById(String transportCallId) {
    if (transportCallId == null) return Mono.empty();
    return transportCallRepository.findById(transportCallId);
  }

  private Mono<Optional<Map<String, String>>> fetchImportExportVoyageNumberByTransportCallId(
      TransportCall transportCall) {
    if (transportCall == null) return Mono.just(Optional.empty());
    if (transportCall.getImportVoyageID() == null) return Mono.just(Optional.empty());

    return voyageRepository
        .findById(transportCall.getImportVoyageID())
        .flatMap(
            voyage -> {
              Mono<Voyage> exportVoyage;
              if (!transportCall.getExportVoyageID().equals(transportCall.getImportVoyageID())) {
                exportVoyage = voyageRepository.findById(transportCall.getExportVoyageID());
              } else {
                exportVoyage = Mono.just(voyage);
              }
              return Mono.zip(Mono.just(voyage), exportVoyage);
            })
        .map(
            voyages ->
                Map.of(
                    "importVoyageNumber",
                    voyages.getT1().getCarrierVoyageNumber(),
                    "exportVoyageNumber",
                    voyages.getT2().getCarrierVoyageNumber()))
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty());
  }

  private Mono<Optional<Vessel>> fetchVesselByVesselID(UUID vesselID) {
    if (vesselID == null) return Mono.just(Optional.empty());
    return vesselRepository.findById(vesselID).map(Optional::of).defaultIfEmpty(Optional.empty());
  }

  private Mono<Optional<ModeOfTransport>> fetchModeOfTransportByTransportCallId(
      String transportCallId) {
    if (transportCallId == null) return Mono.just(Optional.empty());
    return modeOfTransportRepository
        .findByTransportCallID(transportCallId)
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty());
  }

  private Mono<Optional<List<TransportTO>>> fetchTransports(UUID shipmentId) {
    return shipmentTransportRepository
        .findAllByShipmentID(shipmentId)
        .flatMap(
            shipmentTransport ->
                transportRepository
                    .findAllById(List.of(shipmentTransport.getTransportID()))
                    .flatMap(
                        transport ->
                            Mono.zip(
                                Mono.just(transport),
                                fetchTransportCallById(transport.getLoadTransportCallID())))
                    .flatMap(
                        transportAndTransportCall ->
                            Mono.zip(
                                    fetchTransportEventByTransportId(
                                        transportAndTransportCall.getT1().getTransportID()),
                                    fetchLocationByTransportCallId(
                                        transportAndTransportCall.getT1().getLoadTransportCallID()),
                                    fetchLocationByTransportCallId(
                                        transportAndTransportCall
                                            .getT1()
                                            .getDischargeTransportCallID()),
                                    fetchModeOfTransportByTransportCallId(
                                        transportAndTransportCall.getT1().getLoadTransportCallID()),
                                    fetchVesselByTransportCallId(
                                        transportAndTransportCall.getT2().getTransportCallID()),
                                    fetchImportExportVoyageNumberByTransportCallId(
                                        transportAndTransportCall.getT2()))
                                .map(
                                    x -> {
                                      Transport transport = transportAndTransportCall.getT1();
                                      TransportTO transportTO =
                                          transportMapper.transportToDTO(transport);
                                      transportTO.setTransportPlanStage(
                                          shipmentTransport.getTransportPlanStageCode());
                                      transportTO.setTransportPlanStageSequenceNumber(
                                          shipmentTransport.getTransportPlanStageSequenceNumber());
                                      transportTO.setTransportName(transport.getTransportName());
                                      transportTO.setTransportReference(
                                          transport.getTransportReference());
                                      transportTO.setIsUnderShippersResponsibility(
                                          shipmentTransport.getIsUnderShippersResponsibility());

                                      x.getT1()
                                          .ifPresent(
                                              t1 -> {
                                                transportTO.setPlannedDepartureDate(
                                                    t1.getT1().getEventDateTime());
                                                transportTO.setPlannedArrivalDate(
                                                    t1.getT2().getEventDateTime());
                                              });

                                      x.getT2().ifPresent(transportTO::setLoadLocation);
                                      x.getT3().ifPresent(transportTO::setDischargeLocation);

                                      x.getT4()
                                          .ifPresent(
                                              t4 ->
                                                  transportTO.setModeOfTransport(
                                                      t4.getDcsaTransportType()));
                                      x.getT5()
                                          .ifPresent(
                                              t5 -> {
                                                transportTO.setVesselName(t5.getVesselName());
                                                transportTO.setVesselIMONumber(
                                                    t5.getVesselIMONumber());
                                              });

                                      x.getT6()
                                          .ifPresent(
                                              carrierVoyageNumberMap -> {
                                                transportTO.setImportVoyageNumber(
                                                    carrierVoyageNumberMap.get(
                                                        "importVoyageNumber"));
                                                transportTO.setExportVoyageNumber(
                                                    carrierVoyageNumberMap.get(
                                                        "exportVoyageNumber"));
                                              });
                                      return transportTO;
                                    })))
        .collectList()
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty());
  }

  @Override
  @Transactional
  public Mono<BookingResponseTO> cancelBookingByCarrierBookingReference(
      String carrierBookingRequestReference,
      BookingCancellationRequestTO bookingCancellationRequestTO) {
    OffsetDateTime updatedDateTime = OffsetDateTime.now();
    return bookingRepository
        .findByCarrierBookingRequestReference(carrierBookingRequestReference)
        .switchIfEmpty(
            Mono.error(
                new UpdateException("No Booking found with: ." + carrierBookingRequestReference)))
        .flatMap(checkCancelBookingStatus)
        .flatMap(
            booking ->
                bookingRepository
                    .updateDocumentStatusAndUpdatedDateTimeForCarrierBookingRequestReference(
                        bookingCancellationRequestTO.getDocumentStatus(),
                        carrierBookingRequestReference,
                        updatedDateTime)
                    .flatMap(verifyCancellation)
                    .thenReturn(booking))
        .map(
            booking -> {
              booking.setDocumentStatus(ShipmentEventTypeCode.CANC);
              return booking;
            })
        .flatMap(
            booking ->
                createShipmentEventFromBookingCancellation(booking, bookingCancellationRequestTO)
                    .thenReturn(booking))
        .map(
            booking -> {
              BookingResponseTO response = new BookingResponseTO();
              response.setBookingRequestCreatedDateTime(booking.getBookingRequestDateTime());
              response.setBookingRequestUpdatedDateTime(updatedDateTime);
              response.setDocumentStatus(booking.getDocumentStatus());
              response.setCarrierBookingRequestReference(
                  booking.getCarrierBookingRequestReference());
              return response;
            });
  }

  private Mono<ShipmentEvent> createShipmentEventFromBookingCancellation(
      Booking booking, BookingCancellationRequestTO bookingCancellationRequestTO) {
    return shipmentEventFromBooking(booking, bookingCancellationRequestTO.getReason())
        .flatMap(shipmentEventService::create)
        .switchIfEmpty(
            Mono.error(new CreateException("Failed to create shipment event for Booking.")));
  }

  private Mono<ShipmentEvent> createShipmentEventFromBookingTO(BookingTO bookingTo) {
    return createShipmentEvent(shipmentEventFromBooking(bookingMapper.dtoToBooking(bookingTo)));
  }

  private Mono<ShipmentEvent> createShipmentEvent(Mono<ShipmentEvent> shipmentEventMono) {
    return shipmentEventMono
        .flatMap(shipmentEventService::create)
        .switchIfEmpty(
            Mono.error(new CreateException("Failed to create shipment event for Booking.")));
  }

  private Mono<ShipmentEvent> shipmentEventFromBooking(Booking booking) {
    return shipmentEventFromBooking(booking, null);
  }

  private Mono<ShipmentEvent> shipmentEventFromBooking(Booking booking, String reason) {
    ShipmentEvent shipmentEvent = new ShipmentEvent();
    shipmentEvent.setShipmentEventTypeCode(
        ShipmentEventTypeCode.valueOf(booking.getDocumentStatus().name()));
    shipmentEvent.setDocumentTypeCode(DocumentTypeCode.CBR);
    shipmentEvent.setEventClassifierCode(EventClassifierCode.ACT);
    shipmentEvent.setEventType(null);
    shipmentEvent.setDocumentID(booking.getCarrierBookingRequestReference());
    shipmentEvent.setEventDateTime(booking.getUpdatedDateTime());
    shipmentEvent.setEventCreatedDateTime(OffsetDateTime.now());
    shipmentEvent.setReason(reason);
    return Mono.just(shipmentEvent);
  }

  private String validateBookingRequest(BookingTO bookingRequest) {
    if (bookingRequest.getIsImportLicenseRequired()
        && bookingRequest.getImportLicenseReference() == null) {
      return "The attribute importLicenseReference cannot be null if isImportLicenseRequired is true.";
    }

    if (bookingRequest.getIsExportDeclarationRequired()
        && bookingRequest.getExportDeclarationReference() == null) {
      return "The attribute exportDeclarationReference cannot be null if isExportDeclarationRequired is true.";
    }

    if (bookingRequest.getExpectedArrivalDateStart() == null
        && bookingRequest.getExpectedArrivalDateEnd() == null
        && bookingRequest.getExpectedDepartureDate() == null
        && bookingRequest.getVesselIMONumber() == null
        && bookingRequest.getExportVoyageNumber() == null) {
      return "The attributes expectedArrivalDateStart, expectedArrivalDateEnd, expectedDepartureDate and vesselIMONumber/exportVoyageNumber cannot all be null at the same time. These fields are conditional and require that at least one of them is not empty.";
    }

    if (bookingRequest.getExpectedArrivalDateStart() != null
        && bookingRequest.getExpectedArrivalDateEnd() != null
        && bookingRequest
            .getExpectedArrivalDateStart()
            .isAfter(bookingRequest.getExpectedArrivalDateEnd())) {
      return "The attribute expectedArrivalDateEnd must be the same or after expectedArrivalDateStart.";
    }
    return StringUtils.EMPTY;
  }

  private final Function<Boolean, Mono<? extends Boolean>> verifyCancellation =
      isRecordUpdated -> {
        if (isRecordUpdated) {
          return Mono.just(true);
        } else {
          return Mono.error(new UpdateException("Cancellation of booking failed."));
        }
      };

  private final Function<Booking, Mono<Booking>> checkCancelBookingStatus =
      booking -> {
        EnumSet<ShipmentEventTypeCode> allowedDocumentStatuses =
            EnumSet.of(
                ShipmentEventTypeCode.RECE,
                ShipmentEventTypeCode.PENU,
                ShipmentEventTypeCode.CONF,
                ShipmentEventTypeCode.PENC);
        if (allowedDocumentStatuses.contains(booking.getDocumentStatus())) {
          return Mono.just(booking);
        }
        return Mono.error(
            new UpdateException(
                "Cannot Cancel Booking that is not in status RECE, PENU, CONF or PENC"));
      };

  private final Function<Booking, Mono<Booking>> checkUpdateBookingStatus =
      booking -> {
        EnumSet<ShipmentEventTypeCode> allowedDocumentStatuses =
            EnumSet.of(ShipmentEventTypeCode.RECE, ShipmentEventTypeCode.PENU);
        if (allowedDocumentStatuses.contains(booking.getDocumentStatus())) {
          return Mono.just(booking);
        }
        return Mono.error(
            new UpdateException("Cannot Update Booking that is not in status RECE or PENU"));
      };
}

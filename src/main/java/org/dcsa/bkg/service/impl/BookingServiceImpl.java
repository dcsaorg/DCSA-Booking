package org.dcsa.bkg.service.impl;

import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.dcsa.bkg.model.mappers.*;
import org.dcsa.bkg.model.transferobjects.*;
import org.dcsa.bkg.service.BookingService;
import org.dcsa.core.events.model.*;
import org.dcsa.core.events.model.enums.*;
import org.dcsa.core.events.model.transferobjects.LocationTO;
import org.dcsa.core.events.model.transferobjects.PartyContactDetailsTO;
import org.dcsa.core.events.model.transferobjects.PartyTO;
import org.dcsa.core.events.repository.*;
import org.dcsa.core.events.service.ShipmentEventService;
import org.dcsa.core.exception.CreateException;
import org.dcsa.core.exception.UpdateException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
public class BookingServiceImpl implements BookingService {

  // repositories
  private final BookingRepository bookingRepository;
  private final LocationRepository locationRepository;
  private final AddressRepository addressRepository;
  private final FacilityRepository facilityRepository;
  private final CommodityRepository commodityRepository;
  private final ValueAddedServiceRequestRepository valueAddedServiceRequestRepository;
  private final ReferenceRepository referenceRepository;
  private final RequestedEquipmentRepository requestedEquipmentRepository;
  private final DocumentPartyRepository documentPartyRepository;
  private final PartyRepository partyRepository;
  private final PartyContactDetailsRepository partyContactDetailsRepository;
  private final PartyIdentifyingCodeRepository partyIdentifyingCodeRepository;
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

  // mappers
  private final BookingMapper bookingMapper;
  private final BookingSummaryMapper bookingSummaryMapper;
  private final LocationMapper locationMapper;
  private final CommodityMapper commodityMapper;
  private final PartyMapper partyMapper;
  private final ShipmentMapper shipmentMapper;
  private final CarrierClauseMapper carrierClauseMapper;
  private final ConfirmedEquipmentMapper confirmedEquipmentMapper;
  private final ChargeMapper chargeMapper;
  private final PartyContactDetailsMapper partyContactDetailsMapper;
  private final TransportMapper transportMapper;

  // services
  private final ShipmentEventService shipmentEventService;

  @Override
  public Flux<ShipmentSummaryTO> getShipmentSummaries(
      String carrierBookingReference, DocumentStatus documentStatus, Pageable pageable) {

    Flux<Shipment> shipmentResponse =
        shipmentRepository.findAllByCarrierBookingReference(carrierBookingReference, pageable);

    if (carrierBookingReference == null) {
      shipmentResponse = shipmentRepository.findShipmentsByBookingIDNotNull(pageable);
    }

    return shipmentResponse.flatMap(
        shipment -> {
          // IF-statement is here so that we *only* make multiple queries if documentStatus is
          // included as parameter
          if (documentStatus != null) {

            // Potential issue if booking query returns more than the set limit of results
            return bookingRepository
                .findAllByBookingIDAndDocumentStatus(
                    shipment.getBookingID(), documentStatus, pageable)
                .mapNotNull(
                    ignored -> {
                      ShipmentSummaryTO shipmentSummaryTO =
                          new ShipmentSummaryTO();
                      shipmentSummaryTO.setCarrierBookingReference(
                          shipment.getCarrierBookingReference());
                      shipmentSummaryTO.setConfirmationDateTime(
                          shipment.getConfirmationDateTime());
                      shipmentSummaryTO.setTermsAndConditions(
                          shipment.getTermsAndConditions());
                      return shipmentSummaryTO;
                    });
          } else {
            ShipmentSummaryTO shipmentSummaryTO =
                new ShipmentSummaryTO();
            shipmentSummaryTO.setCarrierBookingReference(
                shipment.getCarrierBookingReference());
            shipmentSummaryTO.setConfirmationDateTime(
                shipment.getConfirmationDateTime());
            shipmentSummaryTO.setTermsAndConditions(shipment.getTermsAndConditions());
            return Mono.just(shipmentSummaryTO);
          }
        });
  }

  @Override
  public Flux<BookingSummaryTO> getBookingRequestSummaries(
      String carrierBookingRequestReference, DocumentStatus documentStatus, Pageable pageable) {

    Flux<Booking> queryResponse =
        bookingRepository.findAllByCarrierBookingReferenceAndDocumentStatus(
            carrierBookingRequestReference, documentStatus, pageable);

    return queryResponse.flatMap(
        booking ->
            vesselRepository
                .findByIdOrEmpty(booking.getVesselId())
                .mapNotNull(
                    vessel -> {
                      BookingSummaryTO bookingSummaryTO =
                          bookingSummaryMapper.bookingSummaryTOFromBooking(booking);
                      bookingSummaryTO.setVesselIMONumber(vessel.getVesselIMONumber());
                      return bookingSummaryTO;
                    })
                .defaultIfEmpty(bookingSummaryMapper.bookingSummaryTOFromBooking(booking)));
  }

  @Override
  @Transactional
  public Mono<BookingTO> createBooking(final BookingTO bookingRequest) {

    Booking requestedBooking = bookingMapper.dtoToBooking(bookingRequest);
    requestedBooking.setDocumentStatus(DocumentStatus.RECE);
    requestedBooking.setBookingRequestDateTime(OffsetDateTime.now());

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
              final String cbReqRef = booking.getCarrierBookingRequestReference();
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
                      createLocationByTO(
                          bookingRequest.getInvoicePayableAt(),
                          invPayAT -> bookingRepository.setInvoicePayableAtFor(invPayAT, cbReqRef)),
                      createLocationByTO(
                          bookingRequest.getPlaceOfIssue(),
                          placeOfIss ->
                              bookingRepository.setPlaceOfIssueIDFor(placeOfIss, cbReqRef)),
                      createCommoditiesByBookingIDAndTOs(
                          bookingID, bookingRequest.getCommodities()),
                      createValueAddedServiceRequestsByBookingIDAndTOs(
                          bookingID, bookingRequest.getValueAddedServiceRequests()),
                      createReferencesByBookingIDAndTOs(bookingID, bookingRequest.getReferences()),
                      createRequestedEquipmentsByBookingIDAndTOs(
                          bookingID, bookingRequest.getRequestedEquipments()),
                      createDocumentPartiesByBookingIDAndTOs(
                          bookingID, bookingRequest.getDocumentParties()))
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
              Optional<List<RequestedEquipmentTO>> requestedEquipmentsOpt = t.getT1().getT7();
              Optional<List<DocumentPartyTO>> documentPartiesOpt = t.getT1().getT8();
              Optional<List<ShipmentLocationTO>> shipmentLocationsOpt = t.getT2();

              // populate the booking DTO
              invoicePayableAtOpt.ifPresent(bookingTO::setInvoicePayableAt);
              placeOfIssueOpt.ifPresent(bookingTO::setPlaceOfIssue);
              commoditiesOpt.ifPresent(bookingTO::setCommodities);
              valueAddedServiceRequestsOpt.ifPresent(bookingTO::setValueAddedServiceRequests);
              referencesOpt.ifPresent(bookingTO::setReferences);
              requestedEquipmentsOpt.ifPresent(bookingTO::setRequestedEquipments);
              documentPartiesOpt.ifPresent(bookingTO::setDocumentParties);
              shipmentLocationsOpt.ifPresent(bookingTO::setShipmentLocations);

              return Mono.just(bookingTO);
            })
        .transform(createShipmentEventFromBookingTO);
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
          .findByVesselNameOrEmpty(vesselIMONumber)
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

  private Mono<Optional<LocationTO>> createLocationByTO(
      LocationTO locationTO, Function<String, Mono<Boolean>> updateBookingCallback) {

    if (Objects.isNull(locationTO)) {
      return Mono.just(Optional.empty());
    }

    Location location = locationMapper.dtoToLocation(locationTO);

    if (Objects.isNull(locationTO.getAddress())) {
      return locationRepository
          .save(location)
          .flatMap(l -> updateBookingCallback.apply(l.getId()).thenReturn(l))
          .map(locationMapper::locationToDTO)
          .map(Optional::of);
    } else {
      return addressRepository
          .save(locationTO.getAddress())
          .flatMap(
              a -> {
                location.setAddressID(a.getId());
                return locationRepository
                    .save(location)
                    .flatMap(l -> updateBookingCallback.apply(l.getId()).thenReturn(l))
                    .map(
                        l -> {
                          LocationTO lTO = locationMapper.locationToDTO(l);
                          lTO.setAddress(a);
                          return lTO;
                        })
                    .map(Optional::of);
              });
    }
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

  private Mono<Optional<List<RequestedEquipmentTO>>> createRequestedEquipmentsByBookingIDAndTOs(
      UUID bookingID, List<RequestedEquipmentTO> requestedEquipments) {

    if (Objects.isNull(requestedEquipments) || requestedEquipments.isEmpty()) {
      return Mono.just(Optional.of(Collections.emptyList()));
    }

    Stream<RequestedEquipment> requestedEquipmentsStream =
        requestedEquipments.stream()
            .map(
                reTo -> {
                  RequestedEquipment requestedEquipment = new RequestedEquipment();
                  requestedEquipment.setBookingID(bookingID);
                  requestedEquipment.setRequestedEquipmentSizetype(
                      reTo.getRequestedEquipmentSizetype());
                  requestedEquipment.setRequestedEquipmentUnits(reTo.getRequestedEquipmentUnits());
                  requestedEquipment.setIsShipperOwned(reTo.isShipperOwned());
                  return requestedEquipment;
                });

    return requestedEquipmentRepository
        .saveAll(Flux.fromStream(requestedEquipmentsStream))
        .map(
            re -> {
              RequestedEquipmentTO requestedEquipmentTO = new RequestedEquipmentTO();
              requestedEquipmentTO.setRequestedEquipmentSizetype(
                  re.getRequestedEquipmentSizetype());
              requestedEquipmentTO.setRequestedEquipmentUnits(re.getRequestedEquipmentUnits());
              requestedEquipmentTO.setShipperOwned(re.getIsShipperOwned());
              return requestedEquipmentTO;
            })
        .collectList()
        .map(Optional::of);
  }

  private final UnaryOperator<Mono<BookingTO>> createShipmentEventFromBookingTO =
      bookingTOMono ->
          bookingTOMono
              .flatMap(BookingServiceImpl.this::createShipmentEventFromBookingTO)
              .flatMap(shipmentEvent -> bookingTOMono);

  private Mono<Optional<List<DocumentPartyTO>>> createDocumentPartiesByBookingIDAndTOs(
      final UUID bookingID, List<DocumentPartyTO> documentParties) {

    if (Objects.isNull(documentParties) || documentParties.isEmpty()) {
      return Mono.just(Optional.of(Collections.emptyList()));
    }

    return Flux.fromStream(documentParties.stream())
        .flatMap(
            dp ->
                // party is mandatory, cannot be null in document party as per API specs
                createPartyByTO(dp.getParty())
                    .flatMap(
                        t -> {
                          DocumentParty documentParty = new DocumentParty();
                          documentParty.setPartyID(t.getT1());
                          documentParty.setBookingID(bookingID);
                          documentParty.setPartyFunction(dp.getPartyFunction());
                          documentParty.setIsToBeNotified(dp.getIsToBeNotified());
                          return documentPartyRepository
                              .save(documentParty)
                              .map(
                                  savedDp -> {
                                    DocumentPartyTO documentPartyTO = new DocumentPartyTO();
                                    documentPartyTO.setParty(t.getT2());
                                    documentPartyTO.setDisplayedAddress(dp.getDisplayedAddress());
                                    documentPartyTO.setPartyFunction(savedDp.getPartyFunction());
                                    documentPartyTO.setIsToBeNotified(savedDp.getIsToBeNotified());
                                    return Tuples.of(savedDp.getId(), documentPartyTO);
                                  });
                        }))
        .flatMap(
            t -> {
              Stream<DisplayedAddress> displayedAddressStream =
                  t.getT2().getDisplayedAddress().stream()
                      .map(
                          da -> {
                            DisplayedAddress displayedAddress = new DisplayedAddress();
                            displayedAddress.setDocumentPartyID(t.getT1());
                            displayedAddress.setAddressLine(da);
                            displayedAddress.setAddressLineNumber(
                                t.getT2().getDisplayedAddress().indexOf(da));
                            return displayedAddress;
                          });

              return displayedAddressRepository
                  .saveAll(Flux.fromStream(displayedAddressStream))
                  .map(DisplayedAddress::getAddressLine)
                  .collectList()
                  .flatMap(
                      s -> {
                        t.getT2().setDisplayedAddress(s);
                        return Mono.just(t.getT2());
                      });
            })
        .collectList()
        .map(Optional::of);
  }

  private Mono<Tuple2<String, PartyTO>> createPartyByTO(final PartyTO partyTO) {

    Mono<Tuple2<String, PartyTO>> partyMap;

    if (Objects.isNull(partyTO.getAddress())) {

      partyMap =
          partyRepository
              .save(partyMapper.dtoToParty(partyTO))
              .map(p -> Tuples.of(p.getId(), partyMapper.partyToDTO(p)));

    } else {
      // if there is an address connected to the party, we need to create it first.
      partyMap =
          addressRepository
              .save(partyTO.getAddress())
              .flatMap(
                  a -> {
                    Party party = partyMapper.dtoToParty(partyTO);
                    party.setAddressID(a.getId());
                    return partyRepository
                        .save(party)
                        .map(
                            p -> {
                              PartyTO pTO = partyMapper.partyToDTO(p);
                              pTO.setAddress(a);
                              return Tuples.of(p.getId(), pTO);
                            });
                  });
    }

    return partyMap
        .flatMap(
            t -> {
              Stream<PartyContactDetails> partyContactDetailsStream =
                  partyTO.getPartyContactDetails().stream()
                      .map(
                          pcdTO -> {
                            PartyContactDetails pcd =
                                partyContactDetailsMapper.dtoToPartyContactDetails(pcdTO);
                            pcd.setPartyID(t.getT1());
                            return pcd;
                          });

              return partyContactDetailsRepository
                  .saveAll(Flux.fromStream(partyContactDetailsStream))
                  .map(partyContactDetailsMapper::partyContactDetailsToDTO)
                  .collectList()
                  .flatMap(
                      pcds -> {
                        t.getT2().setPartyContactDetails(pcds);
                        return Mono.just(t);
                      });
            })
        .flatMap(
            t -> {
              Stream<PartyIdentifyingCode> partyIdentifyingCodeStream =
                  partyTO.getIdentifyingCodes().stream()
                      .map(
                          idc -> {
                            PartyIdentifyingCode partyIdentifyingCode = new PartyIdentifyingCode();
                            partyIdentifyingCode.setPartyID(t.getT1());
                            partyIdentifyingCode.setDcsaResponsibleAgencyCode(
                                idc.getDcsaResponsibleAgencyCode());
                            partyIdentifyingCode.setCodeListName(idc.getCodeListName());
                            partyIdentifyingCode.setPartyCode(idc.getPartyCode());
                            return partyIdentifyingCode;
                          });
              return partyIdentifyingCodeRepository
                  .saveAll(
                      Flux.fromStream(
                          partyIdentifyingCodeStream)) // save identifying codes related to party
                  // obj
                  .map(
                      savedIdcs ->
                          PartyTO.IdentifyingCode.builder()
                              .partyCode(savedIdcs.getPartyCode())
                              .codeListName(savedIdcs.getCodeListName())
                              .dcsaResponsibleAgencyCode(savedIdcs.getDcsaResponsibleAgencyCode())
                              .build())
                  .collectList()
                  .flatMap(
                      identifyingCodes -> {
                        PartyTO pTO = t.getT2();
                        pTO.setIdentifyingCodes(identifyingCodes);
                        return Mono.just(Tuples.of(t.getT1(), pTO));
                      });
            });
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
                return addressRepository
                    .save(slTO.getLocation().getAddress())
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
  public Mono<BookingTO> updateBookingByReferenceCarrierBookingRequestReference(
      String carrierBookingRequestReference, BookingTO bookingRequest) {
    return Mono.empty();
  }

  @Override
  public Mono<BookingTO> getBookingByCarrierBookingRequestReference(
      String carrierBookingRequestReference) {
    return bookingRepository
        .findByCarrierBookingRequestReference(carrierBookingRequestReference)
        .map(b -> Tuples.of(b.getId(), bookingMapper.bookingToDTO(b)))
        .doOnSuccess(
            t -> {
              // the mapper creates a new instance of location even if value of invoicePayableAt is
              // null in booking
              // hence we set it to null if its a null object
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
                      fetchLocationByID(invoicePayableAtLocID),
                      fetchLocationByID(placeOfIssueLocID),
                      fetchCommoditiesByBookingID(t.getT1()),
                      fetchValueAddedServiceRequestsByBookingID(t.getT1()),
                      fetchReferencesByBookingID(t.getT1()),
                      fetchRequestedEquipmentsByBookingID(t.getT1()),
                      fetchDocumentPartiesByBookingID(t.getT1()),
                      fetchShipmentLocationsByBookingID(t.getT1()))
                  .doOnSuccess(
                      deepObjs -> {
                        Optional<LocationTO> locationToOpt1 = deepObjs.getT1();
                        Optional<LocationTO> locationToOpt2 = deepObjs.getT2();
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

                        shipmentCutOffTimeTOpt.ifPresent(
                            shipmentTO::setShipmentCutOffTimes);
                        shipmentLocationsToOpt.ifPresent(
                            shipmentTO::setShipmentLocations);
                        carrierClauseToOpt.ifPresent(shipmentTO::setCarrierClauses);
                        confirmedEquipmentTOOpt.ifPresent(
                            shipmentTO::setConfirmedEquipments);
                        chargesToOpt.ifPresent(shipmentTO::setCharges);
                        bookingToOpt.ifPresent(shipmentTO::setBooking);
                        transportsToOpt.ifPresent(shipmentTO::setTransports);
                        return Mono.just(shipmentTO);
                      })
                  .thenReturn(shipmentTO);
            });
  }

  private Mono<Optional<LocationTO>> fetchLocationByID(String id) {
    if (id == null) return Mono.just(Optional.empty());
    return locationRepository
        .findById(id)
        .flatMap(
            location ->
                Mono.zip(
                        addressRepository
                            .findByIdOrEmpty(location.getAddressID())
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty()),
                        facilityRepository
                            .findByIdOrEmpty(location.getFacilityID())
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty()))
                    .flatMap(
                        t2 -> {
                          LocationTO locTO = locationMapper.locationToDTO(location);
                          t2.getT1().ifPresent(locTO::setAddress);
                          t2.getT2().ifPresent(locTO::setFacility);
                          return Mono.just(locTO);
                        }))
        .onErrorReturn(new LocationTO())
        .map(Optional::of);
  }

  private Mono<Optional<LocationTO>> fetchLocationByTransportCallId(String id) {
    if (id == null) return Mono.just(Optional.empty());
    return transportCallRepository.findById(id)
        .flatMap(transportCall -> fetchLocationByID(transportCall.getLocationID()));
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
                        fetchLocationByID(booking.getInvoicePayableAt()),
                        fetchLocationByID(booking.getPlaceOfIssueID()),
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
                        fetchPartyByID(dp.getPartyID()),
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

  private Mono<Optional<PartyTO>> fetchPartyByID(String partyID) {
    if (partyID == null) return Mono.empty();
    return partyRepository
        .findByIdOrEmpty(partyID)
        .flatMap(
            p ->
                Mono.zip(
                        addressRepository
                            .findByIdOrEmpty(p.getAddressID())
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty()),
                        partyIdentifyingCodeRepository
                            .findAllByPartyID(partyID)
                            .map(
                                idc ->
                                    PartyTO.IdentifyingCode.builder()
                                        .partyCode(idc.getPartyCode())
                                        .codeListName(idc.getCodeListName())
                                        .dcsaResponsibleAgencyCode(
                                            idc.getDcsaResponsibleAgencyCode())
                                        .build())
                            .collectList()
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty()),
                        fetchPartyContactDetailsByPartyID(partyID))
                    .flatMap(
                        t -> {
                          Optional<Address> addressOpt = t.getT1();
                          Optional<List<PartyTO.IdentifyingCode>> identifyingCodesOpt = t.getT2();
                          Optional<List<PartyContactDetailsTO>> partyContactDetailsOpt = t.getT3();
                          PartyTO partyTO = partyMapper.partyToDTO(p);
                          addressOpt.ifPresent(partyTO::setAddress);
                          identifyingCodesOpt.ifPresent(partyTO::setIdentifyingCodes);
                          partyContactDetailsOpt.ifPresent(partyTO::setPartyContactDetails);
                          return Mono.just(partyTO);
                        }))
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty());
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
                fetchLocationByID(sl.getLocationID())
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

  private Mono<Optional<Tuple2<TransportEvent, TransportEvent>>> fetchTransportEventByTransportId(UUID transportId) {
      return transportRepository.findById(transportId).flatMap(x ->
          Mono.zip(
                  transportEventRepository.findFirstByTransportCallIDAndEventTypeCodeAndEventClassifierCodeOrderByEventDateTimeDesc(x.getLoadTransportCallID(), TransportEventTypeCode.ARRI, EventClassifierCode.PLN),
                  transportEventRepository.findFirstByTransportCallIDAndEventTypeCodeAndEventClassifierCodeOrderByEventDateTimeDesc(x.getDischargeTransportCallID(), TransportEventTypeCode.DEPA, EventClassifierCode.PLN))
              .flatMap(y -> Mono.just(Tuples.of(y.getT1(), y.getT2()))))
              .map(Optional::of)
              .defaultIfEmpty(Optional.empty());
  }

  private Mono<Optional<Voyage>> fetchExportVoyageByTransportCallId(String transportCallId) {
      if (transportCallId == null) return Mono.just(Optional.empty());
      return transportCallRepository.findById(transportCallId).flatMap(x -> voyageRepository.findById(x.getExportVoyageID())).map(Optional::of).defaultIfEmpty(Optional.empty());
  }
  private Mono<Optional<Vessel>> fetchVesselByTransportCallId(String transportCallId) {
      if (transportCallId == null) return Mono.just(Optional.empty());
      return transportCallRepository.findById(transportCallId).flatMap(x -> vesselRepository.findById(x.getVesselID())).map(Optional::of).defaultIfEmpty(Optional.empty());
  }

  private Mono<Optional<ModeOfTransport>> fetchModeOfTransportByTransportCallId(String transportCallId) {
      if (transportCallId == null) return Mono.just(Optional.empty());
      return modeOfTransportRepository.findByTransportCallID(transportCallId).map(Optional::of).defaultIfEmpty(Optional.empty());
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
                                    fetchTransportEventByTransportId(transport.getTransportID()),
                                    fetchLocationByTransportCallId(transport.getLoadTransportCallID()),
                                    fetchLocationByTransportCallId(transport.getDischargeTransportCallID()),
                                    fetchModeOfTransportByTransportCallId(transport.getLoadTransportCallID()),
                                    fetchVesselByTransportCallId(transport.getLoadTransportCallID()),
                                    fetchExportVoyageByTransportCallId(transport.getLoadTransportCallID()))
                                .map(
                                    x -> {
                                      TransportTO transportTO = transportMapper.transportToDTO(transport);
                                      transportTO.setTransportPlanStage(shipmentTransport.getTransportPlanStageCode());
                                      transportTO.setTransportPlanStageSequenceNumber(shipmentTransport.getTransportPlanStageSequenceNumber());
                                      transportTO.setTransportName(transport.getTransportName());
                                      transportTO.setTransportReference(transport.getTransportReference());
                                      transportTO.setIsUnderShippersResponsibility(shipmentTransport.getIsUnderShippersResponsibility());

                                      x.getT1().ifPresent(t1 -> {
                                          transportTO.setPlannedDepartureDate(t1.getT1().getEventDateTime());
                                          transportTO.setPlannedArrivalDate(t1.getT2().getEventDateTime());
                                      });

                                      x.getT2().ifPresent(transportTO::setLoadLocation);
                                      x.getT3().ifPresent(transportTO::setDischargeLocation);

                                      x.getT4().ifPresent(t4 -> transportTO.setModeOfTransport(t4.getDcsaTransportType()));
                                      x.getT5().ifPresent(t5 -> {
                                          transportTO.setVesselName(t5.getVesselName());
                                          transportTO.setVesselIMONumber(t5.getVesselIMONumber());
                                      });

                                      /*
                                        We are using exportVoyage from loadLocation according to the following mail from Christian:

                                        The voyage number on a booking is always the Export voyage number (the shipment is leaving the origin).
                                        This voyage number will remain the same until the shipment arrives at destination,
                                        but will then be the import voyage number of the arriving vessel.
                                        In other words only one voyage is required on a booking.
                                        The T&T event that is sent to the consignee will mention the voyage number as the “import voyage number”.
                                      */
                                      x.getT6().ifPresent(t6 -> transportTO.setCarrierVoyageNumber(t6.getCarrierVoyageNumber()));
                                      return transportTO;
                                    })))
        .collectList()
        .map(Optional::of)
        .defaultIfEmpty(Optional.empty());
  }

  @Override
  @Transactional
  public Mono<Void> cancelBookingByCarrierBookingReference(String carrierBookingRequestReference) {
    return bookingRepository
        .findByCarrierBookingRequestReference(carrierBookingRequestReference)
        .switchIfEmpty(
            Mono.error(
                new UpdateException("No Booking found with: ." + carrierBookingRequestReference)))
        .flatMap(checkBookingStatus)
        .flatMap(
            booking ->
                Mono.zip(
                    bookingRepository
                        .updateDocumentStatusForCarrierBookingRequestReference(
                            DocumentStatus.CANC, carrierBookingRequestReference)
                        .flatMap(verifyCancellation),
                    Mono.just(booking)
                        .map(
                            bkg -> {
                              bkg.setDocumentStatus(DocumentStatus.CANC);
                              return bkg;
                            })))
        .flatMap(t -> createShipmentEventFromBooking(t.getT2()))
        .flatMap(t -> Mono.empty());
  }

  private Mono<ShipmentEvent> createShipmentEventFromBookingTO(BookingTO bookingTo) {
    return createShipmentEventFromBooking(bookingMapper.dtoToBooking(bookingTo));
  }

  private Mono<ShipmentEvent> createShipmentEventFromBooking(Booking booking) {
    return shipmentEventFromBooking
        .apply(booking)
        .flatMap(shipmentEventService::create)
        .switchIfEmpty(
            Mono.error(new CreateException("Failed to create shipment event for Booking.")));
  }

  private final Function<Booking, Mono<ShipmentEvent>> shipmentEventFromBooking =
      booking -> {
        ShipmentEvent shipmentEvent = new ShipmentEvent();
        shipmentEvent.setShipmentEventTypeCode(
            ShipmentEventTypeCode.valueOf(booking.getDocumentStatus().name()));
        shipmentEvent.setDocumentTypeCode(DocumentTypeCode.CBR);
        shipmentEvent.setEventClassifierCode(EventClassifierCode.ACT);
        shipmentEvent.setEventType(null);
        shipmentEvent.setCarrierBookingReference(null);
        shipmentEvent.setDocumentID(booking.getCarrierBookingRequestReference());
        shipmentEvent.setEventDateTime(booking.getBookingRequestDateTime());
        shipmentEvent.setEventCreatedDateTime(OffsetDateTime.now());
        return Mono.just(shipmentEvent);
      };

  private final Function<Boolean, Mono<? extends Boolean>> verifyCancellation =
      isRecordUpdated -> {
        if (isRecordUpdated) {
          return Mono.just(true);
        } else {
          return Mono.error(new UpdateException("Cancellation of booking failed."));
        }
      };

  private final Function<Booking, Mono<Booking>> checkBookingStatus =
      booking -> {
        EnumSet<DocumentStatus> allowedDocumentStatuses =
            EnumSet.of(DocumentStatus.RECE, DocumentStatus.PENU, DocumentStatus.CONF);
        if (allowedDocumentStatuses.contains(booking.getDocumentStatus())) {
          return Mono.just(booking);
        } else
          return Mono.error(
              new UpdateException(
                  "Cannot Cancel Booking that is not in status RECE, PENU or CONF"));
      };
}

package org.dcsa.bkg.controller;

import org.dcsa.bkg.model.enums.ValueAddedServiceCode;
import org.dcsa.bkg.model.transferobjects.*;
import org.dcsa.bkg.service.BookingService;
import org.dcsa.core.events.model.Address;
import org.dcsa.core.events.model.enums.*;
import org.dcsa.core.events.model.transferobjects.LocationTO;
import org.dcsa.core.events.model.transferobjects.PartyTO;
import org.dcsa.core.exception.handler.GlobalExceptionHandler;
import org.dcsa.core.security.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.UUID;
import java.util.function.Function;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@DisplayName("Tests for BKGController")
@ActiveProfiles("test")
@WebFluxTest(controllers = {BKGController.class})
@Import(value = {GlobalExceptionHandler.class, SecurityConfig.class})
class BKGControllerTest {

  @Autowired WebTestClient webTestClient;

  @MockBean BookingService bookingService;

  private final String BOOKING_ENDPOINT = "/bookings";

  private BookingTO bookingTO;

  @BeforeEach
  void init() {
    // populate DTO with relevant objects to verify json schema returned
    bookingTO = new BookingTO();
    bookingTO.setReceiptDeliveryTypeAtOrigin(ReceiptDeliveryType.CY);
    bookingTO.setDeliveryTypeAtDestination(ReceiptDeliveryType.SD);
    bookingTO.setCargoMovementTypeAtOrigin(CargoMovementType.FCL);
    bookingTO.setCargoMovementTypeAtDestination(CargoMovementType.LCL);
    bookingTO.setServiceContractReference("x".repeat(30));
    bookingTO.setCargoGrossWeightUnit(CargoGrossWeight.KGM);
    bookingTO.setCommunicationChannel(CommunicationChannel.AO);

    CommodityTO commodityTO = new CommodityTO();
    commodityTO.setCommodityType("x".repeat(20));
    commodityTO.setHsCode("x".repeat(10));
    commodityTO.setCargoGrossWeight(CargoGrossWeight.KGM);
    bookingTO.setCommodities(Collections.singletonList(commodityTO));

    ValueAddedServiceRequestTO valueAddedServiceRequestTO = new ValueAddedServiceRequestTO();
    valueAddedServiceRequestTO.setValueAddedServiceCode(ValueAddedServiceCode.CDECL);
    bookingTO.setValueAddedServiceRequests(Collections.singletonList(valueAddedServiceRequestTO));

    ReferenceTO referenceTO = new ReferenceTO();
    referenceTO.setReferenceType(ReferenceTypeCode.FF);
    referenceTO.setReferenceValue("x".repeat(100));
    bookingTO.setReferences(Collections.singletonList(referenceTO));

    RequestedEquipmentTO requestedEquipmentTO = new RequestedEquipmentTO();
    requestedEquipmentTO.setRequestedEquipmentSizeType("x".repeat(4));
    requestedEquipmentTO.setRequestedEquipmentUnits((int) (Math.random() * 100));
    requestedEquipmentTO.setShipperOwned(true);
    bookingTO.setRequestedEquipments(Collections.singletonList(requestedEquipmentTO));

    DocumentPartyTO documentPartyTO = new DocumentPartyTO();
    PartyTO partyTO = new PartyTO();
    partyTO.setIdentifyingCodes(
        Collections.singletonList(PartyTO.IdentifyingCode.builder().build()));
    partyTO.setAddress(new Address());
    documentPartyTO.setParty(partyTO);
    documentPartyTO.setPartyFunction(PartyFunction.N1);
    documentPartyTO.setDisplayedAddress("x".repeat(250));
    documentPartyTO.setPartyContactDetails(new PartyContactDetailsTO());
    documentPartyTO.setToBeNotified(true);
    bookingTO.setDocumentParties(Collections.singletonList(documentPartyTO));

    ShipmentLocationTO shipmentLocationTO = new ShipmentLocationTO();
    shipmentLocationTO.setLocation(new LocationTO());
    shipmentLocationTO.setLocationType(LocationType.DRL);
    shipmentLocationTO.setDisplayedName("x".repeat(250));
    bookingTO.setShipmentLocations(Collections.singletonList(shipmentLocationTO));
  }

  @Test
  @DisplayName("POST booking should return 202 and valid booking json schema.")
  void postBookingsShouldReturn202ForValidBookingRequest() {

    // mock service method call
    when(bookingService.createBooking(any())).thenReturn(Mono.just(bookingTO));

    WebTestClient.ResponseSpec exchange =
        webTestClient
            .post()
            .uri(BOOKING_ENDPOINT)
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(bookingTO))
            .exchange();

    checkStatus202.andThen(checkBookingResponseJsonSchema).apply(exchange);
  }

  @Test
  @DisplayName("POST booking should return 400 for invalid request.")
  void postBookingsShouldReturn400ForInValidBookingRequest() {

    BookingTO invalidBookingTO = new BookingTO();

    WebTestClient.ResponseSpec exchange =
        webTestClient
            .post()
            .uri(BOOKING_ENDPOINT)
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(invalidBookingTO))
            .exchange();

    checkStatus400.apply(exchange);
  }

  @Test
  @DisplayName(
      "PUT booking should return 202 and valid booking json schema for given carrierBookingRequestReference.")
  void putBookingsShouldReturn202ForValidBookingRequest() {

    // mock service method call
    when(bookingService.updateBookingByReferenceCarrierBookingRequestReference(any(), any()))
        .thenReturn(Mono.just(bookingTO));

    WebTestClient.ResponseSpec exchange =
        webTestClient
            .put()
            .uri(BOOKING_ENDPOINT + "/" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(bookingTO))
            .exchange();

    checkStatus202.andThen(checkBookingResponseJsonSchema).apply(exchange);
  }

  @Test
  @DisplayName("PUT booking should return 400 for invalid request.")
  void putBookingsShouldReturn400ForInValidBookingRequest() {

    BookingTO invalidBookingTO = new BookingTO();

    WebTestClient.ResponseSpec exchange =
        webTestClient
            .put()
            .uri(BOOKING_ENDPOINT + "/" + UUID.randomUUID())
            .contentType(MediaType.APPLICATION_JSON)
            .body(BodyInserters.fromValue(invalidBookingTO))
            .exchange();

    checkStatus400.apply(exchange);
  }

  @Test
  @DisplayName(
      "GET booking should return 200 and valid json schema for given carrierBookingRequestReference.")
  void getBookingsShouldReturn200ForValidBookingAcknowledgementReference() {

    // mock service method call
    when(bookingService.getBookingByCarrierBookingRequestReference(any()))
        .thenReturn(Mono.just(bookingTO));

    WebTestClient.ResponseSpec exchange =
        webTestClient
            .get()
            .uri(BOOKING_ENDPOINT + "/" + UUID.randomUUID())
            .accept(MediaType.APPLICATION_JSON)
            .exchange();

    checkStatus200.andThen(checkBookingResponseJsonSchema).apply(exchange);
  }

  private final Function<WebTestClient.ResponseSpec, WebTestClient.ResponseSpec> checkStatus200 =
      (exchange) -> exchange.expectStatus().isOk();

  private final Function<WebTestClient.ResponseSpec, WebTestClient.ResponseSpec> checkStatus202 =
      (exchange) -> exchange.expectStatus().isAccepted();

  private final Function<WebTestClient.ResponseSpec, WebTestClient.ResponseSpec> checkStatus400 =
      (exchange) -> exchange.expectStatus().isBadRequest();

  private final Function<WebTestClient.ResponseSpec, WebTestClient.BodyContentSpec>
      checkBookingResponseJsonSchema =
          (exchange) ->
              exchange
                  .expectBody()
                  .consumeWith(System.out::println)
                  .jsonPath("$.carrierBookingRequestReference")
                  .hasJsonPath()
                  .jsonPath("$.receiptDeliveryTypeAtOrigin")
                  .hasJsonPath()
                  .jsonPath("$.deliveryTypeAtDestination")
                  .hasJsonPath()
                  .jsonPath("$.cargoMovementTypeAtOrigin")
                  .hasJsonPath()
                  .jsonPath("$.cargoMovementTypeAtDestination")
                  .hasJsonPath()
                  .jsonPath("$.serviceContractReference")
                  .hasJsonPath()
                  .jsonPath("$.paymentTerm")
                  .hasJsonPath()
                  .jsonPath("$.cargoGrossWeightUnit")
                  .hasJsonPath()
                  .jsonPath("$.isPartialLoadAllowed")
                  .hasJsonPath()
                  .jsonPath("$.isExportDeclarationRequired")
                  .hasJsonPath()
                  .jsonPath("$.exportDeclarationReference")
                  .hasJsonPath()
                  .jsonPath("$.isImportLicenseRequired")
                  .hasJsonPath()
                  .jsonPath("$.importLicenseReference")
                  .hasJsonPath()
                  .jsonPath("$.submissionDateTime")
                  .hasJsonPath()
                  .jsonPath("$.isAMSACIFilingRequired")
                  .hasJsonPath()
                  .jsonPath("$.isDestinationFilingRequired")
                  .hasJsonPath()
                  .jsonPath("$.contractQuotationReference")
                  .hasJsonPath()
                  .jsonPath("$.expectedDepartureDate")
                  .hasJsonPath()
                  .jsonPath("$.transportDocumentType")
                  .hasJsonPath()
                  .jsonPath("$.transportDocumentReference")
                  .hasJsonPath()
                  .jsonPath("$.bookingChannelReference")
                  .hasJsonPath()
                  .jsonPath("$.incoTerms")
                  .hasJsonPath()
                  .jsonPath("$.communicationChannel")
                  .hasJsonPath()
                  .jsonPath("$.isEquipmentSubstitutionAllowed")
                  .hasJsonPath()
                  .jsonPath("$.vesselName")
                  .hasJsonPath()
                  .jsonPath("$.vesselIMONumber")
                  .hasJsonPath()
                  .jsonPath("$.carrierVoyageNumber")
                  .hasJsonPath()
                  .jsonPath("$.commodities")
                  .hasJsonPath()
                  .jsonPath("$.commodities[0].commodityType")
                  .hasJsonPath()
                  .jsonPath("$.commodities[0].HSCode")
                  .hasJsonPath()
                  .jsonPath("$.commodities[0].cargoGrossWeight")
                  .hasJsonPath()
                  .jsonPath("$.commodities[0].exportLicenseIssueDate")
                  .hasJsonPath()
                  .jsonPath("$.commodities[0].exportLicenseExpiryDate")
                  .hasJsonPath()
                  .jsonPath("$.valueAddedServiceRequests")
                  .hasJsonPath()
                  .jsonPath("$.valueAddedServiceRequests[0].valueAddedServiceCode")
                  .hasJsonPath()
                  .jsonPath("$.references")
                  .hasJsonPath()
                  .jsonPath("$.references[0].referenceType")
                  .hasJsonPath()
                  .jsonPath("$.references[0].referenceValue")
                  .hasJsonPath()
                  .jsonPath("$.requestedEquipments")
                  .hasJsonPath()
                  .jsonPath("$.requestedEquipments[0].requestedEquipmentSizeType")
                  .hasJsonPath()
                  .jsonPath("$.requestedEquipments[0].requestedEquipmentUnits")
                  .hasJsonPath()
                  .jsonPath("$.requestedEquipments[0].isShipperOwned")
                  .hasJsonPath()
                  .jsonPath("$.documentParties")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].party")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].party.partyName")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].party.taxReference1")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].party.taxReference2")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].party.publicKey")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].party.address")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].party.address.name")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].party.address.street")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].party.address.streetNumber")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].party.address.floor")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].party.address.postCode")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].party.address.city")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].party.address.stateRegion")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].party.address.country")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].partyFunction")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].displayedAddress")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].partyContactDetails")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].partyContactDetails.name")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].partyContactDetails.phone")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].partyContactDetails.email")
                  .hasJsonPath()
                  .jsonPath("$.documentParties[0].isToBeNotified")
                  .hasJsonPath()
                  .jsonPath("$.shipmentLocations")
                  .hasJsonPath();
}
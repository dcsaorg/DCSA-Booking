package org.dcsa.bkg.model.transferobjects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.dcsa.bkg.model.enums.TransportPlanStage;
import org.dcsa.core.events.model.Location;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Set;

@DisplayName("Tests for TransportTOTest")
class TransportTOTest {
  private Validator validator;
  private ObjectMapper objectMapper;
  private TransportTO transportTO;

  @BeforeEach
  void init() {
    ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();

    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    transportTO = new TransportTO();
    transportTO.setTransportPlanStage(TransportPlanStage.MNC);
    transportTO.setTransportPlanStageSequenceNumber(25);
    transportTO.setLoadLocation(new Location());
    transportTO.setDischargeLocation(new Location());
    transportTO.setPlannedDepartureDate(LocalDate.now());
    transportTO.setPlannedArrivalDate(LocalDate.now().plusDays(2));
    transportTO.setVesselName("x".repeat(35));
    transportTO.setVesselIMONumber("x".repeat(7));
    transportTO.setCarrierVoyageNumber("x".repeat(50));
    transportTO.setIsUnderShippersResponsibility(true);
  }

  @Test
  @DisplayName("TransportTO should not throw error for valid TO.")
  void testToVerifyNoErrorIsThrowForValidTo() {
    Set<ConstraintViolation<TransportTO>> violations = validator.validate(transportTO);
    Assertions.assertEquals(0, violations.size());
  }

  @Test
  @DisplayName("TransportTO should throw error if TransportPlanStage is not set.")
  void testToVerifyTransportPlanStage() {
    transportTO.setTransportPlanStage(null);
    Set<ConstraintViolation<TransportTO>> violations = validator.validate(transportTO);
    Assertions.assertTrue(
        violations.stream()
            .anyMatch(v -> "Transport Plan Stage is required.".equals(v.getMessage())));
  }

  @Test
  @DisplayName("TransportTO should throw error if PlannedDepartureDate is not set.")
  void testToVerifyPlannedDepartureDateIsRequired() {
    transportTO.setPlannedDepartureDate(null);
    Set<ConstraintViolation<TransportTO>> violations = validator.validate(transportTO);
    Assertions.assertTrue(
        violations.stream()
            .anyMatch(v -> "Planned Departure Date is required.".equals(v.getMessage())));
  }

  @Test
  @DisplayName("TransportTO should throw error if PlannedArrivalDate is not set.")
  void testToVerifyPlannedArrivalDateIsRequired() {
    transportTO.setPlannedArrivalDate(null);
    Set<ConstraintViolation<TransportTO>> violations = validator.validate(transportTO);
    Assertions.assertTrue(
        violations.stream()
            .anyMatch(v -> "Planned Arrival Date is required.".equals(v.getMessage())));
  }

  @Test
  @DisplayName("TransportTO should throw error if VesselName length exceeds max size of 35.")
  void testToVerifyVesselNameIsNotAllowedToExceed35() {
    transportTO.setVesselName("x".repeat(36));
    Set<ConstraintViolation<TransportTO>> violations = validator.validate(transportTO);
    Assertions.assertTrue(
        violations.stream()
            .anyMatch(v -> "Vessel Name has a max size of 35.".equals(v.getMessage())));
  }

  @Test
  @DisplayName("TransportTO should throw error if VesselIMONumber length exceeds max size of 7.")
  void testToVerifyVesselIMONumberIsNotAllowedToExceed7() {
    transportTO.setVesselIMONumber("x".repeat(8));
    Set<ConstraintViolation<TransportTO>> violations = validator.validate(transportTO);
    Assertions.assertTrue(
        violations.stream()
            .anyMatch(v -> "Vessel IMO Number has a max size of 7.".equals(v.getMessage())));
  }

  @Test
  @DisplayName(
      "TransportTO should throw error if CarrierVoyageNumber length exceeds max size of 50.")
  void testToVerifyConfirmedEquipmentSizeTypeIsNotAllowedToExceed50() {
    transportTO.setCarrierVoyageNumber("x".repeat(51));
    Set<ConstraintViolation<TransportTO>> violations = validator.validate(transportTO);
    Assertions.assertTrue(
        violations.stream()
            .anyMatch(v -> "Carrier Voyage Number has a max size of 50.".equals(v.getMessage())));
  }

  @Test
  @DisplayName("TransportTO should return yyyy-MM-dd date format for plannedDepartureDate.")
  void testToCheckISODateFormatForPlannedDepartureDate() throws JsonProcessingException {
    JsonNode object = objectMapper.readTree(objectMapper.writeValueAsString(transportTO));
    String eventDateTime = object.get("plannedDepartureDate").asText();
    SimpleDateFormat sdfrmt = new SimpleDateFormat("yyyy-MM-dd");
    sdfrmt.setLenient(false);
    Assertions.assertDoesNotThrow(
        () -> {
          sdfrmt.parse(eventDateTime);
        });
  }

  @Test
  @DisplayName("TransportTO should return yyyy-MM-dd date format for plannedArrivalDate.")
  void testToCheckISODateFormatForPlannedArrivalDate() throws JsonProcessingException {
    JsonNode object = objectMapper.readTree(objectMapper.writeValueAsString(transportTO));
    String eventDateTime = object.get("plannedArrivalDate").asText();
    SimpleDateFormat sdfrmt = new SimpleDateFormat("yyyy-MM-dd");
    sdfrmt.setLenient(false);
    Assertions.assertDoesNotThrow(
        () -> {
          sdfrmt.parse(eventDateTime);
        });
  }
}
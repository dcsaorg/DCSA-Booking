package org.dcsa.bkg.model.transferobjects;

import org.dcsa.core.events.model.Booking;
import org.dcsa.core.events.model.Location;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;

@DisplayName("Tests for BookingConfirmationTOTest")
class BookingConfirmationTOTest {
  private Validator validator;
  private BookingConfirmationTO bookingConfirmationTO;

  @BeforeEach
  void init() {
    ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();

    bookingConfirmationTO = new BookingConfirmationTO();
    bookingConfirmationTO.setCarrierBookingReferenceID("x".repeat(35));
    bookingConfirmationTO.setTermsAndConditions("x".repeat(50));
    bookingConfirmationTO.setPlaceOfIssue(new Location());
    bookingConfirmationTO.setBooking(new Booking());
    bookingConfirmationTO.setTransports(List.of(new TransportTO()));
    bookingConfirmationTO.setShipmentLocations(List.of(new ShipmentLocationTO()));
    bookingConfirmationTO.setShipmentCutOffTimes(List.of(new ShipmentCutOffTimeTO()));
    bookingConfirmationTO.setConfirmedEquipments(List.of(new ConfirmedEquipmentTO()));
    bookingConfirmationTO.setCharges(List.of(new ChargeTO()));
    bookingConfirmationTO.setCarrierClauses(List.of(new CarrierClauseTO()));
  }

  @Test
  @DisplayName("BookingConfirmationTO should not throw error for valid TO.")
  void testToVerifyNoErrorIsThrowForValidTo() {
    Set<ConstraintViolation<BookingConfirmationTO>> violations =
        validator.validate(bookingConfirmationTO);
    Assertions.assertEquals(0, violations.size());
  }

  @Test
  @DisplayName("BookingConfirmationTO should throw error if PlannedDepartureDate is not set.")
  void testToVerifyCarrierBookingReferenceIDIsRequired() {
    bookingConfirmationTO.setCarrierBookingReferenceID(null);
    Set<ConstraintViolation<BookingConfirmationTO>> violations =
        validator.validate(bookingConfirmationTO);
    Assertions.assertTrue(
        violations.stream()
            .anyMatch(v -> "Carrier Booking Reference ID is required.".equals(v.getMessage())));
  }

  @Test
  @DisplayName("BookingConfirmationTO should throw error if PlannedDepartureDate is not set.")
  void testToVerifyCarrierBookingReferenceIDIsNotAllowedToExceed35() {
    bookingConfirmationTO.setCarrierBookingReferenceID("x".repeat(36));
    Set<ConstraintViolation<BookingConfirmationTO>> violations =
        validator.validate(bookingConfirmationTO);
    Assertions.assertTrue(
        violations.stream()
            .anyMatch(v -> "Carrier Booking Reference ID has a max size of 35.".equals(v.getMessage())));
  }
}

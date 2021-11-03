package org.dcsa.bkg.model.transferobjects;

import lombok.Data;
import org.dcsa.bkg.model.enums.TransportPlanStage;
import org.dcsa.core.events.model.Location;
import org.dcsa.core.events.model.ModeOfTransport;
import org.dcsa.core.events.model.enums.PaymentTerm;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
public class ChargeTO {

  @NotNull(message = "ChargeType is required.")
  @Size(max = 20, message = "ChargeType has a max size of 20.")
  private String chargeType;

  @NotNull(message = "CurrencyAmount is required.")
  private Double currencyAmount;

  @NotNull(message = "CurrencyCode is required.")
  @Size(max = 3, message = "Currency Code has a max size of 3.")
  private String currencyCode;

  @NotNull(message = "IsUnderShippersResponsibility is required.")
  private PaymentTerm isUnderShippersResponsibility;

  @NotNull(message = "CalculationBasis is required.")
  @Size(max = 50, message = "Calculation Basis has a max size of 50.")
  private String calculationBasis;

  @NotNull(message = "UnitPrice is required.")
  private Double unitPrice;

  @NotNull(message = "Quantity is required.")
  private Double quantity;
}
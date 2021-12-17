package org.dcsa.bkg.model.mappers;

import org.dcsa.bkg.model.transferobjects.ShipmentCutOffTimeTO;
import org.dcsa.bkg.model.transferobjects.ShipmentLocationTO;
import org.dcsa.bkg.model.transferobjects.ShipmentTO;
import org.dcsa.core.events.model.Shipment;
import org.dcsa.core.events.model.ShipmentCutOffTime;
import org.dcsa.core.events.model.ShipmentLocation;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface ShipmentMapper {
    ShipmentTO shipmentToDTO(Shipment shipment);
    ShipmentCutOffTimeTO shipmentCutOffTimeToDTO(ShipmentCutOffTime shipmentCutOffTime);
    ShipmentLocationTO shipmentLocationToDTO(ShipmentLocation shipmentLocation);
}

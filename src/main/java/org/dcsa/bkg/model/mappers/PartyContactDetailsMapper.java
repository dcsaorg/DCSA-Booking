package org.dcsa.bkg.model.mappers;

import org.dcsa.core.events.model.PartyContactDetails;
import org.dcsa.core.events.model.transferobjects.PartyContactDetailsTO;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface PartyContactDetailsMapper {
  PartyContactDetailsTO partyContactDetailsToDTO(PartyContactDetails partyContactDetails);

  PartyContactDetails dtoToPartyContactDetails(PartyContactDetailsTO partyContactDetailsTO);
}
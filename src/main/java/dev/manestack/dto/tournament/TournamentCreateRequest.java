package dev.manestack.dto.tournament;

import java.time.OffsetDateTime;

public record TournamentCreateRequest(
    String tournamentName,
    String description,
    String tournamentType,
    String gameVariant,
    OffsetDateTime registrationStart,
    OffsetDateTime registrationEnd,
    OffsetDateTime startTime,
    Integer buyIn,
    Integer entryFee,
    Integer rebuyAmount,
    Integer rebuyFee,
    Integer addonAmount,
    Integer addonFee,
    Integer maxRebuys,
    Boolean addonAllowed,
    Long startingChips,
    Long rebuyChips,
    Long addonChips,
    String blindStructureJson,
    Integer maxPlayers,
    Integer minPlayers,
    Integer seatsPerTable,
    Boolean lateRegistrationAllowed,
    Integer lateRegistrationEndLevel,
    Integer guaranteedPrizePool,
    Integer placesPaid,
    String prizeDistributionJson
) {}
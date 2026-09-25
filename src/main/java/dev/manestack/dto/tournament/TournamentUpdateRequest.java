package dev.manestack.dto.tournament;

import java.time.OffsetDateTime;

public record TournamentUpdateRequest(
    String tournamentName,
    String description,
    OffsetDateTime registrationStart,
    OffsetDateTime registrationEnd,
    OffsetDateTime startTime,
    Integer buyIn,
    Integer entryFee,
    Integer maxPlayers,
    Integer guaranteedPrizePool,
    String blindStructureJson,
    String prizeDistributionJson
) {}
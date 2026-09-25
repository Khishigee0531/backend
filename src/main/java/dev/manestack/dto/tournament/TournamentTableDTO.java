package dev.manestack.dto.tournament;

import java.time.OffsetDateTime;

public record TournamentTableDTO(
    Long tournamentTableId,
    Long tournamentId,
    Long tableId,
    String tableName,
    Integer tableNumber,
    String status,
    OffsetDateTime assignedAt,
    OffsetDateTime completedAt,
    Integer playersSeated,
    String secureId
) {}
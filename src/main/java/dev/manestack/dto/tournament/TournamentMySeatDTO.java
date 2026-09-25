package dev.manestack.dto.tournament;

public record TournamentMySeatDTO(
    Long tournamentId,
    Long tableId,
    String secureId,
    String tableName,
    Integer tableNumber,
    Integer seatNumber,
    Long chips,
    String entryStatus,
    String tournamentStatus
) {}

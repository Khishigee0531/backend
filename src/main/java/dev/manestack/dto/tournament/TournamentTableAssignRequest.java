package dev.manestack.dto.tournament;

public record TournamentTableAssignRequest(
    Long tournamentId,
    Long tableId,
    Integer tableNumber
) {}
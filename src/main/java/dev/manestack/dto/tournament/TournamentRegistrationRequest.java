package dev.manestack.dto.tournament;

public record TournamentRegistrationRequest(
    Integer userId,
    String entryType
) {}
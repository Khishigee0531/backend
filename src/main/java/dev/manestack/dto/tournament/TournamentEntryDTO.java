package dev.manestack.dto.tournament;

import java.time.OffsetDateTime;

public record TournamentEntryDTO(
    Long entryId,
    Long tournamentId,
    Integer userId,
    String username,
    String entryType,
    Integer amountPaid,
    Long chipsReceived,
    Integer seatNumber,
    Long tableId,
    String status,
    OffsetDateTime registeredAt,
    OffsetDateTime seatedAt,
    OffsetDateTime eliminatedAt,
    Integer eliminatedLevel,
    Integer finalPosition,
    Integer prizeWon,
    Integer rebuyCount,
    Boolean addonTaken
) {}
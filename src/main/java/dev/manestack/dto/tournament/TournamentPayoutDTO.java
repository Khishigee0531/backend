package dev.manestack.dto.tournament;

import java.time.OffsetDateTime;

public record TournamentPayoutDTO(
    Long payoutId,
    Long tournamentId,
    Long entryId,
    Integer userId,
    String username,
    Integer amount,
    String payoutType,
    String status,
    OffsetDateTime processedAt,
    OffsetDateTime createdAt
) {}
package dev.manestack.dto.poker;

import dev.manestack.service.poker.card.GameCard;
import java.util.List;

public record HandHistoryDTO(
    long handNumber,
    long winnerUserId,
    String winnerUsername,                   
    List<GameCard> winningHoleCards,
    List<GameCard> winningCommunityCards,
    double winnings
) {}

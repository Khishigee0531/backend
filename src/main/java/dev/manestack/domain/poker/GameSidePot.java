package dev.manestack.domain.poker;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import dev.manestack.service.poker.card.GameCard;

public class GameSidePot {
    private final Integer amount;
    private final Collection<GamePlayer> eligiblePlayers;
    private final Collection<GamePlayer> winners;
    private boolean isMainPot = false;
    private Integer winningsPerPlayer;
    private List<GameCard> winningCommunityCards = new ArrayList<>();

    public GameSidePot(int amount, Collection<GamePlayer> eligiblePlayers) {
        this.amount = amount;
        this.eligiblePlayers = eligiblePlayers;
        this.winners = new ArrayList<>();
    }

    public int getAmount() {
        return amount;
    }

    public Collection<GamePlayer> getEligiblePlayers() {
        return eligiblePlayers;
    }

    public void addWinners(Collection<GamePlayer> winners) {
        this.winners.addAll(winners);
    }

    public Collection<GamePlayer> getWinners() {
        return winners;
    }

    public Integer getWinningsPerPlayer() {
        return winningsPerPlayer;
    }

    public void setWinningsPerPlayer(Integer winningsPerPlayer) {
        this.winningsPerPlayer = winningsPerPlayer;
    }

    public boolean isMainPot() {
        return isMainPot;
    }

    public void setMainPot(boolean mainPot) {
        isMainPot = mainPot;
    }

    public List<GameCard> getWinningCommunityCards() {
        return winningCommunityCards;
    }

    public void setWinningCommunityCards(List<GameCard> winningCommunityCards) {
        this.winningCommunityCards = winningCommunityCards;
    }

    public JsonObject toJson() {
        return new JsonObject()
            .put("winners", new JsonArray(
                winners.stream()
                    .map(this::serializePlayer)
                    .toList()
            ))
            .put("winningCommunityCards", new JsonArray(
                (winningCommunityCards != null ? winningCommunityCards : Collections.<GameCard>emptyList()).stream()
                    .map(card -> new JsonObject()
                        .put("suit", card.getSuit().name())
                        .put("rank", card.getRank().name()))
                    .toList()
            ));
    }

    /** Lightweight serialization for in-game pot display. */
    public JsonObject toGameJson() {
        JsonArray eligibleJson = new JsonArray(
            eligiblePlayers.stream()
                .map(p -> new JsonObject()
                    .put("seatId", p.getSeatId())
                    .put("username", p.getUser() != null ? p.getUser().getUsername() : "")
                    .put("totalContribution", p.getTotalContribution()))
                .toList()
        );
        return new JsonObject()
            .put("amount", amount)
            .put("mainPot", isMainPot)
            .put("eligiblePlayers", eligibleJson);
    }


    private JsonObject serializePlayer(GamePlayer p) {
        JsonObject playerJson = new JsonObject();
        playerJson.put("username", p.getUser().getUsername());

        String handRank = (p.getHand() != null && p.getHand().getRank() != null)
                ? p.getHand().getRank().name()
                : null;
        playerJson.put("handRank", handRank);

        JsonArray holeCardsJson = new JsonArray();
        p.getHoleCards().forEach(card -> {
            holeCardsJson.add(new JsonObject()
                .put("suit", card.getSuit().name())
                .put("rank", card.getRank().name()));
        });
        playerJson.put("holeCards", holeCardsJson);

        JsonArray bestHandCardsJson = new JsonArray();
        if (p.getBestHandCards() != null) {
            p.getBestHandCards().forEach(card -> {
                bestHandCardsJson.add(new JsonObject()
                    .put("suit", card.getSuit().name())
                    .put("rank", card.getRank().name()));
            });
        }
        playerJson.put("bestHandCards", bestHandCardsJson);

        playerJson.put("totalContribution", p.getTotalContribution());
        playerJson.put("totalJackpotContribution", p.getTotalJackpotContribution());
        playerJson.put("finalWinnings", p.getWinnings());

        return playerJson;
    }
}

package dev.manestack.service.poker.card;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.List;

public class GameHand implements Comparable<GameHand> {
    private final GameHandRank rank;
    private final List<Integer> tiebreakers;
    private final List<GameCard> combinationCards;
    private final List<GameCard> rankCards;

    public GameHand(GameHandRank rank, List<Integer> tiebreakers, List<GameCard> combinationCards, List<GameCard> rankCards) {
        this.rank = rank;
        this.tiebreakers = tiebreakers;
        this.combinationCards = combinationCards;
        this.rankCards = rankCards;
    }

    public GameHandRank getRank() {
        return rank;
    }

    public void setBestCombination(List<GameCard> cards) {
        this.combinationCards.clear();
        if (cards != null) {
            this.combinationCards.addAll(cards);
        }
    }

    public List<GameCard> getCombinationCards() {
        return combinationCards;
    }

    @Override
    public int compareTo(GameHand other) {
        int cmp = Integer.compare(this.rank.ordinal(), other.getRank().ordinal());
        if (cmp != 0) return cmp;
        for (int i = 0; i < Math.min(tiebreakers.size(), other.tiebreakers.size()); i++) {
            cmp = Integer.compare(this.tiebreakers.get(i), other.tiebreakers.get(i));
            if (cmp != 0) return cmp;
        }
        return 0;
    }

    public List<Integer> getTiebreakers() {
        return tiebreakers;
    }

    public List<GameCard> getRankCards() {
        return rankCards;
    }

    @Override
    public String toString() {
        return "GameHand{" +
                "rank=" + rank +
                ", tiebreakers=" + tiebreakers +
                ", combinationCards=" + combinationCards +
                '}';
    }

    // **Add this method**
    public JsonObject toJson() {
        JsonObject json = new JsonObject();

        // Serialize rank as its name
        json.put("rank", rank != null ? rank.name() : null);

        // Serialize tiebreakers as JsonArray of integers
        JsonArray tiebreakersJson = new JsonArray();
        if (tiebreakers != null) {
            for (Integer t : tiebreakers) {
                tiebreakersJson.add(t);
            }
        }
        json.put("tiebreakers", tiebreakersJson);

        // Serialize combinationCards
        JsonArray combinationCardsJson = new JsonArray();
        if (combinationCards != null) {
            for (GameCard card : combinationCards) {
                JsonObject cardJson = new JsonObject();
                cardJson.put("rank", card.getRank() != null ? card.getRank().name() : null);
                cardJson.put("suit", card.getSuit() != null ? card.getSuit().name() : null);
                cardJson.put("isSecret", card.isSecret());
                combinationCardsJson.add(cardJson);
            }
        }
        json.put("combinationCards", combinationCardsJson);

        // Serialize rankCards similarly
        JsonArray rankCardsJson = new JsonArray();
        if (rankCards != null) {
            for (GameCard card : rankCards) {
                JsonObject cardJson = new JsonObject();
                cardJson.put("rank", card.getRank() != null ? card.getRank().name() : null);
                cardJson.put("suit", card.getSuit() != null ? card.getSuit().name() : null);
                cardJson.put("isSecret", card.isSecret());
                rankCardsJson.add(cardJson);
            }
        }
        json.put("rankCards", rankCardsJson);

        return json;
    }
}

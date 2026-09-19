package dev.manestack.service.poker.card;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class GameCard {
    private final Suit suit;
    private final Rank rank;
    private final boolean isSecret;

    public GameCard(Suit suit, Rank rank) {
        this.suit = suit;
        this.rank = rank;
        this.isSecret = false;
    }

    public GameCard(boolean isSecret) {
        this.suit = null;
        this.rank = null;
        this.isSecret = isSecret;
    }

    public boolean isSecret() {
        return isSecret;
    }

    public Suit getSuit() {
        return suit;
    }

    public Rank getRank() {
        return rank;
    }

    public enum Suit {
        HEARTS,
        DIAMONDS,
        CLUBS,
        SPADES
    }

    public enum Rank {
        TWO(2),
        THREE(3),
        FOUR(4),
        FIVE(5),
        SIX(6),
        SEVEN(7),
        EIGHT(8),
        NINE(9),
        TEN(10),
        JACK(11),
        QUEEN(12),
        KING(13),
        ACE(14);

        private final int value;

        Rank(int value) {
            this.value = value;
        }

        public int getValue() {
            return value;
        }
    }

    @Override
    public String toString() {
        return "GameCard{" +
                "suit=" + suit +
                ", rank=" + rank +
                ", isSecret=" + isSecret +
                '}';
    }

    // ✅ Convert a card list to JsonArray
    public static JsonArray toJsonArray(List<GameCard> cards, boolean hide) {
        JsonArray array = new JsonArray();
        for (GameCard card : cards) {
            if (hide && card.isSecret()) {
                array.add(new JsonObject()
                        .put("suit", "X")
                        .put("rank", "X")
                        .put("isSecret", true));
            } else {
                array.add(new JsonObject()
                        .put("suit", card.getSuit() != null ? card.getSuit().name() : null)
                        .put("rank", card.getRank() != null ? card.getRank().name() : null)
                        .put("isSecret", false));
            }
        }
        return array;
    }

    // ✅ Generate a full standard deck
    public static List<GameCard> fullDeck() {
        List<GameCard> deck = new ArrayList<>();
        for (Suit suit : Suit.values()) {
            for (Rank rank : Rank.values()) {
                deck.add(new GameCard(suit, rank));
            }
        }
        return deck;
    }

    public static List<GameCard> drawRandomHoleCardsExcluding(List<GameCard> usedCards, int count) {
        List<GameCard> deck = new ArrayList<>(fullDeck());
        deck.removeAll(usedCards); 
        Collections.shuffle(deck);
        return new ArrayList<>(deck.subList(0, Math.min(count, deck.size())));
    }


    public static List<GameCard> fromJsonArray(JsonArray array) {
        List<GameCard> cards = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            JsonObject obj = array.getJsonObject(i);
            boolean isSecret = obj.getBoolean("isSecret", false);

            if (isSecret) {
                cards.add(new GameCard(true));
            } else {
                Suit suit = obj.getString("suit") != null ? Suit.valueOf(obj.getString("suit")) : null;
                Rank rank = obj.getString("rank") != null ? Rank.valueOf(obj.getString("rank")) : null;
                cards.add(new GameCard(suit, rank));
            }
        }
        return cards;
    }
}

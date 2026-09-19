package dev.manestack.service.poker.card;

import java.util.*;
import java.security.MessageDigest;

public class FairGameDeck extends GameDeck {
    private final String serverSeed;
    private final String clientSeed;
    private final String deckHash;

    // Constructor with both clientSeed and serverSeed
    public FairGameDeck(String clientSeed, String serverSeed) {
        super();
        this.clientSeed = clientSeed;
        this.serverSeed = serverSeed;
        shuffleFairly();
        this.deckHash = computeDeckHash();
    }

    // Constructor with only clientSeed (serverSeed generated randomly)
    public FairGameDeck(String clientSeed) {
        this(clientSeed, UUID.randomUUID().toString());
    }

    // No-arg constructor (both seeds random)
    public FairGameDeck() {
        this(UUID.randomUUID().toString(), UUID.randomUUID().toString());
    }

    private void shuffleFairly() {
        try {
            String combinedSeed = serverSeed + ":" + clientSeed;
            byte[] seedBytes = combinedSeed.getBytes();
            long seed = Arrays.hashCode(seedBytes); // safer than simple hashCode()
            Random seeded = new Random(seed);
            Collections.shuffle(getCardsInternal(), seeded);
        } catch (Exception e) {
            throw new RuntimeException("Failed to shuffle fairly", e);
        }
    }

    private String computeDeckHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (GameCard c : getCardsInternal()) {
                digest.update((c.getSuit() + "-" + c.getRank()).getBytes());
            }
            return bytesToHex(digest.digest());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private List<GameCard> getCardsInternal() {
        try {
            var field = GameDeck.class.getDeclaredField("cards");
            field.setAccessible(true);
            return (List<GameCard>) field.get(this);
        } catch (Exception e) {
            throw new RuntimeException("Failed to access cards field", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    // Getters
    public String getDeckHash() { return deckHash; }
    public String getServerSeed() { return serverSeed; }
    public String getClientSeed() { return clientSeed; }
}

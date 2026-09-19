package dev.manestack.service.poker.card;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies Omaha hand evaluation: exactly 2 hole cards + exactly 3 community cards.
 *
 * Key rules tested:
 *  - 2 suited hole cards + 3 matching board cards  → flush  (valid)
 *  - 1 suited hole card  + 4 matching board cards  → NOT flush (max 4 of suit in 5-card combo)
 *  - Board-only full house                         → only THREE_OF_A_KIND in Omaha
 */
public class OmahaEvaluationTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private static GameCard c(GameCard.Suit suit, GameCard.Rank rank) {
        return new GameCard(suit, rank);
    }

    /**
     * Omaha evaluator: tries all C(4,2) × C(board,3) 5-card combinations and
     * returns the best hand. Mirrors GameSession.evaluateBestHandForVariant().
     */
    private static GameHand evaluateOmaha(List<GameCard> hole, List<GameCard> board) {
        if (hole.size() != 4 || board.size() < 3) {
            throw new IllegalArgumentException("Omaha requires 4 hole cards and at least 3 board cards");
        }
        GameHand best = null;
        for (int i = 0; i < hole.size(); i++) {
            for (int j = i + 1; j < hole.size(); j++) {
                for (int a = 0; a < board.size(); a++) {
                    for (int b = a + 1; b < board.size(); b++) {
                        for (int cc = b + 1; cc < board.size(); cc++) {
                            List<GameCard> five = List.of(
                                hole.get(i), hole.get(j),
                                board.get(a), board.get(b), board.get(cc)
                            );
                            GameHand hand = GameHandEvaluator.evaluate(five);
                            if (hand != null && (best == null || hand.compareTo(best) > 0)) {
                                best = hand;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    // ── flush tests ───────────────────────────────────────────────────────────

    /** A♦ K♦ (hole) + Q♦ J♦ 9♦ (board) = 5 diamonds → FLUSH */
    @Test
    void flush_two_diamonds_in_hole_three_on_board() {
        List<GameCard> hole = List.of(
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.ACE),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.KING),
            c(GameCard.Suit.CLUBS,    GameCard.Rank.TWO),
            c(GameCard.Suit.CLUBS,    GameCard.Rank.THREE)
        );
        List<GameCard> board = List.of(
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.QUEEN),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.JACK),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.NINE),
            c(GameCard.Suit.CLUBS,    GameCard.Rank.SEVEN),
            c(GameCard.Suit.SPADES,   GameCard.Rank.FIVE)
        );
        GameHand result = evaluateOmaha(hole, board);
        assertEquals(GameHandRank.FLUSH, result.getRank(),
            "A♦K♦ hole + Q♦J♦9♦ board must be FLUSH");
    }

    /**
     * A♦ is the only diamond in hole; board has 4 diamonds (Q♦J♦9♦7♦) + 5♠.
     * Any valid Omaha 5-card combo can hold at most 1 (hole) + 3 (board) = 4 diamonds
     * → never 5 of a suit → NOT a flush.
     */
    @Test
    void no_flush_one_diamond_in_hole_four_on_board() {
        List<GameCard> hole = List.of(
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.ACE),
            c(GameCard.Suit.CLUBS,    GameCard.Rank.KING),
            c(GameCard.Suit.CLUBS,    GameCard.Rank.TWO),
            c(GameCard.Suit.CLUBS,    GameCard.Rank.THREE)
        );
        List<GameCard> board = List.of(
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.QUEEN),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.JACK),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.NINE),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.SEVEN),
            c(GameCard.Suit.SPADES,   GameCard.Rank.FIVE)
        );
        GameHand result = evaluateOmaha(hole, board);
        assertNotEquals(GameHandRank.FLUSH, result.getRank(),
            "Only 1 diamond in hole — max 4 diamonds in any Omaha combo — must NOT be FLUSH");
        assertNotEquals(GameHandRank.STRAIGHT_FLUSH, result.getRank());
        assertNotEquals(GameHandRank.ROYAL_FLUSH, result.getRank());
    }

    // ── straight / straight-flush / royal-flush ───────────────────────────────

    /** A♠ K♥ (hole) + Q♣ J♦ T♥ (board) = A-K-Q-J-T → STRAIGHT */
    @Test
    void straight_broadway() {
        List<GameCard> hole = List.of(
            c(GameCard.Suit.SPADES,   GameCard.Rank.ACE),
            c(GameCard.Suit.HEARTS,   GameCard.Rank.KING),
            c(GameCard.Suit.CLUBS,    GameCard.Rank.TWO),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.THREE)
        );
        List<GameCard> board = List.of(
            c(GameCard.Suit.CLUBS,    GameCard.Rank.QUEEN),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.JACK),
            c(GameCard.Suit.HEARTS,   GameCard.Rank.TEN),
            c(GameCard.Suit.CLUBS,    GameCard.Rank.NINE),
            c(GameCard.Suit.SPADES,   GameCard.Rank.FOUR)
        );
        GameHand result = evaluateOmaha(hole, board);
        assertEquals(GameHandRank.STRAIGHT, result.getRank(),
            "A♠K♥ hole + Q♣J♦T♥ board = A-K-Q-J-T STRAIGHT");
    }

    /** 9♥ 8♥ (hole) + 7♥ 6♥ 5♥ (board) = 5-6-7-8-9 all hearts → STRAIGHT_FLUSH */
    @Test
    void straight_flush() {
        List<GameCard> hole = List.of(
            c(GameCard.Suit.HEARTS, GameCard.Rank.NINE),
            c(GameCard.Suit.HEARTS, GameCard.Rank.EIGHT),
            c(GameCard.Suit.CLUBS,  GameCard.Rank.TWO),
            c(GameCard.Suit.CLUBS,  GameCard.Rank.THREE)
        );
        List<GameCard> board = List.of(
            c(GameCard.Suit.HEARTS, GameCard.Rank.SEVEN),
            c(GameCard.Suit.HEARTS, GameCard.Rank.SIX),
            c(GameCard.Suit.HEARTS, GameCard.Rank.FIVE),
            c(GameCard.Suit.CLUBS,  GameCard.Rank.KING),
            c(GameCard.Suit.SPADES, GameCard.Rank.ACE)
        );
        GameHand result = evaluateOmaha(hole, board);
        assertEquals(GameHandRank.STRAIGHT_FLUSH, result.getRank(),
            "9♥8♥ hole + 7♥6♥5♥ board = 5-9 all hearts STRAIGHT_FLUSH");
    }

    /** A♥ K♥ (hole) + Q♥ J♥ T♥ (board) = A-K-Q-J-T all hearts → ROYAL_FLUSH */
    @Test
    void royal_flush() {
        List<GameCard> hole = List.of(
            c(GameCard.Suit.HEARTS, GameCard.Rank.ACE),
            c(GameCard.Suit.HEARTS, GameCard.Rank.KING),
            c(GameCard.Suit.CLUBS,  GameCard.Rank.TWO),
            c(GameCard.Suit.CLUBS,  GameCard.Rank.THREE)
        );
        List<GameCard> board = List.of(
            c(GameCard.Suit.HEARTS, GameCard.Rank.QUEEN),
            c(GameCard.Suit.HEARTS, GameCard.Rank.JACK),
            c(GameCard.Suit.HEARTS, GameCard.Rank.TEN),
            c(GameCard.Suit.CLUBS,  GameCard.Rank.SEVEN),
            c(GameCard.Suit.SPADES, GameCard.Rank.FIVE)
        );
        GameHand result = evaluateOmaha(hole, board);
        assertEquals(GameHandRank.ROYAL_FLUSH, result.getRank(),
            "A♥K♥ hole + Q♥J♥T♥ board = ROYAL FLUSH");
    }

    // ── pairs / trips / quads ─────────────────────────────────────────────────

    /** A♠ K♥ (hole) + A♣ K♦ 5♥ (board) = A-A-K-K → TWO_PAIR */
    @Test
    void two_pair() {
        List<GameCard> hole = List.of(
            c(GameCard.Suit.SPADES,   GameCard.Rank.ACE),
            c(GameCard.Suit.HEARTS,   GameCard.Rank.KING),
            c(GameCard.Suit.CLUBS,    GameCard.Rank.TWO),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.THREE)
        );
        List<GameCard> board = List.of(
            c(GameCard.Suit.CLUBS,    GameCard.Rank.ACE),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.KING),
            c(GameCard.Suit.HEARTS,   GameCard.Rank.FIVE),
            c(GameCard.Suit.SPADES,   GameCard.Rank.SIX),
            c(GameCard.Suit.CLUBS,    GameCard.Rank.SEVEN)
        );
        GameHand result = evaluateOmaha(hole, board);
        assertEquals(GameHandRank.TWO_PAIR, result.getRank(),
            "A♠K♥ hole + A♣K♦5♥ board = A-A-K-K TWO_PAIR");
    }

    /** A♠ A♣ (hole) + A♥ K♦ K♣ (board) = A-A-A-K-K → FULL_HOUSE */
    @Test
    void full_house() {
        List<GameCard> hole = List.of(
            c(GameCard.Suit.SPADES,   GameCard.Rank.ACE),
            c(GameCard.Suit.CLUBS,    GameCard.Rank.ACE),
            c(GameCard.Suit.HEARTS,   GameCard.Rank.KING),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.TWO)
        );
        List<GameCard> board = List.of(
            c(GameCard.Suit.HEARTS,   GameCard.Rank.ACE),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.KING),
            c(GameCard.Suit.CLUBS,    GameCard.Rank.KING),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.SEVEN),
            c(GameCard.Suit.SPADES,   GameCard.Rank.FIVE)
        );
        GameHand result = evaluateOmaha(hole, board);
        assertEquals(GameHandRank.FULL_HOUSE, result.getRank(),
            "A♠A♣ hole + A♥K♦K♣ board = A-A-A-K-K FULL_HOUSE");
    }

    /** A♠ A♣ (hole) + A♥ A♦ K♣ (board) = four aces → FOUR_OF_A_KIND */
    @Test
    void four_of_a_kind() {
        List<GameCard> hole = List.of(
            c(GameCard.Suit.SPADES,   GameCard.Rank.ACE),
            c(GameCard.Suit.CLUBS,    GameCard.Rank.ACE),
            c(GameCard.Suit.HEARTS,   GameCard.Rank.KING),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.TWO)
        );
        List<GameCard> board = List.of(
            c(GameCard.Suit.HEARTS,   GameCard.Rank.ACE),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.ACE),
            c(GameCard.Suit.CLUBS,    GameCard.Rank.KING),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.SEVEN),
            c(GameCard.Suit.SPADES,   GameCard.Rank.FIVE)
        );
        GameHand result = evaluateOmaha(hole, board);
        assertEquals(GameHandRank.FOUR_OF_A_KIND, result.getRank(),
            "A♠A♣ hole + A♥A♦K♣ board = FOUR_OF_A_KIND aces");
    }

    // ── board-dominated scenario (critical Omaha correctness check) ───────────

    /**
     * Board = A♥ A♦ A♣ K♥ K♦ (full house by itself).
     * Hole  = 7♠ 8♥ 2♣ 3♦  (no help).
     *
     * WRONG (Texas-style / old bug): evaluator picks best 5 from all 7 cards
     *   → [A♥,A♦,A♣,K♥,K♦] = FULL_HOUSE (ignores hole-card constraint).
     *
     * CORRECT (Omaha): must use exactly 2 hole cards.
     *   → best combo: any 2 hole cards + A♥A♦A♣ = THREE_OF_A_KIND (aces).
     */
    @Test
    void board_full_house_becomes_three_of_a_kind_in_omaha() {
        List<GameCard> hole = List.of(
            c(GameCard.Suit.SPADES,   GameCard.Rank.SEVEN),
            c(GameCard.Suit.HEARTS,   GameCard.Rank.EIGHT),
            c(GameCard.Suit.CLUBS,    GameCard.Rank.TWO),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.THREE)
        );
        List<GameCard> board = List.of(
            c(GameCard.Suit.HEARTS,   GameCard.Rank.ACE),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.ACE),
            c(GameCard.Suit.CLUBS,    GameCard.Rank.ACE),
            c(GameCard.Suit.HEARTS,   GameCard.Rank.KING),
            c(GameCard.Suit.DIAMONDS, GameCard.Rank.KING)
        );
        GameHand result = evaluateOmaha(hole, board);
        assertNotEquals(GameHandRank.FULL_HOUSE, result.getRank(),
            "Board is A-A-A-K-K but Omaha forces 2 hole cards — FULL_HOUSE is impossible here");
        assertEquals(GameHandRank.THREE_OF_A_KIND, result.getRank(),
            "Best valid Omaha hand: any 2 hole cards + A♥A♦A♣ = THREE_OF_A_KIND");
    }
}

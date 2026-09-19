package dev.manestack.service.poker.table;

import dev.manestack.domain.poker.GamePlayer;
import dev.manestack.service.poker.card.GameCard;
import dev.manestack.service.poker.card.GameHand;
import dev.manestack.service.poker.card.GameHandEvaluator;
import dev.manestack.service.poker.card.GameHandRank;
import io.smallrye.mutiny.tuples.Tuple3;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@ApplicationScoped
public class BotSimulator {
    private static final Logger LOG = Logger.getLogger(BotSimulator.class);

    private static final ExecutorService BOT_THREAD_POOL =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "bot-worker");
                t.setDaemon(true);
                return t;
            });

    private static final Random RANDOM = new Random();

    private final Map<Integer, Tuple3<Double, Double, Double>> monteCarloValues = new HashMap<>();

    public static void submitDelayedBotTask(Runnable task, long delayMs) {
        BOT_THREAD_POOL.submit(() -> {
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            task.run();
        });
    }

    public Map<Integer, Tuple3<Double, Double, Double>> getMonteCarloValues() {
        return monteCarloValues;
    }

    public void submitBotAction(GameSession session, GamePlayer bot) {
        BOT_THREAD_POOL.submit(() -> {
            if (bot.isGoodBot()) {
                simulateGoodBot(session, bot);
            } else {
                simulateGame(session, bot);
            }
        });
    }

    boolean shouldAct(GameSession session) {
        boolean shouldAct = true;
        int nonAllInPlayers = (int) session.getOriginalPlayerList().stream()
                .filter(player -> player.isInHand() && !player.isAllIn())
                .count();
        if (nonAllInPlayers == 0) {
            LOG.infov("No non-all-in players left in session {0}, skipping action for current player {1}",
                    session.getSessionId(), session.getCurrentPlayer().getUser().getUsername());
            shouldAct = false;
        } else if (nonAllInPlayers == 1) {
            int maxBet = session.getPlayerBets().values().stream().max(Integer::compareTo).orElse(0);
            if (session.getPlayerBets().getOrDefault(session.getCurrentPlayer().getSeatId(), 0) >= maxBet) {
                shouldAct = false;
            }
        }
        return shouldAct;
    }

    public void simulateGoodBot(GameSession session, GamePlayer botPlayer) {
        try {
            Thread.sleep(RANDOM.nextInt(2000, 4000));

            if (botPlayer == null) return;
            if (!botPlayer.isInHand() || botPlayer.isAllIn()) return;
            if (session.getState() == GameSession.State.SHOWDOWN || session.getState() == GameSession.State.FINISHED || session.getState() == GameSession.State.CHIP_COLLECTION) return;
            if (session.getCurrentActingSeat() == null || session.getCurrentActingSeat() != botPlayer.getSeatId()) return;

            List<GameCard> fullBoard = new ArrayList<>(session.getDestinedCommunityCardList());

            List<GameCard> botCards = new ArrayList<>(botPlayer.getHoleCards());
            botCards.addAll(fullBoard);
            GameHand botBest = GameHandEvaluator.evaluate(botCards);

            boolean botIsGuaranteedWinner = true;
            for (GamePlayer opponent : session.getOriginalPlayerList()) {
                if (opponent == botPlayer) continue;
                if (!opponent.isInHand() || opponent.isFolded()) continue;

                List<GameCard> oppHole = opponent.getHoleCards();
                if (oppHole == null || oppHole.isEmpty()) {
                    botIsGuaranteedWinner = false;
                    break;
                }

                List<GameCard> oppCards = new ArrayList<>(oppHole);
                oppCards.addAll(fullBoard);
                GameHand oppBest = GameHandEvaluator.evaluate(oppCards);

                if (botBest.compareTo(oppBest) <= 0) {
                    botIsGuaranteedWinner = false;
                    break;
                }
            }

            int bb          = session.getTable().getBigBlind();
            int botStack    = botPlayer.getStack();
            int botBet      = session.getPlayerBets().getOrDefault(botPlayer.getSeatId(), 0);
            int highestBet  = session.getPlayerBets().values().stream().max(Integer::compareTo).orElse(0);
            int callAmount  = Math.max(0, highestBet - botBet);
            int pot         = session.getPotSize();
            int minAllowed  = highestBet + Math.min(2 * bb, botStack);
            int raisePot    = Math.min(botStack, Math.max(minAllowed, callAmount + pot));

            GameSession.ActionType action;
            int amount;

            if (botIsGuaranteedWinner) {
                action = GameSession.ActionType.RAISE;
                amount = raisePot;
            } else {
                if (callAmount == 0) {
                    action = GameSession.ActionType.CHECK;
                    amount = 0;
                } else {
                    action = GameSession.ActionType.FOLD;
                    amount = 0;
                }
            }

            session.receivePlayerAction(botPlayer.getUser().getUserId(), action, amount, false);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnv("Good-bot {0} thread interrupted in session {1}", botPlayer.getUser().getUsername(), session.getSessionId());
        }
    }

    public void simulateGame(GameSession session, GamePlayer botPlayer) {
        try {
            Thread.sleep(RANDOM.nextInt(2000, 3000));

            if (botPlayer == null) return;
            if (!botPlayer.isInHand() || botPlayer.isAllIn()) return;
            if (session.getState() == GameSession.State.SHOWDOWN || session.getState() == GameSession.State.FINISHED || session.getState() == GameSession.State.CHIP_COLLECTION) return;
            if (session.getCurrentActingSeat() == null || session.getCurrentActingSeat() != botPlayer.getSeatId()) return;

            int bb = session.getTable().getBigBlind();
            int botStack = botPlayer.getStack();
            int botBet = session.getPlayerBets().getOrDefault(botPlayer.getSeatId(), 0);
            int highestBet = session.getPlayerBets().values().stream().max(Integer::compareTo).orElse(0);
            int callAmount = Math.max(0, highestBet - botBet);

            boolean preflop = session.getState() == GameSession.State.PRE_FLOP;
            PreflopCategory cat = classifyPreflop(botPlayer);

            int pot = session.getPotSize();

            int minRaise = Math.min(2 * bb, botStack);
            int minAllowedRaise = highestBet + minRaise;

            int raisePot = Math.min(botStack, Math.max(minAllowedRaise, callAmount + pot));

            GameSession.ActionType action = GameSession.ActionType.FOLD;
            int amount = 0;

            if (preflop) {

                switch (cat) {

                    case TOP_PAIR:
                        action = GameSession.ActionType.RAISE;
                        amount = raisePot;
                        break;

                    case PREMIUM_PAIR:
                        if (callAmount == 0) {
                            action = GameSession.ActionType.CHECK;
                            amount = 0;
                        } else {
                            action = GameSession.ActionType.CALL;
                            amount = Math.min(callAmount, botStack);
                        }
                        break;

                    case Strong:
                        if (callAmount == 0) {
                            action = GameSession.ActionType.CHECK;
                            amount = 0;
                        } else {
                            action = GameSession.ActionType.CALL;
                            amount = Math.min(callAmount, botStack);
                        }
                        break;

                    default:
                        if (callAmount == 0) {
                            action = GameSession.ActionType.CHECK;
                            amount = 0;
                        } else {
                            action = GameSession.ActionType.FOLD;
                            amount = 0;
                        }
                        break;
                }

            } else {

                PostflopStrength pf = evaluatePostflop(session, botPlayer);
                boolean facingRaise = callAmount > 0;
                boolean canCall = callAmount > 0 && callAmount <= botStack;

                switch (pf) {

                    case NUTS:
                    case STRONG:
                        if (canCall) {
                            action = GameSession.ActionType.CALL;
                            amount = Math.min(callAmount, botStack);
                        } else {
                            action = GameSession.ActionType.RAISE;
                            amount = raisePot;
                        }
                        break;

                    case MEDIUM:
                        if (facingRaise) {
                            action = GameSession.ActionType.CALL;
                            amount = Math.min(callAmount, botStack);
                        } else {
                            action = GameSession.ActionType.RAISE;
                            amount = raisePot;
                        }
                        break;

                    case WEAK:
                        action = facingRaise ? GameSession.ActionType.FOLD : GameSession.ActionType.RAISE;
                        amount = facingRaise ? 0 : raisePot;
                        break;

                    default:
                        action = facingRaise ? GameSession.ActionType.FOLD : GameSession.ActionType.CHECK;
                        amount = 0;
                        break;
                }
            }

            session.receivePlayerAction(botPlayer.getUser().getUserId(), action, amount, false);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnv("Regular-bot {0} thread interrupted in session {1}", botPlayer.getUser().getUsername(), session.getSessionId());
        }
    }

    public double calculateWinningPercentage(GamePlayer bot, List<GameCard> destinedCommunityCards, Map<Integer, GamePlayer> seats, int simulations) {
        int wins = 0;
        int ties = 0;

        List<GameCard> botHand = new ArrayList<>(bot.getHoleCards());
        botHand.addAll(destinedCommunityCards);
        GameHand botBestHand = GameHandEvaluator.evaluate(botHand);

        List<GameCard> usedCards = new ArrayList<>(bot.getHoleCards());
        usedCards.addAll(destinedCommunityCards);

        for (int i = 0; i < simulations; i++) {
            boolean botWinsThisRound = true;

            for (Map.Entry<Integer, GamePlayer> entry : seats.entrySet()) {
                GamePlayer opponent = entry.getValue();
                if (opponent == null || !opponent.isInHand() || opponent == bot) continue;

                List<GameCard> opponentHand = new ArrayList<>();
                if (opponent.getHoleCards() != null && !opponent.getHoleCards().isEmpty()) {
                    opponentHand.addAll(opponent.getHoleCards());
                } else {
                    opponentHand.addAll(drawRandomHoleCardsExcluding(usedCards));
                }

                List<GameCard> fullOpponentHand = new ArrayList<>(opponentHand);
                fullOpponentHand.addAll(destinedCommunityCards);

                GameHand opponentBestHand = GameHandEvaluator.evaluate(fullOpponentHand);

                int cmp = botBestHand.compareTo(opponentBestHand);
                if (cmp < 0) {
                    botWinsThisRound = false;
                    break;
                } else if (cmp == 0) {
                    ties++;
                }
            }

            if (botWinsThisRound) wins++;
        }

        return (wins + ties * 0.5) / simulations;
    }

    public List<GameCard> drawRandomHoleCardsExcluding(List<GameCard> usedCards) {
        List<GameCard> deck = GameCard.fullDeck();
        deck.removeAll(usedCards);

        Collections.shuffle(deck);
        return deck.subList(0, 2);
    }

    enum PreflopCategory {
        TOP_PAIR,
        PREMIUM_PAIR,
        Strong,
        OTHER
    }

    static boolean isStrongCombo(int r1, int r2) {
        int[][] strong = {
            {14,13}, {14,12}, {14,11}, {14,10},
            {13,12}, {13,11}, {13,10}, {13,9},
            {12,11}, {12,10}, {12,9}, {12,8},
            {11,10}, {11,9}, {11,8}, {11,7},
            {10,9},  {10,8}, {10,7}, {10,6}
        };

        for (int[] c : strong) {
            if ((r1 == c[0] && r2 == c[1]) ||
                (r1 == c[1] && r2 == c[0])) {
                return true;
            }
        }

        return false;
    }

    PreflopCategory classifyPreflop(GamePlayer p) {
        GameCard c1 = p.getHoleCards().get(0);
        GameCard c2 = p.getHoleCards().get(1);

        int r1 = c1.getRank().getValue();
        int r2 = c2.getRank().getValue();

        if (r1 == r2) {
            if (r1 >= 12) {
                return PreflopCategory.TOP_PAIR;
            }

            if (r1 >= 2) {
                return PreflopCategory.PREMIUM_PAIR;
            }
        }
        if (isStrongCombo(r1, r2))
            return PreflopCategory.Strong;

        return PreflopCategory.OTHER;
    }

    enum PostflopStrength {
        NUTS,
        STRONG,
        MEDIUM,
        WEAK,
        OTHER,
    }

    PostflopStrength evaluatePostflop(GameSession session, GamePlayer player) {
        List<GameCard> hole = player.getHoleCards();
        List<GameCard> board = session.getCommunityCards();

        List<GameCard> combined = new ArrayList<>();
        combined.addAll(hole);
        combined.addAll(board);

        GameHand best = GameHandEvaluator.evaluate(combined);
        GameHandRank rank = best.getRank();

        switch (rank) {

            case ROYAL_FLUSH:
            case STRAIGHT_FLUSH:
            case FOUR_OF_A_KIND:
            case FULL_HOUSE:
                return PostflopStrength.NUTS;

            case FLUSH:
            case STRAIGHT:
            case THREE_OF_A_KIND:
                return PostflopStrength.STRONG;

            case TWO_PAIR:
                return PostflopStrength.MEDIUM;
            case ONE_PAIR:
                return PostflopStrength.WEAK;

            default:
                return PostflopStrength.OTHER;
        }
    }
}

package dev.manestack.service.poker.table;

import dev.manestack.domain.poker.GamePlayer;
import dev.manestack.domain.poker.GameSidePot;
import dev.manestack.service.poker.card.GameCard;
import dev.manestack.service.poker.card.GameHand;
import dev.manestack.service.poker.card.GameHandEvaluator;
import dev.manestack.service.poker.card.GameVariant;
import org.jboss.logging.Logger;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

public class ShowdownHandler {
    private static final Logger LOG = Logger.getLogger(ShowdownHandler.class);

    private final GameSession session;
    private boolean winningsCalculated = false;
    private boolean showdownStarted = false;
    private GameHand currentBestHand = null;
    private GamePlayer currentBestPlayer = null;
    private final Queue<GamePlayer> showdownQueue = new LinkedList<>();
    private final ScheduledExecutorService showdownScheduler = Executors.newSingleThreadScheduledExecutor();
    private final Map<Integer, GameHand> revealedHands = new HashMap<>();
    private Integer currentActingSeat;

    public ShowdownHandler(GameSession session) {
        this.session = session;
    }

    public void startShowdownRevealSequence() {
        if (showdownStarted) return;
        showdownStarted = true;

        if (session.getState() == GameSession.State.RIVER) {
            session.setState(GameSession.State.SHOWDOWN);
            session.getTable().sendGameStateUpdateToParticipants(session.getState(), session.getCommunityCards());
        }

        LOG.infov("Starting showdown reveal sequence for session {0}", session.getSessionId());

        List<GamePlayer> activePlayers = session.getOriginalPlayerList().stream()
                .filter(GamePlayer::isInHand)
                .toList();

        if (activePlayers.size() == 1) {
            finishShowdownIfNeeded();
            return;
        }

        showdownQueue.clear();

        GamePlayer lastAggressor = session.getOriginalPlayerList().stream()
                .filter(p -> p.getSeatId() == session.getLastRaiseSeat())
                .findFirst()
                .orElse(null);

        int totalPlayers = session.getOriginalPlayerList().size();
        int lastAggressorIndex = session.getOriginalPlayerList().indexOf(lastAggressor);

        int safeStartIndex = (lastAggressorIndex < 0) ? 0 : lastAggressorIndex;

        for (int i = 0; i < totalPlayers; i++) {
            int idx = (safeStartIndex + i) % totalPlayers;
            GamePlayer player = session.getOriginalPlayerList().get(idx);
            if (player.isInHand()) {
                showdownQueue.add(player);
            }
        }

        LOG.infov("Normal showdown. Last aggressor {0} reveals first.",
                lastAggressor != null ? lastAggressor.getUser().getUsername() : "none");

        revealNextShowdownPlayer();
    }

    private void revealNextShowdownPlayer() {
        LOG.debugv("revealNextShowdownPlayer(): queue size={0}", showdownQueue.size());

        if (showdownQueue.isEmpty()) {
            revealNextCommunityStageAllIn();
            return;
        }

        GamePlayer player = showdownQueue.poll();

        if (player == null || !player.isInHand()) {
            LOG.warnv("revealNextShowdownPlayer: invalid player in queue — rescheduling (session={0})", session.getSessionId());
            showdownScheduler.schedule(this::revealNextShowdownPlayer, 50, TimeUnit.MILLISECONDS);
            return;
        }

        GameHand hand = evaluateHand(player);

        boolean isForcedAllInShowdown = session.getOriginalPlayerList().stream()
                .filter(GamePlayer::isInHand)
                .anyMatch(GamePlayer::isAllIn);

        if (player.isAllIn() || isForcedAllInShowdown) {
            reveal(player, hand);
            if (currentBestHand == null || hand.compareTo(currentBestHand) > 0) {
                updateBest(player, hand);
            }
            next();
            return;
        }

        if (currentBestHand == null) {
            reveal(player, hand);
            updateBest(player, hand);
            next();
            return;
        }

        if (hand.compareTo(currentBestHand) >= 0) {
            reveal(player, hand);
            if (hand.compareTo(currentBestHand) > 0) {
                updateBest(player, hand);
            }
            next();
        } else {
            askDecision(player);
        }
    }

    private GameHand evaluateHand(GamePlayer player) {
        return evaluateBestHandForVariant(player.getHoleCards());
    }

    private GameHand evaluateBestHandForVariant(List<GameCard> holeCards) {
        boolean isOmaha = session.getTable().getGameVariant().equals(GameVariant.OMAHA.name());
        if (isOmaha && holeCards.size() == 4 && session.getCommunityCards().size() >= 3) {
            GameHand best = null;
            for (int i = 0; i < holeCards.size(); i++) {
                for (int j = i + 1; j < holeCards.size(); j++) {
                    for (int a = 0; a < session.getCommunityCards().size(); a++) {
                        for (int b = a + 1; b < session.getCommunityCards().size(); b++) {
                            for (int c = b + 1; c < session.getCommunityCards().size(); c++) {
                                List<GameCard> five = List.of(
                                    holeCards.get(i), holeCards.get(j),
                                    session.getCommunityCards().get(a), session.getCommunityCards().get(b), session.getCommunityCards().get(c)
                                );
                                GameHand hand = GameHandEvaluator.evaluate(five);
                                if (hand != null && (best == null || hand.compareTo(best) > 0)) best = hand;
                            }
                        }
                    }
                }
            }
            return best;
        }
        return GameHandEvaluator.evaluate(
            Stream.concat(holeCards.stream(), session.getCommunityCards().stream()).toList()
        );
    }

    private void reveal(GamePlayer player, GameHand hand) {
        revealedHands.put(player.getSeatId(), hand);
        player.setRevealApproved(true);
        player.setTurnStartDate(LocalDateTime.now());
        session.getTable().sendTurnUpdateToParticipants(player, true);
        session.getTable().propagatePlayerEvent(
            player,
            GameSession.ActionType.REVEAL_CARDS,
            0,
            player.getStack(),
            session.getPlayerBets()
        );

        LOG.infov("Revealed {0}", player.getUser().getUsername());
    }

    private void updateBest(GamePlayer player, GameHand hand) {
        currentBestHand = hand;
        currentBestPlayer = player;

        LOG.debugv("updateBest: new leader={0}", player.getUser().getUsername());
    }

    private void next() {
        showdownScheduler.schedule(this::revealNextShowdownPlayer, 800, TimeUnit.MILLISECONDS);
    }

    public void finishShowdownIfNeeded() {
        if (winningsCalculated) return;
        winningsCalculated = true;
        session.finishCurrentHand(revealedHands);
    }

    void revealNextCommunityStageAllIn() {
        LOG.debugv("revealNextCommunityStageAllIn(): state={0}", session.getState());
        switch (session.getState()) {
            case PRE_FLOP -> {
                session.setState(GameSession.State.FLOP);
                session.getCommunityCards().add(session.getDestinedCommunityCards().poll());
                session.getCommunityCards().add(session.getDestinedCommunityCards().poll());
                session.getCommunityCards().add(session.getDestinedCommunityCards().poll());
            }
            case FLOP -> {
                session.setState(GameSession.State.TURN);
                session.getCommunityCards().add(session.getDestinedCommunityCards().poll());
            }
            case TURN -> {
                session.setState(GameSession.State.RIVER);
                session.getCommunityCards().add(session.getDestinedCommunityCards().poll());
            }
            case RIVER -> {
                session.setState(GameSession.State.SHOWDOWN);
            }
            default -> {}
        }

        session.getTable().sendGameStateUpdateToParticipants(session.getState(), session.getCommunityCards());

        if (session.getState() != GameSession.State.SHOWDOWN) {
            showdownScheduler.schedule(this::revealNextCommunityStageAllIn, 2, TimeUnit.SECONDS);
        } else {
            showdownScheduler.schedule(this::finishShowdownIfNeeded, 0, TimeUnit.SECONDS);
        }
    }

    private void askDecision(GamePlayer player) {
        LOG.debugv("askDecision: player={0}", player.getUser().getUsername());

        if (player.isBot()) {
            LOG.infov("Bot {0} mucking losing hand", player.getUser().getUsername());
            currentActingSeat = player.getSeatId();
            player.setShowdownActed(true);
            player.setRevealApproved(false);
            session.getTable().sendPlayerActionUpdate(
                    player,
                    GameSession.ActionType.HIDE_CARDS,
                    0,
                    false,
                    session.getCommunityCards(),
                    session.getPlayerBets()
            );
            next();
            return;
        }

        player.setTurnStartDate(LocalDateTime.now());
        player.setShowdownActed(false);

        currentActingSeat = player.getSeatId();

        session.getTable().sendTurnUpdateToParticipants(player, false, 5);

        showdownScheduler.schedule(() -> {
            if (!showdownStarted || session.getState() == GameSession.State.FINISHED || session.getState() == GameSession.State.WAITING_FOR_PLAYERS) {
                return;
            }
            if (currentActingSeat == null || !currentActingSeat.equals(player.getSeatId())) {
                return;
            }
            if (!player.hasActedShowdown()) {
                LOG.infov("Showdown auto-muck for player {0} (session={1})", player.getUser().getUsername(), session.getSessionId());
                player.setShowdownActed(true);
                player.setRevealApproved(false);
                session.getTable().sendPlayerActionUpdate(player, GameSession.ActionType.AUTO_MUCK, 0, true, session.getCommunityCards(), session.getPlayerBets());
                next();
            }
        }, 5, TimeUnit.SECONDS);
    }

    private void botMuckSilently(GamePlayer player, GameHand hand) {
        player.setRevealApproved(false);
        player.setShowdownActed(true);
        player.setTurnStartDate(LocalDateTime.now());
        session.getTable().sendPlayerActionUpdate(
                player,
                GameSession.ActionType.AUTO_MUCK,
                0,
                false,
                session.getCommunityCards(),
                session.getPlayerBets()
        );
        LOG.infov("Bot {0} silently mucked at showdown", player.getUser().getUsername());
    }

    public boolean handleShowdownAction(GamePlayer player, GameSession.ActionType actionType) {
        if (currentActingSeat == null || player.getSeatId() != currentActingSeat) return false;

        player.setShowdownActed(true);

        if (actionType == GameSession.ActionType.REVEAL_CARDS) {
            GameHand hand = evaluateHand(player);
            revealedHands.put(player.getSeatId(), hand);
            player.setRevealApproved(true);
            if (currentBestHand == null || hand.compareTo(currentBestHand) > 0) {
                updateBest(player, hand);
            }
        } else {
            player.setRevealApproved(false);
            LOG.infov("{0} MUCKED", player.getUser().getUsername());
        }

        session.getTable().sendPlayerActionUpdate(player, actionType, 0, false,
                session.getCommunityCards(), session.getPlayerBets());
        next();
        return true;
    }

    public Map<Integer, GameHand> getRevealedHands() {
        return revealedHands;
    }

    public boolean isWinningsCalculated() {
        return winningsCalculated;
    }

    public void setWinningsCalculated(boolean val) {
        winningsCalculated = val;
    }

    public void shutdown() {
        showdownScheduler.shutdown();
    }
}

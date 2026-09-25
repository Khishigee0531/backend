package dev.manestack.service.poker.table;

import dev.manestack.domain.poker.GamePlayer;
import dev.manestack.domain.poker.GameSidePot;
import dev.manestack.service.GameService;
import dev.manestack.service.UserService;
import dev.manestack.service.poker.card.FairGameDeck;
import dev.manestack.service.poker.card.GameCard;
import dev.manestack.service.poker.card.GameHand;
import dev.manestack.service.poker.card.GameVariant;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;

import org.jboss.logging.Logger;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jooq.DSLContext;


public class GameSession {
    private static final Logger LOG = Logger.getLogger(GameSession.class);
    private static final Integer TIMEOUT_SECONDS = 20;
    /**
     * Watchdog: if a hand sits in an active betting stage with no acting player
     * (or no turn timer running) for longer than this, the session is considered
     * a zombie and is force-finished so the table can resume. Must comfortably
     * exceed TIMEOUT_SECONDS plus normal transition delays.
     */
    private static final long ZOMBIE_GRACE_MS = 45_000;
    /** Timestamp of the last meaningful state/action progress in this session. */
    private volatile long lastProgressMs = System.currentTimeMillis();
    /** Guards against concurrent/duplicate zombie-recovery runs. */
    private final AtomicBoolean forceFinishing = new AtomicBoolean(false);
    public static final Integer ALL_IN_REVEAL_DELAY_MILLIS = 1500;
    public static final Integer CHIP_COLLECTION_DELAY_MILLIS = 700;
    public static final Integer DEALING_DELAY_MILLIS = 950;
    private static final ScheduledExecutorService NEXT_GAME_SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "next-game-scheduler");
        t.setDaemon(true);
        return t;
    });
    private final String sessionId;
    private final GameTable table;
    private final List<GamePlayer> originalPlayerList = new ArrayList<>();
    private State state;
    private GamePlayer currentPlayer;
    private int pot;
    private int pivotalPlayerIndex;
    private boolean isAllInFlip = false;
    private boolean lastActionWasTimeout = false;
    private double totalRake;
    private int lastRaiseSeat = -1;
    private int lastRaiseSize = 0;
    private int lastAggressorSeat = -1; 
    private int bigBlindSeatIndex = -1;
    private int smallBlindSeatIndex = -1;
    private int dealerSeatIndex = -1;
    private final List<GameCard> communityCards = new ArrayList<>();
    private final List<GameCard> destinedCommunityCardList = new ArrayList<>();
    private final Queue<GameCard> destinedCommunityCards = new LinkedList<>();
    private final Queue<GamePlayer> currentQueue = new LinkedList<>();
    private final Queue<GamePlayer> actedQueue = new LinkedList<>();
    private final Map<Integer, Integer> playerBets = new ConcurrentHashMap<>();
    private Map<Integer, GameHand> revealedHands = new HashMap<>();
    private final List<GameSidePot> sidePots = new ArrayList<>();
    private final List<Runnable> onFinishCallbacks = new ArrayList<>();
    private final FairGameDeck deck;      
    private final String clientSeed;      
    private final String serverSeed;        
    private final String deckHash;  
    private final GameService gameService;
    private volatile Integer currentActingSeat;
    private State priorState; 
    private final PlayerRoundService playerRoundService;
    private final BettingService bettingService;
    private final PlayerStateService playerStateService;
    private final PokerScoreService pokerScoreService;
    private final BotSimulator botSimulator;
    private final PayoutCalculator payoutCalculator;
    private ShowdownHandler showdownHandler;
    private boolean showdownHandlerCreated = false;
    
    
    @Inject
    UserService userService;
    @Inject
    DSLContext jooq;



   public GameSession(
            String sessionId,
            GameTable table,
            int dealerPosition,
            Map<Integer, GamePlayer> players,
            UserService userService,
            String clientSeed, 
            GameService gameService,
            PlayerRoundService playerRoundService,
            BettingService bettingService,
            PlayerStateService playerStateService,
            PokerScoreService pokerScoreService,
            BotSimulator botSimulator,
            PayoutCalculator payoutCalculator
    ) {
        this.sessionId = sessionId;
        this.table = table;
        this.state = State.WAITING_FOR_PLAYERS;
        this.userService = userService;
        this.gameService = gameService;
        this.playerRoundService = playerRoundService;
        this.pokerScoreService = pokerScoreService;
        this.bettingService = bettingService;
        this.playerStateService = playerStateService;
        this.botSimulator = botSimulator;
        this.payoutCalculator = payoutCalculator;
        this.clientSeed = clientSeed != null ? clientSeed : UUID.randomUUID().toString();
        this.deck = new FairGameDeck(this.clientSeed);
        this.deckHash = deck.getDeckHash();
        this.serverSeed = deck.getServerSeed();

        for (int i = 0; i < 5; i++) {
            GameCard gameCard = deck.drawCard();
            destinedCommunityCardList.add(gameCard);
            destinedCommunityCards.add(gameCard);
        }

        this.pivotalPlayerIndex = dealerPosition;

        List<Integer> orderedSeats = players.keySet().stream().sorted().toList();
        for (int seat : orderedSeats) {
            GamePlayer player = players.get(seat);
            if (player != null && player.getStack() > 0 ) {
                originalPlayerList.add(player);
            }
        }
    }


    public String getDeckHash() { return deckHash; }
    public String getServerSeed() { return serverSeed; }
    public String getClientSeed() { return clientSeed; }
    
    public static class BlindsInfo {
        public final int smallBlindSeat;
        public final int bigBlindSeat;

        public BlindsInfo(int sb, int bb) {
            this.smallBlindSeat = sb;
            this.bigBlindSeat = bb;
        }
    }

    private GamePlayer getNextActivePlayer(int startIndex) {
        int totalPlayers = originalPlayerList.size();
        for (int i = 1; i <= totalPlayers; i++) {
            int index = (startIndex + i) % totalPlayers;
            GamePlayer player = originalPlayerList.get(index);
            if (player != null && player.isInHand()) {
                return player;
            }
        }
        return null; 
    }

    private BlindsInfo assignAndPostBlinds() {
        lastRaiseSize = 0;
        int smallBlindAmount = table.getSmallBlind();
        int bigBlindAmount = table.getBigBlind();

        GamePlayer dealerPlayer = originalPlayerList.get(pivotalPlayerIndex);
        if (dealerPlayer == null) return null;
        dealerSeatIndex = dealerPlayer.getSeatId();

        GamePlayer smallBlindPlayer = getNextActivePlayer(pivotalPlayerIndex);
        if (smallBlindPlayer == null) return null;

        int actualSB = bettingService.placeBet(smallBlindPlayer, smallBlindAmount);

        if (smallBlindPlayer.isAllIn()) {
            currentQueue.remove(smallBlindPlayer);
        }

        smallBlindSeatIndex = smallBlindPlayer.getSeatId();

        if (originalPlayerList.size() == 2) {
            dealerSeatIndex = smallBlindPlayer.getSeatId();
        }

        actedQueue.add(smallBlindPlayer);
        playerBets.put(smallBlindPlayer.getSeatId(), actualSB);

        table.propagatePlayerEvent(
            smallBlindPlayer,
            smallBlindPlayer.isAllIn() ? ActionType.ALL_IN : ActionType.SMALL_BLIND,
            actualSB,
            smallBlindPlayer.getStack(),
            playerBets
        );

        lastRaiseSeat = smallBlindPlayer.getSeatId();

        GamePlayer bigBlindPlayer = getNextActivePlayer(originalPlayerList.indexOf(smallBlindPlayer));
        if (bigBlindPlayer == null) return null;

        int actualBB = bettingService.placeBet(bigBlindPlayer, bigBlindAmount);

        if (bigBlindPlayer.isAllIn()) {
            currentQueue.remove(bigBlindPlayer);
        }

        bigBlindSeatIndex = bigBlindPlayer.getSeatId();
        playerBets.put(bigBlindPlayer.getSeatId(), actualBB);

        actedQueue.add(bigBlindPlayer);

        table.propagatePlayerEvent(
            bigBlindPlayer,
            bigBlindPlayer.isAllIn() ? ActionType.ALL_IN : ActionType.BIG_BLIND,
            actualBB,
            bigBlindPlayer.getStack(),
            playerBets
        );

        LOG.infov(
            "Blinds assigned - Dealer: {0}, SB: {1}, BB: {2}",
            dealerSeatIndex,
            smallBlindPlayer.getSeatId(),
            bigBlindPlayer.getSeatId()
        );

        rotateQueue(bigBlindSeatIndex);

        return new BlindsInfo(smallBlindSeatIndex, bigBlindSeatIndex);
    }

    public void startGame() {
        LOG.infov("startGame() called for session {0}", sessionId);
        touchProgress();

        for (GamePlayer gamePlayer : originalPlayerList) {
            playerRoundService.resetForNewGame(gamePlayer); 
        }

        state = State.DEALING;
        table.sendGameStateUpdateToParticipants(state, communityCards);

        NEXT_GAME_SCHEDULER.schedule(() -> {
            // Any exception thrown here would be silently swallowed by the
            // ScheduledExecutorService, leaving the hand wedged in PRE_FLOP
            // with no acting player and freezing the table forever. Recover
            // explicitly instead.
            try {
                state = State.PRE_FLOP;
                assignAndPostBlinds();
                dealCards();

                table.sendGameStateUpdateToParticipants(state, communityCards);
                table.sendTableUpdateToParticipants("NO_TIMEOUT", communityCards);

                promptNextPlayer();
                touchProgress();
            } catch (Throwable t) {
                LOG.errorv(t, "Dealing task failed for session {0} — forcing hand recovery", sessionId);
                forceFinishZombieHand("dealing task failed: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }, DEALING_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void touchProgress() {
        lastProgressMs = System.currentTimeMillis();
    }

    private void rotateQueue(int startAfterSeat) {
        currentQueue.clear();

        List<GamePlayer> activePlayers = originalPlayerList.stream()
                .filter(p -> p != null && p.isInHand() && !p.isAllIn())
                .sorted(Comparator.comparingInt(GamePlayer::getSeatId))
                .toList();

        if (activePlayers.isEmpty()) {
            LOG.infov("No active players found to start betting in session {0}", sessionId);
            return;
        }

        int rotateIndex = 0;
        for (int i = 0; i < activePlayers.size(); i++) {
            if (activePlayers.get(i).getSeatId() > startAfterSeat) {
                rotateIndex = i;
                break;
            }
        }

        for (int i = 0; i < activePlayers.size(); i++) {
            int index = (rotateIndex + i) % activePlayers.size();
            currentQueue.add(activePlayers.get(index));
        }
    }

    private void promptNextPlayer() {
        if (state == State.SHOWDOWN || state == State.CHIP_COLLECTION) return;
        long nonAllInPlayersInHand = originalPlayerList.stream()
                .filter(p -> p.isInHand() && !p.isAllIn())
                .count();

        boolean allPlayersActed = currentQueue.isEmpty();


        if (originalPlayerList.size() == 2 && state == State.PRE_FLOP) {
            int sbBet = playerBets.getOrDefault(smallBlindSeatIndex, 0);
            int bbBet = playerBets.getOrDefault(bigBlindSeatIndex, 0);
            GamePlayer bbPlayer = originalPlayerList.stream()
                .filter(p -> p.getSeatId() == bigBlindSeatIndex )
                .findFirst()
                .orElse(null);            
            if (bbPlayer != null && bbPlayer.isAllIn() && bbBet < sbBet) {
                LOG.infov("BB player is all-in for {0} which is less than SB bet of {1}. Going to showdown.", bbBet, sbBet);
                currentActingSeat = null;
                collectChipsAndTransition(this::startShowdownRevealSequence);
                return;
            }
        }

        if (nonAllInPlayersInHand <= 1 && allPlayersActed) {
            LOG.infov("All active players are all-in. Starting showdown.");
            currentActingSeat = null;
            collectChipsAndTransition(this::startShowdownRevealSequence);
            return;
        }

        if (currentQueue.isEmpty()) {
            boolean allBetsEqual = table.getSeats().values().stream()
                .filter(p -> p.isInHand() && !p.isAllIn() )
                .map(p -> playerBets.getOrDefault(p.getSeatId(), 0))
                .distinct()
                .count() <= 1;

            boolean lastAggressorActive = table.getSeats().values().stream()
                .anyMatch(p -> p.isInHand()  && p.getSeatId() == lastAggressorSeat);

            if (allBetsEqual || !lastAggressorActive) {
                currentActingSeat = null;
                collectChipsAndTransition(this::advanceGameState);
                return;
            } else {
                resetActedQueue();
            }
        }

        GamePlayer nextPlayer = null;

        int attempts = currentQueue.size();

        while (attempts-- > 0 && !currentQueue.isEmpty()) {
            GamePlayer candidate = currentQueue.poll();

            if (candidate != null 
                && candidate.isInHand() 
                && !candidate.isAllIn() 
             ) {
                nextPlayer = candidate;
                break;
            }

            if (candidate != null) {
                currentQueue.offer(candidate);
            }
        }

        if (nextPlayer == null) {
            LOG.warn("No valid player left in queue — advancing game state.");
            currentActingSeat = null;
            collectChipsAndTransition(this::advanceGameState);
            return;
        }

        currentPlayer = nextPlayer;
        currentPlayer.setTurnStartDate(LocalDateTime.now());
        currentActingSeat = currentPlayer.getSeatId();
        touchProgress();

        table.sendTurnUpdateToParticipants(
                currentPlayer,
                !botSimulator.shouldAct(this)
        );

        if (currentPlayer.isBot()) {
            GamePlayer bot = currentPlayer;
            botSimulator.submitBotAction(this, bot);
        }
    }

    public void startShowdownRevealSequence() {
        if (!showdownHandlerCreated) {
            showdownHandler = new ShowdownHandler(this);
            showdownHandlerCreated = true;
        }
        showdownHandler.startShowdownRevealSequence();
    }

    private void revealNextCommunityStageAllIn() {
        if (!showdownHandlerCreated) {
            showdownHandler = new ShowdownHandler(this);
            showdownHandlerCreated = true;
        }
        showdownHandler.revealNextCommunityStageAllIn();
    }

    public void finishCurrentHand(Map<Integer, GameHand> revealedHands) {
        this.revealedHands = new HashMap<>(revealedHands);
        payoutCalculator.calculateWinnings(this);
        payoutCalculator.updateStacks(this);
        gameService.saveHandHistory(this)
            .subscribe().with(
                unused -> LOG.infov("Hand history saved for table {0}", table.getTableName()),
                failure -> LOG.errorv(failure, "Failed to save hand history for table {0}", table.getTableName())
            );
        state = State.FINISHED;
        handleFinishedState();
    }

    public void handleLeave(Integer userId) {
        LOG.infov("Player {0} left the game session {1}", userId, sessionId);
        GamePlayer leavingPlayer = originalPlayerList.stream()
                .filter(player -> player.getUser().getUserId() == userId)
                .findFirst()
                .orElse(null);
        if (leavingPlayer == null) return;

        leavingPlayer.setInHand(false);
        leavingPlayer.setFolded(true);
        currentQueue.remove(leavingPlayer);

        if (state == State.SHOWDOWN || state == State.FINISHED || state == State.CHIP_COLLECTION) {
            return;
        }

        long inHandCount = originalPlayerList.stream().filter(GamePlayer::isInHand).count();

        if (inHandCount <= 1) {
            LOG.infov("Only one player remaining in hand. Finishing game state early for session {0}", sessionId);
            currentActingSeat = null;
            currentQueue.clear();
            collectChipsAndTransition(this::startShowdownRevealSequence);
            return;
        }

        if (currentPlayer != null && currentPlayer.getUser().getUserId() == userId) {
            promptNextPlayer();
        }
    }

    public void handleDisconnect(Integer userId) {
        LOG.infov("Player {0} disconnected from game session {1}", userId, sessionId);
        originalPlayerList.stream()
                .filter(player -> player.getUser().getUserId() == userId)
                .findFirst().ifPresent(disconnectedPlayer -> disconnectedPlayer.setDisconnected(true));
    }
    
    public void checkForTurnTimeouts() {
        if (State.FINISHED.equals(state) || state == State.CHIP_COLLECTION) {
            return;
        }
        if (State.SHOWDOWN.equals(state) || state == State.CHIP_COLLECTION) return;

        // Zombie watchdog: a hand wedged in an active betting stage with no
        // progress can never reach handleFinishedState() on its own, which
        // freezes the whole table (startNextGame is only reached through
        // handleFinishedState). Healthy play touches progress at least every
        // TIMEOUT_SECONDS (turn auto-action), so anything beyond the grace
        // window means the flow is dead — force-finish and let the table
        // resume.
        boolean bettingStage = state == State.PRE_FLOP || state == State.FLOP
                || state == State.TURN || state == State.RIVER;
        if (bettingStage) {
            long staleForMs = System.currentTimeMillis() - lastProgressMs;
            if (staleForMs > ZOMBIE_GRACE_MS) {
                String detail = currentPlayer == null
                        ? "no acting player"
                        : "stuck on acting player seat " + currentPlayer.getSeatId();
                forceFinishZombieHand(detail + " for " + staleForMs + "ms in state " + state);
                return;
            }
        }

        if (currentPlayer != null && currentPlayer.isInHand() && !currentPlayer.isAllIn()) {
            if (currentPlayer.isDisconnected()) {
                LOG.infov("Player {0} is disconnected — auto-folding", currentPlayer.getUser().getUsername());
                receivePlayerAction(currentPlayer.getUser().getUserId(), ActionType.FOLD, 0, true);
                return;
            }

            LocalDateTime now = LocalDateTime.now();
            int checkSeconds = TIMEOUT_SECONDS;
            LocalDateTime turnExpiry = currentPlayer.getTurnStartDate() == null ? null : currentPlayer.getTurnStartDate().plusSeconds(checkSeconds);
            
            if (turnExpiry != null && now.isAfter(turnExpiry)) {
                LOG.infov("Player {0} has timed out in session {1}", currentPlayer.getUser().getUsername(), sessionId);
                try {
                    int maxBet = playerBets.values().stream().max(Integer::compareTo).orElse(0);
                    int playerBet = playerBets.getOrDefault(currentPlayer.getSeatId(), 0);
                    
                    boolean shouldFold = maxBet > playerBet;
                        receivePlayerAction(
                            currentPlayer.getUser().getUserId(),
                            shouldFold ? ActionType.FOLD : ActionType.CHECK,
                            0,
                            true
                        );
                } catch (Exception e) {
                    if (State.FINISHED.equals(state)) {
                        return;
                    }
                    LOG.errorv(e, "Error handling timeout for player {0} in session {1}", currentPlayer.getUser().getUsername(), sessionId);
                    receivePlayerAction(currentPlayer.getUser().getUserId(), ActionType.FOLD, 0, true);
                }
            }
        }
    }

    public void receivePlayerAction(Integer playerId, ActionType actionType, int amount, boolean isTimeoutActed) {
        if (state == State.SHOWDOWN || state == State.CHIP_COLLECTION) {
            GamePlayer player = originalPlayerList.stream()
                .filter(p -> p.getUser().getUserId() == playerId)
                .findFirst()
                .orElse(null);

            if (player == null) return;
            if (showdownHandler != null) {
                boolean handled = showdownHandler.handleShowdownAction(player, actionType);
                // Folded players are not in the showdown reveal queue, so
                // handleShowdownAction returns false for them. Handle folded
                // player reveal directly so other players can see their cards.
                if (!handled && ActionType.REVEAL_CARDS.equals(actionType) && player.isFolded()) {
                    player.setRevealApproved(true);
                    player.setShowdownActed(true);
                    table.propagatePlayerEvent(player, actionType, 0, player.getStack(), getPlayerBets());
                }
            }
            return;
        }

        // Folded players may reveal their cards after the hand finishes
        // (state FINISHED). The showdown block above only covers SHOWDOWN.
        if (state == State.FINISHED && ActionType.REVEAL_CARDS.equals(actionType)) {
            GamePlayer player = originalPlayerList.stream()
                .filter(p -> p.getUser().getUserId() == playerId)
                .findFirst()
                .orElse(null);
            if (player != null && player.isFolded() && !player.isRevealApproved()) {
                player.setRevealApproved(true);
                player.setShowdownActed(true);
                table.propagatePlayerEvent(player, actionType, 0, player.getStack(), getPlayerBets());
            }
            return;
        }

        if (state == State.FINISHED || state == State.WAITING_FOR_PLAYERS || state == State.CHIP_COLLECTION) return;

        if (currentPlayer == null) {
            LOG.warnv("receivePlayerAction: currentPlayer is null (session={0}, from playerId={1})", sessionId, playerId);
            return;
        }
        if (currentPlayer.getUser().getUserId() != playerId) {
            LOG.warnv("Stale action from player {0} ignored — acting player is {1} (session={2})",
                    playerId, currentPlayer.getUser().getUserId(), sessionId);
            return;
        }

        if (currentPlayer.isAllIn() || currentPlayer.getStack() <= 0) {
            LOG.infov("Ignoring action: player {0} is all-in or has 0 stack", currentPlayer.getUser().getUsername());
            promptNextPlayer();
            return;
        }

        touchProgress();

        switch (actionType) {

            case FOLD -> {
                currentPlayer.setFolded(true);
                currentPlayer.setInHand(false);
            }

            case CHECK -> {
                int maxBet = playerBets.values().stream().max(Integer::compareTo).orElse(0);
                int currentBet = playerBets.getOrDefault(currentPlayer.getSeatId(), 0);
                if (maxBet > currentBet) {
                    LOG.infov("Invalid CHECK: player must call or fold.");
                    return;
                }
            }

            case CALL -> {
                int highestBet = playerBets.values().stream().max(Integer::compareTo).orElse(0);
                int currentBet = playerBets.getOrDefault(currentPlayer.getSeatId(), 0);
                int callAmount = Math.min(highestBet - currentBet, currentPlayer.getStack());
                if (callAmount < 0) return;

                if (callAmount > 0) {
                    int beforeStack = currentPlayer.getStack();
                    processBet(callAmount);
                    if (beforeStack - callAmount <= 0 || currentPlayer.getStack() == 0) {
                        setCurrentPlayerAllIn();
                    }
                }
            }

            case RAISE -> {
                int highestBet = playerBets.values().stream().max(Integer::compareTo).orElse(0);
                int currentBet = playerBets.getOrDefault(currentPlayer.getSeatId(), 0);
                int callAmountNeeded = highestBet - currentBet;

                int previousRaiseSize = lastRaiseSize > 0 ? lastRaiseSize : table.getBigBlind();
                int minRaiseTotal = currentBet + callAmountNeeded + previousRaiseSize;

                if (amount < minRaiseTotal && amount < currentPlayer.getStack()) {
                    LOG.infov("Invalid RAISE: amount {0} below minimum raise total {1}", amount, minRaiseTotal);
                    return;
                }

                boolean everyoneElseAllIn = originalPlayerList.stream()
                    .filter(p -> p.isInHand() && p.getSeatId() != currentPlayer.getSeatId())
                    .allMatch(GamePlayer::isAllIn);

                if (everyoneElseAllIn) {
                    int maxCall = originalPlayerList.stream()
                        .filter(p -> p.isInHand() && p.getSeatId() != currentPlayer.getSeatId())
                        .mapToInt(p -> playerBets.getOrDefault(p.getSeatId(), 0))
                        .max().orElse(0);

                    int callAmount = Math.min(maxCall - currentBet, currentPlayer.getStack());
                    if (callAmount > 0) processBet(callAmount);

                    LOG.infov("All others all-in → treating RAISE as CALL of {0}", callAmount);

                    if (currentPlayer.getStack() == 0) setCurrentPlayerAllIn();

                    currentQueue.clear();
                    collectChipsAndTransition(this::startShowdownRevealSequence);
                    return;
                }

                int desiredTotal = Math.min(amount, currentPlayer.getStack() + currentBet);
                int increment = desiredTotal - currentBet;
                if (increment <= 0) return;

                int callPart = Math.min(callAmountNeeded, increment);
                int raisePart = increment - callPart;
                if (raisePart > 0) lastRaiseSize = raisePart;

                processBet(increment);

                lastRaiseSeat = currentPlayer.getSeatId();
                lastAggressorSeat = currentPlayer.getSeatId();

                if (currentPlayer.getStack() == 0) {
                    setCurrentPlayerAllIn();
                }

                if (currentPlayer.isAllIn()) {
                    currentQueue.remove(currentPlayer);
                }

                resetActedQueue();
            }

            case ALL_IN -> {
                int allInAmount = currentPlayer.getStack();
                if (allInAmount <= 0) return;

                int currentBet = playerBets.getOrDefault(currentPlayer.getSeatId(), 0);
                int highestBet = playerBets.values().stream().max(Integer::compareTo).orElse(0);
                int callAmountNeeded = highestBet - currentBet;
                int raisePart = allInAmount - callAmountNeeded;
                if (raisePart > 0) lastRaiseSize = raisePart;

                setCurrentPlayerAllIn();
                processBet(allInAmount);

                lastRaiseSeat = currentPlayer.getSeatId();
                lastAggressorSeat = currentPlayer.getSeatId();

                resetActedQueue();
            }

            case REVEAL_CARDS -> currentPlayer.setRevealApproved(true);
            case AUTO_MUCK -> currentPlayer.setRevealApproved(false);

            default -> LOG.infov("Action {0} ignored in current phase.", actionType);
        }
     
        if (isTimeoutActed) {
            playerStateService.markTimeout(currentPlayer);}

        lastActionWasTimeout = isTimeoutActed;

        table.sendPlayerActionUpdate(currentPlayer, actionType, amount, isTimeoutActed, communityCards, playerBets);

        long inHandCount = originalPlayerList.stream()
            .filter(GamePlayer::isInHand)
            .count();

        if (inHandCount <= 1) {
            collectChipsAndTransition(this::startShowdownRevealSequence);
            return;
        }

        long nonAllInPlayersInHand = originalPlayerList.stream()
            .filter(p -> p.isInHand() && !p.isAllIn())
            .count();

        boolean allPlayersActed = currentQueue.isEmpty();
        LOG.infov("Non-all-in players remaining in hand: {0}", nonAllInPlayersInHand);

        boolean allPlayersAllIn = originalPlayerList.stream()
            .filter(p -> p.isInHand())
            .allMatch(p -> p.isAllIn());

        if (allPlayersAllIn && allPlayersActed) {
            LOG.infov("All players are all-in. Starting showdown.");
            currentActingSeat = null;
            collectChipsAndTransition(this::startShowdownRevealSequence);
            return;
        }
        
        promptNextPlayer();
    }

    private void processBet(int amount) {
        int actual = bettingService.placeBet(currentPlayer, amount);

        int currentBet = playerBets.getOrDefault(currentPlayer.getSeatId(), 0);
        playerBets.put(currentPlayer.getSeatId(), currentBet + actual);

        BigDecimal jackpotContribution = BigDecimal.valueOf(actual).multiply(BigDecimal.valueOf(0.001));
        userService.contributeToJackpot(jackpotContribution);

        int contributionInt = jackpotContribution.intValue();
        currentPlayer.setTotalJackpotContribution(
            currentPlayer.getTotalJackpotContribution() + contributionInt
        );
        if (currentPlayer.isAllIn()) {
            currentQueue.remove(currentPlayer);
        }
    }

    private void resetActedQueue() {
        actedQueue.clear();
        currentQueue.clear();

        int highestBet = playerBets.values().stream().max(Integer::compareTo).orElse(0);

        List<GamePlayer> toActPlayers = originalPlayerList.stream()
                .filter(p -> p.isInHand() && !p.isAllIn() && playerBets.getOrDefault(p.getSeatId(), 0) < highestBet)
                .toList();

        if (toActPlayers.isEmpty()) return;

        int aggressorIndex = -1;
        for (int i = 0; i < originalPlayerList.size(); i++) {
            if (originalPlayerList.get(i).getSeatId() == lastAggressorSeat) {
                aggressorIndex = i;
                break;
            }
        }

        if (aggressorIndex == -1) {
            currentQueue.addAll(toActPlayers);
            return;
        }

        int total = originalPlayerList.size();
        for (int i = 1; i <= total; i++) {
            int idx = (aggressorIndex + i) % total;
            GamePlayer p = originalPlayerList.get(idx);
            if (toActPlayers.contains(p)) {
                currentQueue.add(p);
            }
        }

        LOG.infov("Queue rebuilt. Next up: {0}",
                currentQueue.stream().map(p -> p.getUser().getUsername()).toList());
    }
   
    private void dealCards() {
        String variant = table.getGameVariant();  
        int holeCardsCount = variant.equals(GameVariant.OMAHA.name()) ? 4 : 2;
        List<GameCard> usedCards = new ArrayList<>();

        for (GamePlayer player : originalPlayerList) {
            if (player.isSittingOut() || player.isDisconnected() || player.isBusted()) continue;
            for (int i = 0; i < holeCardsCount; i++) {
                GameCard card = deck.drawCard();
                player.addCard(card);
                usedCards.add(card); 
            }
            player.setInHand(true);

            LOG.infov("Player {0} has {1} cards: {2}",
                player.getUser().getUsername(),
                player.getHoleCards().size(),
                player.getHoleCards());
        }

        table.sendPersonalHoleCardsToPlayers();
    }

    private void advanceGameState() {
        touchProgress();
        if (state == State.SHOWDOWN || state == State.FINISHED || state == State.CHIP_COLLECTION) {
        LOG.warnv("advanceGameState() called in terminal/chip-collection state {0} — skipping (session={1})", state, sessionId);
        return;
    }
        for (Integer bet : playerBets.values()) {
            pot += bet;
        }

        if (!State.SHOWDOWN.equals(state) && !State.FINISHED.equals(state) && state != State.CHIP_COLLECTION) {
            sidePots.clear();
            sidePots.addAll(payoutCalculator.createSidePots(this));
        }

        playerBets.clear();
        lastRaiseSize = 0;

        switch (state) {
            case WAITING_FOR_PLAYERS -> {
                LOG.infov("Waiting for players to join for session {0}", sessionId);
            }
            case POSTING_BLINDS -> {
                LOG.infov("Posting blinds for session {0}", sessionId);
            }

            case DEALING -> {
                LOG.infov("Posting blinds for session {0}", sessionId);
            }
            
            case PRE_FLOP -> {
                state = State.FLOP;
                LOG.infov("Transitioning to FLOP for session {0}", sessionId);
                communityCards.add(destinedCommunityCards.poll());
                communityCards.add(destinedCommunityCards.poll());
                communityCards.add(destinedCommunityCards.poll());
                table.sendGameStateUpdateToParticipants(state, communityCards);
            }
            case FLOP -> {
                state = State.TURN;
                LOG.infov("Transitioning to TURN for session {0}", sessionId);
                communityCards.add(destinedCommunityCards.poll());
                table.sendGameStateUpdateToParticipants(state, communityCards);
            }
            case TURN -> {
                state = State.RIVER;
                LOG.infov("Transitioning to RIVER for session {0}", sessionId);
                communityCards.add(destinedCommunityCards.poll());
                table.sendGameStateUpdateToParticipants(state, communityCards);
            }
            case RIVER -> {
                state = State.SHOWDOWN;
                LOG.infov("Transitioning to SHOWDOWN for session {0}", sessionId);
                table.sendGameStateUpdateToParticipants(state, communityCards);
                startShowdownRevealSequence();
                return;
            }
            case CHIP_COLLECTION -> {
                LOG.warnv("advanceGameState() with CHIP_COLLECTION state — not expected (session={0})", sessionId);
                state = priorState != null ? priorState : State.PRE_FLOP;
            }
            case SHOWDOWN ->{}
            case FINISHED -> handleFinishedState();
        }

        if (!State.SHOWDOWN.equals(state) && !State.FINISHED.equals(state) && state != State.CHIP_COLLECTION) {
            rotateQueue(dealerSeatIndex);

            for (GamePlayer gp : table.getSeats().values()) {
                LOG.infov("After rotateQueue - Seat {0}: user={1}, inHand={2}, folded={3}",
                    gp.getSeatId(),
                    gp.getUser() != null ? gp.getUser().getUsername() : "empty",
                    gp.isInHand(),
                    !gp.isInHand());
            }

            promptNextPlayer();
        }

        if (state != State.FINISHED) {
            handleGameStateUpdates();
        }    
    }

    private void handleGameStateUpdates() {
        LOG.infov("handleGameStateUpdates called. Current state: {0}", state);
        table.sendGameStateUpdateToParticipants(state, communityCards);
    }

    private void handleFinishedState() {
        state = State.FINISHED;
        touchProgress();
        handleGameStateUpdates();

        boolean isFolded = originalPlayerList.stream()
                .filter(GamePlayer::isInHand)
                .count() < 2;

        fireOnGameFinishedCallbacks();

        table.markZeroStackPlayersAtHandEnd();

        NEXT_GAME_SCHEDULER.schedule(() -> {
            try {
                table.startNextGame(isFolded);
                showdownHandlerCreated = false;
            } catch (Throwable t) {
                LOG.errorv(t, "startNextGame failed for session {0} — attempting direct restart", sessionId);
                try {
                    showdownHandlerCreated = false;
                    table.startNextGame(isFolded);
                } catch (Throwable t2) {
                    LOG.errorv(t2, "startNextGame retry failed for session {0}", sessionId);
                }
            }
        }, 3, TimeUnit.SECONDS);
        if (showdownHandler != null) {
            showdownHandler.shutdown();
        }
    }

    /**
     * Last-resort recovery for a wedged hand: annul it by returning every chip
     * that went into the pot this hand and finish cleanly so the table
     * schedules its next game. Chips are chopped evenly among the players who
     * were still in the hand.
     */
    public void forceFinishZombieHand(String reason) {
        if (!forceFinishing.compareAndSet(false, true)) {
            return;
        }
        try {
            LOG.errorv("ZOMBIE HAND RECOVERY: force-finishing session {0} — {1}", sessionId, reason);

            long streetBets = playerBets.values().stream().mapToInt(Integer::intValue).sum();
            long totalToSplit = pot + streetBets;

            List<GamePlayer> eligible = originalPlayerList.stream()
                    .filter(GamePlayer::isInHand)
                    .toList();

            if (!eligible.isEmpty()) {
                int share = (int) (totalToSplit / eligible.size());
                int remainder = (int) (totalToSplit - (long) share * eligible.size());
                for (int i = 0; i < eligible.size(); i++) {
                    GamePlayer p = eligible.get(i);
                    int amount = share + (i == 0 ? remainder : 0);
                    if (amount > 0) {
                        p.setStack(p.getStack() + amount);
                    }
                }
                LOG.errorv("Refunded {0} chips evenly across {1} players still in hand ({2} each)",
                        totalToSplit, eligible.size(), share);
            } else {
                // Nobody left in hand — return street bets to their bettors and
                // split whatever was already collected across all participants.
                int refundedStreet = 0;
                for (Map.Entry<Integer, Integer> e : playerBets.entrySet()) {
                    GamePlayer p = findBySeat(e.getKey());
                    if (p != null && e.getValue() > 0) {
                        p.setStack(p.getStack() + e.getValue());
                        refundedStreet += e.getValue();
                    }
                }
                if (!originalPlayerList.isEmpty() && pot > 0) {
                    int share = pot / originalPlayerList.size();
                    int remainder = pot - share * originalPlayerList.size();
                    for (int i = 0; i < originalPlayerList.size(); i++) {
                        GamePlayer p = originalPlayerList.get(i);
                        p.setStack(p.getStack() + share + (i == 0 ? remainder : 0));
                    }
                }
                LOG.errorv("No players in hand — returned {0} of street bets and split collected pot {1}",
                        refundedStreet, pot);
            }

            playerBets.clear();
            pot = 0;
            sidePots.clear();
            revealedHands = new HashMap<>();
            currentActingSeat = null;
            currentPlayer = null;
            lastActionWasTimeout = false;
            state = State.FINISHED;
            handleFinishedState();
        } catch (Throwable t) {
            LOG.errorv(t, "Zombie recovery itself failed for session {0}", sessionId);
        } finally {
            forceFinishing.set(false);
        }
    }

    private GamePlayer findBySeat(Integer seatId) {
        return originalPlayerList.stream()
                .filter(p -> Objects.equals(p.getSeatId(), seatId))
                .findFirst()
                .orElse(null);
    }

    public int getPlayersInHandCount() {
        return (int) originalPlayerList.stream()
            .filter(GamePlayer::isInHand)
            .count();
    }

    private void setCurrentPlayerAllIn() {
        currentPlayer.setAllIn(true);
    }

    public JsonObject createSessionDetails() {
        try {
            JsonObject details = new JsonObject();
            details.put("pots", sidePots);
            details.put("rake", totalRake);
            details.put("hands", showdownHandler != null ? showdownHandler.getRevealedHands() : new HashMap<>());
            details.put("players", originalPlayerList.stream()
                    .map(GamePlayer::toJson)
                    .toList());
            return details;
        } catch (Exception e) {
            LOG.errorv(e, "Failed to create session details for session {0}", sessionId);
            return new JsonObject().put("error", "Failed to create session details");
        }
    }

    public void onGameFinished(Runnable callback) {
        if (state == State.FINISHED) {
            callback.run();
        } else {
            onFinishCallbacks.add(callback);
        }
    }

    private void fireOnGameFinishedCallbacks() {
        for (Runnable callback : onFinishCallbacks) {
            callback.run();
        }
        onFinishCallbacks.clear();
    }
    
    public boolean isWinner(GamePlayer player) {
        for (GameSidePot sidePot : sidePots) {
            for (GamePlayer gamePlayer : sidePot.getWinners()) {
                if (gamePlayer.getUser().getUserId() == player.getUser().getUserId()) {
                    return true; 
                }
            }
        }
        return false; 
    }

    public String getSessionId() {
        return sessionId;
    }

    public GameTable getTable() {
        return table;
    }

    public List<GamePlayer> getOriginalPlayerList() {
        return originalPlayerList;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public GamePlayer getCurrentPlayer() {
        return currentPlayer;
    }

    public int getPot() {
        return pot;
    }

    public Queue<GamePlayer> getCurrentQueue() {
        return currentQueue;
    }

    public Map<Integer, Integer> getPlayerBets() {
        return playerBets;
    }

    public List<GameCard> getCommunityCards() {
        return communityCards;
    }

    public List<GameCard> getDestinedCommunityCardList() {
        return destinedCommunityCardList;
    }

    public Queue<GameCard> getDestinedCommunityCards() {
        return destinedCommunityCards;
    }

    public Integer getCurrentActingSeat() {
        return currentActingSeat;
    }

    public boolean isAllInFlip() {
        return isAllInFlip;
    }

    public void setAllInFlip(boolean allInFlip) {
        isAllInFlip = allInFlip;
    }

    public int getDealerSeatIndex() {
        return dealerSeatIndex;
    }

    public int getSmallBlindSeatIndex() {
        return smallBlindSeatIndex;
    }

    public int getBigBlindSeatIndex() {
        return bigBlindSeatIndex;
    }

    public int getLastRaiseSeat() {
        return lastRaiseSeat;
    }

    public int getLastRaiseSize() {
        return lastRaiseSize;
    }

    public double getTotalRake() {
        return totalRake;
    }

    public void setTotalRake(double totalRake) {
        this.totalRake = totalRake;
    }

    public Map<Integer, GameHand> getRevealedHands() {
        return revealedHands;
    }

    public List<GameSidePot> getSidePots() {
        return sidePots;
    }

    private void collectChipsAndTransition(Runnable transition) {
        touchProgress();
        returnUncalledBets();

        if (lastActionWasTimeout) {
            lastActionWasTimeout = false;
            transition.run();
            return;
        }

        priorState = state;
        state = State.CHIP_COLLECTION;
        table.sendGameStateUpdateToParticipants(state, communityCards);
        NEXT_GAME_SCHEDULER.schedule(() -> {
            state = priorState;
            transition.run();
        }, CHIP_COLLECTION_DELAY_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void returnUncalledBets() {
        sidePots.clear();
        sidePots.addAll(payoutCalculator.createSidePots(this));

        for (GamePlayer p : originalPlayerList) {
            int original = p.getOriginalTotalContribution();
            int reduced = p.getTotalContribution();
            if (original > reduced) {
                int excess = original - reduced;
                p.setStack(p.getStack() + excess);
                p.setOriginalTotalContribution(reduced);

                int seatId = p.getSeatId();
                Integer currentBet = playerBets.get(seatId);
                if (currentBet != null) {
                    int newBet = currentBet - excess;
                    if (newBet <= 0) {
                        playerBets.remove(seatId);
                    } else {
                        playerBets.put(seatId, newBet);
                    }
                }

                LOG.infov("↩ Returned uncalled bet {0} to {1} — stack now {2}",
                    excess, p.getUser().getUsername(), p.getStack());
                break;
            }
        }
    }

    public enum State {
        WAITING_FOR_PLAYERS,
        POSTING_BLINDS,
        DEALING,
        PRE_FLOP,
        FLOP,
        TURN,
        RIVER,
        CHIP_COLLECTION,
        SHOWDOWN,
        FINISHED
    }

    public enum ActionType {
        FOLD,
        SMALL_BLIND,
        BIG_BLIND,
        CALL,
        RAISE,
        CHECK,
        REVEAL_CARDS,
        HIDE_CARDS,
        AUTO_MUCK,
        ALL_IN
    }

    public void setCurrentPlayer(GamePlayer player) {
        this.currentPlayer = player;
        if (player != null) {
            player.setTurnStartDate(LocalDateTime.now()); 
        }
    }

    public boolean isFinished() {
        return state == State.FINISHED;
    }
    
    public int getPotSize() {
        int currentStreetBets = playerBets.values().stream().mapToInt(Integer::intValue).sum();
        return pot + currentStreetBets;
    }
}

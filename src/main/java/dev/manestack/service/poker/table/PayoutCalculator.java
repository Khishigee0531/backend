package dev.manestack.service.poker.table;

import dev.manestack.domain.poker.GamePlayer;
import dev.manestack.domain.poker.GameSidePot;
import dev.manestack.service.UserService;
import dev.manestack.service.poker.card.GameCard;
import dev.manestack.service.poker.card.GameHand;
import dev.manestack.service.poker.card.GameHandEvaluator;
import dev.manestack.service.poker.card.GameHandRank;
import dev.manestack.service.poker.card.GameVariant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@ApplicationScoped
public class PayoutCalculator {

    private static final Logger LOG = Logger.getLogger(PayoutCalculator.class);
    private static final BigDecimal JACKPOT_RATE = BigDecimal.valueOf(0.001);
    private static final int RAKE_CAP = 5000;
    private static final double LOSS_BONUS_RATE = 0.10;

    @Inject
    UserService userService;

    @Inject
    PokerScoreService pokerScoreService;

    public List<GameSidePot> createSidePots(GameSession session) {
        List<GameSidePot> pots = new ArrayList<>();
        List<GamePlayer> playersInHand = session.getOriginalPlayerList().stream()
            .filter(GamePlayer::isInHand)
            .collect(Collectors.toList());

        if (playersInHand.isEmpty()) {
            return pots;
        }

        // Save original contributions before createSidePots modifies them,
        // so updateStacks can return uncalled bets to the big-stack player.
        for (GamePlayer p : session.getOriginalPlayerList()) {
            p.setOriginalTotalContribution(p.getTotalContribution());
        }

        List<Integer> contributions = playersInHand.stream()
            .map(GamePlayer::getTotalContribution)
            .sorted()
            .distinct()
            .toList();

        if (playersInHand.size() == 1) {
            GamePlayer lastPlayer = playersInHand.get(0);
            int totalPot = session.getOriginalPlayerList().stream()
                .mapToInt(GamePlayer::getTotalContribution)
                .sum();

            GameSidePot mainPot = new GameSidePot(totalPot, List.of(lastPlayer));
            mainPot.setMainPot(true);
            pots.add(mainPot);
            return pots;
        }

        int highestContribution = contributions.get(contributions.size() - 1);
        int secondHighestContribution = contributions.size() > 1 ?
            contributions.get(contributions.size() - 2) : 0;

        long playersAtHighestLevel = playersInHand.stream()
            .filter(p -> p.getTotalContribution() == highestContribution)
            .count();

        if (playersAtHighestLevel == 1) {
            GamePlayer playerWithUncalledBet = playersInHand.stream()
                .filter(p -> p.getTotalContribution() == highestContribution)
                .findFirst()
                .orElse(null);

            if (playerWithUncalledBet != null) {
                int uncalledAmount = highestContribution - secondHighestContribution;

                LOG.infov("🔙 [UNCALLED BET] Returning {0} to player {1} (bet {2}, highest call {3})",
                    uncalledAmount,
                    playerWithUncalledBet.getUser().getUsername(),
                    highestContribution,
                    secondHighestContribution);

                playerWithUncalledBet.setTotalContribution(secondHighestContribution);

                contributions = playersInHand.stream()
                    .map(GamePlayer::getTotalContribution)
                    .sorted()
                    .distinct()
                    .toList();
            }
        }

        int previous = 0;
        for (int contribution : contributions) {
            int potAmount = 0;
            List<GamePlayer> eligible = new ArrayList<>();

            for (GamePlayer player : playersInHand) {
                int diff = Math.min(player.getTotalContribution(), contribution) - previous;
                if (diff > 0) {
                    potAmount += diff;
                    eligible.add(player);
                }
            }

            if (potAmount > 0 && !eligible.isEmpty()) {
                previous = contribution;
                GameSidePot pot = new GameSidePot(potAmount, eligible);
                if (pots.isEmpty()) pot.setMainPot(true);
                pots.add(pot);

                LOG.infov("💰 Created {0}: Amount={1}, Eligible={2}",
                    pot.isMainPot() ? "MAIN POT" : "SIDE POT",
                    potAmount,
                    eligible.stream().map(p -> p.getUser().getUsername()).toList());
            }
        }

        return pots;
    }

    public void calculateWinnings(GameSession session) {
        double rakePercent = session.getTable().getRakePercent();
        List<GamePlayer> originalPlayerList = session.getOriginalPlayerList();
        List<GameCard> communityCards = session.getCommunityCards();
        Map<Integer, GameHand> revealedHands = session.getRevealedHands();

        List<GamePlayer> activePlayers = originalPlayerList.stream()
                .filter(GamePlayer::isInHand)
                .toList();

        if (activePlayers.size() == 1) {
            GamePlayer winner = activePlayers.get(0);
            winner.setIsWinner(true);

            int totalPot = originalPlayerList.stream()
                    .mapToInt(GamePlayer::getTotalContribution)
                    .sum();

            int rake = 0;
            if (!communityCards.isEmpty()) {
                int winnerContrib = winner.getTotalContribution();
                int maxOtherContrib = originalPlayerList.stream()
                        .filter(p -> p != winner)
                        .mapToInt(GamePlayer::getTotalContribution)
                        .max()
                        .orElse(0);
                int uncalledBet   = Math.max(0, winnerContrib - maxOtherContrib);
                int contestedPot  = totalPot - uncalledBet;
                if (contestedPot > 0) {
                    rake = Math.min((int)(contestedPot * rakePercent), RAKE_CAP);
                    session.getTable().addRakeCollected(rake);
                    session.setTotalRake(session.getTotalRake() + rake);
                }
            }

            winner.setWinnings(totalPot - rake);
            winner.setNetResult((totalPot - rake) - winner.getTotalContribution());
            winner.setBestHandCards(List.of());

            session.getSidePots().clear();
            return;
        }

        boolean isOmaha = session.getTable().getGameVariant().equals(GameVariant.OMAHA.name());
        for (GamePlayer player : originalPlayerList) {
            if (player.isInHand() && player.getHoleCards() != null && !player.getHoleCards().isEmpty()) {
                List<GameCard> holeCards = player.getHoleCards();
                GameHand bestHand = null;
                List<GameCard> bestHoleCards = List.of();

                if (isOmaha && holeCards.size() == 4 && communityCards.size() >= 3) {
                    for (int i = 0; i < holeCards.size(); i++) {
                        for (int j = i + 1; j < holeCards.size(); j++) {
                            for (int a = 0; a < communityCards.size(); a++) {
                                for (int b = a + 1; b < communityCards.size(); b++) {
                                    for (int c = b + 1; c < communityCards.size(); c++) {
                                        List<GameCard> five = List.of(
                                            holeCards.get(i), holeCards.get(j),
                                            communityCards.get(a), communityCards.get(b), communityCards.get(c)
                                        );
                                        GameHand hand = GameHandEvaluator.evaluate(five);
                                        if (hand != null && (bestHand == null || hand.compareTo(bestHand) > 0)) {
                                            bestHand = hand;
                                            bestHoleCards = List.of(holeCards.get(i), holeCards.get(j));
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    for (int i = 0; i < holeCards.size(); i++) {
                        for (int j = i + 1; j < holeCards.size(); j++) {
                            List<GameCard> combo = List.of(holeCards.get(i), holeCards.get(j));
                            List<GameCard> fullHand = Stream.concat(combo.stream(), communityCards.stream()).toList();
                            GameHand hand = GameHandEvaluator.evaluate(fullHand);

                            if (hand != null && (bestHand == null || hand.compareTo(bestHand) > 0)) {
                                bestHand = hand;
                                bestHoleCards = combo;
                            }
                        }
                    }
                }

                player.setHand(bestHand);
                player.setBestHandCards(bestHoleCards);
            } else {
                player.setHand(null);
                player.setBestHandCards(List.of());
            }
        }

        List<GameSidePot> allSidePots = createSidePots(session);
        session.getSidePots().clear();
        session.getSidePots().addAll(allSidePots);

        for (GameSidePot sidePot : session.getSidePots()) {
            List<GamePlayer> eligiblePlayers = sidePot.getEligiblePlayers().stream()
                    .filter(GamePlayer::isInHand)
                    .toList();
            if (eligiblePlayers.isEmpty()) continue;

            GameHand bestHandInPot = null;
            List<GamePlayer> winners = new ArrayList<>();
            for (GamePlayer player : eligiblePlayers) {
                GameHand playerHand = player.getHand();
                if (playerHand == null) continue;

                if (bestHandInPot == null || playerHand.compareTo(bestHandInPot) > 0) {
                    bestHandInPot = playerHand;
                    winners.clear();
                    winners.add(player);
                } else if (playerHand.compareTo(bestHandInPot) == 0) {
                    winners.add(player);
                }
            }

            sidePot.addWinners(winners);

            int originalPot = sidePot.getAmount();

            int rake = 0;
            if (sidePot.isMainPot() && !communityCards.isEmpty()) {
                int rawRake = (int) (originalPot * rakePercent);
                rake = Math.min(rawRake, RAKE_CAP);
                session.getTable().addRakeCollected(rake);
                session.setTotalRake(session.getTotalRake() + rake);
            }

            int distributablePot = originalPot - rake;
            int splitAmount = winners.isEmpty() ? 0 : distributablePot / winners.size();
            int remainder = winners.isEmpty() ? 0 : distributablePot % winners.size();

            sidePot.setWinningsPerPlayer(splitAmount);

            if (remainder > 0) {
                int maxPlayers = session.getTable().getMaxPlayers();
                int dealerSeat = session.getTable().getCurrentDealer();
                winners.sort((a, b) -> {
                    int posA = (a.getSeatId() - dealerSeat + maxPlayers) % maxPlayers;
                    int posB = (b.getSeatId() - dealerSeat + maxPlayers) % maxPlayers;
                    return Integer.compare(posA, posB);
                });
            }

            int minContributionInPot = eligiblePlayers.stream()
                    .mapToInt(GamePlayer::getTotalContribution)
                    .min()
                    .orElse(0);

            for (int i = 0; i < winners.size(); i++) {
                GamePlayer winner = winners.get(i);
                int award = splitAmount + (i < remainder ? 1 : 0);
                winner.setWinnings(winner.getWinnings() + award);

                int contributionToPot = Math.min(winner.getTotalContribution(), minContributionInPot);
                winner.setNetResult(winner.getNetResult() + award - contributionToPot);
                winner.setIsWinner(true);

                if (winner.getBestHandCards() == null || winner.getBestHandCards().isEmpty()) {
                    List<GameCard> winningHoleCards = List.of();
                    if (winner.getHand() != null && winner.getHand().getRankCards() != null) {
                        winningHoleCards = winner.getHand().getRankCards().stream()
                                .filter(winner.getHoleCards()::contains)
                                .toList();
                    }
                    winner.setBestHandCards(winningHoleCards);
                }
            }

            List<GameCard> winningCommunityCards = new ArrayList<>();
            if (!winners.isEmpty()) {
                GamePlayer firstWinner = winners.get(0);
                GameHand bestHand = firstWinner.getHand();
                if (bestHand != null && bestHand.getRankCards() != null) {
                    winningCommunityCards = bestHand.getRankCards().stream()
                            .filter(card -> !firstWinner.getHoleCards().contains(card))
                            .toList();
                }
            }
            sidePot.setWinningCommunityCards(winningCommunityCards);
        }

        for (GamePlayer player : originalPlayerList) {
            int totalContrib = player.getTotalContribution();
            int totalWinnings = player.getWinnings();
            player.setNetResult(totalWinnings - totalContrib);
            pokerScoreService.updatePlayerCgp(player);
        }

        for (GamePlayer player : originalPlayerList) {
            if (player.getUser() != null) {
                int cgp = player.getCgpScore();
                userService.updateCgp(player.getUser().getUserId(), cgp);
            }
        }

        checkAndTriggerJackpot(session, activePlayers);

        LOG.infov("Winnings calculated for session {0}: {1}", session.getSessionId(),
                originalPlayerList.stream()
                        .map(p -> p.getUser().getUsername() + "=" + p.getWinnings() + "/" + p.getNetResult())
                        .toList());
    }

    private void checkAndTriggerJackpot(GameSession session, List<GamePlayer> players) {
        GamePlayer jackpotWinner = null;
        BigDecimal jackpotPercentage = null;
        GameHandRank bestQualifyingRank = null;

        for (GamePlayer player : players) {
            if (player.getHand() == null || player.getUser() == null) continue;
            GameHandRank rank = player.getHand().getRank();

            BigDecimal pct = null;
            if (rank == GameHandRank.ROYAL_FLUSH)        pct = BigDecimal.ONE;
            else if (rank == GameHandRank.STRAIGHT_FLUSH) pct = BigDecimal.valueOf(0.5);
            else if (rank == GameHandRank.FOUR_OF_A_KIND) pct = BigDecimal.valueOf(0.2);

            if (pct != null && (bestQualifyingRank == null || rank.ordinal() > bestQualifyingRank.ordinal())) {
                jackpotWinner = player;
                jackpotPercentage = pct;
                bestQualifyingRank = rank;
            }
        }

        if (jackpotWinner == null) return;

        final Integer winnerId = jackpotWinner.getUser().getUserId();
        final BigDecimal pct = jackpotPercentage;
        userService.triggerJackpotWin(winnerId, pct)
            .subscribe().with(
                balance -> LOG.infov("Jackpot ({0}%) awarded to user {1}", pct.multiply(BigDecimal.valueOf(100)).intValue(), winnerId),
                failure -> LOG.errorv(failure, "Failed to award jackpot to user {0}", winnerId)
            );
    }

    public void updateStacks(GameSession session) {
        LOG.infov("Updating player stacks for session {0}", session.getSessionId());

        for (GamePlayer player : session.getOriginalPlayerList()) {
            int winnings = player.getWinnings();
            int oldStack = player.getStack();

            // Return uncalled bet: the excess the big-stack contributed that
            // wasn't matched by any opponent. createSidePots() reduced
            // totalContribution but never restored the excess to the stack.
            int uncalledReturn = Math.max(0,
                player.getOriginalTotalContribution() - player.getTotalContribution());
            int newStack = oldStack + winnings + uncalledReturn;

            player.setStack(newStack);
            player.setIsBusted(newStack <= 0);

            int net = player.getNetResult();
            LOG.infov("Player {0} net result: {1}, session: {2}",
                player.getUser().getUsername(), net, session.getSessionId());

            if (net < 0) {
                int lostAmount = -net;
                int bonus = (int) Math.floor(lostAmount * LOSS_BONUS_RATE);

                if (bonus > 0) {
                    userService.addBonus(
                        player.getUser().getUserId(),
                        bonus,
                        "LOSS_COMPENSATION",
                        session.getSessionId()
                    );

                    LOG.infov(
                        "Loss bonus awarded: user={0}, lost={1}, bonus={2}",
                        player.getUser().getUsername(),
                        lostAmount,
                        bonus
                    );
                }
            }

            LOG.infov("Player {0} new stack: {1}", player.getUser().getUsername(), newStack);
        }

        if (session.getTable() != null) {
            session.getTable().propagateCombinedPotUpdate(session.getRevealedHands(), session.getSidePots());
        }
    }
}

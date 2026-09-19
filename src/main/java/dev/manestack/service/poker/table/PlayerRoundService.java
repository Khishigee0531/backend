package dev.manestack.service.poker.table;

import dev.manestack.domain.poker.GamePlayer;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PlayerRoundService {

    public void resetForNewGame(GamePlayer player) {
        // Sitting-out players must not enter the hand — keep them out-of-hand
        // so getNextActivePlayer() skips them for blinds, dealing, and action.
        player.setInHand(!player.isSittingOut());
        player.setAllIn(false);
        player.setFolded(false);
        player.setShowdownActed(false);
        player.setTimeoutActed(false);
        player.setTotalContribution(0);
        player.setOriginalTotalContribution(0);
        player.setAccountedContribution(0);
        player.setWinnings(0);
        player.setNetResult(0);
        player.setRevealApproved(false);
        player.getHoleCards().clear();
        player.setIsWinner(false);
        player.setIsMainPotWinner(false);
        player.setHand(null);
        player.setTotalJackpotContribution(0);
    }
}
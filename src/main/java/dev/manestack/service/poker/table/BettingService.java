package dev.manestack.service.poker.table;

import dev.manestack.domain.poker.GamePlayer;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class BettingService {

    public int placeBet(GamePlayer player, int amount) {
        int actual = Math.min(amount, player.getStack());

        player.setStack(player.getStack() - actual);
        player.setTotalContribution(player.getTotalContribution() + actual);

        if (player.getStack() == 0) {
            player.setAllIn(true);
        }

        return actual;
    }

    public void awardWinnings(GamePlayer player, int amount) {
        player.setStack(player.getStack() + amount);
        player.setWinnings(player.getWinnings() + amount);
    }
    public void addChips(GamePlayer player, int amount) {
        player.setStack(player.getStack() + amount);
    }
}
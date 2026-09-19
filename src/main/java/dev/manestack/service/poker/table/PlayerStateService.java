package dev.manestack.service.poker.table;

import dev.manestack.domain.poker.GamePlayer;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PlayerStateService {

    public void markTimeout(GamePlayer player) {
        int before = player.getTimeoutCount();
        int count = before + 1;
        player.setTimeoutCount(count);

        if (count >= 3 && !player.isSittingOut()) {
            player.setSittingOut(true);
        }
    }

    public void resetTimeout(GamePlayer player) {
        player.setTimeoutActed(false);
        player.setTimeoutCount(0);
        player.setTimeoutActionDate(null);
    }
}
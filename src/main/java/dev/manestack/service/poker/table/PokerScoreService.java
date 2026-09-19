package dev.manestack.service.poker.table;

import dev.manestack.domain.poker.GamePlayer;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PokerScoreService {

    private static final Logger LOG = Logger.getLogger(PokerScoreService.class); 

    public void updatePlayerCgp(GamePlayer player) {
        int before = player.getCgpScore();

        int contribution = player.getTotalContribution();


        int delta = (int) (contribution * 0.05);

        delta = Math.max(1, delta);

        int newCgp = before + delta;

        player.setCgpScore(newCgp);

        LOG.infov(
            "CGP CALC → user={0}, contribution={1}, delta={2}, before={3}, after={4}",
            player.getUser() != null ? player.getUser().getUsername() : "UNKNOWN",
            contribution,
            delta,
            before,
            newCgp
        );
    }
}
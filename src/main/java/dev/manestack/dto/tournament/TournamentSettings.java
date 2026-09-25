package dev.manestack.dto.tournament;

public class TournamentSettings {
    private boolean enabled;

    public TournamentSettings() {
    }

    public TournamentSettings(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

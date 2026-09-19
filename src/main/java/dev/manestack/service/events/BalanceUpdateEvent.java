package dev.manestack.service.events;

import dev.manestack.domain.user.UserBalance;

public class BalanceUpdateEvent {
    public final Integer userId;
    public final UserBalance userBalance;

    public BalanceUpdateEvent(Integer userId, UserBalance userBalance) {
        this.userId = userId;
        this.userBalance = userBalance;
    }
}

package dev.manestack.domain.user;

import dev.manestack.jooq.generated.tables.records.PokerOutcomeRecord;

import java.time.OffsetDateTime;

public class Outcome {
    private long outcomeId;
    private User user;
    private Integer amount;
    private String type;
    private Long gameSessionId;
    private boolean isAccounted;
    private OffsetDateTime createDate;
    private OffsetDateTime accountDate;

    public Outcome() {
    }

    public Outcome(PokerOutcomeRecord record) {
        this.outcomeId = record.getOutcomeId();
        this.amount = record.getAmount();
        this.type = record.getType();
        this.isAccounted = record.getIsAccounted();
        this.createDate = record.getCreateDate();
        this.accountDate = record.getAccountDate();
    }

    public long getOutcomeId() {
        return outcomeId;
    }

    public void setOutcomeId(long outcomeId) {
        this.outcomeId = outcomeId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Integer getAmount() {
        return amount;
    }

    public void setAmount(Integer amount) {
        this.amount = amount;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Long getGameSessionId() {
        return gameSessionId;
    }

    public void setGameSessionId(Long gameSessionId) {
        this.gameSessionId = gameSessionId;
    }

    public boolean isAccounted() {
        return isAccounted;
    }

    public void setAccounted(boolean accounted) {
        isAccounted = accounted;
    }

    public OffsetDateTime getCreateDate() {
        return createDate;
    }

    public void setCreateDate(OffsetDateTime createDate) {
        this.createDate = createDate;
    }

    public OffsetDateTime getAccountDate() {
        return accountDate;
    }

    public void setAccountDate(OffsetDateTime accountDate) {
        this.accountDate = accountDate;
    }
}

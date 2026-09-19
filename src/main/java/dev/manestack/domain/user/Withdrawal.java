package dev.manestack.domain.user;

import dev.manestack.jooq.generated.tables.records.PokerWithdrawalRecord;
import io.vertx.core.json.JsonObject;

import java.time.OffsetDateTime;

public class Withdrawal {
    private Long withdrawalId;
    private Integer userId;
    private Integer amount;
    private OffsetDateTime createDate;
    private Integer approvedBy;
    private OffsetDateTime approveDate;
    private JsonObject details;
    private User user;

    public Withdrawal() {
    }

    public Withdrawal(PokerWithdrawalRecord record) {
        this.withdrawalId = record.getWithdrawalId();
        this.userId = record.getUserId();
        this.amount = record.getAmount();
        this.createDate = record.getCreateDate();
        this.approvedBy = record.getApprovedBy();
        this.approveDate = record.getApproveDate();
        this.details = new JsonObject(record.getDetails().data());
    }

    public void validate() {
        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("Amount must be a positive integer.");
        }
        if (details == null) {
            throw new IllegalArgumentException("Details cannot be null.");
        }
    }

    public Long getWithdrawalId() {
        return withdrawalId;
    }

    public void setWithdrawalId(Long withdrawalId) {
        this.withdrawalId = withdrawalId;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public Integer getAmount() {
        return amount;
    }

    public void setAmount(Integer amount) {
        this.amount = amount;
    }

    public OffsetDateTime getCreateDate() {
        return createDate;
    }

    public void setCreateDate(OffsetDateTime createDate) {
        this.createDate = createDate;
    }

    public Integer getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(Integer approvedBy) {
        this.approvedBy = approvedBy;
    }

    public OffsetDateTime getApproveDate() {
        return approveDate;
    }

    public void setApproveDate(OffsetDateTime approveDate) {
        this.approveDate = approveDate;
    }

    public JsonObject getDetails() {
        return details;
    }

    public void setDetails(JsonObject details) {
        this.details = details;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }
}

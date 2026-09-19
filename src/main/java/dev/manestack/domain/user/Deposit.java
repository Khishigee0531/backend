package dev.manestack.domain.user;

import dev.manestack.jooq.generated.tables.records.PokerDepositRecord;
import io.vertx.core.json.JsonObject;

import java.time.OffsetDateTime;

public class Deposit {
    private Long depositId;
    private Integer userId;
    private Integer adminId;
    private Integer amount;
    private DepositType type;
    private DepositStatus status;
    private String deniedReason;
    private JsonObject details;
    private OffsetDateTime createDate;
    private OffsetDateTime approvedDate;
    private User user;

    public Deposit() {
    }

    public Deposit(PokerDepositRecord record) {
        this.depositId = record.getDepositId();
        this.userId = record.getUserId();
        this.adminId = record.getAdminId();
        this.amount = record.getAmount();
        this.type = DepositType.valueOf(record.getType());
        
        // Handle status field - use reflection to check if field exists
        try {
            String statusStr = (String) record.getClass().getMethod("getStatus").invoke(record);
            this.status = (statusStr != null) ? DepositStatus.valueOf(statusStr) : DepositStatus.PENDING;
        } catch (Exception e) {
            // Field doesn't exist yet (before migration), default to APPROVED for old records
            this.status = (record.getAdminId() != null) ? DepositStatus.APPROVED : DepositStatus.PENDING;
        }
        
        // Handle deniedReason field
        try {
            this.deniedReason = (String) record.getClass().getMethod("getDeniedReason").invoke(record);
        } catch (Exception e) {
            this.deniedReason = null;
        }
        
        // Handle approvedDate field
        try {
            this.approvedDate = (OffsetDateTime) record.getClass().getMethod("getApprovedDate").invoke(record);
        } catch (Exception e) {
            this.approvedDate = null;
        }
        
        this.details = new JsonObject(record.getDetails().data());
        this.createDate = record.getCreateDate();
    }

    public void validate() {
        if (userId == null || userId <= 0) {
            throw new IllegalArgumentException("User ID must be a positive integer.");
        }
        if (amount == null || amount <= 0) {
            throw new IllegalArgumentException("Amount must be a positive integer.");
        }
        if (type == null) {
            throw new IllegalArgumentException("Deposit type cannot be null.");
        }
        if (details == null) {
            throw new IllegalArgumentException("Details cannot be null.");
        }
    }

    // Getters and Setters
    public Long getDepositId() {
        return depositId;
    }

    public void setDepositId(Long depositId) {
        this.depositId = depositId;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public Integer getAdminId() {
        return adminId;
    }

    public void setAdminId(Integer adminId) {
        this.adminId = adminId;
    }

    public Integer getAmount() {
        return amount;
    }

    public void setAmount(Integer amount) {
        this.amount = amount;
    }

    public DepositType getType() {
        return type;
    }

    public void setType(DepositType type) {
        this.type = type;
    }

    public DepositStatus getStatus() {
        return status;
    }

    public void setStatus(DepositStatus status) {
        this.status = status;
    }

    public String getDeniedReason() {
        return deniedReason;
    }

    public void setDeniedReason(String deniedReason) {
        this.deniedReason = deniedReason;
    }

    public OffsetDateTime getCreateDate() {
        return createDate;
    }

    public void setCreateDate(OffsetDateTime createDate) {
        this.createDate = createDate;
    }

    public OffsetDateTime getApprovedDate() {
        return approvedDate;
    }

    public void setApprovedDate(OffsetDateTime approvedDate) {
        this.approvedDate = approvedDate;
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

    public enum DepositType {
        CASH,
        BANK_TRANSFER,
        GIFT
    }

    public enum DepositStatus {
        PENDING,
        APPROVED,
        DENIED
    }
}

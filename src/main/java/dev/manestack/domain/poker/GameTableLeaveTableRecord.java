package dev.manestack.domain.poker;

import java.time.LocalDateTime;

public class GameTableLeaveTableRecord {
    private Integer userId;
    private Integer withdrawAmount;
    private LocalDateTime leaveDate;

    public GameTableLeaveTableRecord() {
    }

    public GameTableLeaveTableRecord(Integer userId, Integer withdrawAmount, LocalDateTime leaveDate) {
        this.userId = userId;
        this.withdrawAmount = withdrawAmount;
        this.leaveDate = leaveDate;
    }

    public Integer getUserId() {
        return userId;
    }

    public void setUserId(Integer userId) {
        this.userId = userId;
    }

    public Integer getWithdrawAmount() {
        return withdrawAmount;
    }

    public void setWithdrawAmount(Integer withdrawAmount) {
        this.withdrawAmount = withdrawAmount;
    }

    public LocalDateTime getLeaveDate() {
        return leaveDate;
    }

    public void setLeaveDate(LocalDateTime leaveDate) {
        this.leaveDate = leaveDate;
    }
}

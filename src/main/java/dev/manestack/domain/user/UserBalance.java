package dev.manestack.domain.user;

public class UserBalance {
    private Integer userId;
    private Integer balance;
    private Integer lockedAmount;
    private Integer bonusBalance;

    public Integer getUserId() { return userId;}
    public void setUserId(Integer userId) {this.userId = userId;}

    public Integer getBalance() {return balance;}
    public void setBalance(Integer balance) {this.balance = balance;}

    public Integer getLockedAmount() {return lockedAmount;}
    public void setLockedAmount(Integer lockedAmount) {this.lockedAmount = lockedAmount;}
    
    public Integer getBonusBalance() { return bonusBalance; }
    public void setBonusBalance(Integer bonusBalance) { this.bonusBalance = bonusBalance; }
}

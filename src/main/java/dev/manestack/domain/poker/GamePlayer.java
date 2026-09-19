package dev.manestack.domain.poker;

import dev.manestack.service.poker.card.GameCard;
import dev.manestack.service.poker.card.GameHand;
import dev.manestack.domain.user.User;
import io.vertx.core.json.JsonObject;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class GamePlayer {
    private User user;
    private Integer seatId;
    private Integer stack;
    private Integer winnings = 0;
    private boolean inHand = false;
    private boolean isAllIn = false;
    private boolean isRevealApproved = false;
    private boolean isDisconnected = false;
    private boolean isTimeoutActed = false;
    private boolean isSittingOut = false;
    private Integer timeoutCount = 0;
    private LocalDateTime timeoutActionDate = null;
    private Integer totalContribution = 0;
    private Integer originalTotalContribution = 0;
    private Integer accountedContribution = 0;
    private LocalDateTime turnStartDate = null;
    private LocalDateTime disconnectedAt = null;
    private LocalDateTime sitOutStartDate = null;
    private LocalDateTime noBalanceStartDate = null;
    private int lastActiveTurn;
    private boolean isWinner;
    private boolean isMainPotWinner;
    private GameHand hand;
    private List<GameCard> bestHandCards = new ArrayList<>();
    private boolean bot;
    private boolean goodBot;
    private int totalJackpotContribution = 0;
    private int netResult;
    private boolean folded;
    private LocalDateTime zeroStackStartDate;
    private final List<GameCard> holeCards = new ArrayList<>();
    private int botRecharges = 0;
    private int cgpScore;
    private long seatedAt;
    private boolean showdownActed = false;
    private boolean isBusted = false;
    // Recharges issued mid-hand are held here until the hand ends; only
    // then are these chips added to the live stack.
    private int pendingRecharge = 0;



    public boolean isBot() {
        return bot;
    }

    public void setBot(boolean bot) {
        this.bot = bot;
    }

    public boolean isGoodBot() {
        return goodBot;
    }

    public void setGoodBot(boolean goodBot) {
        this.goodBot = goodBot;
    }

    public GamePlayer(User user, int stack) {
        this.user = user;
        this.stack = stack;
    }

     public boolean isWinner() {
        return isWinner;
    }

    public void setIsWinner(boolean isWinner) {
        this.isWinner = isWinner;
    }

    public GameHand getHand() {
        return hand;
    }

    public List<GameCard> getBestHandCards() {
        return bestHandCards;
    }

    public void setBestHandCards(List<GameCard> bestHandCards) {
        this.bestHandCards = bestHandCards;
    }

    public void setHand(GameHand hand) {
        this.hand = hand;
    }

    public boolean isMainPotWinner() {
        return isMainPotWinner;
    }
    
    public void setIsMainPotWinner(boolean mainPotWinner) {
        this.isMainPotWinner = mainPotWinner;
    }

    public GamePlayer(User user, Integer balance) {
        this.stack = balance;
        this.user = user;
        this.cgpScore = user.getCgpScore(); 
    }

    public Integer getSeatId() {
        return seatId;
    }

    public void setSeatId(Integer seatId) {
        this.seatId = seatId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public Integer getStack() {
        return stack;
    }

    public void setStack(Integer stack) {
        this.stack = stack;
    }

    public void addCard(GameCard gameCard) {
        holeCards.add(gameCard);
    }

    public boolean isDisconnected() {
        return isDisconnected;
    }
    public void setTimeoutActed(boolean timeoutActed) {
        this.isTimeoutActed = timeoutActed;
    }

    public void setDisconnected(boolean disconnected) {
        if (disconnected) {
            this.disconnectedAt = LocalDateTime.now();
        } else {
            this.disconnectedAt = null;
        }
        isDisconnected = disconnected;
    }

    public boolean isAllIn() {
        return isAllIn;
    }

    public void setAllIn(boolean allIn) {
        isAllIn = allIn;
    }

    public Integer getTotalContribution() {
        return totalContribution;
    }

    public void setTotalContribution(Integer totalContribution) {
        this.totalContribution = totalContribution;
    }

    public Integer getOriginalTotalContribution() {
        return originalTotalContribution;
    }

    public void setOriginalTotalContribution(Integer originalTotalContribution) {
        this.originalTotalContribution = originalTotalContribution;
    }

    public Integer getAccountedContribution() {
        return accountedContribution;
    }

    public void setAccountedContribution(Integer accountedContribution) {
        this.accountedContribution = accountedContribution;
    }

    public boolean isInHand() {
        return inHand;
    }
    public void setInHand(boolean inHand) {
        this.inHand = inHand;
    }
    public LocalDateTime getTurnStartDate() {
        return turnStartDate;
    }

    public void setTurnStartDate(LocalDateTime turnStartDate) {
        this.turnStartDate = turnStartDate;
    }
    public LocalDateTime getZeroStackStartDate() {
        return zeroStackStartDate;
    }
    public void setZeroStackStartDate(LocalDateTime zeroStackStartDate) {
        this.zeroStackStartDate = zeroStackStartDate;
    }

    public boolean isTimeoutActed() {
        return isTimeoutActed;
    }
    public Integer getTimeoutCount() {
        return timeoutCount;
    }
    public void setTimeoutCount(Integer timeoutCount) {
        this.timeoutCount = timeoutCount;
    }

 

    public LocalDateTime getTimeoutActionDate() {
        return timeoutActionDate;
    }

    public void setTimeoutActionDate(LocalDateTime timeoutActionDate) {
        this.timeoutActionDate = timeoutActionDate;
    }

    public Integer getWinnings() {
        return winnings;
    }

    public void setWinnings(int winnings) {
        this.winnings = winnings;
    }
    
    public int getNetResult() {
        return netResult;
    }

    public void setNetResult(int netResult) {
        this.netResult = netResult;
    }

    public LocalDateTime getDisconnectedAt() {
        return disconnectedAt;
    }

    public boolean isSittingOut() {
        return isSittingOut;
    }

    public void setSittingOut(boolean sittingOut) {
        this.isSittingOut = sittingOut;
        if (sittingOut && this.sitOutStartDate == null) {
            this.sitOutStartDate = LocalDateTime.now();
        } else if (!sittingOut) {
            this.sitOutStartDate = null;
        }
    }

    public LocalDateTime getSitOutStartDate() {
        return sitOutStartDate;
    }

    public List<GameCard> getHoleCards() {
        return holeCards;
    }

    public void setRevealApproved(boolean revealApproved) {
        isRevealApproved = revealApproved;
    }

    public boolean isRevealApproved() {
        return isRevealApproved;
    }

    public int getLastActiveTurn() {
        return lastActiveTurn;
    }

    public void setLastActiveTurn(int lastActiveTurn) {
        this.lastActiveTurn = lastActiveTurn;
    }

    public LocalDateTime getNoBalanceStartDate() {
        return noBalanceStartDate;
    }

    public void setNoBalanceStartDate(LocalDateTime noBalanceStartDate) {
        this.noBalanceStartDate = noBalanceStartDate;
    }

    public int getTotalJackpotContribution() {
        return totalJackpotContribution;
    }

    public void setTotalJackpotContribution(int contribution) {
        this.totalJackpotContribution = contribution;
    }

    public boolean isFolded() {
        return folded;
    }

     public void setFolded(boolean folded) {
        this.folded = folded;
    }

    public int getBotRecharges() {
        return botRecharges;
    }

    public void incrementBotRecharges() {
        botRecharges++;
    }

    public int getCgpScore() {
        return cgpScore;
    }

    public void setCgpScore(int cgpScore) {
        this.cgpScore = cgpScore;
    }

    public void adjustCgpScore(int delta) {
        this.cgpScore += delta;
    }

    public long getSeatedAt() {
        return seatedAt;
    }

    public void setSeatedAt(long seatedAt) {
        this.seatedAt = seatedAt;
    }

    public boolean hasActedShowdown() {
        return showdownActed;
    }

    public void setShowdownActed(boolean showdownActed) {
        this.showdownActed = showdownActed;
    }

    public boolean isBusted() {
        return isBusted;
    }

    public void setIsBusted(boolean busted) {
        this.isBusted = busted;
    }

    public int getPendingRecharge() {
        return pendingRecharge;
    }

    public void setPendingRecharge(int pendingRecharge) {
        this.pendingRecharge = pendingRecharge;
    }

    public void addPendingRecharge(int amount) {
        this.pendingRecharge += amount;
    }



    @Override
    public String toString() {
        return "GamePlayer{" +
               "user=" + user +
                ", seatId=" + seatId +
                ", stack=" + stack +
                ", winnings=" + winnings +
                ", inHand=" + inHand +
                ", isAllIn=" + isAllIn +
                ", isDisconnected=" + isDisconnected +
                ", isTimeoutActed=" + isTimeoutActed +
                ", totalContribution=" + totalContribution +
                ", turnStartDate=" + turnStartDate +
                ", disconnectedAt=" + disconnectedAt +
                ", holeCards=" + holeCards +
                ", isWinner=" + isWinner + 
                ", isFolded=" + folded + 
                '}';
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.put("user", user != null ? JsonObject.mapFrom(user) : null);
        json.put("seatId", seatId);
        json.put("stack", stack);
        json.put("winnings", winnings);
        json.put("inHand", inHand);
        json.put("isAllIn", isAllIn);
        json.put("isRevealApproved", isRevealApproved);
        json.put("isDisconnected", isDisconnected);
        json.put("isTimeoutActed", isTimeoutActed);
        json.put("isSittingOut", isSittingOut);
        json.put("timeoutCount", timeoutCount);
        json.put("timeoutActionDate", toEpochMillis(timeoutActionDate));
        json.put("totalContribution", totalContribution);
        json.put("originalTotalContribution", originalTotalContribution);
        json.put("accountedContribution", accountedContribution);
        json.put("turnStartDate", toEpochMillis(turnStartDate));
        json.put("disconnectedAt", toEpochMillis(disconnectedAt));
        json.put("sitOutStartDate", toEpochMillis(sitOutStartDate));
        json.put("noBalanceStartDate", toEpochMillis(noBalanceStartDate));
        json.put("lastActiveTurn", lastActiveTurn);
        json.put("isWinner", isWinner);
        json.put("isMainPotWinner", isMainPotWinner);
        json.put("bestHandCards", bestHandCards);
        json.put("isBot", bot);
        json.put("isGoodBot", goodBot);
        json.put("totalJackpotContribution", totalJackpotContribution);
        json.put("netResult", netResult);
        json.put("isFolded", folded);
        json.put("zeroStackStartDate", toEpochMillis(zeroStackStartDate));
        Map<Integer, String> holeCardStrings = new HashMap<>();
        for (int i = 0; i < holeCards.size(); i++) {
            holeCardStrings.put(i, holeCards.get(i).toString());
        }
        json.put("holeCards", holeCardStrings);
        json.put("botRecharges", botRecharges);
        json.put("cgpScore", cgpScore);
        json.put("seatedAt", seatedAt);
        json.put("showdownActed", showdownActed);
        json.put("isBusted", isBusted);
        json.put("pendingRecharge", pendingRecharge);
        return json;
    }

    private static Long toEpochMillis(LocalDateTime ldt) {
        return ldt != null ? ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
    }
}

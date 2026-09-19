package dev.manestack.domain.user;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class User {
    private int userId;
    private String email;
    private boolean isVerified;
    private String username;
    private String profileUrl;
    private Role role;
    private String bankName;
    private String accountNumber;
    private String password;
    private UserBalance userBalance;    
    private LocalDateTime lastDailyBonus;
    private String avatar;
    private String avatarBorder;
    private int cgpScore;

    public User() {}

    public User(int userId, String email, boolean isVerified, String username, String profileUrl, Role role, String bankName, String accountNumber, String password, UserBalance userBalance) {
        this.userId = userId;
        this.email = email;
        this.isVerified = isVerified;
        this.username = username;
        this.profileUrl = profileUrl;
        this.role = role;
        this.bankName = bankName;
        this.accountNumber = accountNumber;
        this.password = password;
        this.userBalance = userBalance;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean isVerified() {
        return isVerified;
    }

    public void setVerified(boolean verified) {
        isVerified = verified;
    }

    public String getProfileUrl() {
        return profileUrl;
    }

    public void setProfileUrl(String profileUrl) {
        this.profileUrl = profileUrl;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getBankName() {
        return bankName;
    }

    public void setBankName(String bankName) {
        this.bankName = bankName;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
    public String getAvatar() { return avatar; }
    public void setAvatar(String avatar) { this.avatar = avatar; }

    public String getAvatarBorder() { return avatarBorder; }
    public void setAvatarBorder(String avatarBorder) { this.avatarBorder = avatarBorder; }

    public void validateRegister() {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username is required");
        }
        if (username.length() < 3 || username.length() > 20) {
            throw new IllegalArgumentException("Username must be 3-20 characters");
        }
        if (!username.matches("^[a-zA-Z0-9_]+$")) {
            throw new IllegalArgumentException("Username can only contain letters, numbers, and underscores");
        }
        if (email == null || email.trim().isEmpty()) {
            throw new IllegalArgumentException("Email is required");
        }
        if (!email.matches("^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$")) {
            throw new IllegalArgumentException("Invalid email format");
        }
        if (email.length() > 100) {
            throw new IllegalArgumentException("Email must be 100 characters or less");
        }
        if (bankName == null || bankName.trim().isEmpty()) {
            throw new IllegalArgumentException("Bank name is required");
        }
        if (bankName.length() > 100) {
            throw new IllegalArgumentException("Bank name must be 100 characters or less");
        }
        if (accountNumber == null || accountNumber.trim().isEmpty()) {
            throw new IllegalArgumentException("Account number is required");
        }
        if (!accountNumber.matches("^[0-9]+$")) {
            throw new IllegalArgumentException("Account number must contain only digits");
        }
        if (accountNumber.length() < 5 || accountNumber.length() > 20) {
            throw new IllegalArgumentException("Account number must be 5-20 digits");
        }
        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password is required");
        }
        if (password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        if (password.length() > 100) {
            throw new IllegalArgumentException("Password must be 100 characters or less");
        }
    }

    public UserBalance getUserBalance() {
        return userBalance;
    }

    public void setUserBalance(UserBalance userBalance) {
        this.userBalance = userBalance;
    }

    public LocalDateTime getLastDailyBonus() {
        return lastDailyBonus;
    }

    public void setLastDailyBonus(LocalDateTime lastDailyBonus) {
        this.lastDailyBonus = lastDailyBonus;
    }

    public int getCgpScore() {
        return cgpScore;
    }

    public void setCgpScore(int cgpScore) {
        this.cgpScore = cgpScore;
    }

    public enum Role {
        USER("User"),
        ADMIN("Admin"),
        MODERATOR("Moderator"),
        SUPER_ADMIN("Super Admin"),
        BOT("Bot"); 

        private final String displayName;

        Role(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    @Override
    public String toString() {
        return "User{" +
                "userId=" + userId +
                ", email='" + email + '\'' +
                ", isVerified=" + isVerified +
                ", username='" + username + '\'' +
                ", profileUrl='" + profileUrl + '\'' +
                ", role=" + role +
                ", bankName='" + bankName + '\'' +
                ", accountNumber='" + accountNumber + '\'' +
                ", userBalance=" + userBalance +
                '}';
    }
}

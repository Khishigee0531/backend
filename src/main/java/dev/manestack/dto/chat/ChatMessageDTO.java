package dev.manestack.dto.chat;

import java.time.LocalDateTime;

public class ChatMessageDTO {
    private long id;
    private long tableId;
    private long userId;
    private String username;
    private String message;
    private LocalDateTime createdAt;

    public ChatMessageDTO() {}

    public ChatMessageDTO(long id, long tableId, long userId, String username, String message, LocalDateTime createdAt) {
        this.id = id;
        this.tableId = tableId;
        this.userId = userId;
        this.username = username;
        this.message = message;
        this.createdAt = createdAt;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getTableId() { return tableId; }
    public void setTableId(long tableId) { this.tableId = tableId; }

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}

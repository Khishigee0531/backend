package dev.manestack.domain.poker;

import io.vertx.core.json.JsonObject;

import java.time.OffsetDateTime;

public class GameSessionSnapshot {
    private String sessionId;
    private Integer tableId;
    private JsonObject details;
    private OffsetDateTime createDate;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Integer getTableId() {
        return tableId;
    }

    public void setTableId(Integer tableId) {
        this.tableId = tableId;
    }

    public JsonObject getDetails() {
        return details;
    }

    public void setDetails(JsonObject details) {
        this.details = details;
    }

    public OffsetDateTime getCreateDate() {
        return createDate;
    }

    public void setCreateDate(OffsetDateTime createDate) {
        this.createDate = createDate;
    }
}

package dev.manestack.service;

import dev.manestack.domain.user.ChatSettings;
import dev.manestack.dto.chat.ChatMessageDTO;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.jooq.Record;

import java.util.List;

import static dev.manestack.jooq.generated.tables.PokerChatMessage.POKER_CHAT_MESSAGE;
import static dev.manestack.jooq.generated.tables.PokerDataBlock.POKER_DATA_BLOCK;

@ApplicationScoped
public class ChatService {

    private static final String CHAT_SETTINGS_BLOCK = "homepage_chat_settings";

    @Inject
    DSLContext dsl;

    public void saveMessage(ChatMessageDTO msg) {
        // Ensure tableId is null if 0 or not provided
        Long tableId = msg.getTableId();
        if (tableId == null || tableId == 0L) {
            tableId = null; // global chat or unknown table
        }

        dsl.insertInto(POKER_CHAT_MESSAGE)
           .set(POKER_CHAT_MESSAGE.TABLE_ID, tableId)
           .set(POKER_CHAT_MESSAGE.USER_ID, (int) msg.getUserId()) // cast primitive long to int
           .set(POKER_CHAT_MESSAGE.USERNAME, msg.getUsername())
           .set(POKER_CHAT_MESSAGE.MESSAGE, msg.getMessage())
           .set(POKER_CHAT_MESSAGE.CREATED_AT, msg.getCreatedAt())
           .execute();
    }

    public void deleteAllMessages() {
        dsl.deleteFrom(POKER_CHAT_MESSAGE)
           .where(POKER_CHAT_MESSAGE.TABLE_ID.isNull())
           .execute();
    }

    public List<ChatMessageDTO> getRecentMessages(int limit) {
        return dsl.selectFrom(POKER_CHAT_MESSAGE)
                .where(POKER_CHAT_MESSAGE.TABLE_ID.isNull())
                .orderBy(POKER_CHAT_MESSAGE.CREATED_AT.desc())
                .limit(limit)
                .fetchInto(ChatMessageDTO.class)
                .stream()
                .filter(m -> m.getCreatedAt() != null)
                .sorted((a, b) -> a.getCreatedAt().compareTo(b.getCreatedAt()))
                .toList();
    }

    public ChatSettings getChatSettings() {
        Record record = dsl.selectFrom(POKER_DATA_BLOCK)
                .where(POKER_DATA_BLOCK.NAME.eq(CHAT_SETTINGS_BLOCK))
                .fetchOne();
        if (record == null) {
            return new ChatSettings(true, true);
        }
        try {
            JsonObject json = new JsonObject(record.get(POKER_DATA_BLOCK.VALUE));
            return new ChatSettings(
                    json.getBoolean("usersCanChat", true),
                    json.getBoolean("adminsCanChat", true)
            );
        } catch (Exception e) {
            return new ChatSettings(true, true);
        }
    }

    public void saveChatSettings(ChatSettings settings) {
        String value = new JsonObject()
                .put("usersCanChat", settings.isUsersCanChat())
                .put("adminsCanChat", settings.isAdminsCanChat())
                .toString();

        boolean exists = dsl.fetchExists(dsl.selectFrom(POKER_DATA_BLOCK)
                .where(POKER_DATA_BLOCK.NAME.eq(CHAT_SETTINGS_BLOCK)));

        if (exists) {
            dsl.update(POKER_DATA_BLOCK)
                    .set(POKER_DATA_BLOCK.VALUE, value)
                    .where(POKER_DATA_BLOCK.NAME.eq(CHAT_SETTINGS_BLOCK))
                    .execute();
        } else {
            dsl.insertInto(POKER_DATA_BLOCK)
                    .set(POKER_DATA_BLOCK.NAME, CHAT_SETTINGS_BLOCK)
                    .set(POKER_DATA_BLOCK.VALUE, value)
                    .execute();
        }
    }

}

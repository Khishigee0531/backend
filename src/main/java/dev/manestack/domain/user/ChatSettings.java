package dev.manestack.domain.user;

public class ChatSettings {
    private boolean usersCanChat;
    private boolean adminsCanChat;

    public ChatSettings() {
    }

    public ChatSettings(boolean usersCanChat, boolean adminsCanChat) {
        this.usersCanChat = usersCanChat;
        this.adminsCanChat = adminsCanChat;
    }

    public boolean isUsersCanChat() {
        return usersCanChat;
    }

    public void setUsersCanChat(boolean usersCanChat) {
        this.usersCanChat = usersCanChat;
    }

    public boolean isAdminsCanChat() {
        return adminsCanChat;
    }

    public void setAdminsCanChat(boolean adminsCanChat) {
        this.adminsCanChat = adminsCanChat;
    }
}

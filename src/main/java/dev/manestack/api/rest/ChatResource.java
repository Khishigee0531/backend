package dev.manestack.api.rest;

import dev.manestack.dto.chat.ChatMessageDTO;
import dev.manestack.service.ChatService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Tag(name = "Chat", description = "Chat message operations")
@Path("/api/chat")
@Produces(MediaType.APPLICATION_JSON)
public class ChatResource {

    @Inject
    ChatService chatService;

    @Operation(summary = "Get recent messages", description = "Fetch recent chat messages")
    @GET
    @Path("/messages")
    public List<ChatMessageDTO> getMessages(@QueryParam("limit") @DefaultValue("50") int limit) {
        return chatService.getRecentMessages(limit);
    }
}

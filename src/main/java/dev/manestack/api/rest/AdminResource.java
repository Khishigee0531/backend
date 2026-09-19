package dev.manestack.api.rest;

import dev.manestack.api.CurrentUser;
import dev.manestack.api.ws.GlobalSocket;
import dev.manestack.domain.poker.GameSessionSnapshot;
import dev.manestack.domain.user.ChatSettings;
import dev.manestack.domain.user.DataBlock;
import dev.manestack.domain.user.Deposit;
import dev.manestack.domain.user.User;
import dev.manestack.domain.user.Withdrawal;
import dev.manestack.dto.admin.ProfitSummary;
import dev.manestack.dto.admin.ProfitTrendPoint;
import dev.manestack.dto.user.RoleUpdateRequest;
import dev.manestack.service.ChatService;
import dev.manestack.service.DepositService;
import dev.manestack.service.BalanceService;
import dev.manestack.service.GameService;
import dev.manestack.service.ProfitService;
import dev.manestack.service.UserService;
import dev.manestack.service.poker.table.GameTable;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Tag(name = "Admin", description = "Admin-only operations")
@RolesAllowed({"ADMIN"})
@Path("/api/v1/admin")
public class AdminResource {

    @Inject
    CurrentUser currentUser;

    @Inject
    UserService userService;

    @Inject
    DepositService depositService;

    @Inject
    BalanceService balanceService;

    @Inject
    GameService gameService;

    @Inject
    ChatService chatService;

    @Inject
    ProfitService profitService;

    @Inject
    GlobalSocket globalSocket;

    @Operation(summary = "Search users", description = "Search all users including deleted")
    @GET
    @Path("/user/search")
    public Uni<List<User>> searchUsers(@QueryParam("username") @DefaultValue("") String username) {
        return userService.searchUsers(username, true);
    }

    @Operation(summary = "Delete user", description = "Soft-delete a user account")
    @DELETE
    @Path("/user")
    public Uni<Void> deleteUser(@QueryParam("userId") Integer userId) {
        return currentUser.getUserId()
                .chain(adminId -> userService.deleteUser(userId, adminId));
    }

    @Operation(summary = "List deposits", description = "Fetch deposits optionally filtered by user")
    @GET
    @Path("/deposit")
    public Uni<List<Deposit>> fetchDeposits(@QueryParam("userId") Integer userId) {
        return depositService.fetchDeposits(userId);
    }

    @Operation(summary = "Approve deposit", description = "Approve a pending deposit")
    @PUT
    @Path("/deposit/approve")
    public Uni<Deposit> approveDeposit(@QueryParam("depositId") Long depositId) {
        return currentUser.getUserId()
                .chain(adminId -> depositService.approveDeposit(adminId, depositId));
    }

    @Operation(summary = "Deny deposit", description = "Deny a pending deposit with a reason")
    @PUT
    @Path("/deposit/deny")
    public Uni<Deposit> denyDeposit(
            @QueryParam("depositId") Long depositId,
            JsonObject payload
    ) {
        return currentUser.getUserId()
                .chain(adminId -> {
                    String deniedReason = payload != null ? payload.getString("deniedReason") : null;
                    return depositService.denyDeposit(adminId, depositId, deniedReason);
                });
    }

    @Operation(summary = "List withdrawals", description = "Fetch withdrawals optionally filtered by user")
    @GET
    @Path("/withdrawal")
    public Uni<List<Withdrawal>> fetchWithdrawals(@QueryParam("userId") Integer userId) {
        return depositService.fetchWithdrawals(userId);
    }

    @Operation(summary = "Approve withdrawal", description = "Approve a pending withdrawal")
    @PUT
    @Path("/withdrawal/approve")
    public Uni<Withdrawal> approveWithdrawal(@QueryParam("withdrawalId") Long withdrawalId) {
        return currentUser.getUserId()
                .chain(adminId -> depositService.approveWithdrawal(adminId, withdrawalId));
    }

    @Operation(summary = "List tables", description = "Fetch all game tables")
    @GET
    @Path("/table")
    public Uni<Collection<GameTable>> fetchTables() {
        return gameService.fetchTables();
    }

    @Operation(summary = "Create table", description = "Create a new game table")
    @POST
    @Path("/table")
    public Uni<GameTable> createTable(GameTable gameTable) {
        return currentUser.getUserId()
                .chain(adminId -> gameService.createTable(adminId, gameTable));
    }

    @Operation(summary = "Update table", description = "Update an existing game table")
    @PATCH
    @Path("/table")
    public Uni<GameTable> updateTable(GameTable gameTable) {
        return gameService.updateTable(gameTable);
    }

    @Operation(summary = "Delete table", description = "Delete a game table")
    @DELETE
    @Path("/table")
    public Uni<Void> deleteTable(@QueryParam("tableId") Long tableId) {
        return currentUser.getUserId()
                .chain(adminId -> gameService.deleteTable(tableId, adminId));
    }

    @Operation(summary = "Session snapshots", description = "Fetch game session snapshots by table")
    @GET
    @Path("/table/session")
    public Uni<List<GameSessionSnapshot>> fetchSnapshotsByTableId(@QueryParam("tableId") Integer tableId) {
        return gameService.fetchGameSessionSnapshots(tableId);
    }

    @Operation(summary = "Schedule maintenance", description = "Set maintenance window")
    @POST
    @Path("/maintenance")
    public Uni<Void> scheduleMaintenance(JsonObject jsonObject) {
        String startDate = jsonObject.getString("startDate");
        String endDate = jsonObject.getString("endDate");
        if (startDate == null || endDate == null) {
            return Uni.createFrom().failure(new IllegalArgumentException("Start date and end date must be provided"));
        }
        LocalDateTime start = LocalDateTime.parse(startDate);
        LocalDateTime end = LocalDateTime.parse(endDate);
        return currentUser.getUserId()
                .chain(adminId -> gameService.scheduleMaintenanceSchedule(adminId, start, end));
    }

    @Operation(summary = "Kick player", description = "Remove a player from a table")
    @DELETE
    @Path("/table/kick")
    public Uni<Response> kickPlayer(@QueryParam("tableId") Long tableId, @QueryParam("userId") Integer userId) {
        return gameService.kickPlayerFromTable(tableId, userId)
                .onItem().transform(result -> Response.ok().build())
                .onFailure().recoverWithItem(Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
    }

    @Operation(summary = "Cancel pending kick", description = "Cancel a queued kick before it executes")
    @DELETE
    @Path("/table/kick/cancel")
    public Uni<Response> cancelPendingKick(@QueryParam("tableId") Long tableId, @QueryParam("userId") Integer userId) {
        return gameService.cancelPendingKick(tableId, userId)
                .onItem().transform(result -> Response.ok().build())
                .onFailure().recoverWithItem(Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
    }

    @Operation(summary = "List data blocks", description = "Fetch all configuration data blocks")
    @GET
    @Path("/block/list")
    public Uni<Response> fetchDataBlocks() {
        return userService.fetchDataBlocks()
                .onItem().transform(result -> Response.ok(result).build())
                .onFailure().recoverWithItem(Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
    }

    @Operation(summary = "Create data block", description = "Create a configuration data block")
    @POST
    @Path("/block")
    public Uni<Response> createDataBlocks(DataBlock dataBlock) {
        return userService.createDataBlock(dataBlock)
                .onItem().transform(result -> Response.ok(result).build())
                .onFailure().recoverWithItem(Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
    }

    @Operation(summary = "Update data block", description = "Update a configuration data block")
    @PUT
    @Path("/block/{name}")
    public Uni<Response> updateDataBlock(@PathParam("name") String blockName, DataBlock dataBlock) {
        return userService.updateDataBlock(blockName, dataBlock)
                .onItem().transform(result -> Response.ok(result).build())
                .onFailure().recoverWithItem(Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
    }

    @Operation(summary = "Delete data block", description = "Delete a configuration data block")
    @DELETE
    @Path("/block/{name}")
    public Uni<Response> deleteDataBlock(@PathParam("name") String blockName) {
        return userService.deleteDataBlock(blockName)
                .onItem().transform(result -> Response.ok().build())
                .onFailure().recoverWithItem(err ->
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                                .entity("Failed to delete data block: " + err.getMessage())
                                .build()
                );
    }

    @Operation(summary = "Update user role", description = "Change a user's role")
    @PATCH
    @Path("/user/role")
    @Transactional
    public Uni<Response> updateUserRole(@QueryParam("userId") Integer userId, RoleUpdateRequest request) {
        if (request == null || request.role == null) {
            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST).entity("Role is required").build());
        }
        return userService.updateUserRole(userId, request.role.toUpperCase())
                .onItem().transform(unused -> Response.ok().build())
                .onFailure().recoverWithItem(Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
    }

    @Operation(summary = "Broadcast message", description = "Send a system-wide broadcast message")
    @POST
    @Path("/broadcast")
    public Uni<Response> broadcastMessage(JsonObject payload) {
        String message = payload != null ? payload.getString("message") : null;
        if (message == null || message.isBlank()) {
            return Uni.createFrom().item(Response.status(Response.Status.BAD_REQUEST).entity("message is required").build());
        }
        GlobalSocket.broadcastSystemMessage(message);
        return Uni.createFrom().item(Response.ok().build());
    }

    @Operation(summary = "Update user balance", description = "Manually adjust a user's balance")
    @PATCH
    @Path("/user/balance")
    @Transactional
    public Uni<Response> updateUserBalance(
            @QueryParam("userId") Integer userId,
            @QueryParam("amount") Double newBalance,
            @QueryParam("bonus") Integer newBonus
    ) {
        if (userId == null || newBalance == null) {
            return Uni.createFrom()
                    .item(Response.status(Response.Status.BAD_REQUEST)
                            .entity("userId and amount are required")
                            .build());
        }
        return currentUser.getUserId()
                .chain(id -> {
                    if (newBonus == null) {
                        return balanceService.fetchUserBalance(userId)
                                .chain(userBalance -> balanceService.updateUserBalance(
                                        userId,
                                        newBalance.intValue(),
                                        userBalance.getBonusBalance() != null ? userBalance.getBonusBalance() : 0
                                ));
                    } else {
                        return balanceService.updateUserBalance(userId, newBalance.intValue(), newBonus);
                    }
                })
                .chain(updated -> {
                    GlobalSocket.sendBalanceUpdate(userId, updated);
                    return Uni.createFrom().item(updated);
                })
                .onItem().transform(unused -> Response.ok().build())
                .onFailure().recoverWithItem(
                        Response.status(Response.Status.INTERNAL_SERVER_ERROR).build()
                );
    }

    @Operation(summary = "Delete chat messages", description = "Delete all chat messages")
    @DELETE
    @Path("/chat")
    public Uni<Response> deleteAllChatMessages() {
        chatService.deleteAllMessages();
        globalSocket.broadcastJson("CHAT_CLEARED", new JsonObject());
        return Uni.createFrom().item(Response.noContent().build());
    }

    @Operation(summary = "Get chat settings", description = "Fetch homepage chat permission settings")
    @GET
    @Path("/chat/settings")
    public Response fetchChatSettings() {
        return Response.ok(chatService.getChatSettings()).build();
    }

    @Operation(summary = "Update chat settings", description = "Toggle whether users/admins can write in the homepage chat")
    @PUT
    @Path("/chat/settings")
    public Response updateChatSettings(ChatSettings settings) {
        if (settings == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity("Chat settings are required").build();
        }
        chatService.saveChatSettings(settings);
        globalSocket.updateChatSettings(settings);
        return Response.ok(settings).build();
    }

    @Operation(summary = "Profit summary", description = "House profit breakdown for a period (DAY|WEEK|MONTH|ALL)")
    @GET
    @Path("/profit")
    public Uni<ProfitSummary> fetchProfit(@QueryParam("period") @DefaultValue("DAY") String period) {
        return Uni.createFrom().item(profitService.summary(period));
    }

    @Operation(summary = "Profit trend", description = "Daily house profit series for the last N days")
    @GET
    @Path("/profit/trend")
    public Uni<List<ProfitTrendPoint>> fetchProfitTrend(@QueryParam("days") @DefaultValue("30") int days) {
        return Uni.createFrom().item(profitService.trend(days));
    }

}

package dev.manestack.api.rest;

import dev.manestack.api.CurrentUser;
import dev.manestack.domain.user.Deposit;
import dev.manestack.domain.user.Outcome;
import dev.manestack.domain.user.User;
import dev.manestack.domain.user.UserBalance;
import dev.manestack.domain.user.Withdrawal;
import dev.manestack.service.DepositService;
import dev.manestack.service.BalanceService;
import dev.manestack.service.GameService;
import dev.manestack.service.UserService;
import dev.manestack.service.poker.table.GameTable;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.tuples.Tuple2;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.NotFoundException;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Tag(name = "User", description = "User account and profile operations")
@Path("/api/v1/user")
public class UserResource {

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

    @Operation(summary = "Login", description = "Authenticate with username and password")
    @APIResponse(responseCode = "200", description = "JWT token returned")
    @POST
    @Path("/login")
    public Uni<JsonObject> loginUser(JsonObject jsonObject) {
        String username = jsonObject.getString("username");
        String password = jsonObject.getString("password");
        if (username == null || password == null) {
            throw new IllegalArgumentException("Username and password are required");
        }
        username = username.trim().toLowerCase();
        return userService.loginUser(username, password)
                .map(token -> new JsonObject().put("token", token));
    }

    @Operation(summary = "Register", description = "Create a new user account")
    @APIResponse(responseCode = "200", description = "JWT token returned")
    @POST
    @Path("/register")
    public Uni<JsonObject> registerUser(User user) {
        if (user.getUsername() != null) {
            user.setUsername(user.getUsername().trim().toLowerCase());
        }
        if (user.getEmail() != null) {
            user.setEmail(user.getEmail().trim().toLowerCase());
        }
        return userService.registerUser(user)
                .map(token -> new JsonObject().put("token", token));
    }

    @Operation(summary = "Get current user", description = "Fetch authenticated user profile with balance")
    @APIResponse(responseCode = "200", description = "User profile or null if not found")
    @Authenticated
    @GET
    @Path("/me")
    public Uni<User> fetchUserFromToken() {
        return currentUser.getUserId()
                .chain(userId -> userService.fetchUser(userId)
                        .chain(user -> {
                            if (user == null) return Uni.createFrom().nullItem();
                            return balanceService.fetchUserBalance(user.getUserId())
                                    .map(userBalance -> {
                                        user.setUserBalance(userBalance);
                                        return user;
                                    });
                        }));
    }

    @Operation(summary = "Claim bonus", description = "Claim pending bonus balance")
    @Authenticated
    @POST
    @Path("/bonus/claim")
    public Uni<UserBalance> claimBonus() {
        return currentUser.getUserId()
                .chain(userId -> balanceService.fetchUserBalance(userId)
                        .chain(userBalance -> {
                            int bonus = userBalance.getBonusBalance() != null ? userBalance.getBonusBalance() : 0;
                            if (bonus > 0) {
                                return balanceService.updateUserBalance(userId, userBalance.getBalance() + bonus, 0);
                            }
                            return Uni.createFrom().item(userBalance);
                        }));
    }

    @Operation(summary = "Update profile", description = "Update authenticated user's profile")
    @Authenticated
    @PATCH
    @Path("/me")
    public Uni<User> updateUser(User user) {
        return currentUser.getUserId()
                .chain(userId -> userService.updateUser(userId, user));
    }

    @Operation(summary = "Search users", description = "Search users by username")
    @Authenticated
    @GET
    @Path("/search")
    public Uni<List<User>> searchUsers(@QueryParam("username") @DefaultValue("") String username) {
        return userService.searchUsers(username, false);
    }

    @Operation(summary = "List tables", description = "Fetch all active game tables")
    @GET
    @Path("/tables")
    public Uni<Collection<GameTable>> fetchTables() {
        return gameService.fetchTables();
    }

    @GET
    @Path("/maintenance")
    public Tuple2<LocalDateTime, LocalDateTime> fetchMaintenanceSchedule() {
        return gameService.getMaintenanceSchedule();
    }

    @Operation(summary = "My deposits", description = "Fetch current user's deposit history")
    @Authenticated
    @GET
    @Path("/deposit")
    public Uni<List<Deposit>> fetchMyDeposits() {
        return currentUser.getUserId()
                .chain(userId -> depositService.fetchDeposits(userId));
    }

    @Operation(summary = "Create deposit", description = "Submit a deposit request")
    @Authenticated
    @POST
    @Path("/deposit")
    public Uni<Deposit> createDepositRequest(Deposit deposit) {
        return currentUser.getUserId()
                .chain(userId -> depositService.createDepositRequest(userId, deposit));
    }

    @Operation(summary = "My withdrawals", description = "Fetch current user's withdrawal history")
    @Authenticated
    @GET
    @Path("/withdrawal")
    public Uni<List<Withdrawal>> fetchMyWithdrawals() {
        return currentUser.getUserId()
                .chain(userId -> depositService.fetchWithdrawals(userId));
    }

    @Operation(summary = "Create withdrawal", description = "Submit a withdrawal request")
    @Authenticated
    @POST
    @Path("/withdrawal")
    public Uni<Withdrawal> createWithdrawal(Withdrawal withdrawal) {
        return currentUser.getUserId()
                .chain(userId -> depositService.createWithdrawal(userId, withdrawal));
    }

    @Operation(summary = "My outcomes", description = "Fetch current user's game outcomes")
    @Authenticated
    @GET
    @Path("/outcome")
    public Uni<List<Outcome>> fetchUserOutcome() {
        return currentUser.getUserId()
                .chain(userId -> depositService.fetchOutcomes(userId));
    }

    @Operation(summary = "Get data block", description = "Fetch a data block by name")
    @GET
    @Path("/block/{name}")
    public Uni<Response> fetchDataBlockByName(@PathParam("name") String dataBlockName) {
        return currentUser.getUserIdOrNull()
                .chain(() -> userService.fetchDataBlockByName(dataBlockName))
                .onItem().transform(result -> Response.ok(result).build())
                .onFailure().recoverWithItem(Response.status(Response.Status.INTERNAL_SERVER_ERROR).build());
    }

    @Operation(summary = "Claim daily bonus", description = "Claim the daily random bonus")
    @Authenticated
    @POST
    @Path("/daily-bonus")
    public Uni<Response> claimDailyBonus() {
        return currentUser.getUserId()
                .chain(userId -> {
                    int min = 5000;
                    int max = 20000;
                    int step = 1000;
                    int randomMultiplier = new java.util.Random().nextInt((max - min) / step + 1);
                    int bonusAmount = min + randomMultiplier * step;
                    return userService.claimDailyBonus(userId, bonusAmount)
                            .map(userBalance -> {
                                JsonObject result = new JsonObject();
                                result.put("userBalance", userBalance);
                                result.put("bonusAmount", bonusAmount);
                                return Response.ok(result).build();
                            });
                })
                .onFailure().recoverWithItem(ex ->
                        Response.status(Response.Status.BAD_REQUEST)
                                .entity(ex.getMessage())
                                .build()
                );
    }

    @Operation(summary = "Update avatar", description = "Update user avatar and border")
    @Authenticated
    @PATCH
    @Path("/avatar")
    public Uni<User> updateAvatar(JsonObject json) {
        String avatar = json.getString("avatar");
        String avatarBorder = json.getString("avatarBorder");
        return currentUser.getUserId()
                .chain(userId -> userService.updateAvatar(userId, avatar, avatarBorder));
    }

    @Operation(summary = "Get table by ID", description = "Fetch a table by numeric ID")
    @GET
    @Path("/tables/{id: [0-9]+}")
    public Uni<GameTable> fetchTableById(@PathParam("id") Long id) {
        return gameService.fetchTableById(id)
                .onItem().ifNull().failWith(() -> new NotFoundException("Table not found"));
    }

    @Operation(summary = "Get table by secure ID", description = "Fetch a table by its secure string ID")
    @GET
    @Path("/tables/{secureId}")
    public Uni<GameTable> fetchTableBySecureId(@PathParam("secureId") String secureId) {
        return gameService.fetchTableBySecureId(secureId)
                .onItem().ifNull().failWith(() -> new NotFoundException("Table not found"));
    }

    @Operation(summary = "Get table session state", description = "Fetch session state for a table (for background polling)")
    @Authenticated
    @GET
    @Path("/tables/{id: [0-9]+}/session-state")
    public Uni<JsonObject> fetchTableSessionState(@PathParam("id") Long id) {
        return currentUser.getUserId()
                .chain(userId -> gameService.fetchTableSessionState(id, userId));
    }
}

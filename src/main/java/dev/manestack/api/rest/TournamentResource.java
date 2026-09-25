package dev.manestack.api.rest;

import dev.manestack.api.CurrentUser;
import dev.manestack.dto.tournament.*;
import dev.manestack.service.tournament.TournamentService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Tag(name = "Tournament", description = "Tournament browse and registration")
@Path("/api/v1/tournaments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TournamentResource {

    @Inject
    TournamentService tournamentService;

    @Inject
    CurrentUser currentUser;

    @Operation(summary = "Tournament settings", description = "Public site-wide tournament enable/disable flag")
    @GET
    @Path("/settings")
    public TournamentSettings settings() {
        return tournamentService.getTournamentSettings();
    }

    @Operation(summary = "List tournaments", description = "Public list of tournaments")
    @GET
    public Uni<List<TournamentDTO>> list(
            @QueryParam("status") String status,
            @QueryParam("includeFinished") @DefaultValue("false") boolean includeFinished) {
        if (!tournamentService.isTournamentEnabled()) {
            return Uni.createFrom().item(List.of());
        }
        return tournamentService.listTournaments(status, includeFinished);
    }

    @Operation(summary = "Get tournament", description = "Tournament details by id")
    @GET
    @Path("/{tournamentId}")
    public TournamentDTO get(@PathParam("tournamentId") Long tournamentId) {
        return tournamentService.getTournament(tournamentId).await().indefinitely();
    }

    @Operation(summary = "List entries", description = "Players registered for a tournament")
    @GET
    @Path("/{tournamentId}/entries")
    public Uni<List<TournamentEntryDTO>> entries(@PathParam("tournamentId") Long tournamentId) {
        return tournamentService.listEntries(tournamentId);
    }

    @Operation(summary = "My entries", description = "Current user's tournament entries")
    @GET
    @Path("/my-entries")
    @io.quarkus.security.Authenticated
    public Uni<List<TournamentEntryDTO>> myEntries() {
        return currentUser.getUserId().chain(userId -> tournamentService.listMyEntries(userId));
    }

    @Operation(summary = "My seat", description = "Current user's seat/table for a tournament")
    @GET
    @Path("/{tournamentId}/my-seat")
    @io.quarkus.security.Authenticated
    public Uni<TournamentMySeatDTO> mySeat(@PathParam("tournamentId") Long tournamentId) {
        return currentUser.getUserId()
                .chain(userId -> tournamentService.getMySeat(userId, tournamentId));
    }

    @Operation(summary = "Register", description = "Register current user for a tournament")
    @POST
    @Path("/{tournamentId}/register")
    public Uni<Response> register(@PathParam("tournamentId") Long tournamentId) {
        return currentUser.getUserId()
                .chain(userId -> tournamentService.register(userId, tournamentId))
                .onItem().transform(entry -> Response.status(Response.Status.CREATED).entity(entry).build());
    }

    @Operation(summary = "Unregister", description = "Cancel registration and refund buy-in before start")
    @DELETE
    @Path("/{tournamentId}/register")
    public Uni<Response> unregister(@PathParam("tournamentId") Long tournamentId) {
        return currentUser.getUserId()
                .chain(userId -> tournamentService.unregister(userId, tournamentId))
                .onItem().transform(unused -> Response.noContent().build());
    }

    @Operation(summary = "Rebuy", description = "Purchase a rebuy for the current user")
    @POST
    @Path("/{tournamentId}/rebuy")
    public TournamentEntryDTO rebuy(@PathParam("tournamentId") Long tournamentId, TournamentRebuyRequest body) {
        Integer userId = body != null && body.userId() != null
                ? body.userId()
                : currentUser.getUserId().await().indefinitely();
        return tournamentService.rebuy(userId, tournamentId).await().indefinitely();
    }

    @Operation(summary = "Addon", description = "Purchase an addon for the current user")
    @POST
    @Path("/{tournamentId}/addon")
    public TournamentEntryDTO addon(@PathParam("tournamentId") Long tournamentId, TournamentAddonRequest body) {
        Integer userId = body != null && body.userId() != null
                ? body.userId()
                : currentUser.getUserId().await().indefinitely();
        return tournamentService.addon(userId, tournamentId).await().indefinitely();
    }
}

package dev.manestack.api.rest;

import dev.manestack.api.CurrentUser;
import dev.manestack.dto.tournament.*;
import dev.manestack.service.tournament.TournamentService;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Tag(name = "Admin Tournament", description = "Admin tournament management")
@RolesAllowed({"ADMIN"})
@Path("/api/v1/admin/tournaments")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminTournamentResource {

    @Inject
    TournamentService tournamentService;

    @Inject
    CurrentUser currentUser;

    @Operation(summary = "Get tournament settings", description = "Site-wide tournament enable/disable")
    @GET
    @Path("/settings")
    public TournamentSettings settings() {
        return tournamentService.getTournamentSettings();
    }

    @Operation(summary = "Update tournament settings", description = "Toggle site-wide tournament availability")
    @PUT
    @Path("/settings")
    public TournamentSettings updateSettings(TournamentSettings settings) {
        if (settings == null) {
            throw new BadRequestException("settings are required");
        }
        return tournamentService.saveTournamentSettings(settings);
    }

    @Operation(summary = "Create tournament")
    @POST
    public Uni<Response> create(TournamentCreateRequest request) {
        return currentUser.getUserId()
                .chain(adminId -> tournamentService.createTournament(adminId, request))
                .onItem().transform(t -> Response.status(Response.Status.CREATED).entity(t).build());
    }

    @Operation(summary = "Update tournament")
    @PATCH
    @Path("/{tournamentId}")
    public TournamentDTO update(@PathParam("tournamentId") Long tournamentId, TournamentUpdateRequest request) {
        return tournamentService.updateTournament(tournamentId, request).await().indefinitely();
    }

    @Operation(summary = "Update tournament status", description = "Open registration / start / pause / complete / cancel")
    @PATCH
    @Path("/{tournamentId}/status")
    public TournamentDTO updateStatus(@PathParam("tournamentId") Long tournamentId, TournamentStatusUpdateRequest request) {
        return tournamentService.updateStatus(tournamentId, request).await().indefinitely();
    }

    @Operation(summary = "List tournaments (all statuses)")
    @GET
    public Uni<List<TournamentDTO>> list(@QueryParam("status") String status) {
        return tournamentService.listTournaments(status, true);
    }

    @Operation(summary = "Eliminate player")
    @POST
    @Path("/{tournamentId}/entries/eliminate")
    public TournamentEntryDTO eliminate(
            @PathParam("tournamentId") Long tournamentId,
            TournamentEliminateRequest request) {
        if (request == null || request.userId() == null) {
            throw new BadRequestException("userId is required");
        }
        return tournamentService.eliminate(tournamentId, request.userId(), request.finalPosition())
                .chain(entry -> tournamentService.rebalanceTables(tournamentId).replaceWith(entry))
                .await().indefinitely();
    }

    @Operation(summary = "Assign game table to tournament")
    @POST
    @Path("/{tournamentId}/tables")
    public TournamentTableDTO assignTable(
            @PathParam("tournamentId") Long tournamentId,
            TournamentTableAssignRequest request) {
        if (request == null || request.tableId() == null || request.tableNumber() == null) {
            throw new BadRequestException("tableId and tableNumber are required");
        }
        return tournamentService.assignTable(tournamentId, request.tableId(), request.tableNumber())
                .await().indefinitely();
    }

    @Operation(summary = "Unassign game table")
    @DELETE
    @Path("/{tournamentId}/tables/{tournamentTableId}")
    public Response unassignTable(
            @PathParam("tournamentId") Long tournamentId,
            @PathParam("tournamentTableId") Long tournamentTableId) {
        tournamentService.unassignTable(tournamentId, tournamentTableId).await().indefinitely();
        return Response.noContent().build();
    }

    @Operation(summary = "List assigned tables")
    @GET
    @Path("/{tournamentId}/tables")
    public Uni<List<TournamentTableDTO>> listTables(@PathParam("tournamentId") Long tournamentId) {
        return tournamentService.listTournamentTables(tournamentId);
    }

    @Operation(summary = "List payouts")
    @GET
    @Path("/{tournamentId}/payouts")
    public Uni<List<TournamentPayoutDTO>> listPayouts(@PathParam("tournamentId") Long tournamentId) {
        return tournamentService.listPayouts(tournamentId);
    }

    @Operation(summary = "List entries")
    @GET
    @Path("/{tournamentId}/entries")
    public Uni<List<TournamentEntryDTO>> listEntries(@PathParam("tournamentId") Long tournamentId) {
        return tournamentService.listEntries(tournamentId);
    }
}

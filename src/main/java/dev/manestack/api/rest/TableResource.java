package dev.manestack.api.rest;

import dev.manestack.dto.poker.HandHistoryDTO;
import dev.manestack.service.GameService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

import java.util.List;

@Tag(name = "Table", description = "Game table operations")
@Path("/tables")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TableResource {

    @Inject
    GameService gameService;

    @Operation(summary = "Get hand history", description = "Fetch hand history for a table")
    @GET
    @Path("/{tableId}/hand-history")
    public Uni<List<HandHistoryDTO>> getHandHistory(
            @PathParam("tableId") Long tableId,
            @QueryParam("limit") @DefaultValue("10") int limit,
            @QueryParam("offset") @DefaultValue("0") int offset
    ) {
        if (tableId == null || tableId <= 0) throw new BadRequestException("tableId must be positive");
        if (limit <= 0) limit = 10;
        if (offset < 0) offset = 0;
        return gameService.getHandHistory(tableId, limit, offset);
    }
}

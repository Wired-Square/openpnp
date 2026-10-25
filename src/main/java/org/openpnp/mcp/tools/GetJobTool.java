package org.openpnp.mcp.tools;

import java.util.function.Supplier;

import org.openpnp.mcp.McpJson;
import org.openpnp.mcp.McpSchema;
import org.openpnp.mcp.McpTool;
import org.openpnp.mcp.McpToolResult;
import org.openpnp.model.BoardLocation;
import org.openpnp.model.Job;
import org.openpnp.model.Placement;
import org.openpnp.util.Utils2D;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public final class GetJobTool implements McpTool {
    private final Supplier<Job> job;

    public GetJobTool(Supplier<Job> job) {
        this.job = job;
    }

    @Override
    public String getName() {
        return "get_job";
    }

    @Override
    public String getDescription() {
        return "Describes the job loaded in OpenPnP: each board instance (panels flattened) with "
                + "its placements. location is the placement on the board as designed; "
                + "machineLocation is where it lands on the machine, after the board's position, "
                + "rotation and side.";
    }

    @Override
    public JsonObject getInputSchema() {
        return new McpSchema()
                .bool("placementsOnly", "Leave out fiducials and other non-placement types")
                .build();
    }

    @Override
    public Tier getTier() {
        return Tier.READ;
    }

    @Override
    public McpToolResult call(JsonObject arguments) {
        boolean placementsOnly = McpJson.optionalBoolean(arguments, "placementsOnly", false);
        Job loaded = job.get();
        if (loaded == null) {
            throw new IllegalArgumentException("No job is loaded");
        }

        JsonArray boards = new JsonArray();
        for (BoardLocation boardLocation : loaded.getBoardLocations()) {
            JsonObject board = new JsonObject();
            board.addProperty("file", boardLocation.getFileName());
            if (boardLocation.getBoard() == null) {
                board.addProperty("error", "The board file is not loaded");
                boards.add(board);
                continue;
            }
            board.addProperty("name", boardLocation.getBoard().getName());
            board.addProperty("side", boardLocation.getGlobalSide().toString());
            board.addProperty("enabled", boardLocation.isEnabled());
            board.add("location", McpJson.location(boardLocation.getGlobalLocation()));

            JsonArray placements = new JsonArray();
            for (Placement placement : boardLocation.getBoard().getPlacements()) {
                if (placementsOnly && placement.getType() != Placement.Type.Placement) {
                    continue;
                }
                placements.add(describe(loaded, boardLocation, placement));
            }
            board.add("placements", placements);
            boards.add(board);
        }

        JsonObject result = new JsonObject();
        if (loaded.getFile() != null) {
            result.addProperty("file", loaded.getFile().getPath());
        }
        result.add("boards", boards);
        return McpToolResult.json(result);
    }

    private static JsonObject describe(Job job, BoardLocation boardLocation,
            Placement placement) {
        JsonObject json = new JsonObject();
        json.addProperty("id", placement.getId());
        if (placement.getPart() != null) {
            json.addProperty("partId", placement.getPart().getId());
        }
        if (placement.getType() != null) {
            json.addProperty("type", placement.getType().toString());
        }
        json.addProperty("side", placement.getSide().toString());
        json.addProperty("enabled", placement.isEnabled());
        json.addProperty("placed", job.retrievePlacedStatus(boardLocation, placement.getId()));
        json.add("location", McpJson.location(placement.getLocation()));
        json.add("machineLocation", McpJson.location(
                Utils2D.calculateBoardPlacementLocation(boardLocation, placement)));
        return json;
    }
}

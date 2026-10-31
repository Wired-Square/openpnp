package org.openpnp.mcp.tools;

import java.util.ArrayList;
import java.util.List;

import org.openpnp.model.BoardLocation;
import org.openpnp.model.Job;
import org.openpnp.model.Placement;

/** The placements a job run would place, whether or not they are already marked placed. */
final class JobPlacements {
    private JobPlacements() {
    }

    static Job require(Job job) {
        if (job == null) {
            throw new IllegalArgumentException("No job is loaded");
        }
        return job;
    }

    /** Enabled placements with a part, on enabled boards whose file is loaded. */
    static List<Placement> enabled(Job job) {
        List<Placement> placements = new ArrayList<>();
        for (BoardLocation boardLocation : job.getBoardLocations()) {
            if (!boardLocation.isEnabled() || boardLocation.getBoard() == null) {
                continue;
            }
            for (Placement placement : boardLocation.getBoard().getPlacements()) {
                if (placement.isEnabled() && placement.getType() == Placement.Type.Placement
                        && placement.getPart() != null) {
                    placements.add(placement);
                }
            }
        }
        return placements;
    }
}

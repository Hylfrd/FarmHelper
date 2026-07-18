package dev.hylfrd.farmhelper.navigation.search;

import dev.hylfrd.farmhelper.navigation.NavigationGoal;
import dev.hylfrd.farmhelper.navigation.NavigationMode;
import dev.hylfrd.farmhelper.navigation.NavigationPhase;
import dev.hylfrd.farmhelper.navigation.NavigationWorkTicket;
import dev.hylfrd.farmhelper.navigation.SegmentedSpatialSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;

import java.util.Objects;

/**
 * Complete immutable input for one search. It contains only captured domain state and never a
 * Minecraft client/world object.
 */
public record PathSearchRequest(
        NavigationWorkTicket workTicket,
        SegmentedSpatialSnapshot spatialSnapshot,
        BoxSnapshot playerBody,
        NavigationGoal goal,
        NavigationMode mode
) {
    public PathSearchRequest {
        Objects.requireNonNull(workTicket, "workTicket");
        Objects.requireNonNull(spatialSnapshot, "spatialSnapshot");
        Objects.requireNonNull(playerBody, "playerBody");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(mode, "mode");
        if (workTicket.phase() != NavigationPhase.SEARCHING) {
            throw new IllegalArgumentException("path search requires a SEARCHING work ticket");
        }
        if (!spatialSnapshot.ticket().equals(workTicket.runTicket())) {
            throw new IllegalArgumentException("search and spatial snapshot identify different runs");
        }
        long captureRevision = spatialSnapshot.workTicket().revision();
        if (captureRevision >= workTicket.revision()) {
            throw new IllegalArgumentException("search has a stale capture revision");
        }
        if (!playerBody.hasPositiveVolume()) {
            throw new IllegalArgumentException("player body must have positive volume");
        }
        if (!spatialSnapshot.bounds().contains(playerBody)) {
            throw new IllegalArgumentException("player body lies outside the logical snapshot");
        }
        if (Math.abs(playerBody.width() - playerBody.depth()) > 1.0E-9D) {
            throw new IllegalArgumentException(
                    "upstream path nodes require a square horizontal entity footprint");
        }
        processorSize(playerBody.width(), "width");
        processorSize(playerBody.height(), "height");
        PathNode.startFor(playerBody);
        PathNode.targetFor(goal, playerBody.width(), playerBody.height());
    }

    public NavigationWorkTicket captureTicket() {
        return spatialSnapshot.workTicket();
    }

    public PathNode startNode() {
        return PathNode.startFor(playerBody);
    }

    public PathNode targetNode() {
        return PathNode.targetFor(goal, playerBody.width(), playerBody.height());
    }

    /** Upstream executor distance from the player's feet-position vector to the raw target. */
    public float directDistanceToGoal() {
        double x = (playerBody.minX() + playerBody.maxX()) / 2.0D;
        double y = playerBody.minY();
        double z = (playerBody.minZ() + playerBody.maxZ()) / 2.0D;
        double dx = goal.x() - x;
        double dy = goal.y() - y;
        double dz = goal.z() - z;
        // Entity#getDistance widens MathHelper.sqrt_double's float result back to double.
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public BoxSnapshot bodyAt(PathNode node) {
        Objects.requireNonNull(node, "node");
        return new BoxSnapshot(
                node.x(), node.y(), node.z(),
                (double) node.x() + processorSize(playerBody.width(), "width"),
                (double) node.y() + processorSize(playerBody.height(), "height"),
                (double) node.z() + processorSize(playerBody.width(), "width"));
    }

    private static int processorSize(double span, String name) {
        double expanded = span + 1.0D;
        double floor = Math.floor(expanded);
        if (!Double.isFinite(span) || floor <= 0.0D
                || floor > SpatialCaptureRequest.MAX_AXIS_SPAN) {
            throw new IllegalArgumentException(
                    "entity " + name + " exceeds the bounded processor size");
        }
        return (int) floor;
    }
}

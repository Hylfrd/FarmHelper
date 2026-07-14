package dev.hylfrd.farmhelper.runtime.spatial;

import java.util.Arrays;
import java.util.Objects;

/** A conservative spatial conclusion that never folds missing knowledge into a boolean. */
public enum SpaceStatus {
    PASSABLE,
    BLOCKED,
    UNKNOWN;

    /** Three-valued AND: blocked dominates unknown, and all inputs must be passable. */
    public static SpaceStatus allOf(Iterable<SpaceStatus> statuses) {
        Objects.requireNonNull(statuses, "statuses");
        boolean unknown = false;
        for (SpaceStatus status : statuses) {
            Objects.requireNonNull(status, "status");
            if (status == BLOCKED) {
                return BLOCKED;
            }
            unknown |= status == UNKNOWN;
        }
        return unknown ? UNKNOWN : PASSABLE;
    }

    public static SpaceStatus allOf(SpaceStatus... statuses) {
        Objects.requireNonNull(statuses, "statuses");
        return allOf(Arrays.asList(statuses));
    }

    /** Three-valued alternative OR: passable dominates unknown, then blocked. */
    public static SpaceStatus anyOf(Iterable<SpaceStatus> statuses) {
        Objects.requireNonNull(statuses, "statuses");
        boolean unknown = false;
        for (SpaceStatus status : statuses) {
            Objects.requireNonNull(status, "status");
            if (status == PASSABLE) {
                return PASSABLE;
            }
            unknown |= status == UNKNOWN;
        }
        return unknown ? UNKNOWN : BLOCKED;
    }

    public static SpaceStatus anyOf(SpaceStatus... statuses) {
        Objects.requireNonNull(statuses, "statuses");
        return anyOf(Arrays.asList(statuses));
    }
}

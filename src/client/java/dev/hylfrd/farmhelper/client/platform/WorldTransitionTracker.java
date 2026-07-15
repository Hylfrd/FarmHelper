package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.runtime.gamestate.WorldTransition;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;

import java.util.Objects;

/** Lifecycle evidence retained independently from the resettable chat buffer. */
final class WorldTransitionTracker {
    private boolean observed;
    private WorldIdentity previous = WorldIdentity.unknown();

    WorldTransition observe(Observation<WorldSnapshot> current) {
        Objects.requireNonNull(current, "current");
        WorldIdentity identity = WorldIdentity.from(current);
        WorldTransition transition = observed && !previous.equals(identity)
                ? WorldTransition.CHANGING
                : WorldTransition.STABLE;
        observed = true;
        previous = identity;
        return transition;
    }

    private record WorldIdentity(Observation.State state, long epoch) {
        private static WorldIdentity from(Observation<WorldSnapshot> world) {
            return world.isPresent()
                    ? new WorldIdentity(Observation.State.PRESENT, world.get().epoch())
                    : new WorldIdentity(world.state(), 0L);
        }

        private static WorldIdentity unknown() {
            return new WorldIdentity(Observation.State.UNKNOWN, 0L);
        }
    }
}

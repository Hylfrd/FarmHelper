package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.runtime.gamestate.WorldTransition;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorldTransitionTrackerTest {
    @Test
    void firstObservationIsStableAndEveryWorldBoundaryRetainsChangingEvidence() {
        WorldTransitionTracker tracker = new WorldTransitionTracker();
        Observation<WorldSnapshot> first = Observation.present(
                new WorldSnapshot(1L, Observation.unknown()));
        Observation<WorldSnapshot> replacement = Observation.present(
                new WorldSnapshot(2L, Observation.unknown()));

        assertEquals(WorldTransition.STABLE, tracker.observe(first));
        assertEquals(WorldTransition.STABLE, tracker.observe(Observation.present(
                new WorldSnapshot(1L, Observation.present(
                        ResourceIdentifier.parse("minecraft:overworld"))))));
        assertEquals(WorldTransition.STABLE, tracker.observe(first));
        assertEquals(WorldTransition.CHANGING, tracker.observe(replacement));
        assertEquals(WorldTransition.CHANGING, tracker.observe(Observation.absent()));
        assertEquals(WorldTransition.CHANGING, tracker.observe(Observation.present(
                new WorldSnapshot(3L, Observation.unknown()))));
    }
}

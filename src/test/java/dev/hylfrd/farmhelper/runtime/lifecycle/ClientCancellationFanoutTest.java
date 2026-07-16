package dev.hylfrd.farmhelper.runtime.lifecycle;

import dev.hylfrd.farmhelper.control.expectation.ExpectedActionLedger;
import dev.hylfrd.farmhelper.control.expectation.ExpectedMotion;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.navigation.NavigationCancellationReason;
import dev.hylfrd.farmhelper.navigation.NavigationController;
import dev.hylfrd.farmhelper.navigation.NavigationGoal;
import dev.hylfrd.farmhelper.navigation.NavigationOptions;
import dev.hylfrd.farmhelper.navigation.NavigationRequest;
import dev.hylfrd.farmhelper.navigation.NavigationStartObservation;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientCancellationFanoutTest {
    @Test
    void oneFailureCannotPreventAnyLaterOwnerFromReleasing() {
        List<String> attempts = new ArrayList<>();
        ClientCancellationFanout fanout = new ClientCancellationFanout(
                reason -> attempts.add("macro"),
                reason -> {
                    attempts.add("queue");
                    throw new IllegalStateException("queue failed");
                },
                reason -> attempts.add("inventory"),
                reason -> {
                    attempts.add("rotation");
                    throw new AssertionError("rotation failed");
                },
                reason -> attempts.add("input"));

        RuntimeException failure = fanout.cancel(ClientCancellationReason.EXCEPTION).orElseThrow();

        assertEquals(List.of("macro", "queue", "inventory", "rotation", "input"), attempts);
        assertEquals(2, failure.getSuppressed().length);
    }

    @Test
    void earlierFailureCannotSkipNavigationOrExpectationGlobalClearing() {
        ControlOwner owner = new ControlOwner("fanout-navigation");
        NavigationController navigation = new NavigationController();
        navigation.start(
                new NavigationRequest(owner, 1L, new NavigationGoal(0, 70, 0),
                        NavigationOptions.fly()),
                new NavigationStartObservation(
                        Observation.present(new WorldSnapshot(1L, Observation.unknown())),
                        Observation.present(new PlayerSnapshot(
                                Observation.unknown(), Observation.unknown(), Observation.unknown(),
                                Observation.unknown(), Observation.unknown())),
                        Observation.present(ConnectionSnapshot.multiplayer()), Observation.absent()));
        ExpectedActionLedger ledger = new ExpectedActionLedger(() -> 0L);
        ledger.publish(owner, 1L, 1L, 0L, 10L,
                new ExpectedMotion(new MotionSnapshot(1, 0, 0), 0.1D));
        ClientCancellationFanout fanout = new ClientCancellationFanout(
                reason -> { throw new IllegalStateException("earlier participant failed"); },
                reason -> navigation.cancelActive(NavigationCancellationReason.FAILURE),
                reason -> ledger.clearAll());

        RuntimeException failure = fanout.cancel(ClientCancellationReason.EXCEPTION).orElseThrow();

        assertEquals(1, failure.getSuppressed().length);
        assertTrue(navigation.activeTicket().isEmpty());
        assertTrue(ledger.snapshot().actions().isEmpty());
    }
}

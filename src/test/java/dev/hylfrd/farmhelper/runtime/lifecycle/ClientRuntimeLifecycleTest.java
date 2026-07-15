package dev.hylfrd.farmhelper.runtime.lifecycle;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRuntimeLifecycleTest {
    @Test
    void disconnectAndExplicitUnloadHaveTheSameEpochAndCancellationOrder() {
        List<ClientCancellationReason> direct = new ArrayList<>();
        ClientRuntimeLifecycle directLifecycle = new ClientRuntimeLifecycle(direct::add);
        directLifecycle.worldLoaded();
        directLifecycle.disconnected();

        List<ClientCancellationReason> explicit = new ArrayList<>();
        ClientRuntimeLifecycle explicitLifecycle = new ClientRuntimeLifecycle(explicit::add);
        explicitLifecycle.worldLoaded();
        explicitLifecycle.worldUnloaded();
        explicitLifecycle.disconnected();

        assertEquals(List.of(ClientCancellationReason.WORLD_LOAD,
                ClientCancellationReason.WORLD_UNLOAD, ClientCancellationReason.DISCONNECT), direct);
        assertEquals(direct, explicit);
        assertEquals(2L, directLifecycle.worldEpoch());
        assertEquals(2L, explicitLifecycle.worldEpoch());
    }

    @Test
    void sameClassScreenReplacementCancelsByStableIdentity() {
        List<ClientCancellationReason> cancellations = new ArrayList<>();
        ClientRuntimeLifecycle lifecycle = new ClientRuntimeLifecycle(cancellations::add);
        Observation<ScreenSnapshot> first = Observation.present(new ScreenSnapshot(
                1L, Observation.present("container"), Observation.present("Chest")));
        Observation<ScreenSnapshot> replacement = Observation.present(new ScreenSnapshot(
                2L, Observation.present("container"), Observation.present("Chest")));

        lifecycle.observeScreen(first);
        lifecycle.observeScreen(first);
        lifecycle.observeScreen(replacement);

        assertEquals(List.of(ClientCancellationReason.SCREEN_CHANGED), cancellations);
    }

    @Test
    void screenCancellationUsesOnlyObservationStateAndStableIdentity() {
        List<ClientCancellationReason> cancellations = new ArrayList<>();
        ClientRuntimeLifecycle lifecycle = new ClientRuntimeLifecycle(cancellations::add);

        lifecycle.observeScreen(Observation.present(new ScreenSnapshot(
                7L, Observation.present("container"), Observation.present("Chest"))));
        lifecycle.observeScreen(Observation.present(new ScreenSnapshot(
                7L, Observation.present("changed-detail"), Observation.present("Renamed"))));
        assertEquals(List.of(), cancellations);

        lifecycle.observeScreen(Observation.unknown());
        lifecycle.observeScreen(Observation.absent());
        lifecycle.observeScreen(Observation.present(new ScreenSnapshot(
                8L, Observation.unknown(), Observation.unknown())));
        lifecycle.observeScreen(Observation.present(new ScreenSnapshot(
                9L, Observation.unknown(), Observation.unknown())));

        assertEquals(List.of(
                ClientCancellationReason.SCREEN_CHANGED,
                ClientCancellationReason.SCREEN_CHANGED,
                ClientCancellationReason.SCREEN_CHANGED,
                ClientCancellationReason.SCREEN_CHANGED), cancellations);
    }

    @Test
    void expectedScreenCloseConsumesOnlyTheExactPresentToAbsentIdentity() {
        List<ClientCancellationReason> cancellations = new ArrayList<>();
        ClientRuntimeLifecycle lifecycle = new ClientRuntimeLifecycle(cancellations::add);
        Observation<ScreenSnapshot> chat = Observation.present(new ScreenSnapshot(
                17L, Observation.present("chat"), Observation.present("Chat")));

        lifecycle.observeScreen(chat);

        assertFalse(lifecycle.observeExpectedScreenClose(18L, Observation.absent()));
        assertFalse(lifecycle.observeExpectedScreenClose(17L, Observation.unknown()));
        assertTrue(lifecycle.observeExpectedScreenClose(17L, Observation.absent()));
        assertEquals(List.of(), cancellations);

        lifecycle.observeScreen(chat);
        assertEquals(List.of(ClientCancellationReason.SCREEN_CHANGED), cancellations);
    }

    @Test
    void connectionUnknownAndAbsentTransitionsEmitFailClosedBoundaries() {
        List<ClientCancellationReason> cancellations = new ArrayList<>();
        ClientRuntimeLifecycle lifecycle = new ClientRuntimeLifecycle(cancellations::add);

        lifecycle.observeConnection(Observation.unknown());
        lifecycle.observeConnection(Observation.unknown());
        lifecycle.observeConnection(Observation.absent());
        lifecycle.observeConnection(Observation.present("connected"));
        lifecycle.observeConnection(Observation.unknown());

        assertEquals(List.of(
                ClientCancellationReason.CONNECTION_UNAVAILABLE,
                ClientCancellationReason.CONNECTION_UNAVAILABLE,
                ClientCancellationReason.CONNECTION_UNAVAILABLE), cancellations);
    }
}

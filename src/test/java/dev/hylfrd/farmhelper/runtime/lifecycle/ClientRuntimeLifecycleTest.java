package dev.hylfrd.farmhelper.runtime.lifecycle;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}

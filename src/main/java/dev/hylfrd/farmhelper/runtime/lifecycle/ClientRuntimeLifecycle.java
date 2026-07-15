package dev.hylfrd.farmhelper.runtime.lifecycle;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;

import java.util.Objects;
import java.util.function.Consumer;

/** Client-thread lifecycle state with deterministic world epochs and disconnect sequencing. */
public final class ClientRuntimeLifecycle {
    private final Consumer<ClientCancellationReason> cancellation;
    private long worldEpoch;
    private boolean worldPresent;
    private boolean screenObserved;
    private ScreenIdentity screen = ScreenIdentity.unknown();
    private boolean connectionObserved;
    private Observation.State connectionState = Observation.State.UNKNOWN;

    public ClientRuntimeLifecycle(Consumer<ClientCancellationReason> cancellation) {
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    public long worldEpoch() {
        return worldEpoch;
    }

    public void worldLoaded() {
        if (worldPresent) {
            worldUnloaded();
        }
        worldPresent = true;
        advanceWorldEpoch();
        cancellation.accept(ClientCancellationReason.WORLD_LOAD);
    }

    public void worldUnloaded() {
        if (!worldPresent) {
            return;
        }
        worldPresent = false;
        advanceWorldEpoch();
        cancellation.accept(ClientCancellationReason.WORLD_UNLOAD);
    }

    /** Always emits WORLD_UNLOAD before DISCONNECT and advances the epoch at most once. */
    public void disconnected() {
        worldUnloaded();
        cancellation.accept(ClientCancellationReason.DISCONNECT);
    }

    public void clientStopping() {
        cancellation.accept(ClientCancellationReason.CLIENT_STOP);
    }

    public void failed() {
        cancellation.accept(ClientCancellationReason.EXCEPTION);
    }

    public void observeScreen(Observation<ScreenSnapshot> screen) {
        Objects.requireNonNull(screen, "screen");
        ScreenIdentity identity = ScreenIdentity.from(screen);
        if (!screenObserved) {
            screenObserved = true;
            this.screen = identity;
            return;
        }
        if (!this.screen.equals(identity)) {
            this.screen = identity;
            cancellation.accept(ClientCancellationReason.SCREEN_CHANGED);
        }
    }

    /** Emits a boundary when a connection becomes absent/unknown, including first observation. */
    public void observeConnection(Observation<?> connection) {
        Objects.requireNonNull(connection, "connection");
        Observation.State next = connection.state();
        boolean changed = !connectionObserved || connectionState != next;
        connectionObserved = true;
        connectionState = next;
        if (changed && next != Observation.State.PRESENT) {
            cancellation.accept(ClientCancellationReason.CONNECTION_UNAVAILABLE);
        }
    }

    private void advanceWorldEpoch() {
        if (worldEpoch == Long.MAX_VALUE) {
            cancellation.accept(ClientCancellationReason.EXCEPTION);
            throw new IllegalStateException("world epoch exhausted");
        }
        worldEpoch++;
    }

    private record ScreenIdentity(Observation.State state, long identity) {
        private static ScreenIdentity from(Observation<ScreenSnapshot> screen) {
            return screen.isPresent()
                    ? new ScreenIdentity(Observation.State.PRESENT, screen.get().identity())
                    : new ScreenIdentity(screen.state(), 0L);
        }

        private static ScreenIdentity unknown() {
            return new ScreenIdentity(Observation.State.UNKNOWN, 0L);
        }
    }
}

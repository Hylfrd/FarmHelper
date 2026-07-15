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
    private Observation<ScreenSnapshot> screen = Observation.unknown();

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
        if (!screenObserved) {
            screenObserved = true;
            this.screen = screen;
            return;
        }
        if (!this.screen.equals(screen)) {
            this.screen = screen;
            cancellation.accept(ClientCancellationReason.SCREEN_CHANGED);
        }
    }

    private void advanceWorldEpoch() {
        if (worldEpoch == Long.MAX_VALUE) {
            cancellation.accept(ClientCancellationReason.EXCEPTION);
            throw new IllegalStateException("world epoch exhausted");
        }
        worldEpoch++;
    }
}

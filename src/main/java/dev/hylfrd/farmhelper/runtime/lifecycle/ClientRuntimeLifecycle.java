package dev.hylfrd.farmhelper.runtime.lifecycle;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;

import java.util.Objects;
import java.util.function.Consumer;

/** Client-thread lifecycle state with one monotonically increasing world epoch. */
public final class ClientRuntimeLifecycle {
    private final Consumer<ClientCancellationReason> cancellation;
    private long worldEpoch;
    private boolean screenObserved;
    private Observation<String> screenType = Observation.unknown();

    public ClientRuntimeLifecycle(Consumer<ClientCancellationReason> cancellation) {
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
    }

    public long worldEpoch() {
        return worldEpoch;
    }

    public void worldLoaded() {
        advanceWorldEpoch();
        cancellation.accept(ClientCancellationReason.WORLD_LOAD);
    }

    public void worldUnloaded() {
        advanceWorldEpoch();
        cancellation.accept(ClientCancellationReason.WORLD_UNLOAD);
    }

    public void disconnected() {
        advanceWorldEpoch();
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
        Observation<String> nextType = switch (screen.state()) {
            case PRESENT -> screen.get().type();
            case ABSENT -> Observation.absent();
            case UNKNOWN -> Observation.unknown();
        };
        if (!screenObserved) {
            screenObserved = true;
            screenType = nextType;
            return;
        }
        if (!screenType.equals(nextType)) {
            screenType = nextType;
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

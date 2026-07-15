package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Objects;

/** Ordered level, connection, disconnect, and chat-reset boundaries used by the client adapter. */
final class ClientLifecycleSequencer {
    private final FarmHelperClientRuntime runtime;
    private final ClientGameTextSource gameText;
    private final Runnable transientBoundaryInvalidation;
    private Object observedLevel;
    private boolean disconnected;
    private boolean stopping;

    ClientLifecycleSequencer(FarmHelperClientRuntime runtime, ClientGameTextSource gameText) {
        this(runtime, gameText, () -> { });
    }

    ClientLifecycleSequencer(
            FarmHelperClientRuntime runtime,
            ClientGameTextSource gameText,
            Runnable transientBoundaryInvalidation
    ) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.gameText = Objects.requireNonNull(gameText, "gameText");
        this.transientBoundaryInvalidation = Objects.requireNonNull(
                transientBoundaryInvalidation, "transientBoundaryInvalidation");
    }

    void observeLevel(Object current, boolean explicitLoad) {
        if (stopping) {
            return;
        }
        if (explicitLoad && current != null) {
            disconnected = false;
        }
        if (disconnected || current == observedLevel) {
            return;
        }
        transientBoundaryInvalidation.run();
        if (observedLevel != null) {
            runtime.worldUnloaded();
            resetChat();
        }
        observedLevel = current;
        if (current != null) {
            runtime.worldLoaded();
            resetChat();
        }
    }

    void observeConnection(Observation<?> connection) {
        runtime.observeConnection(Objects.requireNonNull(connection, "connection"));
    }

    void disconnect() {
        transientBoundaryInvalidation.run();
        disconnected = true;
        observedLevel = null;
        resetChat();
        runtime.disconnected();
    }

    void clientStopping() {
        transientBoundaryInvalidation.run();
        stopping = true;
        disconnected = true;
        observedLevel = null;
        resetChat();
        runtime.clientStopping();
    }

    void resetChat() {
        gameText.resetChat();
    }
}

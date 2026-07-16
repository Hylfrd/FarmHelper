package dev.hylfrd.farmhelper.client.control;

import dev.hylfrd.farmhelper.control.rotation.RotationSnapshot;
import dev.hylfrd.farmhelper.control.rotation.RotationTerminalReason;
import dev.hylfrd.farmhelper.macro.MacroControlOwner;
import dev.hylfrd.farmhelper.macro.MacroRotationLeaseState;

import java.util.Objects;

/** Client-side bridge from controller callbacks to exact macro request acknowledgements. */
public final class MacroRotationLeaseTracker {
    private long requestToken;
    private MacroRotationLeaseState terminal = MacroRotationLeaseState.idle(0L);

    public synchronized void activated(long token, RotationSnapshot snapshot) {
        requireTrackedToken(token);
        Objects.requireNonNull(snapshot, "snapshot");
        requestToken = token;
        terminal = MacroRotationLeaseState.active(token, snapshot.paused(), snapshot.revision());
    }

    public synchronized void terminated(
            long token,
            RotationTerminalReason reason,
            long controllerRevision
    ) {
        requireTrackedToken(token);
        Objects.requireNonNull(reason, "reason");
        if (requestToken == token) {
            terminal = MacroRotationLeaseState.terminal(token, reason, controllerRevision);
        }
    }

    public synchronized MacroRotationLeaseState observe(RotationSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (requestToken == 0L) {
            return MacroRotationLeaseState.idle(snapshot.revision());
        }
        if (snapshot.active()
                && snapshot.owner().filter(MacroControlOwner.FARMING::equals).isPresent()) {
            return MacroRotationLeaseState.active(
                    requestToken, snapshot.paused(), snapshot.revision());
        }
        return terminal;
    }

    public synchronized boolean owns(long token) {
        return token > 0L && requestToken == token;
    }

    private static void requireTrackedToken(long token) {
        if (token <= 0L) {
            throw new IllegalArgumentException("macro rotation token must be positive");
        }
    }
}

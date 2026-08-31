package dev.hylfrd.farmhelper.feature.reconnect;

import dev.hylfrd.farmhelper.runtime.gamestate.GameStateSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;

import java.util.Objects;

/** One immutable reconnect observation with an explicit monotonic millisecond reading. */
public record ReconnectSnapshot(
        long nowMillis,
        Observation<ConnectionSnapshot> connection,
        Observation<ScreenSnapshot> screen,
        GameStateSnapshot gameState
) {
    public ReconnectSnapshot {
        if (nowMillis < 0L) {
            throw new IllegalArgumentException("nowMillis must be non-negative");
        }
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(screen, "screen");
        Objects.requireNonNull(gameState, "gameState");
    }
}

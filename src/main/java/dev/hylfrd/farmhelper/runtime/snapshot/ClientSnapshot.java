package dev.hylfrd.farmhelper.runtime.snapshot;

import java.util.Objects;

/** Complete immutable client observation for one tick. */
public record ClientSnapshot(
        Observation<PlayerSnapshot> player,
        Observation<WorldSnapshot> world,
        Observation<ConnectionSnapshot> connection,
        Observation<ScreenSnapshot> screen
) {
    public ClientSnapshot {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(screen, "screen");
    }

    public static ClientSnapshot unknown() {
        return new ClientSnapshot(
                Observation.unknown(),
                Observation.unknown(),
                Observation.unknown(),
                Observation.unknown());
    }
}

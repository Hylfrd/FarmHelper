package dev.hylfrd.farmhelper.runtime.snapshot;

import java.util.Objects;

/** A known active connection. Disconnected and unknown states belong to its observation. */
public record ConnectionSnapshot(Mode mode) {
    public enum Mode {
        SINGLEPLAYER,
        MULTIPLAYER
    }

    public ConnectionSnapshot {
        Objects.requireNonNull(mode, "mode");
    }

    public static ConnectionSnapshot singleplayer() {
        return new ConnectionSnapshot(Mode.SINGLEPLAYER);
    }

    public static ConnectionSnapshot multiplayer() {
        return new ConnectionSnapshot(Mode.MULTIPLAYER);
    }
}

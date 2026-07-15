package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;

/** Port implemented by the client adapter which collects bounded text without parsing it. */
@FunctionalInterface
public interface GameTextSnapshotSource {
    RawGameTextSnapshot snapshot(ClientSnapshot clientSnapshot);
}

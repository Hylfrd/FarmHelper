package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.runtime.gamestate.GameTextSnapshotSource;

/** Current text consumer boundary used by Fabric chat callbacks and the ordered tick. */
public interface ClientGameTextSource extends GameTextSnapshotSource {
    void acceptChat(String channel, String text);
}

package dev.hylfrd.farmhelper.feature.reconnect;

/** Semantic server-travel actions used after a connection has been established. */
public interface ReconnectTravelPort {
    boolean enterSkyBlock();

    boolean returnToLobby();

    boolean warpToGarden();
}

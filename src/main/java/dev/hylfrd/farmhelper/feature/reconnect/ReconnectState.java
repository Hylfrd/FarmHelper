package dev.hylfrd.farmhelper.feature.reconnect;

/** Upstream AutoReconnect phases, with NONE renamed to an explicit inactive state. */
public enum ReconnectState {
    STOPPED,
    CONNECTING,
    LOBBY,
    GARDEN
}

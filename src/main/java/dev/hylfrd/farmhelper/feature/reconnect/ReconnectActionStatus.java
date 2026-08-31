package dev.hylfrd.farmhelper.feature.reconnect;

/** Result of requesting the action carried by a reconnect decision. */
public enum ReconnectActionStatus {
    NOT_REQUESTED,
    ACCEPTED,
    REJECTED,
    FAILED
}

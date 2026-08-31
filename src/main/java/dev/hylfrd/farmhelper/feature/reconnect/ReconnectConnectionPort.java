package dev.hylfrd.farmhelper.feature.reconnect;

/** Platform connection actions; the adapter owns the configured server target and disconnect text. */
public interface ReconnectConnectionPort {
    boolean disconnect();

    boolean connect();
}

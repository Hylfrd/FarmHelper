package dev.hylfrd.farmhelper.feature.lag;

/** Result of attempting to record one server time packet. */
public enum LagPacketResult {
    RECORDED,
    NOT_JOINED,
    STALE_IDENTITY,
    CLOCK_REGRESSION
}

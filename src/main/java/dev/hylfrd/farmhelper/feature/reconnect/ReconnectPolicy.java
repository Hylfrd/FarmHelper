package dev.hylfrd.farmhelper.feature.reconnect;

/** Delay policy for a bounded reconnect run. */
public record ReconnectPolicy(
        long connectionSettleDelayMillis,
        long retryDelayMillis,
        long travelDelayMillis,
        long lobbyReturnDelayMillis,
        long timeoutMillis
) {
    public static final long UPSTREAM_CONNECTION_SETTLE_DELAY_MILLIS = 7_500L;
    public static final long UPSTREAM_RETRY_DELAY_MILLIS = 5_000L;
    public static final long UPSTREAM_TRAVEL_DELAY_MILLIS = 5_000L;
    public static final long UPSTREAM_LOBBY_RETURN_DELAY_MILLIS = 60_000L;

    public ReconnectPolicy {
        requireNonNegative(connectionSettleDelayMillis, "connectionSettleDelayMillis");
        requireNonNegative(retryDelayMillis, "retryDelayMillis");
        requireNonNegative(travelDelayMillis, "travelDelayMillis");
        requireNonNegative(lobbyReturnDelayMillis, "lobbyReturnDelayMillis");
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
    }

    /** Preserves the pinned upstream delays while requiring an explicit local timeout. */
    public static ReconnectPolicy upstreamDurations(long timeoutMillis) {
        return new ReconnectPolicy(
                UPSTREAM_CONNECTION_SETTLE_DELAY_MILLIS,
                UPSTREAM_RETRY_DELAY_MILLIS,
                UPSTREAM_TRAVEL_DELAY_MILLIS,
                UPSTREAM_LOBBY_RETURN_DELAY_MILLIS,
                timeoutMillis);
    }

    private static void requireNonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}

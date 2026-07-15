package dev.hylfrd.farmhelper.macro;

import java.util.concurrent.TimeUnit;

/** Minimal P1 time-packet heartbeat; full TPS history remains outside this slice. */
public final class ServerTimeTracker {
    public static final long JOIN_GRACE_NANOS = TimeUnit.SECONDS.toNanos(5L);
    public static final long LAG_THRESHOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(1_300L);

    private long joinedAt = Long.MIN_VALUE;
    private long lastPacketAt = Long.MIN_VALUE;

    public void joined(long nowNanos) {
        requireNonNegative(nowNanos);
        joinedAt = nowNanos;
        lastPacketAt = Long.MIN_VALUE;
    }

    public void receivedTimePacket(long nowNanos) {
        requireNonNegative(nowNanos);
        if (joinedAt == Long.MIN_VALUE) {
            return;
        }
        lastPacketAt = Math.max(lastPacketAt, nowNanos);
    }

    public void reset() {
        joinedAt = Long.MIN_VALUE;
        lastPacketAt = Long.MIN_VALUE;
    }

    public ServerResponsiveness observe(long nowNanos, boolean connectionReady) {
        requireNonNegative(nowNanos);
        if (!connectionReady || joinedAt == Long.MIN_VALUE || nowNanos < joinedAt) {
            return ServerResponsiveness.UNKNOWN;
        }
        if (elapsed(nowNanos, joinedAt) < JOIN_GRACE_NANOS) {
            return ServerResponsiveness.RESPONSIVE;
        }
        long reference = lastPacketAt == Long.MIN_VALUE ? joinedAt : lastPacketAt;
        return elapsed(nowNanos, reference) > LAG_THRESHOLD_NANOS
                ? ServerResponsiveness.LAGGING
                : ServerResponsiveness.RESPONSIVE;
    }

    private static long elapsed(long nowNanos, long thenNanos) {
        try {
            return Math.subtractExact(nowNanos, thenNanos);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static void requireNonNegative(long value) {
        if (value < 0L) {
            throw new IllegalArgumentException("monotonic time must not be negative");
        }
    }
}

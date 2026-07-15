package dev.hylfrd.farmhelper.macro;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerTimeTrackerTest {
    @Test
    void isUnknownBeforeJoinAndResponsiveDuringFiveSecondGrace() {
        ServerTimeTracker tracker = new ServerTimeTracker();
        assertEquals(ServerResponsiveness.UNKNOWN, tracker.observe(0L, true));

        tracker.joined(1L);
        assertEquals(ServerResponsiveness.RESPONSIVE,
                tracker.observe(1L + TimeUnit.MILLISECONDS.toNanos(4_999L), true));
        assertEquals(ServerResponsiveness.UNKNOWN,
                tracker.observe(1L + TimeUnit.SECONDS.toNanos(6L), false));
    }

    @Test
    void becomesLaggingOnlyAfterStrictOnePointThreeSecondThreshold() {
        ServerTimeTracker tracker = new ServerTimeTracker();
        tracker.joined(0L);
        long packet = TimeUnit.SECONDS.toNanos(5L);
        tracker.receivedTimePacket(packet);

        assertEquals(ServerResponsiveness.RESPONSIVE,
                tracker.observe(packet + TimeUnit.MILLISECONDS.toNanos(1_300L), true));
        assertEquals(ServerResponsiveness.LAGGING,
                tracker.observe(packet + TimeUnit.MILLISECONDS.toNanos(1_301L), true));
        tracker.reset();
        assertEquals(ServerResponsiveness.UNKNOWN,
                tracker.observe(packet + TimeUnit.SECONDS.toNanos(10L), true));
    }
}

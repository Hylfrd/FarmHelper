package dev.hylfrd.farmhelper.feature.lag;

import dev.hylfrd.farmhelper.macro.ServerResponsiveness;
import dev.hylfrd.farmhelper.macro.ServerTimeTracker;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LagDetectorTest {
    private static final long WORLD_EPOCH = 42L;
    private static final PositionSnapshot FIRST_POSITION = new PositionSnapshot(1.25d, 70.0d, -4.5d);
    private static final PositionSnapshot SECOND_POSITION = new PositionSnapshot(2.0d, 71.0d, -3.0d);

    @Test
    void prejoinAndUnavailableConnectionEvidenceAreExplicitlyUnknown() {
        Harness harness = harness();

        assertUnavailable(
                harness.detector.observe(ready(WORLD_EPOCH)),
                LagSnapshot.Status.NOT_JOINED);

        harness.detector.joined(WORLD_EPOCH);
        harness.detector.recordTimePacket(packet(WORLD_EPOCH, Observation.present(FIRST_POSITION)));

        assertUnavailable(
                harness.detector.observe(new LagStateEvidence(
                        WORLD_EPOCH, Observation.unknown())),
                LagSnapshot.Status.CONNECTION_UNKNOWN);
        assertUnavailable(
                harness.detector.observe(new LagStateEvidence(
                        WORLD_EPOCH, Observation.absent())),
                LagSnapshot.Status.CONNECTION_UNAVAILABLE);

        LagSnapshot current = harness.detector.observe(ready(WORLD_EPOCH));
        assertEquals(LagSnapshot.Status.CURRENT, current.status());
        assertEquals(Observation.present(FIRST_POSITION), current.lastPacketPosition());

        harness.tracker.reset();
        assertUnavailable(
                harness.detector.observe(ready(WORLD_EPOCH)),
                LagSnapshot.Status.SERVER_UNKNOWN);
    }

    @Test
    void joinGraceAndLagThresholdComeFromTheSharedTracker() {
        Harness harness = harness();
        harness.detector.joined(WORLD_EPOCH);

        harness.clock.advance(ServerTimeTracker.JOIN_GRACE_NANOS - 1L);
        assertEquals(
                ServerResponsiveness.RESPONSIVE,
                harness.detector.observe(ready(WORLD_EPOCH)).responsiveness());

        harness.clock.advance(1L);
        LagSnapshot noPacketAfterGrace = harness.detector.observe(ready(WORLD_EPOCH));
        assertEquals(ServerResponsiveness.LAGGING, noPacketAfterGrace.responsiveness());
        assertTrue(noPacketAfterGrace.recentlyLagging());

        harness.detector.joined(WORLD_EPOCH);
        harness.clock.advance(ServerTimeTracker.JOIN_GRACE_NANOS);
        harness.detector.recordTimePacket(packet(WORLD_EPOCH, Observation.present(FIRST_POSITION)));
        harness.clock.advance(ServerTimeTracker.LAG_THRESHOLD_NANOS);
        assertEquals(
                ServerResponsiveness.RESPONSIVE,
                harness.detector.observe(ready(WORLD_EPOCH)).responsiveness());

        harness.clock.advance(1L);
        LagSnapshot overThreshold = harness.detector.observe(ready(WORLD_EPOCH));
        assertEquals(ServerResponsiveness.LAGGING, overThreshold.responsiveness());
        assertTrue(overThreshold.recentlyLagging());
    }

    @Test
    void recentLagExpiresStrictlyNineHundredMillisecondsAfterLastLagObservation() {
        Harness harness = harness();
        harness.detector.joined(WORLD_EPOCH);
        harness.clock.advance(ServerTimeTracker.JOIN_GRACE_NANOS);
        harness.detector.recordTimePacket(packet(WORLD_EPOCH, Observation.present(FIRST_POSITION)));
        harness.clock.advance(ServerTimeTracker.LAG_THRESHOLD_NANOS + 1L);

        LagSnapshot lagging = harness.detector.observe(ready(WORLD_EPOCH));
        assertEquals(ServerResponsiveness.LAGGING, lagging.responsiveness());
        assertTrue(lagging.recentlyLagging());

        assertEquals(
                LagPacketResult.RECORDED,
                harness.detector.recordTimePacket(
                        packet(WORLD_EPOCH, Observation.present(SECOND_POSITION))));
        LagSnapshot recovered = harness.detector.observe(ready(WORLD_EPOCH));
        assertEquals(ServerResponsiveness.RESPONSIVE, recovered.responsiveness());
        assertTrue(recovered.recentlyLagging());

        harness.clock.advance(LagDetector.RECENT_LAG_NANOS - 1L);
        assertTrue(harness.detector.observe(ready(WORLD_EPOCH)).recentlyLagging());
        harness.clock.advance(1L);
        assertFalse(harness.detector.observe(ready(WORLD_EPOCH)).recentlyLagging());
    }

    @Test
    void newestPacketReplacesPositionWithPresentUnknownOrAbsentEvidence() {
        Harness harness = harness();
        harness.detector.joined(WORLD_EPOCH);

        harness.detector.recordTimePacket(packet(WORLD_EPOCH, Observation.present(FIRST_POSITION)));
        LagSnapshot present = harness.detector.observe(ready(WORLD_EPOCH));
        assertEquals(Observation.present(FIRST_POSITION), present.lastPacketPosition());
        assertEquals(0L, present.lastPacketAgeNanos().orElseThrow());

        harness.clock.advance(1L);
        harness.detector.recordTimePacket(packet(WORLD_EPOCH, Observation.unknown()));
        LagSnapshot unknown = harness.detector.observe(ready(WORLD_EPOCH));
        assertTrue(unknown.lastPacketPosition().isUnknown());
        assertEquals(0L, unknown.lastPacketAgeNanos().orElseThrow());

        harness.clock.advance(1L);
        harness.detector.recordTimePacket(packet(WORLD_EPOCH, Observation.absent()));
        LagSnapshot absent = harness.detector.observe(ready(WORLD_EPOCH));
        assertTrue(absent.lastPacketPosition().isAbsent());
        assertEquals(ServerResponsiveness.RESPONSIVE, absent.responsiveness());
    }

    @Test
    void staleWorldEvidenceIsRedactedAndMutatesNothing() {
        Harness harness = harness();
        harness.detector.joined(WORLD_EPOCH);
        harness.detector.recordTimePacket(packet(WORLD_EPOCH, Observation.present(FIRST_POSITION)));
        harness.clock.advance(TimeUnit.SECONDS.toNanos(1L));

        assertEquals(
                LagPacketResult.STALE_IDENTITY,
                harness.detector.recordTimePacket(
                        packet(WORLD_EPOCH - 1L, Observation.present(SECOND_POSITION))));
        assertUnavailable(
                harness.detector.observe(ready(WORLD_EPOCH - 1L)),
                LagSnapshot.Status.STALE_IDENTITY);

        LagSnapshot current = harness.detector.observe(ready(WORLD_EPOCH));
        assertEquals(Observation.present(FIRST_POSITION), current.lastPacketPosition());
        assertEquals(TimeUnit.SECONDS.toNanos(1L), current.lastPacketAgeNanos().orElseThrow());
        assertTrue(current.tickRate().isEmpty());
    }

    @Test
    void joiningAnotherWorldClearsPositionHistoryAndRecentLagEvidence() {
        Harness harness = harness();
        harness.detector.joined(WORLD_EPOCH);
        harness.clock.advance(ServerTimeTracker.JOIN_GRACE_NANOS);
        harness.detector.recordTimePacket(packet(WORLD_EPOCH, Observation.present(FIRST_POSITION)));
        harness.clock.advance(ServerTimeTracker.LAG_THRESHOLD_NANOS + 1L);
        assertTrue(harness.detector.observe(ready(WORLD_EPOCH)).recentlyLagging());
        harness.detector.recordTimePacket(packet(WORLD_EPOCH, Observation.present(SECOND_POSITION)));

        long nextWorldEpoch = WORLD_EPOCH + 1L;
        harness.detector.joined(nextWorldEpoch);
        LagSnapshot nextWorld = harness.detector.observe(ready(nextWorldEpoch));
        assertEquals(ServerResponsiveness.RESPONSIVE, nextWorld.responsiveness());
        assertFalse(nextWorld.recentlyLagging());
        assertTrue(nextWorld.lastPacketPosition().isUnknown());
        assertTrue(nextWorld.lastPacketAgeNanos().isEmpty());
        assertTrue(nextWorld.tickRate().isEmpty());
        assertEquals(
                LagPacketResult.STALE_IDENTITY,
                harness.detector.recordTimePacket(
                        packet(WORLD_EPOCH, Observation.present(FIRST_POSITION))));
    }

    @Test
    void tpsHistoryOmitsFirstAndDuplicateIntervalsAndKeepsNewestTwentySamples() {
        Harness harness = harness();
        harness.detector.joined(WORLD_EPOCH);
        harness.detector.recordTimePacket(packet(WORLD_EPOCH, Observation.present(FIRST_POSITION)));
        assertTrue(harness.detector.observe(ready(WORLD_EPOCH)).tickRate().isEmpty());

        harness.detector.recordTimePacket(packet(WORLD_EPOCH, Observation.present(SECOND_POSITION)));
        assertTrue(harness.detector.observe(ready(WORLD_EPOCH)).tickRate().isEmpty());

        harness.clock.advance(TimeUnit.SECONDS.toNanos(2L));
        harness.detector.recordTimePacket(packet(WORLD_EPOCH, Observation.present(FIRST_POSITION)));
        assertEquals(
                10.0d,
                harness.detector.observe(ready(WORLD_EPOCH)).tickRate().orElseThrow(),
                0.000_001d);

        for (int sample = 0; sample < LagDetector.TPS_HISTORY_SIZE; sample++) {
            harness.clock.advance(TimeUnit.SECONDS.toNanos(1L));
            harness.detector.recordTimePacket(
                    packet(WORLD_EPOCH, Observation.present(FIRST_POSITION)));
        }
        assertEquals(
                20.0d,
                harness.detector.observe(ready(WORLD_EPOCH)).tickRate().orElseThrow(),
                0.000_001d);

        harness.detector.joined(WORLD_EPOCH);
        harness.detector.recordTimePacket(packet(WORLD_EPOCH, Observation.present(FIRST_POSITION)));
        harness.clock.advance(TimeUnit.MILLISECONDS.toNanos(100L));
        harness.detector.recordTimePacket(packet(WORLD_EPOCH, Observation.present(FIRST_POSITION)));
        assertEquals(
                LagDetector.MAX_TICK_RATE,
                harness.detector.observe(ready(WORLD_EPOCH)).tickRate().orElseThrow(),
                0.000_001d);
    }

    @Test
    void clockRegressionIsExplicitAndDoesNotReplacePacketEvidence() {
        MutableClock clock = new MutableClock(100L);
        ServerTimeTracker tracker = new ServerTimeTracker();
        LagDetector detector = new LagDetector(clock, tracker);
        detector.joined(WORLD_EPOCH);
        clock.set(200L);
        detector.recordTimePacket(packet(WORLD_EPOCH, Observation.present(FIRST_POSITION)));

        clock.set(150L);
        assertEquals(
                LagPacketResult.CLOCK_REGRESSION,
                detector.recordTimePacket(
                        packet(WORLD_EPOCH, Observation.present(SECOND_POSITION))));
        assertUnavailable(
                detector.observe(ready(WORLD_EPOCH)),
                LagSnapshot.Status.CLOCK_REGRESSION);

        clock.set(200L);
        LagSnapshot recovered = detector.observe(ready(WORLD_EPOCH));
        assertEquals(Observation.present(FIRST_POSITION), recovered.lastPacketPosition());
        assertEquals(0L, recovered.lastPacketAgeNanos().orElseThrow());
    }

    @Test
    void resetAndInvalidInputsCannotLeakOrFabricateState() {
        Harness harness = harness();
        harness.detector.joined(WORLD_EPOCH);
        harness.detector.recordTimePacket(packet(WORLD_EPOCH, Observation.present(FIRST_POSITION)));
        harness.detector.reset();
        assertUnavailable(
                harness.detector.observe(ready(WORLD_EPOCH)),
                LagSnapshot.Status.NOT_JOINED);
        assertEquals(ServerResponsiveness.UNKNOWN, harness.tracker.observe(0L, true));

        assertThrows(NullPointerException.class, () -> new LagDetector(null, harness.tracker));
        assertThrows(NullPointerException.class, () -> new LagDetector(harness.clock, null));
        assertThrows(IllegalArgumentException.class, () -> harness.detector.joined(-1L));
        assertThrows(IllegalArgumentException.class,
                () -> new LagPacketEvidence(-1L, Observation.unknown()));
        assertThrows(IllegalArgumentException.class,
                () -> new LagStateEvidence(-1L, Observation.unknown()));
        assertThrows(NullPointerException.class,
                () -> new LagPacketEvidence(WORLD_EPOCH, null));
        assertThrows(NullPointerException.class,
                () -> new LagStateEvidence(WORLD_EPOCH, null));

        MutableClock negativeClock = new MutableClock(-1L);
        LagDetector negative = new LagDetector(negativeClock, new ServerTimeTracker());
        assertThrows(IllegalStateException.class, () -> negative.joined(WORLD_EPOCH));
    }

    private static Harness harness() {
        MutableClock clock = new MutableClock(0L);
        ServerTimeTracker tracker = new ServerTimeTracker();
        return new Harness(clock, tracker, new LagDetector(clock, tracker));
    }

    private static LagPacketEvidence packet(
            long worldEpoch,
            Observation<PositionSnapshot> position
    ) {
        return new LagPacketEvidence(worldEpoch, position);
    }

    private static LagStateEvidence ready(long worldEpoch) {
        return new LagStateEvidence(
                worldEpoch,
                Observation.present(ConnectionSnapshot.multiplayer()));
    }

    private static void assertUnavailable(LagSnapshot snapshot, LagSnapshot.Status status) {
        assertEquals(status, snapshot.status());
        assertEquals(ServerResponsiveness.UNKNOWN, snapshot.responsiveness());
        assertFalse(snapshot.recentlyLagging());
        assertTrue(snapshot.lastPacketPosition().isUnknown());
        assertTrue(snapshot.lastPacketAgeNanos().isEmpty());
        assertTrue(snapshot.tickRate().isEmpty());
    }

    private record Harness(
            MutableClock clock,
            ServerTimeTracker tracker,
            LagDetector detector
    ) {
    }

    private static final class MutableClock implements MonotonicClock {
        private long nowNanos;

        private MutableClock(long nowNanos) {
            this.nowNanos = nowNanos;
        }

        @Override
        public long nowNanos() {
            return nowNanos;
        }

        private void advance(long deltaNanos) {
            if (deltaNanos < 0L) {
                throw new IllegalArgumentException("deltaNanos must not be negative");
            }
            nowNanos = Math.addExact(nowNanos, deltaNanos);
        }

        private void set(long nowNanos) {
            this.nowNanos = nowNanos;
        }
    }
}

package dev.hylfrd.farmhelper.control;

import dev.hylfrd.farmhelper.control.rotation.RotationFrame;
import dev.hylfrd.farmhelper.control.rotation.RotationProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RotationTaskTest {
    private static final float EPSILON = 0.0001F;

    @Test
    void followsShortestYawPathAcrossWrapInBothDirections() {
        RotationTask positiveWrap = new RotationTask(179.0F, 0.0F, -179.0F, 0.0F, 100L);
        RotationTask negativeWrap = new RotationTask(-179.0F, 0.0F, 179.0F, 0.0F, 100L);

        assertEquals(-179.125F, positiveWrap.sample(50_000_000L).yaw(), EPSILON);
        assertEquals(179.125F, negativeWrap.sample(50_000_000L).yaw(), EPSILON);
        assertEquals(-179.0F, positiveWrap.sample(100_000_000L).yaw(), EPSILON);
        assertEquals(179.0F, negativeWrap.sample(100_000_000L).yaw(), EPSILON);
    }

    @Test
    void exactHalfTurnTieAlwaysChoosesNegativeYaw() {
        RotationTask positiveDifferenceTie = new RotationTask(-90.0F, 0.0F, 90.0F, 0.0F, 100L);
        RotationTask negativeDifferenceTie = new RotationTask(90.0F, 0.0F, -90.0F, 0.0F, 100L);

        assertEquals(101.25F, positiveDifferenceTie.sample(50_000_000L).yaw(), EPSILON);
        assertEquals(-78.75F, negativeDifferenceTie.sample(50_000_000L).yaw(), EPSILON);
        assertEquals(-180.0F, RotationTask.normalizeYaw(180.0F));
        assertEquals(-180.0F, RotationTask.normalizeYaw(-180.0F));
    }

    @Test
    void floatUlpsOnBothSidesOfHalfTurnChooseTheTrueShortestDirection() {
        RotationTask positiveBelowTie =
                new RotationTask(-100.0F, 0.0F, Math.nextDown(80.0F), 0.0F, 100L);
        RotationTask positiveAboveTie =
                new RotationTask(-100.0F, 0.0F, Math.nextUp(80.0F), 0.0F, 100L);
        RotationTask negativeBelowTie =
                new RotationTask(100.0F, 0.0F, Math.nextUp(-80.0F), 0.0F, 100L);
        RotationTask negativeAboveTie =
                new RotationTask(100.0F, 0.0F, Math.nextDown(-80.0F), 0.0F, 100L);

        assertEquals(68.75F, positiveBelowTie.sample(50_000_000L).yaw(), EPSILON);
        assertEquals(91.25F, positiveAboveTie.sample(50_000_000L).yaw(), EPSILON);
        assertEquals(-68.75F, negativeBelowTie.sample(50_000_000L).yaw(), EPSILON);
        assertEquals(-91.25F, negativeAboveTie.sample(50_000_000L).yaw(), EPSILON);
    }

    @Test
    void nearHalfTurnKeepsDirectionalPredecessorUntilExactFinalFrame() {
        assertIncompleteAndFinalYaw(
                -100.0F, Math.nextDown(80.0F), Math.nextDown(Math.nextDown(80.0F)));
        assertIncompleteAndFinalYaw(
                -100.0F, Math.nextUp(80.0F), Math.nextUp(Math.nextUp(80.0F)));
        assertIncompleteAndFinalYaw(
                100.0F, Math.nextUp(-80.0F), Math.nextUp(Math.nextUp(-80.0F)));
        assertIncompleteAndFinalYaw(
                100.0F, Math.nextDown(-80.0F), Math.nextDown(Math.nextDown(-80.0F)));
    }

    @Test
    void normalizesOutOfRangeYawAndCanonicalizesZero() {
        assertEquals(-180.0F, RotationTask.normalizeYaw(540.0F));
        assertEquals(-180.0F, RotationTask.normalizeYaw(-540.0F));
        assertEquals(1.0F, RotationTask.normalizeYaw(721.0F));
        assertEquals(0.0F, RotationTask.normalizeYaw(-720.0F));
        assertEquals(
                Float.floatToIntBits(0.0F),
                Float.floatToIntBits(RotationTask.normalizeYaw(-0.0F)));
    }

    @Test
    void clampsStartAndTargetPitchToPhysicalLimits() {
        RotationTask upper = new RotationTask(0.0F, 120.0F, 0.0F, 200.0F, 10L);
        RotationTask lower = new RotationTask(0.0F, -120.0F, 0.0F, -200.0F, 10L);

        assertEquals(90.0F, upper.startPitch());
        assertEquals(90.0F, upper.targetPitch());
        assertEquals(90.0F, upper.sample(5_000_000L).pitch());
        assertEquals(-90.0F, lower.startPitch());
        assertEquals(-90.0F, lower.targetPitch());
        assertEquals(-90.0F, lower.sample(5_000_000L).pitch());
    }

    @Test
    void rejectsEveryNonFiniteInputAndNegativeDuration() {
        assertThrows(IllegalArgumentException.class,
                () -> new RotationTask(Float.NaN, 0.0F, 0.0F, 0.0F, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new RotationTask(0.0F, Float.POSITIVE_INFINITY, 0.0F, 0.0F, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new RotationTask(0.0F, 0.0F, Float.NEGATIVE_INFINITY, 0.0F, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new RotationTask(0.0F, 0.0F, 0.0F, Float.NaN, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new RotationTask(0.0F, 0.0F, 0.0F, 0.0F, -1L));
        assertThrows(IllegalArgumentException.class, () -> RotationTask.normalizeYaw(Float.NaN));
    }

    @Test
    void supportsZeroOneAndNormalDurationsWithoutMinimumCoercion() {
        RotationTask zero = new RotationTask(10.0F, 20.0F, 30.0F, 40.0F, 0L);
        RotationTask one = new RotationTask(10.0F, 20.0F, 30.0F, 40.0F, 1L);
        RotationTask normal = new RotationTask(0.0F, 0.0F, 100.0F, 80.0F, 100L);

        RotationFrame zeroFrame = zero.sample(0L);
        assertTrue(zeroFrame.complete());
        assertEquals(30.0F, zeroFrame.yaw());
        assertEquals(40.0F, zeroFrame.pitch());
        assertEquals(1_000_000L, one.durationNanos());
        assertTrue(one.sample(1_000_000L).complete());
        assertEquals(100_000_000L, normal.durationNanos());
    }

    @Test
    void easeOutQuartHasDeterministicStartMidpointAndEnd() {
        RotationTask task = new RotationTask(0.0F, 0.0F, 100.0F, 80.0F, 100L);

        RotationFrame start = task.sample(0L);
        RotationFrame middle = task.sample(50_000_000L);
        RotationFrame end = task.sample(100_000_000L);

        assertEquals(0.0F, start.yaw(), EPSILON);
        assertEquals(0.0F, start.pitch(), EPSILON);
        assertEquals(0.0F, start.progress(), EPSILON);
        assertEquals(93.75F, middle.yaw(), EPSILON);
        assertEquals(75.0F, middle.pitch(), EPSILON);
        assertEquals(0.5F, middle.progress(), EPSILON);
        assertEquals(100.0F, end.yaw(), EPSILON);
        assertEquals(80.0F, end.pitch(), EPSILON);
        assertTrue(end.complete());
    }

    @Test
    void completionUsesIntegerElapsedTimeAtFloatRoundingBoundaries() {
        RotationTask hundredMilliseconds = new RotationTask(0.0F, 0.0F, 100.0F, 80.0F, 100L);
        RotationTask oneSecond = new RotationTask(0.0F, 0.0F, 100.0F, 80.0F, 1_000L);
        RotationTask maximum = new RotationTask(0.0F, 0.0F, 100.0F, 80.0F, Long.MAX_VALUE);

        RotationFrame hundredMillisecondsEarly = hundredMilliseconds.sample(99_999_999L);
        RotationFrame oneSecondEarly = oneSecond.sample(999_999_971L);
        RotationFrame maximumEarly = maximum.sample(Long.MAX_VALUE - 200_000_000_000L);

        assertIncompleteFiniteProgress(hundredMillisecondsEarly);
        assertIncompleteFiniteProgress(oneSecondEarly);
        assertIncompleteFiniteProgress(maximumEarly);
        assertNotEquals(100.0F, hundredMillisecondsEarly.yaw());
        assertNotEquals(80.0F, hundredMillisecondsEarly.pitch());
        assertNotEquals(100.0F, oneSecondEarly.yaw());
        assertNotEquals(80.0F, oneSecondEarly.pitch());
        assertNotEquals(100.0F, maximumEarly.yaw());
        assertNotEquals(80.0F, maximumEarly.pitch());
        assertEquals(Long.MAX_VALUE, maximum.durationNanos());

        RotationFrame maximumComplete = maximum.sample(Long.MAX_VALUE);
        assertTrue(maximumComplete.complete());
        assertEquals(1.0F, maximumComplete.progress());
        assertEquals(100.0F, maximumComplete.yaw());
        assertEquals(80.0F, maximumComplete.pitch());
    }

    @Test
    void explicitUpstreamProfilesHaveDistinctMidpointsAndExactEnds() {
        RotationTask ordinary = new RotationTask(
                0.0F, 0.0F, 100.0F, 80.0F, 100L,
                RotationProfile.EXPO_QUART, 0.0F);
        RotationTask back = new RotationTask(
                0.0F, 0.0F, 100.0F, 80.0F, 100L,
                RotationProfile.BACK, 0.0F);

        RotationFrame ordinaryMiddle = ordinary.sample(50_000_000L);
        RotationFrame backMiddle = back.sample(50_000_000L);

        assertEquals(96.875F, ordinaryMiddle.yaw(), EPSILON);
        assertEquals(75.0F, ordinaryMiddle.pitch(), EPSILON);
        assertEquals(108.76975F, backMiddle.yaw(), EPSILON);
        assertEquals(87.0158F, backMiddle.pitch(), EPSILON);
        assertEquals(100.0F, back.sample(100_000_000L).yaw(), EPSILON);
        assertEquals(80.0F, back.sample(100_000_000L).pitch(), EPSILON);
    }

    @Test
    void incompleteSamplesStayBitwiseBeforeTargetsAtReviewRoundingBoundary() {
        RotationTask task = new RotationTask(100.0F, 40.0F, 101.0F, 41.0F, 100L);

        RotationFrame atNinetyNineMilliseconds = task.sample(99_000_000L);
        RotationFrame oneNanosecondEarly = task.sample(99_999_999L);

        assertIncompleteFiniteProgress(atNinetyNineMilliseconds);
        assertIncompleteFiniteProgress(oneNanosecondEarly);
        assertSameBits(Math.nextDown(101.0F), atNinetyNineMilliseconds.yaw());
        assertSameBits(Math.nextDown(41.0F), atNinetyNineMilliseconds.pitch());
        assertDifferentBits(101.0F, atNinetyNineMilliseconds.yaw());
        assertDifferentBits(41.0F, atNinetyNineMilliseconds.pitch());
        assertDifferentBits(101.0F, oneNanosecondEarly.yaw());
        assertDifferentBits(41.0F, oneNanosecondEarly.pitch());

        RotationFrame complete = task.sample(100_000_000L);
        assertTrue(complete.complete());
        assertSameBits(101.0F, complete.yaw());
        assertSameBits(41.0F, complete.pitch());
        assertEquals(1.0F, complete.progress());
    }

    @Test
    void incompleteYawUsesDirectionalPredecessorAcrossWrapAndNegativeHalfTurn() {
        assertIncompleteYawBeforeTarget(179.0F, -179.0F, Math.nextDown(-179.0F));
        assertIncompleteYawBeforeTarget(-179.0F, 179.0F, Math.nextUp(179.0F));
        assertIncompleteYawBeforeTarget(
                179.0F,
                -180.0F,
                RotationTask.normalizeYaw(Math.nextDown(-180.0F)));
        assertIncompleteYawBeforeTarget(-179.0F, -180.0F, Math.nextUp(-180.0F));
        assertIncompleteYawBeforeTarget(0.0F, 180.0F, Math.nextUp(-180.0F));
        assertIncompleteYawBeforeTarget(0.0F, -180.0F, Math.nextUp(-180.0F));
    }

    @Test
    void incompleteYawHandlesAdjacentFloatTargetsWithoutCrossingOrJitter() {
        float nextYaw = Math.nextUp(100.0F);
        float beforePositiveBoundary = Math.nextDown(180.0F);

        assertIncompleteYawBeforeTarget(100.0F, nextYaw, 100.0F);
        assertIncompleteYawBeforeTarget(nextYaw, 100.0F, nextYaw);
        assertIncompleteYawBeforeTarget(beforePositiveBoundary, -180.0F, beforePositiveBoundary);
        assertIncompleteYawBeforeTarget(-180.0F, beforePositiveBoundary, -180.0F);

        RotationFrame unchanged = new RotationTask(100.0F, 40.0F, 100.0F, 40.0F, 100L)
                .sample(99_999_999L);
        assertSameBits(100.0F, unchanged.yaw());
        assertSameBits(40.0F, unchanged.pitch());
    }

    @Test
    void incompletePitchUsesDirectionalPredecessorAtAdjacentFloatsAndEndpoints() {
        assertIncompletePitchBeforeTarget(40.0F, 41.0F, Math.nextDown(41.0F));
        assertIncompletePitchBeforeTarget(41.0F, 40.0F, Math.nextUp(40.0F));
        assertIncompletePitchBeforeTarget(80.0F, 90.0F, Math.nextDown(90.0F));
        assertIncompletePitchBeforeTarget(-80.0F, -90.0F, Math.nextUp(-90.0F));

        float nextPitch = Math.nextUp(40.0F);
        assertIncompletePitchBeforeTarget(40.0F, nextPitch, 40.0F);
        assertIncompletePitchBeforeTarget(nextPitch, 40.0F, nextPitch);
    }

    private static void assertIncompleteFiniteProgress(RotationFrame frame) {
        assertFalse(frame.complete());
        assertTrue(Float.isFinite(frame.progress()));
        assertTrue(frame.progress() >= 0.0F);
        assertTrue(frame.progress() < 1.0F);
    }

    private static void assertIncompleteYawBeforeTarget(float start, float target, float expected) {
        RotationTask task = new RotationTask(start, 0.0F, target, 0.0F, 100L);
        RotationFrame frame = task.sample(99_999_999L);

        assertIncompleteFiniteProgress(frame);
        assertSameBits(expected, frame.yaw());
        assertDifferentBits(task.targetYaw(), frame.yaw());
        assertTrue(frame.yaw() >= -180.0F);
        assertTrue(frame.yaw() < 180.0F);
    }

    private static void assertIncompleteAndFinalYaw(float start, float target, float expectedEarly) {
        RotationTask task = new RotationTask(start, 0.0F, target, 0.0F, 100L);
        RotationFrame early = task.sample(99_999_999L);
        RotationFrame complete = task.sample(100_000_000L);

        assertIncompleteFiniteProgress(early);
        assertSameBits(expectedEarly, early.yaw());
        assertDifferentBits(task.targetYaw(), early.yaw());
        assertTrue(complete.complete());
        assertSameBits(task.targetYaw(), complete.yaw());
    }

    private static void assertIncompletePitchBeforeTarget(float start, float target, float expected) {
        RotationTask task = new RotationTask(0.0F, start, 0.0F, target, 100L);
        RotationFrame frame = task.sample(99_999_999L);

        assertIncompleteFiniteProgress(frame);
        assertSameBits(expected, frame.pitch());
        assertDifferentBits(task.targetPitch(), frame.pitch());
        assertTrue(frame.pitch() >= -90.0F);
        assertTrue(frame.pitch() <= 90.0F);
    }

    private static void assertSameBits(float expected, float actual) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }

    private static void assertDifferentBits(float unexpected, float actual) {
        assertNotEquals(Float.floatToRawIntBits(unexpected), Float.floatToRawIntBits(actual));
    }
}

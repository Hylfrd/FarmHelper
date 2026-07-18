package dev.hylfrd.farmhelper.navigation.follow;

import dev.hylfrd.farmhelper.navigation.NavigationGoal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FixedUpstreamPathSmootherTest {
    @Test
    void probesFourHeightsAndFourCornersInFixedUpstreamOrder() {
        List<Ray> rays = new ArrayList<>();
        NavigationGoal start = goal(0.0D, 64.0D, 0.0D);
        NavigationGoal middle = goal(1.0D, 64.0D, 0.0D);
        NavigationGoal end = goal(2.0D, 65.0D, 3.0D);

        List<NavigationGoal> result = FixedUpstreamPathSmoother.smooth(
                List.of(start, middle, end),
                (from, to) -> {
                    rays.add(new Ray(from, to));
                    return FollowRayEvidence.CLEAR;
                });

        assertEquals(List.of(start, end), result);
        assertEquals(16, rays.size());
        int index = 0;
        for (double height : FixedUpstreamPathSmoother.HEIGHT_OFFSETS) {
            for (FixedUpstreamPathSmoother.CornerOffset corner
                    : FixedUpstreamPathSmoother.CORNER_OFFSETS) {
                assertEquals(new Ray(
                        goal(start.x() + corner.x(), start.y() + height,
                                start.z() + corner.z()),
                        goal(end.x() + corner.x(), end.y() + height,
                                end.z() + corner.z())),
                        rays.get(index++));
            }
        }
    }

    @Test
    void blockedAndUnknownEvidenceAreBothConservativeAndShortCircuit() {
        List<NavigationGoal> path =
                List.of(goal(0.0D, 0.0D, 0.0D), goal(1.0D, 0.0D, 0.0D),
                        goal(2.0D, 0.0D, 0.0D));
        for (FollowRayEvidence evidence
                : List.of(FollowRayEvidence.BLOCKED, FollowRayEvidence.UNKNOWN)) {
            AtomicInteger probes = new AtomicInteger();
            List<NavigationGoal> result = FixedUpstreamPathSmoother.smooth(
                    path,
                    (from, to) -> probes.incrementAndGet() == 6
                            ? evidence : FollowRayEvidence.CLEAR);

            assertEquals(path, result);
            assertEquals(6, probes.get());
        }
    }

    @Test
    void continuesScanningLaterCandidatesAfterAnEarlierSkipIsBlocked() {
        List<NavigationGoal> path =
                List.of(goal(0.0D, 0.0D, 0.0D), goal(1.0D, 0.0D, 0.0D),
                        goal(2.0D, 0.0D, 0.0D), goal(3.0D, 0.0D, 0.0D));
        AtomicInteger probes = new AtomicInteger();

        List<NavigationGoal> result = FixedUpstreamPathSmoother.smooth(
                path,
                (from, to) -> probes.incrementAndGet() == 1
                        ? FollowRayEvidence.BLOCKED : FollowRayEvidence.CLEAR);

        assertEquals(List.of(path.getFirst(), path.getLast()), result);
        assertEquals(17, probes.get());
    }

    @Test
    void approvedRepairAlwaysRetainsTwoNodeAndBlockedTailEndpoints() {
        NavigationGoal start = goal(0.0D, 0.0D, 0.0D);
        NavigationGoal middle = goal(1.0D, 0.0D, 0.0D);
        NavigationGoal end = goal(2.0D, 0.0D, 0.0D);
        AtomicInteger twoNodeProbes = new AtomicInteger();

        assertEquals(List.of(start, end), FixedUpstreamPathSmoother.smooth(
                List.of(start, end),
                (from, to) -> {
                    twoNodeProbes.incrementAndGet();
                    return FollowRayEvidence.UNKNOWN;
                }));
        assertEquals(0, twoNodeProbes.get());
        assertEquals(List.of(start, middle, end), FixedUpstreamPathSmoother.smooth(
                List.of(start, middle, end),
                (from, to) -> FollowRayEvidence.BLOCKED));
    }

    @Test
    void emptyAndSingletonPathsAreImmutableValueCopies() {
        assertEquals(List.of(), FixedUpstreamPathSmoother.smooth(
                List.of(), (from, to) -> FollowRayEvidence.CLEAR));
        NavigationGoal only = goal(1.0D, 2.0D, 3.0D);
        assertEquals(List.of(only), FixedUpstreamPathSmoother.smooth(
                List.of(only), (from, to) -> FollowRayEvidence.CLEAR));
    }

    private static NavigationGoal goal(double x, double y, double z) {
        return new NavigationGoal(x, y, z);
    }

    private record Ray(NavigationGoal from, NavigationGoal to) {
    }
}

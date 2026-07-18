package dev.hylfrd.farmhelper.navigation.follow;

import dev.hylfrd.farmhelper.navigation.NavigationGoal;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Fixed-upstream fly-path smoothing geometry with conservative unknown handling.
 * Adjacent search nodes are retained without a ray query; non-adjacent skips require all 16 rays.
 */
public final class FixedUpstreamPathSmoother {
    public static final List<Double> HEIGHT_OFFSETS =
            List.of(0.1D, 0.9D, 1.1D, 1.9D);
    public static final List<CornerOffset> CORNER_OFFSETS = List.of(
            new CornerOffset(0.05D, 0.05D),
            new CornerOffset(0.05D, 0.95D),
            new CornerOffset(0.95D, 0.05D),
            new CornerOffset(0.95D, 0.95D));

    private FixedUpstreamPathSmoother() {
    }

    public static List<NavigationGoal> smooth(
            List<NavigationGoal> path,
            FollowRayProbe probe
    ) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(probe, "probe");
        List<NavigationGoal> source = List.copyOf(path);
        if (source.size() < 2) {
            return source;
        }

        List<NavigationGoal> smoothed = new ArrayList<>();
        smoothed.add(source.getFirst());
        int lowerIndex = 0;
        while (lowerIndex < source.size() - 1) {
            int lastValidIndex = lowerIndex + 1;
            NavigationGoal start = source.get(lowerIndex);
            for (int upperIndex = lowerIndex + 2;
                    upperIndex < source.size();
                    upperIndex++) {
                if (allFixedUpstreamRaysClear(start, source.get(upperIndex), probe)) {
                    lastValidIndex = upperIndex;
                }
            }
            smoothed.add(source.get(lastValidIndex));
            lowerIndex = lastValidIndex;
        }
        return List.copyOf(smoothed);
    }

    private static boolean allFixedUpstreamRaysClear(
            NavigationGoal start,
            NavigationGoal end,
            FollowRayProbe probe
    ) {
        for (double height : HEIGHT_OFFSETS) {
            for (CornerOffset corner : CORNER_OFFSETS) {
                NavigationGoal from = offset(start, corner, height);
                NavigationGoal to = offset(end, corner, height);
                FollowRayEvidence evidence =
                        Objects.requireNonNull(probe.probe(from, to), "ray evidence");
                if (evidence != FollowRayEvidence.CLEAR) {
                    return false;
                }
            }
        }
        return true;
    }

    private static NavigationGoal offset(
            NavigationGoal goal,
            CornerOffset corner,
            double height
    ) {
        return new NavigationGoal(
                goal.x() + corner.x(),
                goal.y() + height,
                goal.z() + corner.z());
    }

    public record CornerOffset(double x, double z) {
        public CornerOffset {
            if (!Double.isFinite(x) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("corner offsets must be finite");
            }
        }
    }
}

package dev.hylfrd.farmhelper.navigation;

import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable logical capture assembled from unchanged bounded {@code SpatialSnapshot} segments.
 *
 * <p>A body/collision query may cross a seam only when its whole AABB is contained by at least
 * one segment. Capture planners therefore overlap adjacent segments with a body-sized halo. The
 * logical union may span 1,500 blocks, while every individual segment remains subject to the
 * existing 256-axis/8,192-cell limits.</p>
 */
public final class SegmentedSpatialSnapshot {
    public static final double MAX_LOGICAL_AXIS_SPAN = 1_500.0D;
    public static final int MAX_SEGMENTS = 32;
    public static final double MAX_SEAM_HALO = 16.0D;
    /** Seven maximum-size segments (57,344 cells) fit; a ninth cannot bypass the total budget. */
    public static final int MAX_AGGREGATE_CELLS = 65_536;

    private final NavigationTicket ticket;
    private final BoxSnapshot bounds;
    private final List<SpatialSegment> segments;
    private final int capturedCellCount;

    public SegmentedSpatialSnapshot(
            NavigationTicket ticket,
            BoxSnapshot bounds,
            List<SpatialSegment> segments
    ) {
        this.ticket = Objects.requireNonNull(ticket, "ticket");
        this.bounds = Objects.requireNonNull(bounds, "bounds");
        Objects.requireNonNull(segments, "segments");
        if (!bounds.hasPositiveVolume()) {
            throw new IllegalArgumentException("logical bounds must have positive volume");
        }
        if (bounds.width() > MAX_LOGICAL_AXIS_SPAN
                || bounds.height() > MAX_LOGICAL_AXIS_SPAN
                || bounds.depth() > MAX_LOGICAL_AXIS_SPAN) {
            throw new IllegalArgumentException("logical bounds exceed the 1,500-block axis limit");
        }
        if (segments.isEmpty() || segments.size() > MAX_SEGMENTS) {
            throw new IllegalArgumentException("segment count must be in [1, " + MAX_SEGMENTS + "]");
        }

        List<SpatialSegment> copy = List.copyOf(segments);
        Set<Long> tokens = new HashSet<>();
        int cells = 0;
        for (int index = 0; index < copy.size(); index++) {
            SpatialSegment segment = Objects.requireNonNull(copy.get(index), "segment");
            if (segment.index() != index) {
                throw new IllegalArgumentException("segment indexes must be contiguous and ordered");
            }
            if (!segment.ticket().equals(ticket)) {
                throw new IllegalArgumentException("segment has a stale navigation ticket");
            }
            if (!tokens.add(segment.requestToken())) {
                throw new IllegalArgumentException("segment request tokens must be unique");
            }
            validateHaloBounds(segment.snapshot().bounds());
            try {
                cells = Math.addExact(cells, segment.capturedCellCount());
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("aggregate captured-cell count overflow", exception);
            }
            validateAggregateCellCount(cells);
        }
        validateOverlaps(copy);
        validateCoverage(copy);
        this.segments = copy;
        capturedCellCount = cells;
    }

    public NavigationTicket ticket() {
        return ticket;
    }

    public BoxSnapshot bounds() {
        return bounds;
    }

    public List<SpatialSegment> segments() {
        return segments;
    }

    public int capturedCellCount() {
        return capturedCellCount;
    }

    public Set<Long> requestTokens() {
        Set<Long> tokens = new HashSet<>();
        for (SpatialSegment segment : segments) {
            tokens.add(segment.requestToken());
        }
        return Set.copyOf(tokens);
    }

    List<SpatialSegment> containing(BoxSnapshot query) {
        Objects.requireNonNull(query, "query");
        List<SpatialSegment> containing = new ArrayList<>();
        for (SpatialSegment segment : segments) {
            if (segment.snapshot().bounds().contains(query)) {
                containing.add(segment);
            }
        }
        return List.copyOf(containing);
    }

    List<SpatialSegment> intersecting(BoxSnapshot query) {
        Objects.requireNonNull(query, "query");
        List<SpatialSegment> intersecting = new ArrayList<>();
        for (SpatialSegment segment : segments) {
            if (segment.snapshot().bounds().intersects(query)) {
                intersecting.add(segment);
            }
        }
        return List.copyOf(intersecting);
    }

    private void validateHaloBounds(BoxSnapshot segment) {
        if (!segment.intersects(bounds)) {
            throw new IllegalArgumentException("segment does not intersect logical bounds");
        }
        if (bounds.minX() - segment.minX() > MAX_SEAM_HALO
                || bounds.minY() - segment.minY() > MAX_SEAM_HALO
                || bounds.minZ() - segment.minZ() > MAX_SEAM_HALO
                || segment.maxX() - bounds.maxX() > MAX_SEAM_HALO
                || segment.maxY() - bounds.maxY() > MAX_SEAM_HALO
                || segment.maxZ() - bounds.maxZ() > MAX_SEAM_HALO) {
            throw new IllegalArgumentException("segment exceeds the bounded seam halo");
        }
    }

    private static void validateOverlaps(List<SpatialSegment> segments) {
        for (int leftIndex = 0; leftIndex < segments.size(); leftIndex++) {
            BoxSnapshot left = segments.get(leftIndex).snapshot().bounds();
            for (int rightIndex = leftIndex + 1; rightIndex < segments.size(); rightIndex++) {
                BoxSnapshot right = segments.get(rightIndex).snapshot().bounds();
                if (!left.intersects(right)) {
                    continue;
                }
                if (left.contains(right) || right.contains(left)) {
                    throw new IllegalArgumentException("nested or duplicate segment overlap is illegal");
                }
                double overlapX = overlap(left.minX(), left.maxX(), right.minX(), right.maxX());
                double overlapY = overlap(left.minY(), left.maxY(), right.minY(), right.maxY());
                double overlapZ = overlap(left.minZ(), left.maxZ(), right.minZ(), right.maxZ());
                boolean boundedSplit = splitHalo(overlapX, left.width(), right.width())
                        || splitHalo(overlapY, left.height(), right.height())
                        || splitHalo(overlapZ, left.depth(), right.depth());
                if (!boundedSplit) {
                    throw new IllegalArgumentException("segment overlap is larger than the seam halo");
                }
            }
        }
    }

    private void validateCoverage(List<SpatialSegment> segments) {
        List<Double> x = cuts(bounds.minX(), bounds.maxX(), segments, Axis.X);
        List<Double> y = cuts(bounds.minY(), bounds.maxY(), segments, Axis.Y);
        List<Double> z = cuts(bounds.minZ(), bounds.maxZ(), segments, Axis.Z);
        for (int xi = 0; xi + 1 < x.size(); xi++) {
            for (int yi = 0; yi + 1 < y.size(); yi++) {
                for (int zi = 0; zi + 1 < z.size(); zi++) {
                    BoxSnapshot cell = new BoxSnapshot(
                            x.get(xi), y.get(yi), z.get(zi),
                            x.get(xi + 1), y.get(yi + 1), z.get(zi + 1));
                    if (cell.hasPositiveVolume() && segments.stream().noneMatch(
                            segment -> segment.snapshot().bounds().contains(cell))) {
                        throw new IllegalArgumentException("segments leave a gap in logical coverage");
                    }
                }
            }
        }
    }

    private static List<Double> cuts(
            double minimum,
            double maximum,
            List<SpatialSegment> segments,
            Axis axis
    ) {
        TreeSet<Double> cuts = new TreeSet<>();
        cuts.add(minimum);
        cuts.add(maximum);
        for (SpatialSegment segment : segments) {
            BoxSnapshot box = segment.snapshot().bounds();
            double min = Math.max(minimum, axis.minimum(box));
            double max = Math.min(maximum, axis.maximum(box));
            if (min > minimum && min < maximum) {
                cuts.add(min);
            }
            if (max > minimum && max < maximum) {
                cuts.add(max);
            }
        }
        return List.copyOf(cuts);
    }

    private static double overlap(double leftMin, double leftMax, double rightMin, double rightMax) {
        return Math.max(0.0D, Math.min(leftMax, rightMax) - Math.max(leftMin, rightMin));
    }

    private static boolean splitHalo(double overlap, double leftSpan, double rightSpan) {
        return overlap > 0.0D && overlap <= MAX_SEAM_HALO
                && overlap < leftSpan && overlap < rightSpan;
    }

    static void validateAggregateCellCount(long count) {
        if (count < 0L || count > MAX_AGGREGATE_CELLS) {
            throw new IllegalArgumentException("aggregate capture exceeds the hard cell limit");
        }
    }

    private enum Axis {
        X {
            @Override double minimum(BoxSnapshot box) { return box.minX(); }
            @Override double maximum(BoxSnapshot box) { return box.maxX(); }
        },
        Y {
            @Override double minimum(BoxSnapshot box) { return box.minY(); }
            @Override double maximum(BoxSnapshot box) { return box.maxY(); }
        },
        Z {
            @Override double minimum(BoxSnapshot box) { return box.minZ(); }
            @Override double maximum(BoxSnapshot box) { return box.maxZ(); }
        };

        abstract double minimum(BoxSnapshot box);
        abstract double maximum(BoxSnapshot box);
    }
}

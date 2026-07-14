package dev.hylfrd.farmhelper.runtime.spatial;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Immutable Garden-only 5x5 plot/Barn geometry. Callers must confirm Garden before using it. */
public final class GardenPlotMap {
    public static final int MIN_BLOCK = -240;
    public static final int MAX_BLOCK_EXCLUSIVE = 240;
    private static final int REGION_SIZE = 96;
    private static final int[][] NUMBERS = {
            {21, 13, 9, 14, 22},
            {15, 5, 1, 6, 16},
            {10, 2, 0, 3, 11},
            {17, 7, 4, 8, 18},
            {23, 19, 12, 20, 24}
    };
    private static final GardenPlotMap STANDARD = createStandard();

    private final List<Area> areas;
    private final Map<Integer, Area> byNumber;

    private GardenPlotMap(List<Area> areas) {
        this.areas = List.copyOf(areas);
        Map<Integer, Area> indexed = new HashMap<>();
        for (Area area : areas) {
            if (indexed.put(area.number(), area) != null) {
                throw new IllegalArgumentException("duplicate Garden area number: " + area.number());
            }
        }
        this.byNumber = Map.copyOf(indexed);
    }

    public static GardenPlotMap standard() {
        return STANDARD;
    }

    public List<Area> areas() {
        return areas;
    }

    public Observation<Area> area(int number) {
        Area area = byNumber.get(number);
        return area == null ? Observation.absent() : Observation.present(area);
    }

    public Observation<Area> areaAt(int blockX, int blockZ) {
        if (blockX < MIN_BLOCK || blockX >= MAX_BLOCK_EXCLUSIVE
                || blockZ < MIN_BLOCK || blockZ >= MAX_BLOCK_EXCLUSIVE) {
            return Observation.absent();
        }
        int column = Math.floorDiv(blockX - MIN_BLOCK, REGION_SIZE);
        int row = Math.floorDiv(blockZ - MIN_BLOCK, REGION_SIZE);
        return area(NUMBERS[row][column]);
    }

    private static GardenPlotMap createStandard() {
        List<Area> areas = new ArrayList<>(25);
        for (int row = 0; row < 5; row++) {
            for (int column = 0; column < 5; column++) {
                int minX = MIN_BLOCK + column * REGION_SIZE;
                int minZ = MIN_BLOCK + row * REGION_SIZE;
                areas.add(new Area(
                        NUMBERS[row][column],
                        minX,
                        minZ,
                        minX + REGION_SIZE,
                        minZ + REGION_SIZE,
                        new Center(minX + REGION_SIZE / 2, minZ + REGION_SIZE / 2)));
            }
        }
        return new GardenPlotMap(areas);
    }

    public record Center(int x, int z) {
    }

    public record Area(
            int number,
            int minX,
            int minZ,
            int maxXExclusive,
            int maxZExclusive,
            Center center
    ) {
        public Area {
            if (number < 0 || number > 24) {
                throw new IllegalArgumentException("Garden area number must be in [0, 24]");
            }
            if (maxXExclusive <= minX || maxZExclusive <= minZ) {
                throw new IllegalArgumentException("Garden area must have positive horizontal size");
            }
            if (center == null) {
                throw new NullPointerException("center");
            }
        }

        public boolean barn() {
            return number == 0;
        }
    }
}

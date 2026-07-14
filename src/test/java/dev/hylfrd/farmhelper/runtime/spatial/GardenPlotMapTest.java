package dev.hylfrd.farmhelper.runtime.spatial;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GardenPlotMapTest {
    private static final int[][] MATRIX = {
            {21, 13, 9, 14, 22},
            {15, 5, 1, 6, 16},
            {10, 2, 0, 3, 11},
            {17, 7, 4, 8, 18},
            {23, 19, 12, 20, 24}
    };

    @Test
    void allTwentyFiveAreasHaveFrozenCornersCentersAndMatrixNumbers() {
        GardenPlotMap plots = GardenPlotMap.standard();
        Set<Integer> observedNumbers = new HashSet<>();

        for (int row = 0; row < 5; row++) {
            for (int column = 0; column < 5; column++) {
                int expected = MATRIX[row][column];
                int minX = -240 + column * 96;
                int minZ = -240 + row * 96;
                GardenPlotMap.Area area = plots.area(expected).get();
                observedNumbers.add(area.number());

                assertEquals(expected, plots.areaAt(minX, minZ).get().number());
                assertEquals(expected, plots.areaAt(minX + 95, minZ).get().number());
                assertEquals(expected, plots.areaAt(minX, minZ + 95).get().number());
                assertEquals(expected, plots.areaAt(minX + 95, minZ + 95).get().number());
                assertEquals(expected, plots.areaAt(area.center().x(), area.center().z()).get().number());
                assertEquals(new GardenPlotMap.Center(-192 + column * 96, -192 + row * 96), area.center());
            }
        }

        assertEquals(25, plots.areas().size());
        assertEquals(25, observedNumbers.size());
        assertTrue(plots.area(0).get().barn());
        assertFalse(plots.area(1).get().barn());
    }

    @Test
    void outerRangeIsHalfOpenAndInternalBoundariesSelectTheNextArea() {
        GardenPlotMap plots = GardenPlotMap.standard();

        assertTrue(plots.areaAt(-241, 0).isAbsent());
        assertTrue(plots.areaAt(240, 0).isAbsent());
        assertTrue(plots.areaAt(0, -241).isAbsent());
        assertTrue(plots.areaAt(0, 240).isAbsent());
        assertEquals(21, plots.areaAt(-240, -240).get().number());
        assertEquals(24, plots.areaAt(239, 239).get().number());
        assertEquals(13, plots.areaAt(-144, -240).get().number());
        assertEquals(15, plots.areaAt(-240, -144).get().number());
    }

    @Test
    void rewarpGeometryKeepsYAndUsesThreeDimensionalDistance() {
        RewarpPosition rewarp = new RewarpPosition(3, 70, -4);

        assertEquals(new BlockPosition(3, 70, -4), rewarp.block());
        assertEquals(13.0D, rewarp.squaredDistance(new BlockPosition(5, 73, -4)));
        assertEquals(Math.sqrt(13.0D), rewarp.distance(new BlockPosition(5, 73, -4)));
        assertTrue(Double.isFinite(new RewarpPosition(Integer.MIN_VALUE, 0, 0)
                .squaredDistance(new BlockPosition(Integer.MAX_VALUE, 0, 0))));
        assertEquals(rewarp, RewarpPosition.nearest(
                java.util.List.of(new RewarpPosition(100, 70, 100), rewarp),
                new BlockPosition(0, 70, 0)).orElseThrow());
    }
}

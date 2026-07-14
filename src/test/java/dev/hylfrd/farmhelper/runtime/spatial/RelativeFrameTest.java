package dev.hylfrd.farmhelper.runtime.spatial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RelativeFrameTest {
    @Test
    void cardinalFramesUseRightUpForwardConvention() {
        assertFrame(0, -1, 0, 0, 1);
        assertFrame(90, 0, -1, -1, 0);
        assertFrame(180, 1, 0, 0, -1);
        assertFrame(270, 0, 1, 1, 0);
    }

    @Test
    void cardinalTiesAdvanceAndYawNormalizes() {
        assertEquals(RelativeFrame.cardinal(90), RelativeFrame.cardinal(45));
        assertEquals(RelativeFrame.cardinal(180), RelativeFrame.cardinal(135));
        assertEquals(RelativeFrame.cardinal(270), RelativeFrame.cardinal(225));
        assertEquals(RelativeFrame.cardinal(0), RelativeFrame.cardinal(315));
        assertEquals(RelativeFrame.cardinal(0), RelativeFrame.cardinal(-45));
        assertEquals(RelativeFrame.cardinal(90), RelativeFrame.cardinal(405));
        assertEquals(359.5D, RelativeFrame.normalizeYaw(-0.5D));
    }

    @Test
    void sugarCaneCanUseIndependentEightWayIntegerFrame() {
        RelativeFrame diagonal = RelativeFrame.eightWay(45);

        assertEquals(-1, diagonal.forwardX());
        assertEquals(1, diagonal.forwardZ());
        assertEquals(-1, diagonal.rightX());
        assertEquals(-1, diagonal.rightZ());
        assertEquals(RelativeFrame.eightWay(0), RelativeFrame.eightWay(22.5));
        assertEquals(RelativeFrame.eightWay(45), RelativeFrame.eightWay(22.50001));
    }

    @Test
    void gridAnchorUsesStrictPointSevenThreshold() {
        assertEquals(10, RelativeFrame.gridAnchorY(10.0));
        assertEquals(10, RelativeFrame.gridAnchorY(10.5));
        assertEquals(10, RelativeFrame.gridAnchorY(10.7));
        assertEquals(11, RelativeFrame.gridAnchorY(10.70001));
        assertEquals(11, RelativeFrame.gridAnchorY(10.9375));
    }

    @Test
    void blockAndChunkCoordinatesUseMathematicalFloor() {
        BlockPosition relative = RelativeFrame.cardinal(0).blockAt(-0.01, -1.3, -16.01, 0, 0, 0);

        assertEquals(new BlockPosition(-1, -2, -17), relative);
        assertEquals(new ChunkPosition(-1, -1), new BlockPosition(-1, 0, -1).chunk());
        assertEquals(new ChunkPosition(-1, -2), new BlockPosition(-16, 0, -17).chunk());
        assertEquals(new ChunkPosition(0, 0), new BlockPosition(15, 0, 15).chunk());
        assertEquals(new ChunkPosition(1, 1), new BlockPosition(16, 0, 16).chunk());
    }

    private static void assertFrame(double yaw, int rightX, int rightZ, int forwardX, int forwardZ) {
        assertEquals(new RelativeFrame(rightX, rightZ, forwardX, forwardZ), RelativeFrame.cardinal(yaw));
    }
}

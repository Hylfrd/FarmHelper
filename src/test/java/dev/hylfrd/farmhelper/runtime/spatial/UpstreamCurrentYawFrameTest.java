package dev.hylfrd.farmhelper.runtime.spatial;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UpstreamCurrentYawFrameTest {
    @Test
    void xAndZComponentsKeepTheAsymmetricThirtyAndSixtyDegreeSeams() {
        RelativeFrame south = new RelativeFrame(-1, 0, 0, 1);
        RelativeFrame southWest = new RelativeFrame(-1, -1, -1, 1);
        RelativeFrame west = new RelativeFrame(0, -1, -1, 0);

        assertEquals(south, UpstreamCurrentYawFrame.from(Math.nextDown(30.0D)));
        assertEquals(southWest, UpstreamCurrentYawFrame.from(30.0D));
        assertEquals(southWest, UpstreamCurrentYawFrame.from(Math.nextUp(30.0D)));
        assertEquals(southWest, UpstreamCurrentYawFrame.from(Math.nextDown(60.0D)));
        assertEquals(west, UpstreamCurrentYawFrame.from(60.0D));
        assertEquals(west, UpstreamCurrentYawFrame.from(Math.nextUp(60.0D)));
    }

    @Test
    void negativeAndWrappedYawUseTheSameFixedUpstreamSector() {
        assertEquals(new RelativeFrame(-1, 0, 0, 1),
                UpstreamCurrentYawFrame.from(-0.5D));
        assertEquals(new RelativeFrame(-1, -1, -1, 1),
                UpstreamCurrentYawFrame.from(390.0D));
    }
}

package dev.hylfrd.farmhelper.macro;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MacroTimingTest {
    @Test
    void scalesUpstreamAngleBandsAndHonorsIndependentFloor() {
        assertEquals(650L, MacroTiming.scaledRotationMillis(1_000L, 50L, 1.0F, 1.0F));
        assertEquals(770L, MacroTiming.scaledRotationMillis(1_000L, 50L, 30.0F, 1.0F));
        assertEquals(900L, MacroTiming.scaledRotationMillis(1_000L, 50L, 60.0F, 1.0F));
        assertEquals(1_000L, MacroTiming.scaledRotationMillis(1_000L, 50L, 90.0F, 1.0F));
        assertEquals(1_100L, MacroTiming.scaledRotationMillis(1_000L, 50L, 110.0F, 1.0F));
        assertEquals(149L, MacroTiming.scaledRotationMillis(1L, 149L, 1.0F, 1.0F));
        assertThrows(IllegalArgumentException.class,
                () -> MacroTiming.scaledRotationMillis(-1L, 50L, 1.0F, 1.0F));
    }
}

package dev.hylfrd.farmhelper.macro;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroModeTest {
    @Test
    void allFourteenCodesMapToExplicitModesAndTheFixedEightFamilies() {
        List<MacroMode> expectedModes = List.of(
                MacroMode.VERTICAL_NORMAL,
                MacroMode.VERTICAL_PUMPKIN_MELON,
                MacroMode.VERTICAL_MELONGKINGDE,
                MacroMode.MELON_PUMPKIN_DEFAULT,
                MacroMode.SUGAR_CANE,
                MacroMode.VERTICAL_CACTUS,
                MacroMode.VERTICAL_SUNTZU,
                MacroMode.COCOA,
                MacroMode.COCOA_TRAPDOORS,
                MacroMode.VERTICAL_COCOA_LEFT_RIGHT,
                MacroMode.MUSHROOM,
                MacroMode.MUSHROOM_ROTATE,
                MacroMode.MUSHROOM_SDS,
                MacroMode.CIRCULAR);
        List<MacroFamily> expectedFamilies = List.of(
                MacroFamily.VERTICAL_S_SHAPE,
                MacroFamily.VERTICAL_S_SHAPE,
                MacroFamily.VERTICAL_S_SHAPE,
                MacroFamily.MELON_PUMPKIN_DEFAULT,
                MacroFamily.SUGAR_CANE,
                MacroFamily.VERTICAL_S_SHAPE,
                MacroFamily.VERTICAL_S_SHAPE,
                MacroFamily.COCOA,
                MacroFamily.COCOA,
                MacroFamily.VERTICAL_S_SHAPE,
                MacroFamily.MUSHROOM,
                MacroFamily.MUSHROOM_ROTATE,
                MacroFamily.MUSHROOM_SDS,
                MacroFamily.CIRCULAR);

        for (int code = 0; code < expectedModes.size(); code++) {
            MacroMode mode = MacroMode.fromCode(code).orElseThrow();
            assertEquals(expectedModes.get(code), mode);
            assertEquals(code, mode.code());
            assertEquals(expectedFamilies.get(code), mode.family());
        }
        assertEquals(14, MacroMode.values().length);
        assertEquals(8, java.util.Arrays.stream(MacroMode.values())
                .map(MacroMode::family).distinct().count());
        assertTrue(MacroMode.fromCode(-1).isEmpty());
        assertTrue(MacroMode.fromCode(14).isEmpty());
    }
}

package dev.hylfrd.farmhelper.macro;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MacroModeTest {
    @Test
    void allFourteenCodesMapToTheFixedEightFamilies() {
        List<MacroFamily> expected = List.of(
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

        for (int code = 0; code < expected.size(); code++) {
            MacroMode mode = MacroMode.fromCode(code).orElseThrow();
            assertEquals(code, mode.code());
            assertEquals(expected.get(code), mode.family());
        }
        assertEquals(14, MacroMode.values().length);
        assertEquals(8, java.util.Arrays.stream(MacroMode.values())
                .map(MacroMode::family).distinct().count());
        assertTrue(MacroMode.fromCode(-1).isEmpty());
        assertTrue(MacroMode.fromCode(14).isEmpty());
    }
}

package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.runtime.spatial.RewarpPosition;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MacroSettingsTest {
    @Test
    void replacePrevalidatesEveryLateFieldBeforeMutatingAnything() {
        MacroSettings settings = populated();
        View before = View.of(settings);

        assertThrows(IllegalArgumentException.class, () -> settings.replace(
                MacroMode.MELON_PUMPKIN_DEFAULT, Optional.empty(), List.of(),
                false, false, false, false, false,
                true, 90.01F, true, 45.0F));
        assertEquals(before, View.of(settings));

        assertThrows(IllegalArgumentException.class, () -> settings.replace(
                MacroMode.MELON_PUMPKIN_DEFAULT, Optional.empty(), List.of(),
                false, false, false, false, false,
                true, -45.0F, true, Float.NaN));
        assertEquals(before, View.of(settings));
    }

    @Test
    void snapshotIsIndependentAndRejectsDirectMutation() {
        MacroSettings source = populated();
        MacroSettings snapshot = source.snapshot();
        source.mode(VerticalCropMode.COCOA);
        source.customPitchLevel(12.0F);

        assertEquals(VerticalCropMode.PUMPKIN_MELON, snapshot.mode());
        assertEquals(51.0F, snapshot.customPitchLevel());
        assertThrows(IllegalStateException.class,
                () -> snapshot.macroMode(MacroMode.VERTICAL_NORMAL));
    }

    private static MacroSettings populated() {
        MacroSettings settings = new MacroSettings();
        settings.replace(
                MacroMode.VERTICAL_PUMPKIN_MELON,
                Optional.of(new MacroSpawnPose(
                        new RewarpPosition(100, 70, 100), 20.0F, 5.0F, 3)),
                List.of(new RewarpPosition(90, 70, 90)),
                true, false, true, true, true,
                true, 51.0F, true, -35.0F);
        return settings;
    }

    private record View(
            VerticalCropMode mode,
            MacroMode macroMode,
            Optional<MacroSpawnPose> spawn,
            List<RewarpPosition> rewarps,
            boolean alwaysHoldW,
            boolean holdLeftClick,
            boolean rotateAfterWarped,
            boolean rotateAfterDrop,
            boolean dontFix,
            boolean customPitch,
            float customPitchLevel,
            boolean customYaw,
            float customYawLevel
    ) {
        private static View of(MacroSettings settings) {
            return new View(
                    settings.mode(), settings.macroMode(), settings.spawn(), settings.rewarps(),
                    settings.alwaysHoldW(), settings.holdLeftClickWhenChangingRow(),
                    settings.rotateAfterWarped(), settings.rotateAfterDrop(),
                    settings.dontFixAfterWarping(), settings.customPitch(),
                    settings.customPitchLevel(), settings.customYaw(), settings.customYawLevel());
        }
    }
}

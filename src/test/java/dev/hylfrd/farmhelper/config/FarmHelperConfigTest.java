package dev.hylfrd.farmhelper.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmHelperConfigTest {
    @Test
    void normalizesAndClampsRotationTargets() {
        FarmHelperConfig config = new FarmHelperConfig();

        config.setTargetYaw(540.0F);
        config.setTargetPitch(120.0F);

        assertEquals(-180.0F, config.targetYaw());
        assertEquals(90.0F, config.targetPitch());
    }

    @Test
    void resetRestoresDefaults() {
        FarmHelperConfig config = new FarmHelperConfig();
        config.setTargetYaw(45.0F);
        config.setTargetPitch(-30.0F);
        config.setOpenSettingsKey(65);
        config.setAlwaysHoldW(true);
        config.setHoldLeftClickWhenChangingRow(false);
        config.setRotateAfterWarped(true);
        config.setRotateAfterDrop(true);
        config.setDontFixAfterWarping(true);
        config.setCustomPitch(true);
        config.setCustomPitchLevel(50.0F);
        config.setCustomYaw(true);
        config.setCustomYawLevel(90.0F);
        config.setCheckDesync(false);
        config.setDesyncPauseDelayMillis(9_000);

        config.reset();

        assertEquals(0.0F, config.targetYaw());
        assertEquals(0.0F, config.targetPitch());
        assertEquals(FarmHelperConfig.DEFAULT_OPEN_SETTINGS_KEY, config.openSettingsKey());
        assertFalse(config.alwaysHoldW());
        assertTrue(config.holdLeftClickWhenChangingRow());
        assertFalse(config.rotateAfterWarped());
        assertFalse(config.rotateAfterDrop());
        assertFalse(config.dontFixAfterWarping());
        assertFalse(config.customPitch());
        assertEquals(0.0F, config.customPitchLevel());
        assertFalse(config.customYaw());
        assertEquals(0.0F, config.customYawLevel());
        assertTrue(config.checkDesync());
        assertEquals(FarmHelperConfig.DEFAULT_DESYNC_PAUSE_DELAY_MILLIS,
                config.desyncPauseDelayMillis());
    }

    @Test
    void copyPreservesIndependentMacroInputConfiguration() {
        FarmHelperConfig config = new FarmHelperConfig();
        config.setAlwaysHoldW(true);
        config.setHoldLeftClickWhenChangingRow(false);
        config.setCheckDesync(false);
        config.setDesyncPauseDelayMillis(7_500);

        FarmHelperConfig copy = config.copy();
        config.setAlwaysHoldW(false);
        config.setHoldLeftClickWhenChangingRow(true);
        config.setCheckDesync(true);
        config.setDesyncPauseDelayMillis(5_000);

        assertTrue(copy.alwaysHoldW());
        assertFalse(copy.holdLeftClickWhenChangingRow());
        assertFalse(copy.checkDesync());
        assertEquals(7_500, copy.desyncPauseDelayMillis());
        assertFalse(config.alwaysHoldW());
        assertTrue(config.holdLeftClickWhenChangingRow());
        assertTrue(config.checkDesync());
        assertEquals(5_000, config.desyncPauseDelayMillis());
    }

    @Test
    void individualConfigKeyResetDoesNotChangeOtherValues() {
        FarmHelperConfig config = new FarmHelperConfig();
        config.setTargetYaw(45.0F);
        config.setTargetPitch(30.0F);

        FarmHelperConfigKey.TARGET_YAW.reset(config);

        assertEquals(0.0F, config.targetYaw());
        assertEquals(30.0F, config.targetPitch());
    }

    @Test
    void rejectsNonFiniteRuntimeValues() {
        FarmHelperConfig config = new FarmHelperConfig();

        assertThrows(IllegalArgumentException.class, () -> config.setTargetYaw(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> config.setTargetPitch(Float.POSITIVE_INFINITY));
        assertEquals(0.0F, config.targetYaw());
        assertEquals(0.0F, config.targetPitch());
    }

    @Test
    void validatesSettingsKeyCodesWithoutChangingThePreviousValue() {
        FarmHelperConfig config = new FarmHelperConfig();
        config.setOpenSettingsKey(65);

        assertThrows(IllegalArgumentException.class, () -> config.setOpenSettingsKey(999));

        assertEquals(65, config.openSettingsKey());
    }

    @Test
    void customMechanismAnglesAcceptEndpointsAndRejectInvalidValuesAtomically() {
        FarmHelperConfig config = new FarmHelperConfig();
        config.setCustomPitchLevel(-90.0F);
        config.setCustomYawLevel(180.0F);

        assertThrows(IllegalArgumentException.class,
                () -> config.setCustomPitchLevel(Math.nextDown(-90.0F)));
        assertThrows(IllegalArgumentException.class,
                () -> config.setCustomPitchLevel(Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> config.setCustomYawLevel(Math.nextUp(180.0F)));
        assertThrows(IllegalArgumentException.class,
                () -> config.setCustomYawLevel(Float.POSITIVE_INFINITY));

        assertEquals(-90.0F, config.customPitchLevel());
        assertEquals(180.0F, config.customYawLevel());
    }

    @Test
    void desyncDelayAcceptsExactEndpointsAndRejectsOutOfRangeAtomically() {
        FarmHelperConfig config = new FarmHelperConfig();

        config.setDesyncPauseDelayMillis(FarmHelperConfig.MIN_DESYNC_PAUSE_DELAY_MILLIS);
        assertEquals(FarmHelperConfig.MIN_DESYNC_PAUSE_DELAY_MILLIS,
                config.desyncPauseDelayMillis());
        config.setDesyncPauseDelayMillis(FarmHelperConfig.MAX_DESYNC_PAUSE_DELAY_MILLIS);

        assertThrows(IllegalArgumentException.class,
                () -> config.setDesyncPauseDelayMillis(
                        FarmHelperConfig.MIN_DESYNC_PAUSE_DELAY_MILLIS - 1));
        assertThrows(IllegalArgumentException.class,
                () -> config.setDesyncPauseDelayMillis(
                        FarmHelperConfig.MAX_DESYNC_PAUSE_DELAY_MILLIS + 1));
        assertEquals(FarmHelperConfig.MAX_DESYNC_PAUSE_DELAY_MILLIS,
                config.desyncPauseDelayMillis());
    }

    @Test
    void replaceWithCopiesDesyncConfiguration() {
        FarmHelperConfig target = new FarmHelperConfig();
        FarmHelperConfig replacement = new FarmHelperConfig();
        replacement.setCheckDesync(false);
        replacement.setDesyncPauseDelayMillis(8_000);

        target.replaceWith(replacement);
        replacement.setCheckDesync(true);
        replacement.setDesyncPauseDelayMillis(5_000);

        assertFalse(target.checkDesync());
        assertEquals(8_000, target.desyncPauseDelayMillis());
    }
}

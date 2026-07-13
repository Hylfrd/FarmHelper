package dev.hylfrd.farmhelper.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        config.reset();

        assertEquals(0.0F, config.targetYaw());
        assertEquals(0.0F, config.targetPitch());
        assertEquals(FarmHelperConfig.DEFAULT_OPEN_SETTINGS_KEY, config.openSettingsKey());
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
}

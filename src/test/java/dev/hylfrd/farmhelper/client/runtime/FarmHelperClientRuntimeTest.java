package dev.hylfrd.farmhelper.client.runtime;

import dev.hylfrd.farmhelper.config.FarmHelperConfigKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmHelperClientRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void configCommandOperationsPersistAndResetOnlyTheRequestedKey() {
        Path configPath = temporaryDirectory.resolve("farmhelper.json");
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(configPath);

        assertTrue(runtime.setConfig(FarmHelperConfigKey.TARGET_YAW, 45.0F));
        assertTrue(runtime.setConfig(FarmHelperConfigKey.TARGET_PITCH, 30.0F));
        assertTrue(runtime.resetConfig(FarmHelperConfigKey.TARGET_YAW));

        FarmHelperClientRuntime reloaded = new FarmHelperClientRuntime(configPath);
        assertEquals(0.0F, reloaded.configValue(FarmHelperConfigKey.TARGET_YAW));
        assertEquals(30.0F, reloaded.configValue(FarmHelperConfigKey.TARGET_PITCH));
    }

    @Test
    void rejectedConfigUpdateRestoresThePreviousValue() {
        Path configPath = temporaryDirectory.resolve("farmhelper.json");
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(configPath);
        assertTrue(runtime.setConfig(FarmHelperConfigKey.TARGET_YAW, 60.0F));

        assertFalse(runtime.setConfig(FarmHelperConfigKey.TARGET_YAW, Float.NaN));

        assertEquals(60.0F, runtime.configValue(FarmHelperConfigKey.TARGET_YAW));
        FarmHelperClientRuntime reloaded = new FarmHelperClientRuntime(configPath);
        assertEquals(60.0F, reloaded.configValue(FarmHelperConfigKey.TARGET_YAW));
    }
}

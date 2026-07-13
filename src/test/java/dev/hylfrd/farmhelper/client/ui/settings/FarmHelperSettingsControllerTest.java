package dev.hylfrd.farmhelper.client.ui.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmHelperSettingsControllerTest {
    @Test
    void liveConfigKeyChangesRequireRegisteredMappingSynchronization() {
        assertFalse(FarmHelperSettingsController.needsKeyMappingUpdate(344, 344));
        assertTrue(FarmHelperSettingsController.needsKeyMappingUpdate(65, 344));
        assertTrue(FarmHelperSettingsController.needsKeyMappingUpdate(344, -1));
    }
}

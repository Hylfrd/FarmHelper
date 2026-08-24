package dev.hylfrd.farmhelper.client.ui.settings;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmHelperSettingsControllerTest {
    @Test
    void liveConfigKeyChangesRequireRegisteredMappingSynchronization() {
        assertFalse(FarmHelperSettingsController.needsKeyMappingUpdate(344, 344));
        assertTrue(FarmHelperSettingsController.needsKeyMappingUpdate(65, 344));
        assertTrue(FarmHelperSettingsController.needsKeyMappingUpdate(344, -1));
    }

    @Test
    void realClientBoundaryReproducesRightShiftScancodeMissAndFallbackOpensOnce() {
        assertEquals(340, InputConstants.KEY_LSHIFT);
        assertEquals(344, InputConstants.KEY_RSHIFT);
        assertNotEquals(InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT);

        KeyMapping mapping = newMapping(InputConstants.KEY_RSHIFT, "right-shift");
        KeyEvent computerUseRightShift = new KeyEvent(-1, 54, 0);
        InputConstants.Key eventKey = InputConstants.getKey(computerUseRightShift);

        assertEquals(InputConstants.Type.SCANCODE, eventKey.getType());
        assertEquals(54, eventKey.getValue());
        assertFalse(mapping.matches(computerUseRightShift));
        KeyMapping.click(eventKey);
        assertFalse(mapping.consumeClick());

        FarmHelperSettingsController.KeyPressState state = new FarmHelperSettingsController.KeyPressState();
        int openedScreens = 0;
        boolean screenOpen = false;

        if (FarmHelperSettingsController.shouldOpenFromKey(
                FarmHelperSettingsController.consumeClicks(mapping), state.observe(true), screenOpen)) {
            openedScreens++;
            screenOpen = true;
        }
        if (FarmHelperSettingsController.shouldOpenFromKey(
                FarmHelperSettingsController.consumeClicks(mapping), state.observe(true), screenOpen)) {
            openedScreens++;
        }

        assertEquals(1, openedScreens);
    }

    @Test
    void vanillaKeysymQueueAndPhysicalStateShareOneRisingEdgeAndScreenGuard() {
        KeyMapping mapping = newMapping(InputConstants.KEY_RSHIFT, "keysym");
        KeyEvent rightShiftKeysym = new KeyEvent(InputConstants.KEY_RSHIFT, 54, 0);
        InputConstants.Key eventKey = InputConstants.getKey(rightShiftKeysym);

        assertEquals(InputConstants.Type.KEYSYM, eventKey.getType());
        assertEquals(InputConstants.KEY_RSHIFT, eventKey.getValue());
        assertTrue(mapping.matches(rightShiftKeysym));
        KeyMapping.click(eventKey);
        KeyMapping.click(eventKey);

        assertTrue(FarmHelperSettingsController.consumeClicks(mapping));
        assertFalse(FarmHelperSettingsController.consumeClicks(mapping));
        assertTrue(FarmHelperSettingsController.shouldOpenFromKey(true, false, false));
        assertFalse(FarmHelperSettingsController.shouldOpenFromKey(true, true, true));

        FarmHelperSettingsController.KeyPressState state = new FarmHelperSettingsController.KeyPressState();
        assertTrue(state.observe(true));
        assertFalse(state.observe(true));
        assertFalse(state.observe(true));
        assertFalse(state.observe(false));
        assertTrue(state.observe(true));
    }

    @Test
    void unrelatedKeyDoesNotReachMappingOrPhysicalFallbackAndRemapStillUsesKeysym() {
        KeyMapping mapping = newMapping(InputConstants.KEY_RSHIFT, "remap");
        KeyEvent leftShift = new KeyEvent(InputConstants.KEY_LSHIFT, 42, 0);
        KeyEvent unrelated = new KeyEvent(InputConstants.KEY_A, 30, 0);

        assertFalse(mapping.matches(leftShift));
        assertFalse(mapping.matches(unrelated));
        KeyMapping.click(InputConstants.getKey(leftShift));
        KeyMapping.click(InputConstants.getKey(unrelated));
        assertFalse(FarmHelperSettingsController.consumeClicks(mapping));

        mapping.setKey(InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_A));
        KeyMapping.resetMapping();
        assertTrue(mapping.matches(unrelated));
        KeyMapping.click(InputConstants.getKey(unrelated));
        assertTrue(FarmHelperSettingsController.consumeClicks(mapping));
        assertFalse(FarmHelperSettingsController.shouldOpenFromKey(false, false, false));
    }

    private static KeyMapping newMapping(int key, String suffix) {
        return new KeyMapping(
                "farmhelper.audit012." + suffix + "." + System.nanoTime(),
                InputConstants.Type.KEYSYM,
                key,
                KeyMapping.Category.MISC);
    }
}

package dev.hylfrd.farmhelper.client.ui.settings;

import com.mojang.blaze3d.platform.InputConstants;
import dev.hylfrd.farmhelper.config.FarmHelperConfig;
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
    void rightShiftEventIdentityIsUnambiguousAtVanillaBoundary() {
        assertEquals(340, InputConstants.KEY_LSHIFT);
        assertEquals(344, InputConstants.KEY_RSHIFT);
        assertNotEquals(InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT);
        assertEquals(54, rightShiftScancode());
        assertEquals(42, leftShiftScancode());

        KeyMapping mapping = newMapping(InputConstants.KEY_RSHIFT, "right-shift");
        KeyEvent keysymEvent = new KeyEvent(InputConstants.KEY_RSHIFT, rightShiftScancode(), 0);
        InputConstants.Key keysym = InputConstants.getKey(keysymEvent);

        assertEquals(InputConstants.Type.KEYSYM, keysym.getType());
        assertEquals(InputConstants.KEY_RSHIFT, keysym.getValue());
        assertTrue(mapping.matches(keysymEvent));
        KeyMapping.click(keysym);
        assertTrue(mapping.consumeClick());
        assertFalse(mapping.consumeClick());

        KeyEvent scancodeEvent = new KeyEvent(-1, rightShiftScancode(), 0);
        InputConstants.Key scancode = InputConstants.getKey(scancodeEvent);

        assertEquals(InputConstants.Type.SCANCODE, scancode.getType());
        assertEquals(rightShiftScancode(), scancode.getValue());
        assertFalse(mapping.matches(scancodeEvent));
        KeyMapping.click(scancode);
        assertFalse(mapping.consumeClick());

        KeyMapping scancodeMapping = newMapping(
                InputConstants.Type.SCANCODE, rightShiftScancode(), "right-shift-scancode");
        KeyEvent leftScancodeEvent = new KeyEvent(-1, leftShiftScancode(), 0);
        assertTrue(scancodeMapping.matches(scancodeEvent));
        assertFalse(scancodeMapping.matches(leftScancodeEvent));
        KeyMapping.click(scancode);
        assertTrue(scancodeMapping.consumeClick());
    }

    @Test
    void controllerTickSeamConsumesOnePressAndSuppressesHeldRepeat() {
        KeyMapping mapping = newMapping(InputConstants.KEY_RSHIFT, "tick");
        InputConstants.Key key = InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_RSHIFT);
        ControllerTriggerSeam seam = new ControllerTriggerSeam(false);

        assertFalse(seam.observeHeldState(false));
        KeyMapping.set(key, true);
        assertTrue(mapping.isDown());
        assertEquals(0, seam.tick(mapping));

        assertTrue(seam.observeHeldState(true));
        assertFalse(seam.observeHeldState(true));
        assertFalse(seam.observeHeldState(false));
        assertTrue(seam.observeHeldState(true));

        KeyMapping.click(key);
        KeyMapping.click(key);
        assertEquals(1, seam.tick(mapping));
        assertEquals(1, seam.openedScreens());
        assertFalse(mapping.consumeClick());
        assertEquals(0, seam.tick(mapping));

        KeyMapping.set(key, false);
        assertFalse(mapping.isDown());
    }

    @Test
    void existingScreenAndUnrelatedKeyRemainOutsideTheSettingsTrigger() {
        KeyMapping mapping = newMapping(InputConstants.KEY_RSHIFT, "guards");
        ControllerTriggerSeam existingScreen = new ControllerTriggerSeam(true);
        InputConstants.Key rightShift = InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_RSHIFT);
        InputConstants.Key unrelated = InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_A);

        KeyMapping.click(rightShift);
        KeyMapping.click(unrelated);
        assertEquals(0, existingScreen.tick(mapping));
        assertEquals(0, existingScreen.openedScreens());
        assertFalse(mapping.consumeClick());
    }

    @Test
    void remappedF6UsesStandardKeysymMappingAndOpensOnce() {
        assertTrue(FarmHelperSettingsController.needsKeyMappingUpdate(
                FarmHelperConfig.DEFAULT_OPEN_SETTINGS_KEY, InputConstants.KEY_F6));

        KeyMapping mapping = newMapping(InputConstants.KEY_RSHIFT, "f6");
        mapping.setKey(InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_F6));
        KeyMapping.resetMapping();

        try {
            KeyEvent f6Event = new KeyEvent(InputConstants.KEY_F6, 0, 0);
            InputConstants.Key f6 = InputConstants.getKey(f6Event);
            ControllerTriggerSeam seam = new ControllerTriggerSeam(false);

            assertEquals(InputConstants.Type.KEYSYM, f6.getType());
            assertEquals(InputConstants.KEY_F6, f6.getValue());
            assertTrue(mapping.matches(f6Event));
            KeyMapping.click(f6);
            assertEquals(1, seam.tick(mapping));
            assertFalse(mapping.consumeClick());
            assertEquals(1, seam.openedScreens());

            KeyEvent rightShiftEvent = new KeyEvent(InputConstants.KEY_RSHIFT, rightShiftScancode(), 0);
            assertFalse(mapping.matches(rightShiftEvent));
            KeyMapping.click(InputConstants.getKey(rightShiftEvent));
            assertEquals(0, seam.tick(mapping));
            assertFalse(mapping.consumeClick());
        } finally {
            mapping.setKey(InputConstants.Type.KEYSYM.getOrCreate(InputConstants.KEY_RSHIFT));
            KeyMapping.resetMapping();
        }
    }

    private static int rightShiftScancode() {
        return 54;
    }

    private static int leftShiftScancode() {
        return 42;
    }

    private static KeyMapping newMapping(int key, String suffix) {
        return newMapping(InputConstants.Type.KEYSYM, key, suffix);
    }

    private static KeyMapping newMapping(InputConstants.Type type, int key, String suffix) {
        return new KeyMapping(
                "farmhelper.audit012." + suffix + "." + System.nanoTime(),
                type,
                key,
                KeyMapping.Category.MISC);
    }

    /** Test-only seam matching the existing controller's click-drain and screen guard. */
    private static final class ControllerTriggerSeam {
        private boolean previousDown;
        private boolean screenOpen;
        private int openedScreens;

        private ControllerTriggerSeam(boolean screenOpen) {
            this.screenOpen = screenOpen;
        }

        private boolean observeHeldState(boolean isDown) {
            boolean risingEdge = isDown && !previousDown;
            previousDown = isDown;
            return risingEdge;
        }

        private int tick(KeyMapping mapping) {
            int openedThisTick = 0;
            while (mapping.consumeClick()) {
                if (screenOpen) {
                    continue;
                }
                screenOpen = true;
                openedScreens++;
                openedThisTick++;
            }
            return openedThisTick;
        }

        private int openedScreens() {
            return openedScreens;
        }
    }
}

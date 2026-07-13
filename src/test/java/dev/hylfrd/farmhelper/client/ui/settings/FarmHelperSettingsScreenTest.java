package dev.hylfrd.farmhelper.client.ui.settings;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmHelperSettingsScreenTest {
    @Test
    void tinyWindowLayoutNeverProducesNegativeControlGeometry() {
        FarmHelperSettingsScreen.Layout layout = FarmHelperSettingsScreen.Layout.compute(160, 90);

        assertTrue(layout.sidebarX() >= 0);
        assertTrue(layout.sidebarWidth() > 0);
        assertTrue(layout.contentX() >= 0);
        assertTrue(layout.contentWidth() > 0);
        assertTrue(layout.contentHeight() > 0);
        assertTrue(layout.sidebarX() + layout.sidebarWidth() <= layout.contentX());
        assertTrue(layout.contentX() + layout.contentWidth() <= 160);
    }
}

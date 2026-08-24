package dev.hylfrd.farmhelper.client.ui.settings;

import dev.hylfrd.farmhelper.config.FarmHelperConfig;
import dev.hylfrd.farmhelper.ui.settings.SettingCategory;
import dev.hylfrd.farmhelper.ui.settings.SettingsCatalog;
import dev.hylfrd.farmhelper.ui.settings.SettingsSession;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void categoryResetClearsItsValidationMarkerAndAllowsTheResetDraftToSave() {
        FarmHelperConfig live = new FarmHelperConfig();
        live.setTargetYaw(45.0F);
        SettingsCatalog catalog = SettingsCatalog.standard();
        SettingsSession session = new SettingsSession(catalog, live);
        session.selectCategory(SettingCategory.ROTATION);
        Set<String> invalidSettings = new HashSet<>(Set.of(SettingsCatalog.TARGET_YAW.id()));

        session.resetCategory();
        FarmHelperSettingsScreen.clearInvalidSettingsForCategory(
                invalidSettings, catalog, session.category());

        assertTrue(invalidSettings.isEmpty());
        assertEquals(0.0F, session.draft().read(SettingsCatalog.TARGET_YAW));
        AtomicReference<FarmHelperConfig> saved = new AtomicReference<>();
        assertTrue(session.save(candidate -> {
            saved.set(candidate.copy());
            return true;
        }));
        assertEquals(0.0F, saved.get().targetYaw());
    }

    @Test
    void resetMarkersDoNotClearUnrelatedCategoryErrorsOrActionFields() {
        SettingsCatalog catalog = SettingsCatalog.standard();
        Set<String> invalidSettings = new HashSet<>(Set.of(
                SettingsCatalog.TARGET_YAW.id(),
                SettingsCatalog.CHECK_DESYNC.id(),
                SettingsCatalog.RESET_ROTATION.id()));

        FarmHelperSettingsScreen.clearInvalidSettingsForCategory(
                invalidSettings, catalog, SettingCategory.ROTATION);

        assertEquals(Set.of(SettingsCatalog.CHECK_DESYNC.id(), SettingsCatalog.RESET_ROTATION.id()),
                invalidSettings);

        invalidSettings.add(SettingsCatalog.TARGET_PITCH.id());
        FarmHelperSettingsScreen.clearInvalidSettingsForAction(
                invalidSettings, SettingsCatalog.RESET_ROTATION);

        assertEquals(Set.of(SettingsCatalog.CHECK_DESYNC.id(), SettingsCatalog.RESET_ROTATION.id()),
                invalidSettings);
        assertFalse(invalidSettings.contains(SettingsCatalog.TARGET_YAW.id()));
        assertFalse(invalidSettings.contains(SettingsCatalog.TARGET_PITCH.id()));
    }

    @Test
    void rotationResetActionClearsItsMarkersAndAllowsTheResetDraftToSave() {
        FarmHelperConfig live = new FarmHelperConfig();
        live.setTargetYaw(45.0F);
        live.setTargetPitch(20.0F);
        SettingsCatalog catalog = SettingsCatalog.standard();
        SettingsSession session = new SettingsSession(catalog, live);
        Set<String> invalidSettings = new HashSet<>(Set.of(
                SettingsCatalog.TARGET_YAW.id(), SettingsCatalog.TARGET_PITCH.id()));

        session.draft().activate(SettingsCatalog.RESET_ROTATION);
        FarmHelperSettingsScreen.clearInvalidSettingsForAction(
                invalidSettings, SettingsCatalog.RESET_ROTATION);

        assertTrue(invalidSettings.isEmpty());
        assertEquals(0.0F, session.draft().read(SettingsCatalog.TARGET_YAW));
        assertEquals(0.0F, session.draft().read(SettingsCatalog.TARGET_PITCH));
        AtomicReference<FarmHelperConfig> saved = new AtomicReference<>();
        assertTrue(session.save(candidate -> {
            saved.set(candidate.copy());
            return true;
        }));
        assertEquals(0.0F, saved.get().targetYaw());
        assertEquals(0.0F, saved.get().targetPitch());
    }
}

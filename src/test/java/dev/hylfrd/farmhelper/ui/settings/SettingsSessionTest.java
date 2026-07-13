package dev.hylfrd.farmhelper.ui.settings;

import dev.hylfrd.farmhelper.config.FarmHelperConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsSessionTest {
    @Test
    void editsStayInDraftUntilValidatedSaveSucceeds() {
        FarmHelperConfig live = new FarmHelperConfig();
        SettingsSession session = new SettingsSession(SettingsCatalog.standard(), live);
        AtomicReference<FarmHelperConfig> saved = new AtomicReference<>();

        session.draft().write(SettingsCatalog.TARGET_YAW, 45.0);

        assertEquals(0.0F, live.targetYaw());
        assertTrue(session.draft().dirty());
        assertTrue(session.save(candidate -> {
            saved.set(candidate.copy());
            live.replaceWith(candidate);
            return true;
        }));
        assertEquals(45.0F, saved.get().targetYaw());
        assertEquals(45.0F, live.targetYaw());
        assertFalse(session.draft().dirty());
        assertEquals("Settings saved.", session.feedback());
    }

    @Test
    void failedSaveDoesNotTouchLiveConfigOrDiscardDraft() {
        FarmHelperConfig live = new FarmHelperConfig();
        SettingsSession session = new SettingsSession(SettingsCatalog.standard(), live);
        session.draft().write(SettingsCatalog.TARGET_PITCH, -30.0);

        assertFalse(session.save(candidate -> false));

        assertEquals(0.0F, live.targetPitch());
        assertEquals(-30.0, session.draft().read(SettingsCatalog.TARGET_PITCH));
        assertTrue(session.draft().dirty());
    }

    @Test
    void validationRejectsInvalidDraftWithoutPartialMutation() {
        SettingsDraft draft = new SettingsDraft(new FarmHelperConfig());

        assertThrows(IllegalArgumentException.class,
                () -> draft.write(SettingsCatalog.TARGET_PITCH, 100.0));

        assertEquals(0.0, draft.read(SettingsCatalog.TARGET_PITCH));
        assertFalse(draft.dirty());
    }

    @Test
    void categoryAndSearchFilterLabelsDescriptionsAndKeywords() {
        SettingsSession session = new SettingsSession(SettingsCatalog.standard(), new FarmHelperConfig());
        session.setQuery("vertical");

        assertEquals(List.of(SettingsCatalog.TARGET_PITCH), session.visibleSettings());

        session.selectCategory(SettingCategory.INTERFACE);
        session.setQuery("shortcut");
        assertEquals(List.of(SettingsCatalog.OPEN_SETTINGS_KEY), session.visibleSettings());
    }

    @Test
    void actionAndCategoryResetOnlyMutateTheDraft() {
        FarmHelperConfig live = new FarmHelperConfig();
        live.setTargetYaw(45.0F);
        live.setTargetPitch(20.0F);
        live.setOpenSettingsKey(65);
        SettingsSession session = new SettingsSession(SettingsCatalog.standard(), live);

        session.draft().activate(SettingsCatalog.RESET_ROTATION);
        assertEquals(0.0, session.draft().read(SettingsCatalog.TARGET_YAW));
        assertEquals(65, session.draft().read(SettingsCatalog.OPEN_SETTINGS_KEY));
        assertEquals(45.0F, live.targetYaw());

        session.selectCategory(SettingCategory.INTERFACE);
        session.resetCategory();
        assertEquals(FarmHelperConfig.DEFAULT_OPEN_SETTINGS_KEY,
                session.draft().read(SettingsCatalog.OPEN_SETTINGS_KEY));
    }

    @Test
    void viewportClampsAfterSearchResizeAndLargeScrolls() {
        SettingsViewport viewport = new SettingsViewport();

        viewport.scroll(100, 10, 3);
        assertEquals(7, viewport.firstRow());
        viewport.clamp(1, 0);
        assertEquals(0, viewport.firstRow());
        viewport.scroll(-100, 10, 3);
        assertEquals(0, viewport.firstRow());
    }

    @Test
    void catalogRejectsDuplicateStableIds() {
        assertThrows(IllegalArgumentException.class,
                () -> new SettingsCatalog(List.of(SettingsCatalog.TARGET_YAW, SettingsCatalog.TARGET_YAW)));
    }

    @Test
    void frameworkDefinesEveryRequiredReusableControlKind() {
        enum Example { FIRST, SECOND }
        FarmHelperConfig config = new FarmHelperConfig();

        List<SettingDefinition<?>> definitions = List.of(
                SettingDefinition.bool("test.boolean", SettingCategory.INTERFACE, "Boolean", "Boolean control.",
                        value -> value.targetYaw() != 0, (value, enabled) -> value.setTargetYaw(enabled ? 1 : 0)),
                SettingDefinition.integer("test.integer", SettingCategory.INTERFACE, "Integer", "Integer control.",
                        -1, 348, FarmHelperConfig::openSettingsKey, FarmHelperConfig::setOpenSettingsKey),
                SettingDefinition.decimal("test.decimal", SettingCategory.ROTATION, "Decimal", "Decimal control.",
                        -90, 90, value -> (double) value.targetPitch(),
                        (value, number) -> value.setTargetPitch(number.floatValue())),
                SettingDefinition.enumeration("test.enum", SettingCategory.INTERFACE, "Enum", "Enum control.",
                        List.of(Example.FIRST, Example.SECOND), value -> Example.FIRST, (value, choice) -> { }),
                SettingDefinition.string("test.string", SettingCategory.INTERFACE, "String", "String control.",
                        20, value -> "", (value, text) -> { }),
                SettingDefinition.color("test.color", SettingCategory.INTERFACE, "Color", "Color control.",
                        value -> 0xFFFFFF, (value, color) -> { }),
                SettingDefinition.keybind("test.keybind", SettingCategory.INTERFACE, "Keybind", "Keybind control.",
                        FarmHelperConfig::openSettingsKey, FarmHelperConfig::setOpenSettingsKey),
                SettingDefinition.action("test.action", SettingCategory.INTERFACE, "Action", "Action control.",
                        FarmHelperConfig::reset));

        assertEquals(List.of(SettingKind.BOOLEAN, SettingKind.INTEGER, SettingKind.DECIMAL, SettingKind.ENUM,
                        SettingKind.STRING, SettingKind.COLOR, SettingKind.KEYBIND, SettingKind.ACTION),
                definitions.stream().map(SettingDefinition::kind).toList());
        assertEquals(8, Stream.of(SettingKind.values()).count());
        assertEquals(FarmHelperConfig.DEFAULT_OPEN_SETTINGS_KEY, config.openSettingsKey());
    }
}

package dev.hylfrd.farmhelper.ui.settings;

import dev.hylfrd.farmhelper.config.FarmHelperConfig;
import dev.hylfrd.farmhelper.config.FarmHelperConfigStore;
import dev.hylfrd.farmhelper.macro.MacroMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsSessionTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void standardCatalogContainsOnlyPersistedMacroAndDesyncBehaviorFields() {
        assertEquals(List.of(
                        "rotation.targetYaw", "rotation.targetPitch", "rotation.reset",
                        "interface.openSettingsKey", "macro.mode", "macro.alwaysHoldW",
                        "macro.holdLeftClickWhenChangingRow", "rotation.rotateAfterWarped",
                        "rotation.rotateAfterDrop", "rotation.dontFixAfterWarping",
                        "rotation.customPitch", "rotation.customPitchLevel", "rotation.customYaw",
                        "rotation.customYawLevel", "desync.checkDesync", "desync.pauseDelayMillis"),
                SettingsCatalog.standard().definitions().stream()
                        .map(SettingDefinition::id)
                        .toList());
        assertEquals(List.of(MacroMode.values()), SettingsCatalog.MACRO_MODE.choices());
        assertEquals(SettingCategory.MACRO, SettingsCatalog.MACRO_MODE.category());
        assertEquals(SettingCategory.ROTATION, SettingsCatalog.CUSTOM_PITCH_LEVEL.category());
        assertEquals(SettingCategory.FAILSAFE, SettingsCatalog.CHECK_DESYNC.category());

        FarmHelperConfig defaults = new FarmHelperConfig();
        assertEquals(MacroMode.VERTICAL_NORMAL, SettingsCatalog.MACRO_MODE.read(defaults));
        assertFalse(SettingsCatalog.ALWAYS_HOLD_W.read(defaults));
        assertTrue(SettingsCatalog.HOLD_LEFT_CLICK_WHEN_CHANGING_ROW.read(defaults));
        assertFalse(SettingsCatalog.ROTATE_AFTER_WARPED.read(defaults));
        assertFalse(SettingsCatalog.ROTATE_AFTER_DROP.read(defaults));
        assertFalse(SettingsCatalog.DONT_FIX_AFTER_WARPING.read(defaults));
        assertFalse(SettingsCatalog.CUSTOM_PITCH.read(defaults));
        assertEquals(0.0, SettingsCatalog.CUSTOM_PITCH_LEVEL.read(defaults));
        assertFalse(SettingsCatalog.CUSTOM_YAW.read(defaults));
        assertEquals(0.0, SettingsCatalog.CUSTOM_YAW_LEVEL.read(defaults));
        assertEquals(FarmHelperConfig.DEFAULT_CHECK_DESYNC,
                SettingsCatalog.CHECK_DESYNC.read(defaults));
        assertEquals(FarmHelperConfig.DEFAULT_DESYNC_PAUSE_DELAY_MILLIS,
                SettingsCatalog.DESYNC_PAUSE_DELAY.read(defaults));
    }

    @Test
    void draftDirtyTrackingCoversMacroAndDesyncFieldsAndCanRevert() {
        FarmHelperConfig live = new FarmHelperConfig();
        live.setMacroMode(MacroMode.CIRCULAR.code());
        live.setAlwaysHoldW(true);
        live.setHoldLeftClickWhenChangingRow(false);
        live.setRotateAfterWarped(true);
        live.setRotateAfterDrop(true);
        live.setDontFixAfterWarping(true);
        live.setCustomPitch(true);
        live.setCustomPitchLevel(-90.0F);
        live.setCustomYaw(true);
        live.setCustomYawLevel(180.0F);
        live.setCheckDesync(false);
        live.setDesyncPauseDelayMillis(FarmHelperConfig.MAX_DESYNC_PAUSE_DELAY_MILLIS);

        SettingsDraft draft = new SettingsDraft(live);
        draft.write(SettingsCatalog.MACRO_MODE, MacroMode.VERTICAL_NORMAL);
        draft.write(SettingsCatalog.ALWAYS_HOLD_W, false);
        draft.write(SettingsCatalog.HOLD_LEFT_CLICK_WHEN_CHANGING_ROW, true);
        draft.write(SettingsCatalog.ROTATE_AFTER_WARPED, false);
        draft.write(SettingsCatalog.ROTATE_AFTER_DROP, false);
        draft.write(SettingsCatalog.DONT_FIX_AFTER_WARPING, false);
        draft.write(SettingsCatalog.CUSTOM_PITCH, false);
        draft.write(SettingsCatalog.CUSTOM_PITCH_LEVEL, 0.0);
        draft.write(SettingsCatalog.CUSTOM_YAW, false);
        draft.write(SettingsCatalog.CUSTOM_YAW_LEVEL, 0.0);
        draft.write(SettingsCatalog.CHECK_DESYNC, true);
        draft.write(SettingsCatalog.DESYNC_PAUSE_DELAY,
                FarmHelperConfig.DEFAULT_DESYNC_PAUSE_DELAY_MILLIS);

        assertTrue(draft.dirty());

        draft.write(SettingsCatalog.MACRO_MODE, MacroMode.CIRCULAR);
        draft.write(SettingsCatalog.ALWAYS_HOLD_W, true);
        draft.write(SettingsCatalog.HOLD_LEFT_CLICK_WHEN_CHANGING_ROW, false);
        draft.write(SettingsCatalog.ROTATE_AFTER_WARPED, true);
        draft.write(SettingsCatalog.ROTATE_AFTER_DROP, true);
        draft.write(SettingsCatalog.DONT_FIX_AFTER_WARPING, true);
        draft.write(SettingsCatalog.CUSTOM_PITCH, true);
        draft.write(SettingsCatalog.CUSTOM_PITCH_LEVEL, -90.0);
        draft.write(SettingsCatalog.CUSTOM_YAW, true);
        draft.write(SettingsCatalog.CUSTOM_YAW_LEVEL, 180.0);
        draft.write(SettingsCatalog.CHECK_DESYNC, false);
        draft.write(SettingsCatalog.DESYNC_PAUSE_DELAY,
                FarmHelperConfig.MAX_DESYNC_PAUSE_DELAY_MILLIS);

        assertFalse(draft.dirty());
    }

    @Test
    void sessionSaveRoundTripsPersistedMacroAndDesyncBehavior() throws IOException {
        FarmHelperConfig live = new FarmHelperConfig();
        SettingsSession session = new SettingsSession(SettingsCatalog.standard(), live);
        session.draft().write(SettingsCatalog.MACRO_MODE, MacroMode.MUSHROOM_SDS);
        session.draft().write(SettingsCatalog.ALWAYS_HOLD_W, true);
        session.draft().write(SettingsCatalog.HOLD_LEFT_CLICK_WHEN_CHANGING_ROW, false);
        session.draft().write(SettingsCatalog.ROTATE_AFTER_WARPED, true);
        session.draft().write(SettingsCatalog.ROTATE_AFTER_DROP, true);
        session.draft().write(SettingsCatalog.DONT_FIX_AFTER_WARPING, true);
        session.draft().write(SettingsCatalog.CUSTOM_PITCH, true);
        session.draft().write(SettingsCatalog.CUSTOM_PITCH_LEVEL, -45.0);
        session.draft().write(SettingsCatalog.CUSTOM_YAW, true);
        session.draft().write(SettingsCatalog.CUSTOM_YAW_LEVEL, 135.0);
        session.draft().write(SettingsCatalog.CHECK_DESYNC, false);
        session.draft().write(SettingsCatalog.DESYNC_PAUSE_DELAY, 7_500);

        FarmHelperConfigStore store = new FarmHelperConfigStore(
                temporaryDirectory.resolve("settings-round-trip.json"));
        assertTrue(session.save(candidate -> {
            try {
                store.save(candidate);
                live.replaceWith(candidate);
                return true;
            } catch (IOException exception) {
                return false;
            }
        }));

        FarmHelperConfig loaded = store.load().config();
        assertEquals(FarmHelperConfig.CURRENT_SCHEMA_VERSION, loaded.schemaVersion());
        assertEquals(MacroMode.MUSHROOM_SDS.code(), loaded.macroMode());
        assertTrue(loaded.alwaysHoldW());
        assertFalse(loaded.holdLeftClickWhenChangingRow());
        assertTrue(loaded.rotateAfterWarped());
        assertTrue(loaded.rotateAfterDrop());
        assertTrue(loaded.dontFixAfterWarping());
        assertTrue(loaded.customPitch());
        assertEquals(-45.0F, loaded.customPitchLevel());
        assertTrue(loaded.customYaw());
        assertEquals(135.0F, loaded.customYawLevel());
        assertFalse(loaded.checkDesync());
        assertEquals(7_500, loaded.desyncPauseDelayMillis());
        assertEquals(loaded.macroMode(), live.macroMode());
        assertFalse(session.draft().dirty());
    }

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

package dev.hylfrd.farmhelper.client.ui.settings;

import com.mojang.text2speech.Narrator;
import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.client.runtime.TestFarmHelperClientRuntimeFactory;
import dev.hylfrd.farmhelper.config.FarmHelperConfig;
import dev.hylfrd.farmhelper.config.FarmHelperConfigStore;
import dev.hylfrd.farmhelper.ui.settings.SettingCategory;
import dev.hylfrd.farmhelper.ui.settings.SettingsCatalog;
import dev.hylfrd.farmhelper.ui.settings.SettingsSession;
import net.minecraft.client.InputType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sun.misc.Unsafe;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
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
    void freshScreenStartsCleanAndRealCategoryResetPreservesUnrelatedInvalidMarkers(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        try (HeadlessMinecraft ignored = new HeadlessMinecraft()) {
            ScreenFixture fixture = newScreen(temporaryDirectory.resolve("category-reset.json"));
            FarmHelperSettingsScreen screen = fixture.screen();

            assertTrue(invalidSettings(screen).isEmpty());

            press(screen, "Failsafe");
            editBox(screen, 1).setValue("not-a-number");
            assertEquals(Set.of(SettingsCatalog.DESYNC_PAUSE_DELAY.id()), invalidSettings(screen));

            screen.resize(720, 480);
            assertEquals(Set.of(SettingsCatalog.DESYNC_PAUSE_DELAY.id()), invalidSettings(screen));

            press(screen, "Rotation");
            assertEquals(Set.of(SettingsCatalog.DESYNC_PAUSE_DELAY.id()), invalidSettings(screen));

            editBox(screen, 1).setValue("not-an-angle");
            assertEquals(Set.of(
                    SettingsCatalog.DESYNC_PAUSE_DELAY.id(), SettingsCatalog.TARGET_YAW.id()),
                    invalidSettings(screen));

            screen.mouseScrolled(300.0, 100.0, 0.0, -1.0);
            assertEquals(Set.of(
                    SettingsCatalog.DESYNC_PAUSE_DELAY.id(), SettingsCatalog.TARGET_YAW.id()),
                    invalidSettings(screen));

            press(screen, "Reset");

            assertEquals(Set.of(SettingsCatalog.DESYNC_PAUSE_DELAY.id()), invalidSettings(screen));
            assertEquals("0", editBox(screen, 1).getValue());
            assertEquals("0", editBox(screen, 2).getValue());

            press(screen, "Save");

            assertEquals(Set.of(SettingsCatalog.DESYNC_PAUSE_DELAY.id()), invalidSettings(screen));
            assertEquals(45.0F, fixture.runtime().configSnapshot().targetYaw());

            FarmHelperSettingsScreen reopened = new FarmHelperSettingsScreen(null, fixture.runtime(),
                    fixture.openKey());
            reopened.init(800, 600);
            assertTrue(invalidSettings(reopened).isEmpty());
        }
    }

    @Test
    void realRotationResetActionClearsOnlyItsMarkersAndKeepsSaveBlockedByUnrelatedMarker(
            @TempDir Path temporaryDirectory
    ) throws IOException {
        try (HeadlessMinecraft ignored = new HeadlessMinecraft()) {
            ScreenFixture fixture = newScreen(temporaryDirectory.resolve("rotation-reset.json"));
            FarmHelperSettingsScreen screen = fixture.screen();

            press(screen, "Failsafe");
            editBox(screen, 1).setValue("not-a-number");
            press(screen, "Rotation");
            editBox(screen, 1).setValue("not-an-angle");
            editBox(screen, 2).setValue("not-a-pitch");

            assertEquals(Set.of(
                    SettingsCatalog.DESYNC_PAUSE_DELAY.id(),
                    SettingsCatalog.TARGET_YAW.id(),
                    SettingsCatalog.TARGET_PITCH.id()), invalidSettings(screen));

            press(screen, "Reset rotation");

            assertEquals(Set.of(SettingsCatalog.DESYNC_PAUSE_DELAY.id()), invalidSettings(screen));
            assertEquals("0", editBox(screen, 1).getValue());
            assertEquals("0", editBox(screen, 2).getValue());

            press(screen, "Save");

            assertEquals(Set.of(SettingsCatalog.DESYNC_PAUSE_DELAY.id()), invalidSettings(screen));
            assertEquals(45.0F, fixture.runtime().configSnapshot().targetYaw());
            assertEquals(20.0F, fixture.runtime().configSnapshot().targetPitch());
        }
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

    private static ScreenFixture newScreen(Path configPath) throws IOException {
        FarmHelperConfig live = new FarmHelperConfig();
        live.setTargetYaw(45.0F);
        live.setTargetPitch(20.0F);
        new FarmHelperConfigStore(configPath).save(live);

        FarmHelperClientRuntime runtime = TestFarmHelperClientRuntimeFactory.create(configPath);
        KeyMapping openKey = new KeyMapping(
                "farmhelper.test.openSettings", 65, KeyMapping.Category.MISC);
        FarmHelperSettingsScreen screen = new FarmHelperSettingsScreen(null, runtime, openKey);
        screen.init(800, 600);
        return new ScreenFixture(screen, runtime, openKey);
    }

    private static void press(FarmHelperSettingsScreen screen, String message) {
        Button button = screen.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(candidate -> candidate.getMessage().getString().equals(message)
                        || candidate.getMessage().getString().equals("> " + message))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing button " + message));
        button.onPress(null);
    }

    private static EditBox editBox(FarmHelperSettingsScreen screen, int index) {
        List<EditBox> editBoxes = screen.children().stream()
                .filter(EditBox.class::isInstance)
                .map(EditBox.class::cast)
                .toList();
        if (index < 0 || index >= editBoxes.size()) {
            throw new AssertionError("Missing edit box index " + index + " in " + editBoxes.size());
        }
        return editBoxes.get(index);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> invalidSettings(FarmHelperSettingsScreen screen) {
        try {
            Field field = FarmHelperSettingsScreen.class.getDeclaredField("invalidSettings");
            field.setAccessible(true);
            return (Set<String>) field.get(screen);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Could not inspect screen validation state", exception);
        }
    }

    private record ScreenFixture(
            FarmHelperSettingsScreen screen, FarmHelperClientRuntime runtime, KeyMapping openKey
    ) {
    }

    private static final class HeadlessMinecraft implements AutoCloseable {
        private static final Unsafe UNSAFE = unsafe();
        private final Minecraft previous;

        private HeadlessMinecraft() {
            previous = (Minecraft) staticField(Minecraft.class, "instance");
            Minecraft fake = allocate(Minecraft.class);
            fake.setLastInputType(InputType.NONE);
            putObject(fake, Minecraft.class, "narrator", inactiveNarrator(fake));
            putStaticObject(Minecraft.class, "instance", fake);
        }

        @Override
        public void close() {
            putStaticObject(Minecraft.class, "instance", previous);
        }

        private static Object inactiveNarrator(Minecraft minecraft) {
            GameNarratorHolder holder = new GameNarratorHolder(minecraft);
            return holder.value();
        }

        private static Unsafe unsafe() {
            try {
                Field field = Unsafe.class.getDeclaredField("theUnsafe");
                field.setAccessible(true);
                return (Unsafe) field.get(null);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Could not access test allocator", exception);
            }
        }

        @SuppressWarnings("unchecked")
        private static <T> T allocate(Class<T> type) {
            try {
                return (T) UNSAFE.allocateInstance(type);
            } catch (InstantiationException exception) {
                throw new AssertionError("Could not allocate " + type.getName(), exception);
            }
        }

        private static Object staticField(Class<?> type, String name) {
            try {
                Field field = type.getDeclaredField(name);
                return UNSAFE.getObject(UNSAFE.staticFieldBase(field), UNSAFE.staticFieldOffset(field));
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Could not read " + type.getName() + "." + name, exception);
            }
        }

        private static void putStaticObject(Class<?> type, String name, Object value) {
            try {
                Field field = type.getDeclaredField(name);
                UNSAFE.putObject(UNSAFE.staticFieldBase(field), UNSAFE.staticFieldOffset(field), value);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Could not write " + type.getName() + "." + name, exception);
            }
        }

        private static void putObject(Object target, Class<?> type, String name, Object value) {
            try {
                Field field = type.getDeclaredField(name);
                UNSAFE.putObject(target, UNSAFE.objectFieldOffset(field), value);
            } catch (ReflectiveOperationException exception) {
                throw new AssertionError("Could not write " + type.getName() + "." + name, exception);
            }
        }

        private static final class GameNarratorHolder {
            private final Object value;

            private GameNarratorHolder(Minecraft minecraft) {
                value = allocate(net.minecraft.client.GameNarrator.class);
                Narrator inactive = (Narrator) Proxy.newProxyInstance(
                        Narrator.class.getClassLoader(), new Class<?>[]{Narrator.class},
                        (proxy, method, args) -> defaultValue(method.getReturnType()));
                putObject(value, net.minecraft.client.GameNarrator.class, "narrator", inactive);
                putObject(value, net.minecraft.client.GameNarrator.class, "minecraft", minecraft);
            }

            private Object value() {
                return value;
            }

            private static Object defaultValue(Class<?> type) {
                if (!type.isPrimitive()) {
                    return null;
                }
                if (type == boolean.class) {
                    return false;
                }
                if (type == char.class) {
                    return '\0';
                }
                if (type == byte.class) {
                    return (byte) 0;
                }
                if (type == short.class) {
                    return (short) 0;
                }
                if (type == int.class) {
                    return 0;
                }
                if (type == long.class) {
                    return 0L;
                }
                if (type == float.class) {
                    return 0.0F;
                }
                if (type == double.class) {
                    return 0.0D;
                }
                throw new AssertionError("Unsupported primitive " + type);
            }
        }
    }
}

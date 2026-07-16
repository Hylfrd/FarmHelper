package dev.hylfrd.farmhelper.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmHelperConfigStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void missingFileCreatesAndPersistsDefaults() throws IOException {
        Path path = temporaryDirectory.resolve("config").resolve("farmhelper.json");

        ConfigLoadResult result = new FarmHelperConfigStore(path).load();

        assertEquals(ConfigLoadStatus.CREATED_DEFAULTS, result.status());
        assertEquals(FarmHelperConfig.CURRENT_SCHEMA_VERSION, result.config().schemaVersion());
        assertEquals(0.0F, result.config().targetYaw());
        assertEquals(0.0F, result.config().targetPitch());
        assertTrue(Files.isRegularFile(path));
        assertTrue(Files.readString(path, StandardCharsets.UTF_8).contains(
                "\"schemaVersion\": " + FarmHelperConfig.CURRENT_SCHEMA_VERSION));
        assertEquals(FarmHelperConfig.DEFAULT_OPEN_SETTINGS_KEY, result.config().openSettingsKey());
    }

    @Test
    void savesAndLoadsValidatedRoundTrip() throws IOException {
        Path path = temporaryDirectory.resolve("farmhelper.json");
        FarmHelperConfigStore store = new FarmHelperConfigStore(path);
        FarmHelperConfig config = new FarmHelperConfig();
        config.setTargetYaw(135.5F);
        config.setTargetPitch(-42.25F);

        store.save(config);
        ConfigLoadResult result = store.load();

        assertEquals(ConfigLoadStatus.LOADED, result.status());
        assertEquals(135.5F, result.config().targetYaw());
        assertEquals(-42.25F, result.config().targetPitch());
    }

    @Test
    void missingCurrentFieldsUseTypedDefaults() throws IOException {
        Path path = temporaryDirectory.resolve("farmhelper.json");
        Files.writeString(path, "{\"schemaVersion\":6}", StandardCharsets.UTF_8);

        ConfigLoadResult result = new FarmHelperConfigStore(path).load();

        assertEquals(ConfigLoadStatus.LOADED, result.status());
        assertEquals(0.0F, result.config().targetYaw());
        assertEquals(0.0F, result.config().targetPitch());
    }

    @Test
    void atomicSaveReplacesTargetAndCleansTemporaryFile() throws IOException {
        Path path = temporaryDirectory.resolve("farmhelper.json");
        FarmHelperConfigStore store = new FarmHelperConfigStore(path);
        FarmHelperConfig first = new FarmHelperConfig();
        first.setTargetYaw(10.0F);
        store.save(first);

        FarmHelperConfig second = new FarmHelperConfig();
        second.setTargetYaw(20.0F);
        store.save(second);

        assertEquals(20.0F, store.load().config().targetYaw());
        try (var files = Files.list(temporaryDirectory)) {
            List<String> names = files.map(file -> file.getFileName().toString()).toList();
            assertEquals(List.of("farmhelper.json"), names);
        }
    }

    @Test
    void failedAtomicReplacementPreservesPreviousFileAndCleansTemporaryFile() throws IOException {
        Path path = temporaryDirectory.resolve("farmhelper.json");
        FarmHelperConfigStore initialStore = new FarmHelperConfigStore(path);
        FarmHelperConfig initial = new FarmHelperConfig();
        initial.setTargetYaw(10.0F);
        initialStore.save(initial);
        String originalJson = Files.readString(path, StandardCharsets.UTF_8);
        AtomicBoolean replacementAttempted = new AtomicBoolean();
        FarmHelperConfigStore failingStore = new FarmHelperConfigStore(path, (source, target) -> {
            replacementAttempted.set(true);
            assertEquals(path, target);
            assertEquals(originalJson, Files.readString(target, StandardCharsets.UTF_8));
            assertTrue(Files.readString(source, StandardCharsets.UTF_8).contains("\"targetYaw\": 20.0"));
            throw new IOException("injected replacement failure");
        });
        FarmHelperConfig replacement = new FarmHelperConfig();
        replacement.setTargetYaw(20.0F);

        assertThrows(IOException.class, () -> failingStore.save(replacement));

        assertTrue(replacementAttempted.get());
        assertEquals(originalJson, Files.readString(path, StandardCharsets.UTF_8));
        assertEquals(10.0F, initialStore.load().config().targetYaw());
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of("farmhelper.json"), files.map(file -> file.getFileName().toString()).toList());
        }
    }

    @Test
    void malformedFileIsBackedUpAndRecovered() throws IOException {
        Path path = temporaryDirectory.resolve("farmhelper.json");
        Files.writeString(path, "{ definitely-not-json", StandardCharsets.UTF_8);

        ConfigLoadResult result = new FarmHelperConfigStore(path).load();

        assertEquals(ConfigLoadStatus.RECOVERED_DEFAULTS, result.status());
        assertTrue(result.backup().isPresent());
        assertEquals("{ definitely-not-json", Files.readString(result.backup().orElseThrow(), StandardCharsets.UTF_8));
        assertEquals(ConfigLoadStatus.LOADED, new FarmHelperConfigStore(path).load().status());
    }

    @Test
    void invalidValueIsBackedUpAndRecovered() throws IOException {
        Path path = temporaryDirectory.resolve("farmhelper.json");
        Files.writeString(path, """
                {
                  "schemaVersion": 3,
                  "rotation": {"targetYaw": 900, "targetPitch": 0}
                }
                """, StandardCharsets.UTF_8);

        ConfigLoadResult result = new FarmHelperConfigStore(path).load();

        assertEquals(ConfigLoadStatus.RECOVERED_DEFAULTS, result.status());
        assertEquals(0.0F, result.config().targetYaw());
        assertTrue(result.backup().isPresent());
    }

    @Test
    void invalidFieldTypeIsBackedUpAndRecovered() throws IOException {
        Path path = temporaryDirectory.resolve("farmhelper.json");
        Files.writeString(path, """
                {
                  "schemaVersion": 3,
                  "rotation": {"targetYaw": "east", "targetPitch": 0}
                }
                """, StandardCharsets.UTF_8);

        ConfigLoadResult result = new FarmHelperConfigStore(path).load();

        assertEquals(ConfigLoadStatus.RECOVERED_DEFAULTS, result.status());
        assertEquals(0.0F, result.config().targetYaw());
        assertTrue(result.backup().isPresent());
        assertEquals(ConfigLoadStatus.LOADED, new FarmHelperConfigStore(path).load().status());
    }

    @Test
    void missingSchemaIsBackedUpAndRecovered() throws IOException {
        Path path = temporaryDirectory.resolve("farmhelper.json");
        Files.writeString(path, "{\"rotation\":{\"targetYaw\":25}}", StandardCharsets.UTF_8);

        ConfigLoadResult result = new FarmHelperConfigStore(path).load();

        assertEquals(ConfigLoadStatus.RECOVERED_DEFAULTS, result.status());
        assertEquals(0.0F, result.config().targetYaw());
        assertTrue(result.backup().isPresent());
        assertEquals(ConfigLoadStatus.LOADED, new FarmHelperConfigStore(path).load().status());
    }

    @Test
    void migratesVersionOneAndRewritesCurrentSchema() throws IOException {
        Path path = temporaryDirectory.resolve("farmhelper.json");
        Files.writeString(path, """
                {
                  "schemaVersion": 1,
                  "targetYaw": 45.5,
                  "targetPitch": -30
                }
                """, StandardCharsets.UTF_8);

        ConfigLoadResult result = new FarmHelperConfigStore(path).load();
        String migrated = Files.readString(path, StandardCharsets.UTF_8);

        assertEquals(ConfigLoadStatus.MIGRATED, result.status());
        assertEquals(45.5F, result.config().targetYaw());
        assertEquals(-30.0F, result.config().targetPitch());
        assertTrue(migrated.contains("\"schemaVersion\": 6"));
        assertTrue(migrated.contains("\"rotation\""));
        assertFalse(migrated.contains("\"targetYaw\": 45.5\n"));
    }

    @Test
    void futureSchemaIsPreservedAsBackupBeforeDefaultsAreWritten() throws IOException {
        Path path = temporaryDirectory.resolve("farmhelper.json");
        String future = "{\"schemaVersion\":999,\"rotation\":{\"targetYaw\":12}}";
        Files.writeString(path, future, StandardCharsets.UTF_8);

        ConfigLoadResult result = new FarmHelperConfigStore(path).load();

        assertEquals(ConfigLoadStatus.RECOVERED_DEFAULTS, result.status());
        assertEquals(future, Files.readString(result.backup().orElseThrow(), StandardCharsets.UTF_8));
        assertEquals(0.0F, result.config().targetYaw());
    }

    @Test
    void savedSchemaContainsNoRemovedRemoteOrCredentialFields() throws IOException {
        Path path = temporaryDirectory.resolve("farmhelper.json");
        new FarmHelperConfigStore(path).save(new FarmHelperConfig());

        String json = Files.readString(path, StandardCharsets.UTF_8).toLowerCase();

        assertFalse(json.contains("oneconfig"));
        assertFalse(json.contains("webhook"));
        assertFalse(json.contains("discord"));
        assertFalse(json.contains("remote"));
        assertFalse(json.contains("proxy"));
        assertFalse(json.contains("token"));
        assertFalse(json.contains("password"));
        assertFalse(json.contains("analytics"));
    }

    @Test
    void removedFieldsCannotRemainInActiveConfiguration() throws IOException {
        Path path = temporaryDirectory.resolve("farmhelper.json");
        String contaminated = """
                {
                  "schemaVersion": 3,
                  "rotation": {"targetYaw": 35, "targetPitch": 0},
                  "discordRemoteControlToken": "secret-token",
                  "proxyPassword": "secret-password"
                }
                """;
        Files.writeString(path, contaminated, StandardCharsets.UTF_8);

        ConfigLoadResult result = new FarmHelperConfigStore(path).load();
        String active = Files.readString(path, StandardCharsets.UTF_8).toLowerCase();

        assertEquals(ConfigLoadStatus.RECOVERED_DEFAULTS, result.status());
        assertEquals(0.0F, result.config().targetYaw());
        assertEquals(contaminated, Files.readString(result.backup().orElseThrow(), StandardCharsets.UTF_8));
        assertFalse(active.contains("discord"));
        assertFalse(active.contains("remote"));
        assertFalse(active.contains("token"));
        assertFalse(active.contains("proxy"));
        assertFalse(active.contains("password"));
        assertFalse(active.contains("secret"));
    }


    @Test
    void migratesVersionTwoAndAddsDefaultSettingsKey() throws IOException {
        Path path = temporaryDirectory.resolve("farmhelper.json");
        Files.writeString(path, """
                {
                  "schemaVersion": 2,
                  "rotation": {"targetYaw": 45, "targetPitch": -20}
                }
                """, StandardCharsets.UTF_8);

        ConfigLoadResult result = new FarmHelperConfigStore(path).load();
        String migrated = Files.readString(path, StandardCharsets.UTF_8);

        assertEquals(ConfigLoadStatus.MIGRATED, result.status());
        assertEquals(45.0F, result.config().targetYaw());
        assertEquals(FarmHelperConfig.DEFAULT_OPEN_SETTINGS_KEY, result.config().openSettingsKey());
        assertTrue(migrated.contains("\"schemaVersion\": 6"));
        assertTrue(migrated.contains("\"openSettingsKey\": 344"));
    }

    @Test
    void roundTripsValidatedSettingsKey() throws IOException {
        Path path = temporaryDirectory.resolve("farmhelper.json");
        FarmHelperConfig config = new FarmHelperConfig();
        config.setOpenSettingsKey(65);

        new FarmHelperConfigStore(path).save(config);

        assertEquals(65, new FarmHelperConfigStore(path).load().config().openSettingsKey());
    }

    @Test
    void invalidSettingsKeyIsBackedUpAndRecovered() throws IOException {
        Path path = temporaryDirectory.resolve("farmhelper.json");
        Files.writeString(path, """
                {
                  "schemaVersion": 3,
                  "rotation": {"targetYaw": 0, "targetPitch": 0},
                  "ui": {"openSettingsKey": 999}
                }
                """, StandardCharsets.UTF_8);

        ConfigLoadResult result = new FarmHelperConfigStore(path).load();

        assertEquals(ConfigLoadStatus.RECOVERED_DEFAULTS, result.status());
        assertEquals(FarmHelperConfig.DEFAULT_OPEN_SETTINGS_KEY, result.config().openSettingsKey());
        assertTrue(result.backup().isPresent());
    }

    @Test
    void p1MacroSpawnRewarpAndTimingConfigRoundTrips() throws IOException {
        Path path = temporaryDirectory.resolve("macro-roundtrip.json");
        FarmHelperConfig config = new FarmHelperConfig();
        config.setMacroMode(6);
        config.setAlwaysHoldW(true);
        config.setHoldLeftClickWhenChangingRow(false);
        config.setMacroSpawn(new MacroLocationConfig(10, 72, -10, 90.0F, -38.5F, 6));
        assertTrue(config.addMacroRewarp(
                new MacroLocationConfig(20, 71, -10, 90.0F, -38.5F, 6)));

        FarmHelperConfigStore store = new FarmHelperConfigStore(path);
        store.save(config);
        FarmHelperConfig loaded = store.load().config();

        assertEquals(6, loaded.macroMode());
        assertEquals(config.macroSpawn(), loaded.macroSpawn());
        assertEquals(config.macroRewarps(), loaded.macroRewarps());
        assertTrue(loaded.alwaysHoldW());
        assertFalse(loaded.holdLeftClickWhenChangingRow());
    }

    @Test
    void migratesVersionFourWithTypedMacroInputDefaults() throws IOException {
        Path path = temporaryDirectory.resolve("schema-four.json");
        Files.writeString(path, """
                {
                  "schemaVersion": 4,
                  "macro": {"mode": 6}
                }
                """, StandardCharsets.UTF_8);

        ConfigLoadResult result = new FarmHelperConfigStore(path).load();

        assertEquals(ConfigLoadStatus.MIGRATED, result.status());
        assertEquals(6, result.config().macroMode());
        assertFalse(result.config().alwaysHoldW());
        assertTrue(result.config().holdLeftClickWhenChangingRow());
        String migrated = Files.readString(path, StandardCharsets.UTF_8);
        assertTrue(migrated.contains("\"schemaVersion\": 6"));
        assertTrue(migrated.contains("\"alwaysHoldW\": false"));
        assertTrue(migrated.contains("\"holdLeftClickWhenChangingRow\": true"));
    }

    @Test
    void macroInputFlagsRejectNonBooleanPersistedTypes() throws IOException {
        for (String field : List.of("alwaysHoldW", "holdLeftClickWhenChangingRow")) {
            Path path = temporaryDirectory.resolve(field + ".json");
            Files.writeString(path, """
                    {
                      "schemaVersion": 5,
                      "macro": {"%s": "false"}
                    }
                    """.formatted(field), StandardCharsets.UTF_8);

            ConfigLoadResult result = new FarmHelperConfigStore(path).load();

            assertEquals(ConfigLoadStatus.RECOVERED_DEFAULTS, result.status(), field);
            assertTrue(result.backup().isPresent(), field);
            assertFalse(result.config().alwaysHoldW(), field);
            assertTrue(result.config().holdLeftClickWhenChangingRow(), field);
        }
    }

    @Test
    void migratesVersionFiveWithSafeP2MechanismDefaults() throws IOException {
        Path path = temporaryDirectory.resolve("schema-five.json");
        Files.writeString(path, """
                {
                  "schemaVersion": 5,
                  "macro": {
                    "mode": 3,
                    "alwaysHoldW": true,
                    "holdLeftClickWhenChangingRow": false
                  }
                }
                """, StandardCharsets.UTF_8);

        ConfigLoadResult result = new FarmHelperConfigStore(path).load();

        assertEquals(ConfigLoadStatus.MIGRATED, result.status());
        assertEquals(3, result.config().macroMode());
        assertTrue(result.config().alwaysHoldW());
        assertFalse(result.config().holdLeftClickWhenChangingRow());
        assertFalse(result.config().rotateAfterWarped());
        assertFalse(result.config().rotateAfterDrop());
        assertFalse(result.config().dontFixAfterWarping());
        assertFalse(result.config().customPitch());
        assertEquals(0.0F, result.config().customPitchLevel());
        assertFalse(result.config().customYaw());
        assertEquals(0.0F, result.config().customYawLevel());
        assertTrue(Files.readString(path, StandardCharsets.UTF_8)
                .contains("\"schemaVersion\": 6"));
    }

    @Test
    void p2MechanismSettingsRoundTripWithoutUsingManualRotationTargets() throws IOException {
        Path path = temporaryDirectory.resolve("p2-mechanism.json");
        FarmHelperConfig config = new FarmHelperConfig();
        config.setTargetYaw(11.0F);
        config.setTargetPitch(12.0F);
        config.setMacroMode(3);
        config.setRotateAfterWarped(true);
        config.setRotateAfterDrop(true);
        config.setDontFixAfterWarping(true);
        config.setCustomPitch(true);
        config.setCustomPitchLevel(-90.0F);
        config.setCustomYaw(true);
        config.setCustomYawLevel(180.0F);

        FarmHelperConfigStore store = new FarmHelperConfigStore(path);
        store.save(config);
        FarmHelperConfig loaded = store.load().config();

        assertEquals(11.0F, loaded.targetYaw());
        assertEquals(12.0F, loaded.targetPitch());
        assertEquals(3, loaded.macroMode());
        assertTrue(loaded.rotateAfterWarped());
        assertTrue(loaded.rotateAfterDrop());
        assertTrue(loaded.dontFixAfterWarping());
        assertTrue(loaded.customPitch());
        assertEquals(-90.0F, loaded.customPitchLevel());
        assertTrue(loaded.customYaw());
        assertEquals(180.0F, loaded.customYawLevel());
    }

    @Test
    void spawnUpdatePersistsPosePlotAndEvictsNearbyRewarp() {
        FarmHelperConfig config = new FarmHelperConfig();
        assertTrue(config.addMacroRewarp(
                new MacroLocationConfig(1, 70, 0, 0.0F, 0.0F, 0)));
        MacroLocationConfig spawn = new MacroLocationConfig(0, 70, 0, 45.0F, 10.0F, 0);

        config.setMacroSpawn(spawn);

        assertEquals(spawn, config.macroSpawn().orElseThrow());
        assertTrue(config.macroRewarps().isEmpty());
    }
}

package dev.hylfrd.farmhelper.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Set;

/** Loads and atomically saves the project-owned JSON configuration. */
public final class FarmHelperConfigStore {
    private static final int FIRST_SUPPORTED_SCHEMA_VERSION = 1;
    private static final Set<String> VERSION_ONE_ROOT_FIELDS = Set.of(
            "schemaVersion", "targetYaw", "targetPitch"
    );
    private static final Set<String> VERSION_TWO_ROOT_FIELDS = Set.of("schemaVersion", "rotation");
    private static final Set<String> VERSION_THREE_ROOT_FIELDS = Set.of("schemaVersion", "rotation", "ui");
    private static final Set<String> VERSION_FOUR_ROOT_FIELDS = Set.of("schemaVersion", "rotation", "ui", "macro");
    private static final Set<String> VERSION_FIVE_ROOT_FIELDS = VERSION_FOUR_ROOT_FIELDS;
    private static final Set<String> ROTATION_FIELDS = Set.of("targetYaw", "targetPitch");
    private static final Set<String> UI_FIELDS = Set.of("openSettingsKey");
    private static final Set<String> VERSION_FOUR_MACRO_FIELDS = Set.of("mode", "spawn", "rewarps");
    private static final Set<String> VERSION_FIVE_MACRO_FIELDS = Set.of(
            "mode", "spawn", "rewarps", "alwaysHoldW", "holdLeftClickWhenChangingRow");
    private static final Set<String> MACRO_FIELDS = Set.of(
            "mode", "spawn", "rewarps", "alwaysHoldW", "holdLeftClickWhenChangingRow",
            "rotateAfterWarped", "rotateAfterDrop", "dontFixAfterWarping",
            "customPitch", "customPitchLevel", "customYaw", "customYawLevel");
    private static final Set<String> LOCATION_FIELDS = Set.of("x", "y", "z", "yaw", "pitch", "plot");
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path configPath;
    private final AtomicReplacer atomicReplacer;

    public FarmHelperConfigStore(Path configPath) {
        this(configPath, FarmHelperConfigStore::replaceAtomically);
    }

    FarmHelperConfigStore(Path configPath, AtomicReplacer atomicReplacer) {
        this.configPath = Objects.requireNonNull(configPath, "configPath").toAbsolutePath().normalize();
        this.atomicReplacer = Objects.requireNonNull(atomicReplacer, "atomicReplacer");
    }

    public Path configPath() {
        return configPath;
    }

    public synchronized ConfigLoadResult load() {
        if (Files.notExists(configPath)) {
            FarmHelperConfig defaults = new FarmHelperConfig();
            try {
                save(defaults);
                return new ConfigLoadResult(defaults, ConfigLoadStatus.CREATED_DEFAULTS, null, "");
            } catch (IOException | RuntimeException exception) {
                return unsavedDefaults(exception);
            }
        }

        try {
            JsonObject root = readRoot();
            int schemaVersion = requiredSchemaVersion(root);
            if (schemaVersion > FarmHelperConfig.CURRENT_SCHEMA_VERSION) {
                throw new UnsupportedSchemaException(schemaVersion);
            }
            if (schemaVersion < FIRST_SUPPORTED_SCHEMA_VERSION) {
                throw new ConfigFormatException("Unsupported schema version " + schemaVersion);
            }

            FarmHelperConfig config = switch (schemaVersion) {
                case 1 -> migrateVersionOne(root);
                case 2 -> readVersionTwo(root);
                case 3 -> readVersionThree(root);
                case 4 -> readVersionFour(root);
                case 5 -> readVersionFive(root);
                case FarmHelperConfig.CURRENT_SCHEMA_VERSION -> readCurrent(root);
                default -> throw new ConfigFormatException("No migration path for schema version " + schemaVersion);
            };

            if (schemaVersion != FarmHelperConfig.CURRENT_SCHEMA_VERSION) {
                save(config);
                return new ConfigLoadResult(config, ConfigLoadStatus.MIGRATED, null,
                        "Migrated schema " + schemaVersion + " to " + FarmHelperConfig.CURRENT_SCHEMA_VERSION);
            }
            return new ConfigLoadResult(config, ConfigLoadStatus.LOADED, null, "");
        } catch (IOException | JsonParseException | IllegalArgumentException exception) {
            return recoverDefaults(exception);
        }
    }

    public synchronized void save(FarmHelperConfig config) throws IOException {
        Objects.requireNonNull(config, "config");
        FarmHelperConfig validated = FarmHelperConfig.fromPersisted(
                config.targetYaw(), config.targetPitch(), config.openSettingsKey(),
                config.macroMode(), config.macroSpawn().orElse(null), config.macroRewarps(),
                config.alwaysHoldW(), config.holdLeftClickWhenChangingRow(),
                config.rotateAfterWarped(), config.rotateAfterDrop(), config.dontFixAfterWarping(),
                config.customPitch(), config.customPitchLevel(), config.customYaw(),
                config.customYawLevel());
        byte[] json = (GSON.toJson(toJson(validated)) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);

        Path parent = configPath.getParent();
        if (parent == null) {
            throw new IOException("Configuration path has no parent directory");
        }
        Files.createDirectories(parent);

        Path temporary = Files.createTempFile(parent, configPath.getFileName().toString(), ".tmp");
        try {
            writeAndSync(temporary, json);
            atomicReplacer.replace(temporary, configPath);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private JsonObject readRoot() throws IOException {
        String json = Files.readString(configPath, StandardCharsets.UTF_8);
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonObject()) {
            throw new ConfigFormatException("Configuration root must be a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    private static FarmHelperConfig readCurrent(JsonObject root) {
        requireOnlyFields(root, VERSION_FIVE_ROOT_FIELDS, "configuration root");
        JsonObject rotation = optionalObject(root, "rotation");
        float yaw = 0.0F;
        float pitch = 0.0F;
        if (rotation != null) {
            requireOnlyFields(rotation, ROTATION_FIELDS, "rotation");
            yaw = optionalFloat(rotation, "targetYaw", 0.0F);
            pitch = optionalFloat(rotation, "targetPitch", 0.0F);
        }

        JsonObject ui = optionalObject(root, "ui");
        int openSettingsKey = FarmHelperConfig.DEFAULT_OPEN_SETTINGS_KEY;
        if (ui != null) {
            requireOnlyFields(ui, UI_FIELDS, "ui");
            openSettingsKey = optionalInteger(ui, "openSettingsKey", FarmHelperConfig.DEFAULT_OPEN_SETTINGS_KEY);
        }
        JsonObject macro = optionalObject(root, "macro");
        int mode = 0;
        MacroLocationConfig spawn = null;
        java.util.List<MacroLocationConfig> rewarps = java.util.List.of();
        boolean alwaysHoldW = false;
        boolean holdLeftClickWhenChangingRow = true;
        boolean rotateAfterWarped = false;
        boolean rotateAfterDrop = false;
        boolean dontFixAfterWarping = false;
        boolean customPitch = false;
        float customPitchLevel = 0.0F;
        boolean customYaw = false;
        float customYawLevel = 0.0F;
        if (macro != null) {
            requireOnlyFields(macro, MACRO_FIELDS, "macro");
            mode = optionalInteger(macro, "mode", 0);
            JsonObject spawnObject = optionalObject(macro, "spawn");
            spawn = spawnObject == null ? null : location(spawnObject, "macro.spawn");
            rewarps = locations(macro.get("rewarps"), "macro.rewarps");
            alwaysHoldW = optionalBoolean(macro, "alwaysHoldW", false);
            holdLeftClickWhenChangingRow = optionalBoolean(
                    macro, "holdLeftClickWhenChangingRow", true);
            rotateAfterWarped = optionalBoolean(macro, "rotateAfterWarped", false);
            rotateAfterDrop = optionalBoolean(macro, "rotateAfterDrop", false);
            dontFixAfterWarping = optionalBoolean(macro, "dontFixAfterWarping", false);
            customPitch = optionalBoolean(macro, "customPitch", false);
            customPitchLevel = optionalFloat(macro, "customPitchLevel", 0.0F);
            customYaw = optionalBoolean(macro, "customYaw", false);
            customYawLevel = optionalFloat(macro, "customYawLevel", 0.0F);
        }
        return FarmHelperConfig.fromPersisted(yaw, pitch, openSettingsKey, mode, spawn, rewarps,
                alwaysHoldW, holdLeftClickWhenChangingRow, rotateAfterWarped, rotateAfterDrop,
                dontFixAfterWarping, customPitch, customPitchLevel, customYaw, customYawLevel);
    }

    private static FarmHelperConfig readVersionFive(JsonObject root) {
        requireOnlyFields(root, VERSION_FIVE_ROOT_FIELDS, "schema 5 configuration root");
        JsonObject rotation = optionalObject(root, "rotation");
        JsonObject ui = optionalObject(root, "ui");
        JsonObject macro = optionalObject(root, "macro");
        float yaw = rotation == null ? 0.0F : optionalFloat(rotation, "targetYaw", 0.0F);
        float pitch = rotation == null ? 0.0F : optionalFloat(rotation, "targetPitch", 0.0F);
        if (rotation != null) {
            requireOnlyFields(rotation, ROTATION_FIELDS, "rotation");
        }
        int key = ui == null ? FarmHelperConfig.DEFAULT_OPEN_SETTINGS_KEY
                : optionalInteger(ui, "openSettingsKey", FarmHelperConfig.DEFAULT_OPEN_SETTINGS_KEY);
        if (ui != null) {
            requireOnlyFields(ui, UI_FIELDS, "ui");
        }
        int mode = 0;
        MacroLocationConfig spawn = null;
        java.util.List<MacroLocationConfig> rewarps = java.util.List.of();
        boolean alwaysHoldW = false;
        boolean holdLeftClickWhenChangingRow = true;
        if (macro != null) {
            requireOnlyFields(macro, VERSION_FIVE_MACRO_FIELDS, "schema 5 macro");
            mode = optionalInteger(macro, "mode", 0);
            JsonObject spawnObject = optionalObject(macro, "spawn");
            spawn = spawnObject == null ? null : location(spawnObject, "macro.spawn");
            rewarps = locations(macro.get("rewarps"), "macro.rewarps");
            alwaysHoldW = optionalBoolean(macro, "alwaysHoldW", false);
            holdLeftClickWhenChangingRow = optionalBoolean(
                    macro, "holdLeftClickWhenChangingRow", true);
        }
        return FarmHelperConfig.fromPersisted(yaw, pitch, key, mode, spawn, rewarps,
                alwaysHoldW, holdLeftClickWhenChangingRow);
    }

    private static FarmHelperConfig readVersionFour(JsonObject root) {
        requireOnlyFields(root, VERSION_FOUR_ROOT_FIELDS, "schema 4 configuration root");
        JsonObject rotation = optionalObject(root, "rotation");
        JsonObject ui = optionalObject(root, "ui");
        JsonObject macro = optionalObject(root, "macro");
        float yaw = rotation == null ? 0.0F : optionalFloat(rotation, "targetYaw", 0.0F);
        float pitch = rotation == null ? 0.0F : optionalFloat(rotation, "targetPitch", 0.0F);
        if (rotation != null) {
            requireOnlyFields(rotation, ROTATION_FIELDS, "rotation");
        }
        int key = ui == null ? FarmHelperConfig.DEFAULT_OPEN_SETTINGS_KEY
                : optionalInteger(ui, "openSettingsKey", FarmHelperConfig.DEFAULT_OPEN_SETTINGS_KEY);
        if (ui != null) {
            requireOnlyFields(ui, UI_FIELDS, "ui");
        }
        int mode = 0;
        MacroLocationConfig spawn = null;
        java.util.List<MacroLocationConfig> rewarps = java.util.List.of();
        if (macro != null) {
            requireOnlyFields(macro, VERSION_FOUR_MACRO_FIELDS, "macro");
            mode = optionalInteger(macro, "mode", 0);
            JsonObject spawnObject = optionalObject(macro, "spawn");
            spawn = spawnObject == null ? null : location(spawnObject, "macro.spawn");
            rewarps = locations(macro.get("rewarps"), "macro.rewarps");
        }
        return FarmHelperConfig.fromPersisted(yaw, pitch, key, mode, spawn, rewarps,
                false, true);
    }

    private static FarmHelperConfig readVersionThree(JsonObject root) {
        requireOnlyFields(root, VERSION_THREE_ROOT_FIELDS, "schema 3 configuration root");
        JsonObject rotation = optionalObject(root, "rotation");
        JsonObject ui = optionalObject(root, "ui");
        float yaw = rotation == null ? 0.0F : optionalFloat(rotation, "targetYaw", 0.0F);
        float pitch = rotation == null ? 0.0F : optionalFloat(rotation, "targetPitch", 0.0F);
        if (rotation != null) {
            requireOnlyFields(rotation, ROTATION_FIELDS, "rotation");
        }
        int key = ui == null ? FarmHelperConfig.DEFAULT_OPEN_SETTINGS_KEY
                : optionalInteger(ui, "openSettingsKey", FarmHelperConfig.DEFAULT_OPEN_SETTINGS_KEY);
        if (ui != null) {
            requireOnlyFields(ui, UI_FIELDS, "ui");
        }
        return FarmHelperConfig.fromPersisted(yaw, pitch, key);
    }

    private static FarmHelperConfig readVersionTwo(JsonObject root) {
        requireOnlyFields(root, VERSION_TWO_ROOT_FIELDS, "schema 2 configuration root");
        JsonObject rotation = optionalObject(root, "rotation");
        if (rotation == null) {
            return new FarmHelperConfig();
        }
        requireOnlyFields(rotation, ROTATION_FIELDS, "rotation");
        return FarmHelperConfig.fromPersisted(
                optionalFloat(rotation, "targetYaw", 0.0F),
                optionalFloat(rotation, "targetPitch", 0.0F));
    }

    private static FarmHelperConfig migrateVersionOne(JsonObject root) {
        requireOnlyFields(root, VERSION_ONE_ROOT_FIELDS, "schema 1 configuration root");
        float yaw = optionalFloat(root, "targetYaw", 0.0F);
        float pitch = optionalFloat(root, "targetPitch", 0.0F);
        return FarmHelperConfig.fromPersisted(yaw, pitch);
    }

    private ConfigLoadResult recoverDefaults(Exception cause) {
        FarmHelperConfig defaults = new FarmHelperConfig();
        Path backup;
        try {
            backup = nextBackupPath();
            Files.move(configPath, backup);
        } catch (IOException | RuntimeException backupFailure) {
            return new ConfigLoadResult(defaults, ConfigLoadStatus.DEFAULTS_NOT_SAVED, null,
                    diagnostic(cause) + "; backup failed: " + diagnostic(backupFailure));
        }

        try {
            save(defaults);
            return new ConfigLoadResult(defaults, ConfigLoadStatus.RECOVERED_DEFAULTS, backup, diagnostic(cause));
        } catch (IOException | RuntimeException saveFailure) {
            return new ConfigLoadResult(defaults, ConfigLoadStatus.DEFAULTS_NOT_SAVED, backup,
                    diagnostic(cause) + "; save failed: " + diagnostic(saveFailure));
        }
    }

    private ConfigLoadResult unsavedDefaults(Exception cause) {
        return new ConfigLoadResult(new FarmHelperConfig(), ConfigLoadStatus.DEFAULTS_NOT_SAVED, null,
                diagnostic(cause));
    }

    private Path nextBackupPath() {
        Path first = configPath.resolveSibling(configPath.getFileName() + ".corrupt");
        if (Files.notExists(first)) {
            return first;
        }
        for (int index = 1; index < Integer.MAX_VALUE; index++) {
            Path candidate = configPath.resolveSibling(configPath.getFileName() + ".corrupt." + index);
            if (Files.notExists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No available configuration backup name");
    }

    private static JsonObject toJson(FarmHelperConfig config) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", FarmHelperConfig.CURRENT_SCHEMA_VERSION);

        JsonObject rotation = new JsonObject();
        rotation.addProperty("targetYaw", config.targetYaw());
        rotation.addProperty("targetPitch", config.targetPitch());
        root.add("rotation", rotation);

        JsonObject ui = new JsonObject();
        ui.addProperty("openSettingsKey", config.openSettingsKey());
        root.add("ui", ui);

        JsonObject macro = new JsonObject();
        macro.addProperty("mode", config.macroMode());
        macro.add("spawn", config.macroSpawn().isPresent()
                ? locationJson(config.macroSpawn().orElseThrow())
                : com.google.gson.JsonNull.INSTANCE);
        JsonArray rewarps = new JsonArray();
        config.macroRewarps().forEach(rewarp -> rewarps.add(locationJson(rewarp)));
        macro.add("rewarps", rewarps);
        macro.addProperty("alwaysHoldW", config.alwaysHoldW());
        macro.addProperty("holdLeftClickWhenChangingRow",
                config.holdLeftClickWhenChangingRow());
        macro.addProperty("rotateAfterWarped", config.rotateAfterWarped());
        macro.addProperty("rotateAfterDrop", config.rotateAfterDrop());
        macro.addProperty("dontFixAfterWarping", config.dontFixAfterWarping());
        macro.addProperty("customPitch", config.customPitch());
        macro.addProperty("customPitchLevel", config.customPitchLevel());
        macro.addProperty("customYaw", config.customYaw());
        macro.addProperty("customYawLevel", config.customYawLevel());
        root.add("macro", macro);
        return root;
    }

    private static JsonObject locationJson(MacroLocationConfig location) {
        JsonObject value = new JsonObject();
        value.addProperty("x", location.x());
        value.addProperty("y", location.y());
        value.addProperty("z", location.z());
        value.addProperty("yaw", location.yaw());
        value.addProperty("pitch", location.pitch());
        value.addProperty("plot", location.plot());
        return value;
    }

    private static MacroLocationConfig location(JsonObject object, String name) {
        requireOnlyFields(object, LOCATION_FIELDS, name);
        return new MacroLocationConfig(
                requiredInteger(object, "x", name),
                requiredInteger(object, "y", name),
                requiredInteger(object, "z", name),
                optionalFloat(object, "yaw", 0.0F),
                optionalFloat(object, "pitch", 0.0F),
                optionalInteger(object, "plot", -1));
    }

    private static java.util.List<MacroLocationConfig> locations(JsonElement value, String name) {
        if (value == null || value.isJsonNull()) {
            return java.util.List.of();
        }
        if (!value.isJsonArray()) {
            throw new ConfigFormatException(name + " must be an array");
        }
        java.util.List<MacroLocationConfig> result = new java.util.ArrayList<>();
        for (JsonElement element : value.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new ConfigFormatException(name + " entries must be objects");
            }
            result.add(location(element.getAsJsonObject(), name + " entry"));
        }
        return java.util.List.copyOf(result);
    }

    private static int requiredInteger(JsonObject root, String field, String name) {
        if (!root.has(field)) {
            throw new ConfigFormatException(name + "." + field + " is required");
        }
        return optionalInteger(root, field, 0);
    }

    private static int requiredSchemaVersion(JsonObject root) {
        JsonElement value = root.get("schemaVersion");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ConfigFormatException("schemaVersion must be an integer");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ConfigFormatException("schemaVersion must be an integer", exception);
        }
    }

    private static JsonObject optionalObject(JsonObject root, String name) {
        JsonElement value = root.get(name);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonObject()) {
            throw new ConfigFormatException(name + " must be an object");
        }
        return value.getAsJsonObject();
    }

    private static float optionalFloat(JsonObject root, String name, float defaultValue) {
        JsonElement value = root.get(name);
        if (value == null || value.isJsonNull()) {
            return defaultValue;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ConfigFormatException(name + " must be a number");
        }
        try {
            BigDecimal decimal = value.getAsBigDecimal();
            float result = decimal.floatValue();
            if (!Float.isFinite(result)) {
                throw new ConfigFormatException(name + " must be finite");
            }
            return result;
        } catch (NumberFormatException exception) {
            throw new ConfigFormatException(name + " must be a number", exception);
        }
    }

    private static int optionalInteger(JsonObject root, String name, int defaultValue) {
        JsonElement value = root.get(name);
        if (value == null || value.isJsonNull()) {
            return defaultValue;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new ConfigFormatException(name + " must be an integer");
        }
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ConfigFormatException(name + " must be an integer", exception);
        }
    }

    private static boolean optionalBoolean(JsonObject root, String name, boolean defaultValue) {
        JsonElement value = root.get(name);
        if (value == null || value.isJsonNull()) {
            return defaultValue;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new ConfigFormatException(name + " must be a boolean");
        }
        return value.getAsBoolean();
    }

    private static void requireOnlyFields(JsonObject object, Set<String> allowedFields, String location) {
        for (String field : object.keySet()) {
            if (!allowedFields.contains(field)) {
                throw new ConfigFormatException("Unknown field " + location + "." + field);
            }
        }
    }

    private static void writeAndSync(Path path, byte[] content) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(content);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
            channel.force(true);
        }
    }

    private static void replaceAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String diagnostic(Exception exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private static class ConfigFormatException extends IllegalArgumentException {
        ConfigFormatException(String message) {
            super(message);
        }

        ConfigFormatException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class UnsupportedSchemaException extends ConfigFormatException {
        UnsupportedSchemaException(int version) {
            super("Configuration schema " + version + " is newer than supported schema "
                    + FarmHelperConfig.CURRENT_SCHEMA_VERSION);
        }
    }

    @FunctionalInterface
    interface AtomicReplacer {
        void replace(Path source, Path target) throws IOException;
    }
}

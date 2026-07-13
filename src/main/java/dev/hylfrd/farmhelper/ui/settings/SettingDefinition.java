package dev.hylfrd.farmhelper.ui.settings;

import dev.hylfrd.farmhelper.config.FarmHelperConfig;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

/** Strongly typed binding between one UI control and the configuration draft. */
public final class SettingDefinition<T> {
    private final String id;
    private final SettingCategory category;
    private final String label;
    private final String description;
    private final SettingKind kind;
    private final Function<FarmHelperConfig, T> reader;
    private final BiConsumer<FarmHelperConfig, T> writer;
    private final Consumer<FarmHelperConfig> action;
    private final Function<T, String> validator;
    private final List<T> choices;
    private final String searchText;

    private SettingDefinition(
            String id,
            SettingCategory category,
            String label,
            String description,
            SettingKind kind,
            Function<FarmHelperConfig, T> reader,
            BiConsumer<FarmHelperConfig, T> writer,
            Consumer<FarmHelperConfig> action,
            Function<T, String> validator,
            List<T> choices,
            List<String> keywords
    ) {
        this.id = requireId(id);
        this.category = Objects.requireNonNull(category, "category");
        this.label = requireText(label, "label");
        this.description = requireText(description, "description");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.reader = reader;
        this.writer = writer;
        this.action = action;
        this.validator = validator;
        this.choices = List.copyOf(choices);
        this.searchText = String.join(" ", id, label, description, String.join(" ", keywords))
                .toLowerCase(Locale.ROOT);
    }

    public static SettingDefinition<Boolean> bool(
            String id, SettingCategory category, String label, String description,
            Function<FarmHelperConfig, Boolean> reader, BiConsumer<FarmHelperConfig, Boolean> writer,
            String... keywords
    ) {
        return value(id, category, label, description, SettingKind.BOOLEAN, reader, writer,
                ignored -> "", List.of(), keywords);
    }

    public static SettingDefinition<Integer> integer(
            String id, SettingCategory category, String label, String description, int minimum, int maximum,
            Function<FarmHelperConfig, Integer> reader, BiConsumer<FarmHelperConfig, Integer> writer,
            String... keywords
    ) {
        if (minimum > maximum) {
            throw new IllegalArgumentException("minimum must not exceed maximum");
        }
        return value(id, category, label, description, SettingKind.INTEGER, reader, writer,
                number -> number < minimum || number > maximum
                        ? "Value must be between " + minimum + " and " + maximum + "." : "",
                List.of(), keywords);
    }

    public static SettingDefinition<Double> decimal(
            String id, SettingCategory category, String label, String description, double minimum, double maximum,
            Function<FarmHelperConfig, Double> reader, BiConsumer<FarmHelperConfig, Double> writer,
            String... keywords
    ) {
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum > maximum) {
            throw new IllegalArgumentException("decimal range must be finite and ordered");
        }
        return value(id, category, label, description, SettingKind.DECIMAL, reader, writer,
                number -> !Double.isFinite(number) || number < minimum || number > maximum
                        ? "Value must be between " + minimum + " and " + maximum + "." : "",
                List.of(), keywords);
    }

    public static <E extends Enum<E>> SettingDefinition<E> enumeration(
            String id, SettingCategory category, String label, String description, List<E> choices,
            Function<FarmHelperConfig, E> reader, BiConsumer<FarmHelperConfig, E> writer,
            String... keywords
    ) {
        List<E> allowed = List.copyOf(choices);
        if (allowed.isEmpty()) {
            throw new IllegalArgumentException("enum choices must not be empty");
        }
        return value(id, category, label, description, SettingKind.ENUM, reader, writer,
                choice -> allowed.contains(choice) ? "" : "Value is not an allowed choice.", allowed, keywords);
    }

    public static SettingDefinition<String> string(
            String id, SettingCategory category, String label, String description, int maximumLength,
            Function<FarmHelperConfig, String> reader, BiConsumer<FarmHelperConfig, String> writer,
            String... keywords
    ) {
        if (maximumLength < 1) {
            throw new IllegalArgumentException("maximumLength must be positive");
        }
        return value(id, category, label, description, SettingKind.STRING, reader, writer,
                text -> text.length() > maximumLength ? "Text is too long." : "", List.of(), keywords);
    }

    public static SettingDefinition<Integer> color(
            String id, SettingCategory category, String label, String description,
            Function<FarmHelperConfig, Integer> reader, BiConsumer<FarmHelperConfig, Integer> writer,
            String... keywords
    ) {
        return value(id, category, label, description, SettingKind.COLOR, reader, writer,
                color -> color < 0 || color > 0xFFFFFF ? "Color must be between #000000 and #FFFFFF." : "",
                List.of(), keywords);
    }

    public static SettingDefinition<Integer> keybind(
            String id, SettingCategory category, String label, String description,
            Function<FarmHelperConfig, Integer> reader, BiConsumer<FarmHelperConfig, Integer> writer,
            String... keywords
    ) {
        return value(id, category, label, description, SettingKind.KEYBIND, reader, writer,
                key -> key != -1 && (key < 32 || key > 348) ? "Key code is not supported." : "",
                List.of(), keywords);
    }

    public static SettingDefinition<Void> action(
            String id, SettingCategory category, String label, String description,
            Consumer<FarmHelperConfig> action, String... keywords
    ) {
        return new SettingDefinition<>(id, category, label, description, SettingKind.ACTION,
                null, null, Objects.requireNonNull(action, "action"), ignored -> "", List.of(), List.of(keywords));
    }

    private static <T> SettingDefinition<T> value(
            String id, SettingCategory category, String label, String description, SettingKind kind,
            Function<FarmHelperConfig, T> reader, BiConsumer<FarmHelperConfig, T> writer,
            Function<T, String> validator, List<T> choices, String... keywords
    ) {
        return new SettingDefinition<>(id, category, label, description, kind,
                Objects.requireNonNull(reader, "reader"), Objects.requireNonNull(writer, "writer"), null,
                Objects.requireNonNull(validator, "validator"), choices, List.of(keywords));
    }

    public String id() {
        return id;
    }

    public SettingCategory category() {
        return category;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public SettingKind kind() {
        return kind;
    }

    public List<T> choices() {
        return choices;
    }

    public boolean matches(String normalizedQuery) {
        return normalizedQuery.isBlank() || searchText.contains(normalizedQuery);
    }

    public T read(FarmHelperConfig config) {
        if (reader == null) {
            throw new IllegalStateException("Action settings do not have a value");
        }
        return reader.apply(config);
    }

    public void write(FarmHelperConfig config, T value) {
        Objects.requireNonNull(value, "value");
        if (writer == null) {
            throw new IllegalStateException("Action settings do not accept values");
        }
        String error = validator.apply(value);
        if (!error.isBlank()) {
            throw new IllegalArgumentException(error);
        }
        writer.accept(config, value);
    }

    public void reset(FarmHelperConfig config) {
        if (reader != null) {
            write(config, read(new FarmHelperConfig()));
        }
    }

    public void activate(FarmHelperConfig config) {
        if (action == null) {
            throw new IllegalStateException("Only action settings can be activated");
        }
        action.accept(config);
    }

    private static String requireId(String id) {
        String value = requireText(id, "id");
        if (!value.matches("[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)*")) {
            throw new IllegalArgumentException("setting id must be stable lower camel case segments");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}

package dev.hylfrd.farmhelper.macro;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Explicit family registry; recognized-but-unimplemented modes never receive placeholders. */
public final class MacroRegistry {
    private final Map<MacroFamily, Supplier<? extends Macro>> factories;

    public MacroRegistry(Map<MacroFamily, Supplier<? extends Macro>> factories) {
        Objects.requireNonNull(factories, "factories");
        EnumMap<MacroFamily, Supplier<? extends Macro>> copy = new EnumMap<>(MacroFamily.class);
        factories.forEach((family, factory) -> copy.put(
                Objects.requireNonNull(family, "family"),
                Objects.requireNonNull(factory, "factory")));
        this.factories = Map.copyOf(copy);
    }

    public Optional<Macro> create(MacroMode mode) {
        Objects.requireNonNull(mode, "mode");
        Supplier<? extends Macro> factory = factories.get(mode.family());
        return factory == null ? Optional.empty() : Optional.of(
                Objects.requireNonNull(factory.get(), "macro factory result"));
    }

    public boolean implemented(MacroMode mode) {
        return factories.containsKey(Objects.requireNonNull(mode, "mode").family());
    }
}

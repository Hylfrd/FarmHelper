package dev.hylfrd.farmhelper.control.inventory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Plain immutable projection of the modern name, lore, and custom-data components. */
public record ItemComponentSummary(
        Optional<String> customName,
        Optional<String> itemName,
        List<String> lore,
        Optional<String> customData) {
    public ItemComponentSummary {
        customName = copyText(customName, "customName");
        itemName = copyText(itemName, "itemName");
        Objects.requireNonNull(lore, "lore");
        lore = lore.stream().map(line -> Objects.requireNonNull(line, "lore line")).toList();
        customData = copyText(customData, "customData");
    }

    public static ItemComponentSummary empty() {
        return new ItemComponentSummary(Optional.empty(), Optional.empty(), List.of(), Optional.empty());
    }

    public Optional<String> displayName() {
        return customName.or(() -> itemName);
    }

    private static Optional<String> copyText(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        return value.map(text -> Objects.requireNonNull(text, name + " value"));
    }
}

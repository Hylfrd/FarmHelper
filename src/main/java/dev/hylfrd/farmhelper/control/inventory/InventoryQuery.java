package dev.hylfrd.farmhelper.control.inventory;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** Immutable item/slot query replacing upstream mutable Slot predicates. */
public record InventoryQuery(
        OptionalInt menuSlot,
        Optional<String> itemIdentifier,
        Optional<String> displayName,
        MatchMode nameMatch,
        Optional<String> loreContains,
        Optional<String> customDataContains,
        boolean hotbarOnly,
        boolean caseSensitive) {
    public enum MatchMode {
        EXACT,
        CONTAINS
    }

    public InventoryQuery {
        Objects.requireNonNull(menuSlot, "menuSlot");
        itemIdentifier = normalized(itemIdentifier, "itemIdentifier");
        displayName = normalized(displayName, "displayName");
        Objects.requireNonNull(nameMatch, "nameMatch");
        loreContains = normalized(loreContains, "loreContains");
        customDataContains = normalized(customDataContains, "customDataContains");
        if (menuSlot.isPresent() && menuSlot.getAsInt() < 0) {
            throw new IllegalArgumentException("menuSlot must not be negative");
        }
    }

    public static InventoryQuery anyItem() {
        return new InventoryQuery(OptionalInt.empty(), Optional.empty(), Optional.empty(),
                MatchMode.CONTAINS, Optional.empty(), Optional.empty(), false, false);
    }

    public static InventoryQuery slot(int menuSlot) {
        return anyItem().atSlot(menuSlot);
    }

    public InventoryQuery atSlot(int slot) {
        return new InventoryQuery(OptionalInt.of(slot), itemIdentifier, displayName, nameMatch,
                loreContains, customDataContains, hotbarOnly, caseSensitive);
    }

    public InventoryQuery withIdentifier(String identifier) {
        return new InventoryQuery(menuSlot, Optional.of(identifier), displayName, nameMatch,
                loreContains, customDataContains, hotbarOnly, caseSensitive);
    }

    public InventoryQuery withDisplayName(String name, MatchMode match) {
        return new InventoryQuery(menuSlot, itemIdentifier, Optional.of(name), match,
                loreContains, customDataContains, hotbarOnly, caseSensitive);
    }

    public InventoryQuery withLoreContaining(String text) {
        return new InventoryQuery(menuSlot, itemIdentifier, displayName, nameMatch,
                Optional.of(text), customDataContains, hotbarOnly, caseSensitive);
    }

    public InventoryQuery withCustomDataContaining(String text) {
        return new InventoryQuery(menuSlot, itemIdentifier, displayName, nameMatch,
                loreContains, Optional.of(text), hotbarOnly, caseSensitive);
    }

    public InventoryQuery inHotbar() {
        return new InventoryQuery(menuSlot, itemIdentifier, displayName, nameMatch,
                loreContains, customDataContains, true, caseSensitive);
    }

    public InventoryQuery respectingCase() {
        return new InventoryQuery(menuSlot, itemIdentifier, displayName, nameMatch,
                loreContains, customDataContains, hotbarOnly, true);
    }

    public boolean matches(InventorySlot slot) {
        Objects.requireNonNull(slot, "slot");
        if (menuSlot.isPresent() && slot.menuSlot() != menuSlot.getAsInt()) {
            return false;
        }
        if (hotbarOnly && slot.hotbarSelection().isEmpty()) {
            return false;
        }
        if (!slot.item().isPresent()) {
            return false;
        }
        InventoryItem item = slot.item().get();
        if (itemIdentifier.isPresent()
                && !equalsText(item.summary().identifier().toString(), itemIdentifier.orElseThrow())) {
            return false;
        }
        if (displayName.isPresent()) {
            Optional<String> actualName = item.components().displayName();
            if (actualName.isEmpty() || !matchesText(actualName.orElseThrow(), displayName.orElseThrow(), nameMatch)) {
                return false;
            }
        }
        if (loreContains.isPresent() && item.components().lore().stream()
                .noneMatch(line -> containsText(line, loreContains.orElseThrow()))) {
            return false;
        }
        return customDataContains.isEmpty()
                || item.components().customData()
                        .filter(data -> containsText(data, customDataContains.orElseThrow()))
                        .isPresent();
    }

    private boolean matchesText(String actual, String expected, MatchMode match) {
        return match == MatchMode.EXACT ? equalsText(actual, expected) : containsText(actual, expected);
    }

    private boolean equalsText(String actual, String expected) {
        return canonical(actual).equals(canonical(expected));
    }

    private boolean containsText(String actual, String expected) {
        return canonical(actual).contains(canonical(expected));
    }

    private String canonical(String value) {
        return caseSensitive ? value : value.toLowerCase(Locale.ROOT);
    }

    private static Optional<String> normalized(Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        return value.map(text -> {
            Objects.requireNonNull(text, name + " value");
            if (text.isEmpty()) {
                throw new IllegalArgumentException(name + " must not be empty");
            }
            return text;
        });
    }
}

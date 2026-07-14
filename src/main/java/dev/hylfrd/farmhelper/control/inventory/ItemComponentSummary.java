package dev.hylfrd.farmhelper.control.inventory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Bounded, formatting-free public projection of modern display text components. */
public record ItemComponentSummary(
        Optional<String> customName,
        Optional<String> itemName,
        List<String> lore) {
    public static final int MAX_NAME_CODE_POINTS = 128;
    public static final int MAX_LORE_LINES = 16;
    public static final int MAX_LORE_CODE_POINTS = 256;

    public ItemComponentSummary {
        customName = sanitizeOptional(customName, "customName", MAX_NAME_CODE_POINTS);
        itemName = sanitizeOptional(itemName, "itemName", MAX_NAME_CODE_POINTS);
        Objects.requireNonNull(lore, "lore");
        lore = lore.stream()
                .limit(MAX_LORE_LINES)
                .map(line -> sanitize(Objects.requireNonNull(line, "lore line"), MAX_LORE_CODE_POINTS))
                .toList();
    }

    public static ItemComponentSummary empty() {
        return new ItemComponentSummary(Optional.empty(), Optional.empty(), List.of());
    }

    public Optional<String> displayName() {
        return customName.or(() -> itemName);
    }

    private static Optional<String> sanitizeOptional(Optional<String> value, String name, int limit) {
        Objects.requireNonNull(value, name);
        return value.map(text -> sanitize(Objects.requireNonNull(text, name + " value"), limit));
    }

    /** Removes legacy formatting, control/format characters, and bounds retained code points. */
    public static String sanitize(String value, int maximumCodePoints) {
        Objects.requireNonNull(value, "value");
        if (maximumCodePoints < 0) {
            throw new IllegalArgumentException("maximumCodePoints must not be negative");
        }
        StringBuilder sanitized = new StringBuilder(Math.min(value.length(), maximumCodePoints));
        int retained = 0;
        boolean skipFormattingCode = false;
        for (int offset = 0; offset < value.length() && retained < maximumCodePoints;) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (skipFormattingCode) {
                skipFormattingCode = false;
                continue;
            }
            if (codePoint == 0x00A7) {
                skipFormattingCode = true;
                continue;
            }
            int type = Character.getType(codePoint);
            if (Character.isISOControl(codePoint) || type == Character.FORMAT
                    || type == Character.PRIVATE_USE || type == Character.SURROGATE) {
                continue;
            }
            sanitized.appendCodePoint(codePoint);
            retained++;
        }
        return sanitized.toString();
    }
}

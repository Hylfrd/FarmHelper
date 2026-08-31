package dev.hylfrd.farmhelper.feature.jacob;

import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Crop names recognized by the fixed upstream Jacob scoreboard vocabulary. */
public enum JacobCrop {
    WHEAT,
    CARROT,
    POTATO,
    NETHER_WART,
    SUGAR_CANE,
    MUSHROOM,
    MELON,
    PUMPKIN,
    COCOA_BEANS,
    CACTUS;

    /**
     * Converts one cleaned server line using the same token families as upstream
     * {@code GameStateHandler#convertCrop(String)}.
     */
    public static Optional<JacobCrop> fromLine(String line) {
        Objects.requireNonNull(line, "line");
        String upper = line.toUpperCase(Locale.ROOT);
        if (upper.contains("WHEAT")) {
            return Optional.of(WHEAT);
        }
        if (upper.contains("CARROT")) {
            return Optional.of(CARROT);
        }
        if (upper.contains("POTATO")) {
            return Optional.of(POTATO);
        }
        if (upper.contains("NETHER") || upper.contains("WART")) {
            return Optional.of(NETHER_WART);
        }
        if (upper.contains("SUGAR") || upper.contains("CANE")) {
            return Optional.of(SUGAR_CANE);
        }
        if (upper.contains("MUSHROOM")) {
            return Optional.of(MUSHROOM);
        }
        if (upper.contains("MELON")) {
            return Optional.of(MELON);
        }
        if (upper.contains("PUMPKIN")) {
            return Optional.of(PUMPKIN);
        }
        if (upper.contains("COCOA") || upper.contains("BEAN")) {
            return Optional.of(COCOA_BEANS);
        }
        if (upper.contains("CACTUS")) {
            return Optional.of(CACTUS);
        }
        return Optional.empty();
    }
}

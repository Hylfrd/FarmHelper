package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.runtime.spatial.CropBlockKind;

import java.util.Optional;

/** Macro-domain crop identity, deliberately separate from raw block and scoreboard models. */
public enum MacroCrop {
    WHEAT,
    CARROT,
    POTATO,
    NETHER_WART,
    PUMPKIN,
    MELON,
    CACTUS,
    SUGAR_CANE,
    COCOA,
    RED_MUSHROOM,
    BROWN_MUSHROOM;

    public static Optional<MacroCrop> fromBlockKind(CropBlockKind kind) {
        return switch (kind) {
            case WHEAT -> Optional.of(WHEAT);
            case CARROT -> Optional.of(CARROT);
            case POTATO -> Optional.of(POTATO);
            case NETHER_WART -> Optional.of(NETHER_WART);
            case PUMPKIN -> Optional.of(PUMPKIN);
            case MELON -> Optional.of(MELON);
            case CACTUS -> Optional.of(CACTUS);
            case SUGAR_CANE -> Optional.of(SUGAR_CANE);
            case COCOA -> Optional.of(COCOA);
            case RED_MUSHROOM -> Optional.of(RED_MUSHROOM);
            case BROWN_MUSHROOM -> Optional.of(BROWN_MUSHROOM);
            default -> Optional.empty();
        };
    }
}

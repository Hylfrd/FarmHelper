package dev.hylfrd.farmhelper.macro;

import java.util.Optional;

/** Stable persisted mode identifiers from the fixed upstream macro dispatch table. */
public enum MacroMode {
    VERTICAL_NORMAL(0, MacroFamily.VERTICAL_S_SHAPE),
    VERTICAL_PUMPKIN_MELON(1, MacroFamily.VERTICAL_S_SHAPE),
    VERTICAL_MELONGKINGDE(2, MacroFamily.VERTICAL_S_SHAPE),
    MELON_PUMPKIN_DEFAULT(3, MacroFamily.MELON_PUMPKIN_DEFAULT),
    SUGAR_CANE(4, MacroFamily.SUGAR_CANE),
    VERTICAL_CACTUS(5, MacroFamily.VERTICAL_S_SHAPE),
    VERTICAL_SUNTZU(6, MacroFamily.VERTICAL_S_SHAPE),
    COCOA(7, MacroFamily.COCOA),
    COCOA_TRAPDOORS(8, MacroFamily.COCOA),
    VERTICAL_COCOA_LEFT_RIGHT(9, MacroFamily.VERTICAL_S_SHAPE),
    MUSHROOM(10, MacroFamily.MUSHROOM),
    MUSHROOM_ROTATE(11, MacroFamily.MUSHROOM_ROTATE),
    MUSHROOM_SDS(12, MacroFamily.MUSHROOM_SDS),
    CIRCULAR(13, MacroFamily.CIRCULAR);

    private final int code;
    private final MacroFamily family;

    MacroMode(int code, MacroFamily family) {
        this.code = code;
        this.family = family;
    }

    public int code() {
        return code;
    }

    public MacroFamily family() {
        return family;
    }

    public Optional<VerticalCropMode> verticalMode() {
        return VerticalCropMode.fromCode(code);
    }

    public static Optional<MacroMode> fromCode(int code) {
        return code < 0 || code >= values().length
                ? Optional.empty()
                : Optional.of(values()[code]);
    }
}

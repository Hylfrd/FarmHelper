package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.runtime.spatial.CropBlockKind;

import java.util.Optional;
import java.util.Set;

/** The six upstream vertical S-shape modes that are safe to select in P1. */
public enum VerticalCropMode {
    NORMAL(0, 2.8F, 0.5F, false, Set.of(
            CropBlockKind.WHEAT, CropBlockKind.CARROT, CropBlockKind.POTATO)),
    PUMPKIN_MELON(1, 28.0F, 2.0F, true, Set.of(
            CropBlockKind.PUMPKIN, CropBlockKind.MELON)),
    MELONGKINGDE(2, -59.2F, 1.0F, false, Set.of(
            CropBlockKind.PUMPKIN, CropBlockKind.MELON)),
    CACTUS_NETHER_WART(5, 0.0F, 0.5F, false, Set.of(
            CropBlockKind.CACTUS, CropBlockKind.NETHER_WART)),
    SUNTZU(6, -38.0F, -1.5F, true, Set.of(CropBlockKind.CACTUS)),
    COCOA(9, -90.0F, 0.0F, false, Set.of(CropBlockKind.COCOA));

    private final int code;
    private final float pitch;
    private final float pitchJitter;
    private final boolean forwardAssist;
    private final Set<CropBlockKind> crops;

    VerticalCropMode(
            int code,
            float pitch,
            float pitchJitter,
            boolean forwardAssist,
            Set<CropBlockKind> crops
    ) {
        this.code = code;
        this.pitch = pitch;
        this.pitchJitter = pitchJitter;
        this.forwardAssist = forwardAssist;
        this.crops = Set.copyOf(crops);
    }

    public int code() {
        return code;
    }

    public float targetPitch(double randomUnit) {
        if (!Double.isFinite(randomUnit) || randomUnit < 0.0D || randomUnit >= 1.0D) {
            throw new IllegalArgumentException("randomUnit must be in [0, 1)");
        }
        float result = (float) ((double) pitch + (double) pitchJitter * randomUnit);
        float endpoint = pitch + pitchJitter;
        if (pitchJitter > 0.0F && result >= endpoint) {
            return Math.nextDown(endpoint);
        }
        if (pitchJitter < 0.0F && result <= endpoint) {
            return Math.nextUp(endpoint);
        }
        return result;
    }

    public boolean forwardAssist() {
        return forwardAssist;
    }

    public boolean accepts(CropBlockKind kind) {
        return compatibility(kind) == CropCompatibility.COMPATIBLE;
    }

    public CropCompatibility compatibility(CropBlockKind kind) {
        return crops.contains(kind) ? CropCompatibility.COMPATIBLE : CropCompatibility.INCOMPATIBLE;
    }

    public boolean cactusMode() {
        return this == CACTUS_NETHER_WART || this == SUNTZU;
    }

    public static Optional<VerticalCropMode> fromCode(int code) {
        for (VerticalCropMode mode : values()) {
            if (mode.code == code) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}

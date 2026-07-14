package dev.hylfrd.farmhelper.runtime.spatial;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

import java.util.Map;
import java.util.Objects;

/** Crop kind and harvest maturity derived solely from an immutable block-state snapshot. */
public record CropObservation(CropBlockKind kind, boolean mature, boolean directlyHarvestable) {
    private static final Map<String, CropBlockKind> AGE_SEVEN = Map.of(
            "wheat", CropBlockKind.WHEAT,
            "carrots", CropBlockKind.CARROT,
            "potatoes", CropBlockKind.POTATO);
    private static final Map<String, CropBlockKind> ALWAYS_MATURE = Map.ofEntries(
            Map.entry("cactus", CropBlockKind.CACTUS),
            Map.entry("sugar_cane", CropBlockKind.SUGAR_CANE),
            Map.entry("melon", CropBlockKind.MELON),
            Map.entry("pumpkin", CropBlockKind.PUMPKIN),
            Map.entry("red_mushroom", CropBlockKind.RED_MUSHROOM),
            Map.entry("brown_mushroom", CropBlockKind.BROWN_MUSHROOM));
    private static final Map<String, CropBlockKind> STEMS = Map.of(
            "melon_stem", CropBlockKind.MELON_STEM,
            "pumpkin_stem", CropBlockKind.PUMPKIN_STEM,
            "attached_melon_stem", CropBlockKind.ATTACHED_MELON_STEM,
            "attached_pumpkin_stem", CropBlockKind.ATTACHED_PUMPKIN_STEM);

    public CropObservation {
        Objects.requireNonNull(kind, "kind");
        if (mature && !directlyHarvestable) {
            throw new IllegalArgumentException("a non-harvestable stem cannot be mature");
        }
    }

    public static Observation<CropObservation> observe(BlockStateSnapshot state) {
        Objects.requireNonNull(state, "state");
        if (!"minecraft".equals(state.blockId().namespace())) {
            return Observation.unknown();
        }
        String path = state.blockId().path();
        CropBlockKind ageSeven = AGE_SEVEN.get(path);
        if (ageSeven != null) {
            return ageBased(state, ageSeven, 7);
        }
        if ("nether_wart".equals(path)) {
            return ageBased(state, CropBlockKind.NETHER_WART, 3);
        }
        if ("cocoa".equals(path)) {
            return ageBased(state, CropBlockKind.COCOA, 2);
        }
        CropBlockKind alwaysMature = ALWAYS_MATURE.get(path);
        if (alwaysMature != null) {
            return Observation.present(new CropObservation(alwaysMature, true, true));
        }
        CropBlockKind stem = STEMS.get(path);
        if (stem != null) {
            return Observation.present(new CropObservation(stem, false, false));
        }
        return Observation.absent();
    }

    private static Observation<CropObservation> ageBased(
            BlockStateSnapshot state,
            CropBlockKind kind,
            int matureAge
    ) {
        String value = state.properties().get("age");
        if (value == null) {
            return Observation.unknown();
        }
        final int age;
        try {
            age = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return Observation.unknown();
        }
        if (age < 0 || age > matureAge) {
            return Observation.unknown();
        }
        return Observation.present(new CropObservation(kind, age == matureAge, true));
    }
}

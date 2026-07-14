package dev.hylfrd.farmhelper.runtime.spatial;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CropObservationTest {
    @Test
    void ageSevenCropsHaveExactMaturity() {
        assertAgeCrop("wheat", CropBlockKind.WHEAT, 7);
        assertAgeCrop("carrots", CropBlockKind.CARROT, 7);
        assertAgeCrop("potatoes", CropBlockKind.POTATO, 7);
    }

    @Test
    void netherWartAndCocoaHaveTheirOwnMaximumAge() {
        assertAgeCrop("nether_wart", CropBlockKind.NETHER_WART, 3);
        assertAgeCrop("cocoa", CropBlockKind.COCOA, 2);
    }

    @Test
    void presenceMatureCropsNeedNoAgeProperty() {
        Map<String, CropBlockKind> crops = Map.ofEntries(
                Map.entry("cactus", CropBlockKind.CACTUS),
                Map.entry("sugar_cane", CropBlockKind.SUGAR_CANE),
                Map.entry("melon", CropBlockKind.MELON),
                Map.entry("pumpkin", CropBlockKind.PUMPKIN),
                Map.entry("red_mushroom", CropBlockKind.RED_MUSHROOM),
                Map.entry("brown_mushroom", CropBlockKind.BROWN_MUSHROOM));

        crops.forEach((id, kind) -> {
            CropObservation crop = observed(id, Map.of()).get();
            assertEquals(kind, crop.kind());
            assertTrue(crop.mature());
            assertTrue(crop.directlyHarvestable());
        });
    }

    @Test
    void stemsAreRelatedButNeverDirectlyHarvestable() {
        Map<String, CropBlockKind> stems = Map.of(
                "melon_stem", CropBlockKind.MELON_STEM,
                "pumpkin_stem", CropBlockKind.PUMPKIN_STEM,
                "attached_melon_stem", CropBlockKind.ATTACHED_MELON_STEM,
                "attached_pumpkin_stem", CropBlockKind.ATTACHED_PUMPKIN_STEM);

        stems.forEach((id, kind) -> {
            CropObservation crop = observed(id, Map.of("age", "7")).get();
            assertEquals(kind, crop.kind());
            assertFalse(crop.mature());
            assertFalse(crop.directlyHarvestable());
        });
    }

    @Test
    void nonCropUnknownIdsAndInvalidPropertiesStayDistinct() {
        assertTrue(observed("stone", Map.of()).isAbsent());
        assertTrue(CropObservation.observe(state("example:future_crop", Map.of())).isUnknown());
        assertTrue(observed("wheat", Map.of()).isUnknown());
        assertTrue(observed("wheat", Map.of("age", "ripe")).isUnknown());
        assertTrue(observed("wheat", Map.of("age", "-1")).isUnknown());
        assertTrue(observed("wheat", Map.of("age", "8")).isUnknown());
        assertTrue(observed("nether_wart", Map.of("age", "4")).isUnknown());
        assertTrue(observed("cocoa", Map.of("age", "3")).isUnknown());
    }

    private static void assertAgeCrop(String id, CropBlockKind kind, int maximumAge) {
        for (int age = 0; age <= maximumAge; age++) {
            CropObservation crop = observed(id, Map.of("age", Integer.toString(age))).get();
            assertEquals(kind, crop.kind());
            assertEquals(age == maximumAge, crop.mature());
            assertTrue(crop.directlyHarvestable());
        }
    }

    private static Observation<CropObservation> observed(String path, Map<String, String> properties) {
        return CropObservation.observe(state("minecraft:" + path, properties));
    }

    private static BlockStateSnapshot state(String identifier, Map<String, String> properties) {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse(identifier),
                properties,
                SpatialTestFixtures.EMPTY_FLUID,
                Observation.present(CollisionShapeSnapshot.EMPTY));
    }
}

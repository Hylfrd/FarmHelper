package dev.hylfrd.farmhelper.feature.bps;

import dev.hylfrd.farmhelper.macro.MacroCrop;
import dev.hylfrd.farmhelper.macro.MacroPauseCause;
import dev.hylfrd.farmhelper.macro.MacroTerminalReason;
import dev.hylfrd.farmhelper.runtime.interaction.BlockInteractionFace;
import dev.hylfrd.farmhelper.runtime.interaction.BlockInteractionSignal;
import dev.hylfrd.farmhelper.runtime.interaction.BlockInteractionSignalType;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.CollisionShapeSnapshot;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BpsTrackerTest {
    private static final long GENERATION = 4L;
    private static final long WORLD_EPOCH = 7L;
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1L);

    @Test
    void countsOnlySuccessfulMatchingBreaksAndDoesNotRequireCropMaturity() {
        BpsTracker tracker = startedTracker();

        assertEquals(BpsRecordResult.NOT_BREAK_SUCCESS,
                tracker.record(click(), MacroCrop.WHEAT, 0L));
        assertEquals(BpsRecordResult.BLOCK_STATE_UNAVAILABLE,
                tracker.record(success(Observation.unknown()), MacroCrop.WHEAT, 0L));
        assertEquals(BpsRecordResult.BLOCK_STATE_UNAVAILABLE,
                tracker.record(success(Observation.absent()), MacroCrop.WHEAT, 0L));
        assertEquals(BpsRecordResult.CROP_MISMATCH,
                tracker.record(success("minecraft:stone"), MacroCrop.WHEAT, 0L));
        assertEquals(BpsRecordResult.CROP_MISMATCH,
                tracker.record(success("minecraft:carrots"), MacroCrop.WHEAT, 0L));

        assertEquals(BpsRecordResult.COUNTED,
                tracker.record(
                        success(block("minecraft:wheat", Map.of("age", "0"))),
                        MacroCrop.WHEAT,
                        0L));

        assertEquals(new BpsSnapshot(1.0D, 1, SECOND, false), tracker.snapshot(SECOND));
    }

    @Test
    void mapsEveryMacroCropAndPreservesUpstreamCombinedMushroomCounting() {
        Map<MacroCrop, String> blocks = new LinkedHashMap<>();
        blocks.put(MacroCrop.WHEAT, "minecraft:wheat");
        blocks.put(MacroCrop.CARROT, "minecraft:carrots");
        blocks.put(MacroCrop.POTATO, "minecraft:potatoes");
        blocks.put(MacroCrop.NETHER_WART, "minecraft:nether_wart");
        blocks.put(MacroCrop.PUMPKIN, "minecraft:pumpkin");
        blocks.put(MacroCrop.MELON, "minecraft:melon");
        blocks.put(MacroCrop.CACTUS, "minecraft:cactus");
        blocks.put(MacroCrop.SUGAR_CANE, "minecraft:sugar_cane");
        blocks.put(MacroCrop.COCOA, "minecraft:cocoa");
        blocks.put(MacroCrop.RED_MUSHROOM, "minecraft:brown_mushroom");
        blocks.put(MacroCrop.BROWN_MUSHROOM, "minecraft:red_mushroom");
        BpsTracker tracker = startedTracker();

        blocks.forEach((crop, blockId) -> assertEquals(
                BpsRecordResult.COUNTED,
                tracker.record(success(blockId), crop, SECOND),
                crop.name()));

        BpsSnapshot snapshot = tracker.snapshot(2L * SECOND);
        assertEquals(blocks.size(), snapshot.retainedBlocks());
        assertEquals(5.5D, snapshot.blocksPerSecond());
        assertFalse(snapshot.capacityLimited());
    }

    @Test
    void repeatedSuccessSignalsAreCountedIndependently() {
        BpsTracker tracker = startedTracker();
        BlockInteractionSignal signal = success("minecraft:melon");

        assertEquals(BpsRecordResult.COUNTED,
                tracker.record(signal, MacroCrop.MELON, SECOND));
        assertEquals(BpsRecordResult.COUNTED,
                tracker.record(signal, MacroCrop.MELON, SECOND));
        assertEquals(BpsRecordResult.COUNTED,
                tracker.record(signal, MacroCrop.MELON, SECOND));

        assertEquals(new BpsSnapshot(1.5D, 3, 2L * SECOND, false),
                tracker.snapshot(2L * SECOND));
    }

    @Test
    void tenSecondWindowIncludesExactBoundaryThenEvictsOlderBreaks() {
        BpsTracker tracker = startedTracker();
        tracker.record(success("minecraft:wheat"), MacroCrop.WHEAT, 0L);
        tracker.record(success("minecraft:wheat"), MacroCrop.WHEAT, 5L * SECOND);

        assertEquals(new BpsSnapshot(0.2D, 2, 10L * SECOND, false),
                tracker.snapshot(10L * SECOND));
        assertEquals(new BpsSnapshot(0.1D, 1, 10L * SECOND, false),
                tracker.snapshot(10L * SECOND + 1L));
        assertEquals(new BpsSnapshot(0.0D, 0, 10L * SECOND, false),
                tracker.snapshot(15L * SECOND + 1L));
    }

    @Test
    void fixedCapacityIsObservableUntilEvictedEvidenceLeavesTheWindow() {
        BpsTracker tracker = new BpsTracker(10L * SECOND, 2);
        tracker.started(GENERATION, 0L);
        tracker.record(success("minecraft:cactus"), MacroCrop.CACTUS, 0L);
        tracker.record(success("minecraft:cactus"), MacroCrop.CACTUS, SECOND);
        tracker.record(success("minecraft:cactus"), MacroCrop.CACTUS, 2L * SECOND);

        BpsSnapshot saturated = tracker.snapshot(2L * SECOND);
        assertEquals(2, saturated.retainedBlocks());
        assertEquals(1.0D, saturated.blocksPerSecond());
        assertTrue(saturated.capacityLimited());
        assertTrue(tracker.snapshot(10L * SECOND).capacityLimited());

        BpsSnapshot complete = tracker.snapshot(10L * SECOND + 1L);
        assertEquals(2, complete.retainedBlocks());
        assertFalse(complete.capacityLimited());
        assertEquals(2, tracker.maxRetainedBlocks());
    }

    @Test
    void pauseFreezesRateAndResumeExcludesPausedDuration() {
        BpsTracker tracker = startedTracker();
        tracker.record(success("minecraft:potatoes"), MacroCrop.POTATO, SECOND);
        tracker.record(success("minecraft:potatoes"), MacroCrop.POTATO, 2L * SECOND);
        tracker.paused(GENERATION, 3L * SECOND, Set.of(MacroPauseCause.MANUAL));

        BpsSnapshot paused = tracker.snapshot(100L * SECOND);
        assertEquals(BpsTracker.State.PAUSED, tracker.state());
        assertEquals(2.0D / 3.0D, paused.blocksPerSecond());
        assertEquals(3L * SECOND, paused.elapsedNanos());
        assertEquals(BpsRecordResult.PAUSED,
                tracker.record(success("minecraft:potatoes"), MacroCrop.POTATO, 100L * SECOND));

        tracker.resumed(GENERATION, 103L * SECOND);

        assertEquals(BpsTracker.State.RUNNING, tracker.state());
        assertEquals(new BpsSnapshot(0.5D, 2, 4L * SECOND, false),
                tracker.snapshot(104L * SECOND));
    }

    @Test
    void generationAndWorldEpochFenceTheRollingWindow() {
        BpsTracker tracker = startedTracker();
        assertEquals(BpsRecordResult.COUNTED,
                tracker.record(success("minecraft:sugar_cane"), MacroCrop.SUGAR_CANE, SECOND));
        assertEquals(BpsRecordResult.STALE_GENERATION,
                tracker.record(
                        success("minecraft:sugar_cane", GENERATION + 1L, WORLD_EPOCH),
                        MacroCrop.SUGAR_CANE,
                        SECOND));
        assertEquals(BpsRecordResult.STALE_WORLD_EPOCH,
                tracker.record(
                        success("minecraft:sugar_cane", GENERATION, WORLD_EPOCH - 1L),
                        MacroCrop.SUGAR_CANE,
                        2L * SECOND));

        assertEquals(BpsRecordResult.COUNTED,
                tracker.record(
                        success("minecraft:sugar_cane", GENERATION, WORLD_EPOCH + 1L),
                        MacroCrop.SUGAR_CANE,
                        3L * SECOND));
        assertEquals(new BpsSnapshot(1.0D, 1, SECOND, false),
                tracker.snapshot(4L * SECOND));
        assertEquals(WORLD_EPOCH + 1L, tracker.worldEpoch().orElseThrow());

        tracker.stopped(GENERATION + 1L, MacroTerminalReason.CLIENT_STOP);
        assertEquals(BpsTracker.State.RUNNING, tracker.state());
        tracker.stopped(GENERATION, MacroTerminalReason.CLIENT_STOP);
        assertEquals(BpsTracker.State.STOPPED, tracker.state());
        assertEquals(BpsSnapshot.ZERO, tracker.snapshot(0L));
    }

    @Test
    void invalidBoundsLifecycleIdentityAndClockRegressionAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new BpsTracker(0L, 1));
        assertThrows(IllegalArgumentException.class, () -> new BpsTracker(1L, 0));
        BpsTracker tracker = new BpsTracker();
        assertEquals(BpsRecordResult.STOPPED,
                tracker.record(success("minecraft:wheat"), MacroCrop.WHEAT, 0L));
        assertThrows(IllegalArgumentException.class, () -> tracker.started(0L, 0L));

        tracker.started(GENERATION, SECOND);
        tracker.snapshot(2L * SECOND);
        assertThrows(IllegalArgumentException.class,
                () -> tracker.snapshot(2L * SECOND - 1L));
    }

    private static BpsTracker startedTracker() {
        BpsTracker tracker = new BpsTracker();
        tracker.started(GENERATION, 0L);
        return tracker;
    }

    private static BlockInteractionSignal click() {
        return new BlockInteractionSignal(
                BlockInteractionSignalType.CLICK_INTENT,
                new BlockPosition(0, 70, 0),
                BlockInteractionFace.NORTH,
                Observation.unknown(),
                GENERATION,
                WORLD_EPOCH);
    }

    private static BlockInteractionSignal success(String blockId) {
        return success(blockId, GENERATION, WORLD_EPOCH);
    }

    private static BlockInteractionSignal success(
            String blockId,
            long generation,
            long worldEpoch
    ) {
        return success(Observation.present(block(blockId, Map.of())), generation, worldEpoch);
    }

    private static BlockInteractionSignal success(Observation<BlockStateSnapshot> state) {
        return success(state, GENERATION, WORLD_EPOCH);
    }

    private static BlockInteractionSignal success(
            Observation<BlockStateSnapshot> state,
            long generation,
            long worldEpoch
    ) {
        return new BlockInteractionSignal(
                BlockInteractionSignalType.BREAK_SUCCESS,
                new BlockPosition(0, 70, 0),
                BlockInteractionFace.UNKNOWN,
                state,
                generation,
                worldEpoch);
    }

    private static BlockInteractionSignal success(BlockStateSnapshot state) {
        return success(Observation.present(state));
    }

    private static BlockStateSnapshot block(String blockId, Map<String, String> properties) {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse(blockId),
                properties,
                ResourceIdentifier.parse("minecraft:empty"),
                Observation.present(CollisionShapeSnapshot.EMPTY));
    }
}

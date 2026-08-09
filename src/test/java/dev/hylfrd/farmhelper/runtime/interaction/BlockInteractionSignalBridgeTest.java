package dev.hylfrd.farmhelper.runtime.interaction;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.CollisionShapeSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockInteractionSignalBridgeTest {
    private static final BlockPosition POSITION = new BlockPosition(3, 70, -2);
    private static final BlockStateSnapshot PRE_BREAK = new BlockStateSnapshot(
            ResourceIdentifier.parse("minecraft:wheat"),
            java.util.Map.of("age", "7"),
            ResourceIdentifier.parse("minecraft:empty"),
            Observation.present(CollisionShapeSnapshot.EMPTY));

    @Test
    void clickIntentIsRawOrderedAndNeverDeduplicated() {
        AtomicLong generation = new AtomicLong(4L);
        AtomicLong worldEpoch = new AtomicLong(7L);
        List<BlockInteractionSignal> signals = new ArrayList<>();
        BlockInteractionSignalBridge bridge = bridge(generation, worldEpoch, signals);

        bridge.emitClickIntent(POSITION, BlockInteractionFace.NORTH);
        bridge.emitClickIntent(POSITION, BlockInteractionFace.NORTH);

        assertEquals(2, signals.size());
        assertEquals(List.of(
                BlockInteractionSignalType.CLICK_INTENT,
                BlockInteractionSignalType.CLICK_INTENT),
                signals.stream().map(BlockInteractionSignal::type).toList());
        assertTrue(signals.stream().allMatch(signal -> signal.preBreakState().isUnknown()));
        assertTrue(signals.stream().allMatch(signal -> signal.generation() == 4L));
        assertTrue(signals.stream().allMatch(signal -> signal.worldEpoch() == 7L));
    }

    @Test
    void falseReturnDoesNotPublishButTrueReturnKeepsHeadStateAndOrdering() {
        AtomicLong generation = new AtomicLong();
        AtomicLong worldEpoch = new AtomicLong();
        List<BlockInteractionSignal> signals = new ArrayList<>();
        BlockInteractionSignalBridge bridge = bridge(generation, worldEpoch, signals);

        bridge.emitClickIntent(POSITION, BlockInteractionFace.UP);
        bridge.beginBreak(POSITION, Observation.present(PRE_BREAK));
        bridge.completeBreak(false);
        assertEquals(1, signals.size());

        bridge.beginBreak(POSITION, Observation.present(PRE_BREAK));
        bridge.completeBreak(true);

        assertEquals(List.of(
                BlockInteractionSignalType.CLICK_INTENT,
                BlockInteractionSignalType.BREAK_SUCCESS),
                signals.stream().map(BlockInteractionSignal::type).toList());
        BlockInteractionSignal success = signals.get(1);
        assertEquals(BlockInteractionFace.UNKNOWN, success.face());
        assertEquals(Observation.present(PRE_BREAK), success.preBreakState());
    }

    @Test
    void eachTrueReturnPublishesItsOwnAttempt() {
        List<BlockInteractionSignal> signals = new ArrayList<>();
        BlockInteractionSignalBridge bridge = bridge(
                new AtomicLong(), new AtomicLong(), signals);

        bridge.beginBreak(POSITION, Observation.present(PRE_BREAK));
        bridge.completeBreak(true);
        bridge.beginBreak(POSITION, Observation.present(PRE_BREAK));
        bridge.completeBreak(true);

        assertEquals(2, signals.size());
        assertTrue(signals.stream().allMatch(signal ->
                signal.type() == BlockInteractionSignalType.BREAK_SUCCESS));
    }

    @Test
    void staleGenerationOrWorldEpochCannotPublishSuccess() {
        AtomicLong generation = new AtomicLong(2L);
        AtomicLong worldEpoch = new AtomicLong(5L);
        List<BlockInteractionSignal> signals = new ArrayList<>();
        BlockInteractionSignalBridge bridge = bridge(generation, worldEpoch, signals);

        bridge.beginBreak(POSITION, Observation.present(PRE_BREAK));
        generation.incrementAndGet();
        bridge.completeBreak(true);

        bridge.beginBreak(POSITION, Observation.present(PRE_BREAK));
        worldEpoch.incrementAndGet();
        bridge.completeBreak(true);

        assertTrue(signals.isEmpty());
    }

    private static BlockInteractionSignalBridge bridge(
            AtomicLong generation,
            AtomicLong worldEpoch,
            List<BlockInteractionSignal> signals
    ) {
        return new BlockInteractionSignalBridge(
                generation::get, worldEpoch::get, signals::add);
    }
}

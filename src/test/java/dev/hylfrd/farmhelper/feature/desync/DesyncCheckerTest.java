package dev.hylfrd.farmhelper.feature.desync;

import dev.hylfrd.farmhelper.config.FarmHelperConfig;
import dev.hylfrd.farmhelper.macro.MacroCrop;
import dev.hylfrd.farmhelper.macro.MacroManager;
import dev.hylfrd.farmhelper.macro.MacroPauseCause;
import dev.hylfrd.farmhelper.macro.MacroState;
import dev.hylfrd.farmhelper.macro.ServerResponsiveness;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.CollisionShapeSnapshot;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesyncCheckerTest {
    private static final long WORLD_EPOCH = 7L;
    private static final long NOW = TimeUnit.SECONDS.toNanos(10L);
    private static final Observation<BlockStateSnapshot> CLICKED_CROP =
            Observation.present(block("minecraft:wheat", Map.of("age", "7")));

    @Test
    void acceptancePriorityRejectsDisabledStaleUnknownNonCropAndFailsafeEvidence() {
        Harness harness = harness();
        BlockPosition position = new BlockPosition(0, 70, 0);
        DesyncClick valid = harness.click(position, CLICKED_CROP);
        Function<BlockPosition, Observation<BlockStateSnapshot>> matureWheat =
                ignored -> CLICKED_CROP;

        harness.config.setCheckDesync(false);
        assertEquals(DesyncCheckResult.DISABLED,
                harness.record(valid, MacroCrop.WHEAT, false,
                        true, ServerResponsiveness.RESPONSIVE, matureWheat));
        harness.config.setCheckDesync(true);

        assertEquals(DesyncCheckResult.STALE_IDENTITY,
                harness.record(new DesyncClick(
                                harness.manager.generation() + 1L,
                                WORLD_EPOCH, position, CLICKED_CROP),
                        MacroCrop.WHEAT, false, true,
                        ServerResponsiveness.RESPONSIVE, matureWheat));
        assertEquals(DesyncCheckResult.STALE_IDENTITY,
                harness.record(new DesyncClick(
                                harness.manager.generation(),
                                WORLD_EPOCH + 1L, position, CLICKED_CROP),
                        MacroCrop.WHEAT, false, true,
                        ServerResponsiveness.RESPONSIVE, matureWheat));

        assertEquals(DesyncCheckResult.CLICK_BLOCK_UNKNOWN,
                harness.record(harness.click(position, Observation.unknown()),
                        MacroCrop.WHEAT, true, true,
                        ServerResponsiveness.RESPONSIVE, matureWheat));
        assertEquals(DesyncCheckResult.NOT_CROP,
                harness.record(harness.click(
                                position,
                                Observation.present(block("minecraft:stone", Map.of()))),
                        MacroCrop.WHEAT, true, true,
                        ServerResponsiveness.RESPONSIVE, matureWheat));
        assertEquals(DesyncCheckResult.FAILSAFE_ACTIVE,
                harness.record(valid, MacroCrop.WHEAT, true,
                        true, ServerResponsiveness.RESPONSIVE, matureWheat));
        assertEquals(0, harness.checker.acceptedClickCount());
    }

    @Test
    void sharedLagUnknownAndConnectionGatesNeverBecomeDesyncEvidence() {
        Harness harness = harness();
        Function<BlockPosition, Observation<BlockStateSnapshot>> matureWheat =
                ignored -> CLICKED_CROP;

        assertEquals(DesyncCheckResult.ACCEPTED,
                harness.record(harness.click(0, CLICKED_CROP), MacroCrop.WHEAT,
                        false, true, ServerResponsiveness.RESPONSIVE, matureWheat));
        assertEquals(1, harness.checker.acceptedClickCount());

        assertEquals(DesyncCheckResult.SERVER_UNKNOWN,
                harness.record(harness.click(1, CLICKED_CROP), MacroCrop.WHEAT,
                        false, true, ServerResponsiveness.UNKNOWN, matureWheat));
        assertEquals(0, harness.checker.acceptedClickCount());

        assertEquals(DesyncCheckResult.ACCEPTED,
                harness.record(harness.click(2, CLICKED_CROP), MacroCrop.WHEAT,
                        false, true, ServerResponsiveness.RESPONSIVE, matureWheat));
        assertEquals(DesyncCheckResult.SERVER_LAGGING,
                harness.record(harness.click(3, CLICKED_CROP), MacroCrop.WHEAT,
                        false, true, ServerResponsiveness.LAGGING, matureWheat));
        assertEquals(0, harness.checker.acceptedClickCount());

        assertEquals(DesyncCheckResult.CONNECTION_UNAVAILABLE,
                harness.record(harness.click(4, CLICKED_CROP), MacroCrop.WHEAT,
                        false, false, ServerResponsiveness.RESPONSIVE, matureWheat));
        assertEquals(DesyncChecker.State.STOPPED, harness.checker.state());
    }

    @Test
    void sixtyClickWindowEvictsOldestAndTriggersAtExactlyFortyFive() {
        Harness harness = harness();
        Map<BlockPosition, Observation<BlockStateSnapshot>> current = new HashMap<>();
        Observation<BlockStateSnapshot> air =
                Observation.present(block("minecraft:air", Map.of()));

        DesyncCheckResult result = DesyncCheckResult.STOPPED;
        for (int index = 0; index < DesyncChecker.WINDOW_SIZE; index++) {
            BlockPosition position = position(index);
            current.put(position, index >= 1 && index <= 44 ? CLICKED_CROP : air);
            result = harness.record(harness.click(position, CLICKED_CROP), MacroCrop.WHEAT,
                    false, true, ServerResponsiveness.RESPONSIVE,
                    block -> current.getOrDefault(block, Observation.unknown()));
        }

        assertEquals(DesyncCheckResult.ACCEPTED, result);
        assertEquals(DesyncChecker.WINDOW_SIZE, harness.checker.acceptedClickCount());
        assertEquals(MacroState.RUNNING, harness.manager.state());

        BlockPosition next = position(DesyncChecker.WINDOW_SIZE);
        current.put(next, CLICKED_CROP);
        result = harness.record(harness.click(next, CLICKED_CROP), MacroCrop.WHEAT,
                false, true, ServerResponsiveness.RESPONSIVE,
                block -> current.getOrDefault(block, Observation.unknown()));

        assertEquals(DesyncCheckResult.TRIGGERED, result);
        assertEquals(0, harness.checker.acceptedClickCount());
        assertEquals(DesyncChecker.State.RECOVERING, harness.checker.state());
        assertEquals(MacroState.PAUSED, harness.manager.state());
        assertEquals(java.util.Set.of(MacroPauseCause.FEATURE), harness.manager.pauseCauses());
    }

    @Test
    void fortyFourNeverTriggersAndUnknownCurrentBlocksCountAsNoEvidence() {
        Harness harness = harness();
        Map<BlockPosition, Observation<BlockStateSnapshot>> current = new HashMap<>();
        for (int index = 0; index < DesyncChecker.WINDOW_SIZE; index++) {
            current.put(position(index), index < 44 ? CLICKED_CROP : Observation.unknown());
        }

        DesyncCheckResult result = fillWindow(
                harness, MacroCrop.WHEAT,
                block -> current.getOrDefault(block, Observation.unknown()));

        assertEquals(DesyncCheckResult.ACCEPTED, result);
        assertEquals(DesyncChecker.WINDOW_SIZE, harness.checker.acceptedClickCount());
        assertEquals(MacroState.RUNNING, harness.manager.state());
    }

    @Test
    void upstreamCropSpecificCurrentStateRulesArePreserved() {
        assertTriggers(MacroCrop.WHEAT, block("minecraft:wheat", Map.of("age", "7")));
        assertDoesNotTrigger(MacroCrop.WHEAT, block("minecraft:wheat", Map.of("age", "6")));
        assertTriggers(MacroCrop.CARROT, block("minecraft:carrots", Map.of("age", "7")));
        assertTriggers(MacroCrop.POTATO, block("minecraft:potatoes", Map.of("age", "7")));
        assertTriggers(MacroCrop.NETHER_WART, block("minecraft:nether_wart", Map.of("age", "3")));
        assertDoesNotTrigger(
                MacroCrop.NETHER_WART, block("minecraft:nether_wart", Map.of("age", "2")));
        assertTriggers(MacroCrop.COCOA, block("minecraft:cocoa", Map.of("age", "2")));
        assertDoesNotTrigger(MacroCrop.COCOA, block("minecraft:cocoa", Map.of("age", "1")));
        assertTriggers(MacroCrop.SUGAR_CANE, block("minecraft:sugar_cane", Map.of()));
        assertTriggers(MacroCrop.CACTUS, block("minecraft:cactus", Map.of()));
        assertTriggers(MacroCrop.RED_MUSHROOM, block("minecraft:brown_mushroom", Map.of()));
        assertTriggers(MacroCrop.BROWN_MUSHROOM, block("minecraft:red_mushroom", Map.of()));
        assertTriggers(MacroCrop.MELON, block("minecraft:stone", Map.of()));
        assertTriggers(MacroCrop.PUMPKIN, block("minecraft:melon", Map.of()));
        assertDoesNotTrigger(MacroCrop.MELON, block("minecraft:air", Map.of()));
        assertDoesNotTrigger(MacroCrop.PUMPKIN, block("minecraft:cave_air", Map.of()));
    }

    @Test
    void defaultDelayIsExactAndRecoveryWaitsForResponsiveSharedEvidence() {
        Harness harness = harness();
        triggerAt(harness, NOW);
        long due = NOW + TimeUnit.MILLISECONDS.toNanos(
                FarmHelperConfig.DEFAULT_DESYNC_PAUSE_DELAY_MILLIS);

        assertEquals(due, harness.checker.recoveryDueNanos().orElseThrow());
        assertEquals(DesyncCheckResult.RECOVERY_PENDING,
                harness.checker.tickRecovery(
                        due - 1L, harness.manager.generation(), WORLD_EPOCH,
                        true, ServerResponsiveness.RESPONSIVE));
        assertEquals(DesyncCheckResult.SERVER_UNKNOWN,
                harness.checker.tickRecovery(
                        due, harness.manager.generation(), WORLD_EPOCH,
                        true, ServerResponsiveness.UNKNOWN));
        assertEquals(DesyncCheckResult.SERVER_LAGGING,
                harness.checker.tickRecovery(
                        due, harness.manager.generation(), WORLD_EPOCH,
                        true, ServerResponsiveness.LAGGING));
        assertEquals(MacroState.PAUSED, harness.manager.state());

        assertEquals(DesyncCheckResult.RECOVERED,
                harness.checker.tickRecovery(
                        due, harness.manager.generation(), WORLD_EPOCH,
                        true, ServerResponsiveness.RESPONSIVE));
        assertEquals(DesyncChecker.State.COLLECTING, harness.checker.state());
        assertEquals(MacroState.RUNNING, harness.manager.state());
        assertTrue(harness.checker.recoveryDueNanos().isEmpty());
    }

    @Test
    void exactConfiguredDelayEndpointsAndDeadlineOverflowAreDeterministic() {
        for (int delay : new int[] {
                FarmHelperConfig.MIN_DESYNC_PAUSE_DELAY_MILLIS,
                FarmHelperConfig.MAX_DESYNC_PAUSE_DELAY_MILLIS}) {
            Harness harness = harness();
            harness.config.setDesyncPauseDelayMillis(delay);

            triggerAt(harness, 1L);

            assertEquals(1L + TimeUnit.MILLISECONDS.toNanos(delay),
                    harness.checker.recoveryDueNanos().orElseThrow());
        }

        Harness overflow = harness();
        triggerAt(overflow, Long.MAX_VALUE - 1L);
        assertEquals(Long.MAX_VALUE, overflow.checker.recoveryDueNanos().orElseThrow());
        assertEquals(DesyncCheckResult.RECOVERY_PENDING,
                overflow.checker.tickRecovery(
                        Long.MAX_VALUE - 1L, overflow.manager.generation(), WORLD_EPOCH,
                        true, ServerResponsiveness.RESPONSIVE));
        assertEquals(DesyncCheckResult.RECOVERED,
                overflow.checker.tickRecovery(
                        Long.MAX_VALUE, overflow.manager.generation(), WORLD_EPOCH,
                        true, ServerResponsiveness.RESPONSIVE));
    }

    @Test
    void recoveryReleasesOnlyItsSharedFeatureSuspension() {
        Harness harness = harness();
        harness.manager.manualPause();
        triggerAt(harness, NOW);
        assertEquals(java.util.Set.of(MacroPauseCause.MANUAL, MacroPauseCause.FEATURE),
                harness.manager.pauseCauses());

        long due = harness.checker.recoveryDueNanos().orElseThrow();
        assertEquals(DesyncCheckResult.RECOVERED,
                harness.checker.tickRecovery(
                        due, harness.manager.generation(), WORLD_EPOCH,
                        true, ServerResponsiveness.RESPONSIVE));

        assertEquals(MacroState.PAUSED, harness.manager.state());
        assertEquals(java.util.Set.of(MacroPauseCause.MANUAL), harness.manager.pauseCauses());
        harness.manager.manualResume();
        assertEquals(MacroState.RUNNING, harness.manager.state());
    }

    @Test
    void pauseResumeStopResetWorldAndConnectionBoundariesClearState() {
        Harness harness = harness();
        harness.record(harness.click(0, CLICKED_CROP), MacroCrop.WHEAT,
                false, true, ServerResponsiveness.RESPONSIVE, ignored -> CLICKED_CROP);
        assertEquals(1, harness.checker.acceptedClickCount());

        harness.checker.pause();
        assertEquals(0, harness.checker.acceptedClickCount());
        assertEquals(DesyncChecker.State.COLLECTING, harness.checker.state());
        harness.checker.resume();
        assertEquals(DesyncChecker.State.COLLECTING, harness.checker.state());

        triggerAt(harness, NOW);
        harness.checker.stop();
        assertEquals(DesyncChecker.State.STOPPED, harness.checker.state());
        assertEquals(MacroState.RUNNING, harness.manager.state());

        harness.checker.start(WORLD_EPOCH);
        harness.checker.reset();
        assertEquals(DesyncChecker.State.STOPPED, harness.checker.state());
        harness.checker.start(WORLD_EPOCH);
        harness.checker.worldChanged(WORLD_EPOCH + 1L);
        assertEquals(DesyncChecker.State.STOPPED, harness.checker.state());
        harness.checker.start(WORLD_EPOCH + 1L);
        harness.checker.connectionLost();
        assertEquals(DesyncChecker.State.STOPPED, harness.checker.state());
    }

    @Test
    void staleRecoveryCannotResumeAnotherMacroIdentityOrWorld() {
        Harness staleWorld = harness();
        triggerAt(staleWorld, NOW);
        long due = staleWorld.checker.recoveryDueNanos().orElseThrow();

        assertEquals(DesyncCheckResult.STALE_IDENTITY,
                staleWorld.checker.tickRecovery(
                        due, staleWorld.manager.generation(), WORLD_EPOCH + 1L,
                        true, ServerResponsiveness.RESPONSIVE));
        assertEquals(DesyncChecker.State.STOPPED, staleWorld.checker.state());
        assertEquals(MacroState.RUNNING, staleWorld.manager.state());

        Harness staleGeneration = harness();
        triggerAt(staleGeneration, NOW);
        due = staleGeneration.checker.recoveryDueNanos().orElseThrow();
        assertEquals(DesyncCheckResult.STALE_IDENTITY,
                staleGeneration.checker.tickRecovery(
                        due, staleGeneration.manager.generation() + 1L, WORLD_EPOCH,
                        true, ServerResponsiveness.RESPONSIVE));
        assertEquals(MacroState.RUNNING, staleGeneration.manager.state());
    }

    @Test
    void stoppedMacroInvalidatesTheCollectorWithoutReleasingAReplacement() {
        Harness harness = harness();
        long generation = harness.manager.generation();
        harness.manager.stop();

        assertEquals(DesyncCheckResult.MACRO_INACTIVE,
                harness.record(
                        new DesyncClick(generation, WORLD_EPOCH, position(0), CLICKED_CROP),
                        MacroCrop.WHEAT, false, true,
                        ServerResponsiveness.RESPONSIVE, ignored -> CLICKED_CROP));
        assertEquals(DesyncChecker.State.STOPPED, harness.checker.state());
    }

    @Test
    void inputIdentityAndStartValidationRejectInvalidState() {
        MacroManager stopped = new MacroManager();
        DesyncChecker checker = new DesyncChecker(new FarmHelperConfig(), stopped);

        assertThrows(IllegalStateException.class, () -> checker.start(WORLD_EPOCH));
        assertThrows(IllegalArgumentException.class, () -> checker.start(-1L));
        assertThrows(IllegalArgumentException.class,
                () -> new DesyncClick(0L, WORLD_EPOCH, position(0), CLICKED_CROP));
        assertThrows(IllegalArgumentException.class,
                () -> new DesyncClick(1L, -1L, position(0), CLICKED_CROP));
    }

    private static void assertTriggers(MacroCrop crop, BlockStateSnapshot current) {
        Harness harness = harness();
        assertEquals(DesyncCheckResult.TRIGGERED,
                fillWindow(harness, crop, ignored -> Observation.present(current)), crop.name());
    }

    private static void assertDoesNotTrigger(MacroCrop crop, BlockStateSnapshot current) {
        Harness harness = harness();
        assertEquals(DesyncCheckResult.ACCEPTED,
                fillWindow(harness, crop, ignored -> Observation.present(current)), crop.name());
        assertEquals(MacroState.RUNNING, harness.manager.state(), crop.name());
    }

    private static DesyncCheckResult fillWindow(
            Harness harness,
            MacroCrop crop,
            Function<BlockPosition, Observation<BlockStateSnapshot>> current
    ) {
        DesyncCheckResult result = DesyncCheckResult.STOPPED;
        for (int index = 0; index < DesyncChecker.WINDOW_SIZE; index++) {
            result = harness.record(harness.click(index, CLICKED_CROP), crop,
                    false, true, ServerResponsiveness.RESPONSIVE, current);
        }
        return result;
    }

    private static void triggerAt(Harness harness, long nowNanos) {
        DesyncCheckResult result = DesyncCheckResult.STOPPED;
        for (int index = 0; index < DesyncChecker.WINDOW_SIZE; index++) {
            result = harness.checker.recordClick(
                    harness.click(index, CLICKED_CROP),
                    MacroCrop.WHEAT,
                    false,
                    true,
                    ServerResponsiveness.RESPONSIVE,
                    ignored -> CLICKED_CROP,
                    nowNanos);
        }
        assertEquals(DesyncCheckResult.TRIGGERED, result);
    }

    private static Harness harness() {
        FarmHelperConfig config = new FarmHelperConfig();
        MacroManager manager = new MacroManager();
        manager.start();
        DesyncChecker checker = new DesyncChecker(config, manager);
        checker.start(WORLD_EPOCH);
        return new Harness(config, manager, checker);
    }

    private static BlockPosition position(int index) {
        return new BlockPosition(index, 70, 0);
    }

    private static BlockStateSnapshot block(String identifier, Map<String, String> properties) {
        return new BlockStateSnapshot(
                ResourceIdentifier.parse(identifier),
                properties,
                ResourceIdentifier.parse("minecraft:empty"),
                Observation.present(CollisionShapeSnapshot.EMPTY));
    }

    private record Harness(
            FarmHelperConfig config,
            MacroManager manager,
            DesyncChecker checker
    ) {
        private DesyncClick click(int x, Observation<BlockStateSnapshot> clickedBlock) {
            return click(position(x), clickedBlock);
        }

        private DesyncClick click(
                BlockPosition position,
                Observation<BlockStateSnapshot> clickedBlock
        ) {
            return new DesyncClick(manager.generation(), checker.worldEpoch(), position, clickedBlock);
        }

        private DesyncCheckResult record(
                DesyncClick click,
                MacroCrop crop,
                boolean failsafeActive,
                boolean connectionReady,
                ServerResponsiveness serverResponsiveness,
                Function<BlockPosition, Observation<BlockStateSnapshot>> current
        ) {
            return checker.recordClick(
                    click, crop, failsafeActive, connectionReady,
                    serverResponsiveness, current, NOW);
        }
    }
}

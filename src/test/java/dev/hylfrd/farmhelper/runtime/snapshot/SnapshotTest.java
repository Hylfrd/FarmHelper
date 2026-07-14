package dev.hylfrd.farmhelper.runtime.snapshot;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotTest {
    @Test
    void legalZeroPositionAndRotationNeverMasqueradeAsMissing() {
        PlayerSnapshot zeroPlayer = new PlayerSnapshot(
                Observation.present(new PositionSnapshot(0.0D, 0.0D, 0.0D)),
                Observation.present(new MotionSnapshot(0.0D, 0.0D, 0.0D)),
                Observation.present(new RotationSnapshot(0.0F, 0.0F)),
                Observation.absent(),
                Observation.present(List.of()));
        ClientSnapshot present = new ClientSnapshot(
                Observation.present(zeroPlayer),
                Observation.absent(),
                Observation.absent(),
                Observation.absent());
        ClientSnapshot noPlayer = new ClientSnapshot(
                Observation.absent(),
                Observation.absent(),
                Observation.absent(),
                Observation.absent());

        assertTrue(present.player().isPresent());
        assertEquals(new PositionSnapshot(0.0D, 0.0D, 0.0D),
                present.player().get().position().get());
        assertEquals(new RotationSnapshot(0.0F, 0.0F),
                present.player().get().rotation().get());
        assertTrue(noPlayer.player().isAbsent());
        assertNotEquals(present, noPlayer);
    }

    @Test
    void statusEffectsAreDeeplyImmutable() {
        List<StatusEffectSummary> mutableEffects = new ArrayList<>();
        mutableEffects.add(new StatusEffectSummary(
                ResourceIdentifier.parse("minecraft:speed"),
                1,
                200,
                false,
                true,
                true));
        PlayerSnapshot player = new PlayerSnapshot(
                Observation.unknown(),
                Observation.unknown(),
                Observation.unknown(),
                Observation.present(new ItemSummary(ResourceIdentifier.parse("minecraft:carrot"), 32)),
                Observation.present(mutableEffects));

        mutableEffects.clear();

        List<StatusEffectSummary> snapshotEffects = player.statusEffects().get();
        assertEquals(1, snapshotEffects.size());
        assertThrows(UnsupportedOperationException.class, () -> snapshotEffects.add(
                new StatusEffectSummary(ResourceIdentifier.parse("minecraft:haste"), 0, 20, false, true, true)));
    }

    @Test
    void distinguishesNoWorldNoPlayerAndUnknownState() {
        ClientSnapshot disconnected = new ClientSnapshot(
                Observation.absent(),
                Observation.absent(),
                Observation.absent(),
                Observation.absent());
        ClientSnapshot worldWithoutPlayer = new ClientSnapshot(
                Observation.absent(),
                Observation.present(new WorldSnapshot(
                        Observation.present(ResourceIdentifier.parse("minecraft:overworld")))),
                Observation.present(ConnectionSnapshot.singleplayer()),
                Observation.absent());
        ClientSnapshot notYetObserved = ClientSnapshot.unknown();

        assertTrue(disconnected.player().isAbsent());
        assertTrue(disconnected.world().isAbsent());
        assertTrue(disconnected.connection().isAbsent());
        assertTrue(worldWithoutPlayer.player().isAbsent());
        assertTrue(worldWithoutPlayer.world().isPresent());
        assertTrue(worldWithoutPlayer.connection().isPresent());
        assertTrue(notYetObserved.player().isUnknown());
        assertTrue(notYetObserved.world().isUnknown());
        assertTrue(notYetObserved.connection().isUnknown());
        assertNotEquals(disconnected, notYetObserved);
    }

    @Test
    void representsSingleplayerMultiplayerUnknownConnectionsAndDimensions() {
        WorldSnapshot overworld = new WorldSnapshot(
                Observation.present(ResourceIdentifier.parse("minecraft:overworld")));
        WorldSnapshot unknownDimension = new WorldSnapshot(Observation.unknown());

        ClientSnapshot singleplayer = new ClientSnapshot(
                Observation.absent(),
                Observation.present(overworld),
                Observation.present(ConnectionSnapshot.singleplayer()),
                Observation.absent());
        ClientSnapshot multiplayer = new ClientSnapshot(
                Observation.absent(),
                Observation.present(overworld),
                Observation.present(ConnectionSnapshot.multiplayer()),
                Observation.present(new ScreenSnapshot(
                        Observation.present("inventory"),
                        Observation.present("Inventory"))));
        ClientSnapshot unknownConnection = new ClientSnapshot(
                Observation.absent(),
                Observation.present(unknownDimension),
                Observation.unknown(),
                Observation.absent());

        assertEquals(ConnectionSnapshot.Mode.SINGLEPLAYER, singleplayer.connection().get().mode());
        assertEquals(ConnectionSnapshot.Mode.MULTIPLAYER, multiplayer.connection().get().mode());
        assertEquals(ResourceIdentifier.parse("minecraft:overworld"),
                singleplayer.world().get().dimension().get());
        assertTrue(unknownConnection.connection().isUnknown());
        assertTrue(unknownConnection.world().get().dimension().isUnknown());
        assertEquals("Inventory", multiplayer.screen().get().title().get());
    }
}

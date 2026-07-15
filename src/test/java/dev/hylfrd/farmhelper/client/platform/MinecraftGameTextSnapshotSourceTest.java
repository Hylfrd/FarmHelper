package dev.hylfrd.farmhelper.client.platform;

import com.mojang.authlib.GameProfile;
import dev.hylfrd.farmhelper.runtime.gamestate.BuffStatus;
import dev.hylfrd.farmhelper.runtime.gamestate.GameStateParseResult;
import dev.hylfrd.farmhelper.runtime.gamestate.GameStateParser;
import dev.hylfrd.farmhelper.runtime.gamestate.GameTextInputBudget;
import dev.hylfrd.farmhelper.runtime.gamestate.GardenCrop;
import dev.hylfrd.farmhelper.runtime.gamestate.PlayerFacts;
import dev.hylfrd.farmhelper.runtime.gamestate.RawGameTextSnapshot;
import dev.hylfrd.farmhelper.runtime.gamestate.SemanticLocation;
import dev.hylfrd.farmhelper.runtime.gamestate.WorldTransition;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftGameTextSnapshotSourceTest {
    @Test
    void teamDecoratedVisibleTextReachesAreaEconomyPestJacobAndBuffParsing() {
        Scoreboard scoreboard = new Scoreboard();
        List<String> scoreboardLines = new ArrayList<>();
        scoreboardLines.add(visibleLine(scoreboard, "Purse: ", "1,234"));
        scoreboardLines.add(visibleLine(scoreboard, "Jacob's ", "Contest"));
        scoreboardLines.add(visibleLine(scoreboard, "Wheat ", "2m30s"));
        scoreboardLines.add(visibleLine(scoreboard, "Collected ", "1,234"));
        scoreboardLines.add(visibleLine(scoreboard, "GOLD with ", "1,234"));
        scoreboardLines.add(visibleLine(scoreboard, "The Garden ൠ ", "x5"));
        scoreboardLines.add(visibleLine(scoreboard, "Plot 12 ", "x2"));

        RawGameTextSnapshot raw = new RawGameTextSnapshot(
                Observation.present("SKYBLOCK"),
                Observation.present(scoreboardLines),
                Observation.present(List.of("Area: Garden", "Organic Matter: 1k", "Fuel: 2k")),
                Observation.present(List.of("Active Effects", "Cookie Buff",
                        "You have a God Potion active!", "Pest Repellent 59m")),
                Observation.present(List.of()),
                Observation.present(List.of()),
                new PlayerFacts(Observation.present(false), Observation.present(12),
                        Observation.present(0.1D)),
                Observation.present(WorldTransition.STABLE), 3L);

        GameStateParseResult result = new GameStateParser().parse(multiplayer(), raw);

        assertEquals(SemanticLocation.GARDEN, result.snapshot().location().get());
        assertEquals(new BigDecimal("1234"), result.snapshot().economy().purse().get());
        assertEquals(5, result.snapshot().garden().totalPests().get());
        assertEquals(2, result.snapshot().garden().currentPlotPests().get());
        assertEquals(GardenCrop.WHEAT,
                result.snapshot().jacob().currentContest().get().crop().get());
        assertEquals(BuffStatus.ACTIVE, result.snapshot().buffs().cookie().get());
    }

    @Test
    void componentConversionIsBoundedAndOversizedLinesBecomeUnknown() {
        assertEquals("visible", MinecraftGameTextSnapshotSource.bounded(
                Component.literal("visible")).get());
        assertTrue(MinecraftGameTextSnapshotSource.bounded(Component.literal(
                "x".repeat(GameTextInputBudget.MAX_LINE_CHARACTERS + 1))).isUnknown());
    }

    @Test
    void tabComparatorUsesVanillaDescendingTabOrderBeforeOtherKeys() {
        PlayerInfo lower = new PlayerInfo(new GameProfile(UUID.randomUUID(), "alpha"), false);
        PlayerInfo higher = new PlayerInfo(new GameProfile(UUID.randomUUID(), "zeta"), false);
        lower.setTabListOrder(1);
        higher.setTabListOrder(5);

        List<PlayerInfo> sorted = new ArrayList<>(List.of(lower, higher));
        sorted.sort(MinecraftGameTextSnapshotSource.tabRenderOrder());

        assertEquals(List.of(higher, lower), sorted);
    }

    private static String visibleLine(Scoreboard scoreboard, String prefix, String suffix) {
        int index = scoreboard.getPlayerTeams().size();
        String owner = "holder-" + index;
        PlayerTeam team = scoreboard.addPlayerTeam("team-" + index);
        team.setPlayerPrefix(Component.literal(prefix));
        team.setPlayerSuffix(Component.literal(suffix));
        scoreboard.addPlayerToTeam(owner, team);
        PlayerScoreEntry entry = new PlayerScoreEntry(owner, index, Component.empty(), null);
        return MinecraftGameTextSnapshotSource.visibleScoreboardLine(scoreboard, entry).get();
    }

    private static ClientSnapshot multiplayer() {
        return new ClientSnapshot(
                Observation.present(new PlayerSnapshot(
                        Observation.unknown(), Observation.unknown(), Observation.unknown(),
                        Observation.unknown(), Observation.unknown())),
                Observation.present(new WorldSnapshot(3L, Observation.present(
                        ResourceIdentifier.parse("minecraft:overworld")))),
                Observation.present(ConnectionSnapshot.multiplayer()),
                Observation.absent());
    }
}

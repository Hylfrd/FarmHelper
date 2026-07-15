package dev.hylfrd.farmhelper.client.platform;

import com.mojang.authlib.GameProfile;
import net.minecraft.ChatFormatting;
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
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
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

    @Test
    void visibleScoreboardUsesVanillaLimitHiddenFilterAndCaseInsensitiveTieOrder() {
        List<PlayerScoreEntry> raw = new ArrayList<>();
        raw.add(new PlayerScoreEntry("beta", 100, Component.empty(), null));
        raw.add(new PlayerScoreEntry("Alpha", 100, Component.empty(), null));
        raw.add(new PlayerScoreEntry("alpha", 100, Component.empty(), null));
        raw.add(new PlayerScoreEntry("#hidden", 10_000, Component.empty(), null));
        for (int index = 0; index < 20; index++) {
            raw.add(new PlayerScoreEntry("owner-" + index, 99 - index, Component.empty(), null));
        }

        List<PlayerScoreEntry> visible = MinecraftGameTextSnapshotSource
                .visibleScoreboardEntries(raw).get();

        assertEquals(15, visible.size());
        assertEquals(List.of("Alpha", "alpha", "beta"), visible.subList(0, 3).stream()
                .map(PlayerScoreEntry::owner).toList());
        assertTrue(visible.stream().noneMatch(PlayerScoreEntry::isHidden));
    }

    @Test
    void rawScoreboardBudgetFailsClosedBeforeVisibleFilteringOrSorting() {
        List<PlayerScoreEntry> raw = new ArrayList<>();
        for (int index = 0; index <= GameTextInputBudget.MAX_SCOREBOARD_RAW_ENTRIES; index++) {
            raw.add(new PlayerScoreEntry("#hidden-" + index, index, Component.empty(), null));
        }

        assertTrue(MinecraftGameTextSnapshotSource.visibleScoreboardEntries(raw).isUnknown());
    }

    @Test
    void sortedVisibleScoreboardPreservesJacobParserAdjacency() {
        Scoreboard scoreboard = new Scoreboard();
        List<PlayerScoreEntry> rawEntries = List.of(
                decoratedEntry(scoreboard, "medal", 97, "GOLD with ", "1,234"),
                decoratedEntry(scoreboard, "crop", 99, "Wheat ", "2m30s"),
                decoratedEntry(scoreboard, "header", 100, "Jacob's ", "Contest"),
                decoratedEntry(scoreboard, "collected", 98, "Collected ", "1,234"));
        List<String> lines = MinecraftGameTextSnapshotSource.visibleScoreboardEntries(rawEntries)
                .get().stream()
                .map(entry -> MinecraftGameTextSnapshotSource
                        .visibleScoreboardLine(scoreboard, entry).get())
                .toList();
        RawGameTextSnapshot raw = new RawGameTextSnapshot(
                Observation.present("SKYBLOCK"),
                Observation.present(lines),
                Observation.present(List.of("Area: Garden")),
                Observation.present(List.of()),
                Observation.present(List.of()),
                Observation.present(List.of()),
                PlayerFacts.unknown(),
                Observation.present(WorldTransition.STABLE),
                4L);

        GameStateParseResult result = new GameStateParser().parse(multiplayer(), raw);

        assertEquals(List.of("Jacob's Contest", "Wheat 2m30s", "Collected 1,234",
                "GOLD with 1,234"), lines);
        assertEquals(GardenCrop.WHEAT,
                result.snapshot().jacob().currentContest().get().crop().get());
    }

    @Test
    void teamColorSidebarObjectiveTakesPriorityAndFallsBackToGenericSidebar() {
        Scoreboard scoreboard = new Scoreboard();
        Objective generic = objective(scoreboard, "generic", "Generic");
        Objective teamObjective = objective(scoreboard, "red", "Red");
        scoreboard.setDisplayObjective(DisplaySlot.SIDEBAR, generic);
        scoreboard.setDisplayObjective(DisplaySlot.TEAM_RED, teamObjective);
        PlayerTeam team = scoreboard.addPlayerTeam("local-red");
        team.setColor(ChatFormatting.RED);
        scoreboard.addPlayerToTeam("local-player", team);

        assertEquals(teamObjective, MinecraftGameTextSnapshotSource.visibleSidebarObjective(
                scoreboard, "local-player"));

        scoreboard.setDisplayObjective(DisplaySlot.TEAM_RED, null);
        assertEquals(generic, MinecraftGameTextSnapshotSource.visibleSidebarObjective(
                scoreboard, "local-player"));
        assertEquals(generic, MinecraftGameTextSnapshotSource.visibleSidebarObjective(
                scoreboard, null));
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

    private static PlayerScoreEntry decoratedEntry(
            Scoreboard scoreboard,
            String owner,
            int score,
            String prefix,
            String suffix) {
        PlayerTeam team = scoreboard.addPlayerTeam("team-" + owner);
        team.setPlayerPrefix(Component.literal(prefix));
        team.setPlayerSuffix(Component.literal(suffix));
        scoreboard.addPlayerToTeam(owner, team);
        return new PlayerScoreEntry(owner, score, Component.empty(), null);
    }

    private static Objective objective(Scoreboard scoreboard, String name, String title) {
        return scoreboard.addObjective(
                name,
                ObjectiveCriteria.DUMMY,
                Component.literal(title),
                ObjectiveCriteria.RenderType.INTEGER,
                false,
                null);
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

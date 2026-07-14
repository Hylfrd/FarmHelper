package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameStateParserTest {
    private final GameStateParser parser = new GameStateParser();

    @Test
    void parsesAllSharedFieldsIncludingLegalZeroAndUnicode() {
        RawGameTextSnapshot raw = raw(
                "§aSKYBLOCK",
                List.of(
                        "Purse: 1\u202f234,5 (+25,5)",
                        "Bits: 0",
                        "Copper: 9,876",
                        "Jacob's Contest",
                        "Wheat 2m30s",
                        "Collected 1,234",
                        "GOLD with 1,234",
                        "The Garden ൠ x0",
                        "Plot 12 x0",
                        "Server closing: 1:05 soon"),
                List.of(
                        "§bArea:\u00a0Garden",
                        "Guests (0)",
                        "Plots:",
                        "Bonus: +10%",
                        "Starts In: 3m 05s",
                        "Carrot", "Potato", "Sugar Cane",
                        "Spray: Example Spray",
                        "Time Left: 4m",
                        "Organic Matter: 1.234k",
                        "Fuel: 0k"),
                List.of("Active Effects", "Cookie Buff", "You have a God Potion active!", "Pest Repellent 59m"),
                List.of("Vacuum Bag: 1,234 Pests"),
                List.of(
                        new RawChatMessage(1, "§6§lYUCK! A pest appeared."),
                        new RawChatMessage(1, "§6§lYUCK! A pest appeared."),
                        new RawChatMessage(2, "YUM! ൠ Pests will now spawn twice as fast!"),
                        new RawChatMessage(3, "[SkyBlock] Anonymous is visiting Your Garden!"),
                        new RawChatMessage(4, "Server is restarting! Evacuate!")),
                new PlayerFacts(Observation.present(false), Observation.present(12), Observation.present(0.1D)),
                Observation.present(WorldTransition.STABLE));

        GameStateParseResult result = parser.parse(multiplayer(), raw);
        GameStateSnapshot state = result.snapshot();

        assertEquals(SemanticLocation.GARDEN, state.location().get());
        assertEquals(true, state.skyBlock().get());
        assertEquals(true, state.inGarden().get());
        assertEquals(65, state.serverClosingSeconds().get());
        assertEquals(new BigDecimal("1234.5"), state.economy().purse().get());
        assertEquals(0L, state.economy().bits().get());
        assertEquals(9_876L, state.economy().copper().get());
        assertEquals(100, state.economy().speed().get());

        JacobContestSnapshot current = state.jacob().currentContest().get();
        assertEquals(150, current.remainingSeconds().get());
        assertEquals(GardenCrop.WHEAT, current.crop().get());
        assertEquals(1_234L, current.collected().get());
        assertEquals(JacobMedal.GOLD, current.medal().get());
        JacobNextContestSnapshot next = state.jacob().nextContest().get();
        assertEquals(185, next.startsInSeconds().get());
        assertEquals(List.of(GardenCrop.CARROT, GardenCrop.POTATO, GardenCrop.SUGAR_CANE), next.crops().get());

        assertEquals(BuffStatus.ACTIVE, state.buffs().cookie().get());
        assertEquals(BuffStatus.ACTIVE, state.buffs().godPotion().get());
        assertEquals(BuffStatus.ACTIVE, state.buffs().pestRepellent().get());
        assertEquals(BuffStatus.ACTIVE, state.buffs().pestHunter().get());
        assertEquals(BuffStatus.ACTIVE, state.buffs().sprayonator().get());
        assertEquals(BuffStatus.ACTIVE, state.buffs().composter().get());

        assertEquals(false, state.garden().guestPresent().get());
        assertEquals(0, state.garden().totalPests().get());
        assertEquals(0, state.garden().currentPlotPests().get());
        assertEquals(List.of(), state.garden().infestedPlots().get());
        assertEquals(1_234, state.garden().vacuumPests().get());
        assertEquals(1_234L, state.garden().composterOrganicMatter().get());
        assertEquals(0L, state.garden().composterFuel().get());
        assertEquals(List.of(
                new GameChatSignal(1, GameChatSignalType.PEST_SPAWNED),
                new GameChatSignal(2, GameChatSignalType.REPELLENT_ACTIVATED),
                new GameChatSignal(3, GameChatSignalType.GUEST_ARRIVED),
                new GameChatSignal(4, GameChatSignalType.EVACUATION_REQUIRED)), result.chatSignals().get());
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test
    void propagatesLocationStatesAndUsesEvidencePriority() {
        RawGameTextSnapshot garden = minimalRaw(List.of("Area: Garden"), Observation.present(WorldTransition.STABLE));
        assertTrue(parser.parse(singleplayer(), garden).snapshot().location().isAbsent());
        assertTrue(parser.parse(ClientSnapshot.unknown(), garden).snapshot().location().isUnknown());

        ClientSnapshot noPlayer = new ClientSnapshot(
                Observation.absent(), Observation.present(world("overworld")),
                Observation.present(ConnectionSnapshot.multiplayer()), Observation.absent());
        assertTrue(parser.parse(noPlayer, garden).snapshot().location().isAbsent());

        RawGameTextSnapshot changing = minimalRaw(
                List.of("Area: Garden"), Observation.present(WorldTransition.CHANGING));
        assertEquals(SemanticLocation.TELEPORTING,
                parser.parse(multiplayer(), changing).snapshot().location().get());

        RawGameTextSnapshot limboChat = withChat(changing,
                List.of(new RawChatMessage(7, "You were spawned in Limbo.")));
        assertEquals(SemanticLocation.LIMBO,
                parser.parse(multiplayer(), limboChat).snapshot().location().get());

        RawGameTextSnapshot structural = new RawGameTextSnapshot(
                Observation.present(""), Observation.present(List.of()), Observation.present(List.of()),
                Observation.present(List.of()), Observation.absent(), Observation.present(List.of()),
                new PlayerFacts(Observation.present(true), Observation.present(0), Observation.present(0.1D)),
                Observation.present(WorldTransition.STABLE), 2);
        assertEquals(SemanticLocation.LIMBO,
                parser.parse(multiplayer("the_end"), structural).snapshot().location().get());
    }

    @Test
    void recognizesLobbyAndRefusesUnknownAreaOrMissingEvidence() {
        RawGameTextSnapshot lobby = raw(
                "BED WARS", List.of("www.hypixel.net"), List.of(), List.of(), List.of(), List.of(),
                PlayerFacts.unknown(), Observation.present(WorldTransition.STABLE));
        GameStateSnapshot lobbyState = parser.parse(multiplayer(), lobby).snapshot();
        assertEquals(SemanticLocation.LOBBY, lobbyState.location().get());
        assertEquals(false, lobbyState.skyBlock().get());

        GameStateParseResult unknownArea = parser.parse(multiplayer(),
                minimalRaw(List.of("Area: New Unknown Island"), Observation.present(WorldTransition.STABLE)));
        assertTrue(unknownArea.snapshot().location().isUnknown());
        assertTrue(unknownArea.diagnostics().contains(
                new ParseDiagnostic("location.area", ParseDiagnosticCode.UNKNOWN_FORMAT)));

        GameStateParseResult conflict = parser.parse(multiplayer(),
                minimalRaw(List.of("Area: Garden", "Area: Hub"), Observation.present(WorldTransition.STABLE)));
        assertTrue(conflict.snapshot().location().isUnknown());
        assertTrue(conflict.diagnostics().contains(
                new ParseDiagnostic("location.area", ParseDiagnosticCode.CONFLICT)));

        RawGameTextSnapshot missing = RawGameTextSnapshot.unknown(3);
        assertTrue(parser.parse(multiplayer(), missing).snapshot().location().isUnknown());
    }

    @Test
    void parsesPositiveGardenCountsAndExplicitInactiveAndZeroStates() {
        RawGameTextSnapshot raw = raw(
                "SKYBLOCK",
                List.of(
                        "Purse: 0",
                        "Bits: 25",
                        "Copper: 0",
                        "The Garden ൠ x7",
                        "Plot 4 x2",
                        "Server closing: 0:00 now"),
                List.of(
                        "Area: Garden",
                        "Guests (2)",
                        "Plots: 1, 20",
                        "Bonus: INACTIVE",
                        "Spray: None",
                        "Time Left: INACTIVE"),
                List.of("Active Effects", "Cookie Buff", "Not active"),
                List.of("Vacuum Bag: 0 Pests"),
                List.of(),
                new PlayerFacts(Observation.present(false), Observation.present(0), Observation.present(0.0D)),
                Observation.present(WorldTransition.STABLE));

        GameStateSnapshot state = parser.parse(multiplayer(), raw).snapshot();

        assertEquals(BigDecimal.ZERO, state.economy().purse().get());
        assertEquals(25L, state.economy().bits().get());
        assertEquals(0L, state.economy().copper().get());
        assertEquals(0, state.economy().speed().get());
        assertEquals(0, state.serverClosingSeconds().get());
        assertEquals(true, state.garden().guestPresent().get());
        assertEquals(7, state.garden().totalPests().get());
        assertEquals(2, state.garden().currentPlotPests().get());
        assertEquals(List.of(1, 20), state.garden().infestedPlots().get());
        assertEquals(0, state.garden().vacuumPests().get());
        assertEquals(BuffStatus.INACTIVE, state.buffs().cookie().get());
        assertEquals(BuffStatus.INACTIVE, state.buffs().godPotion().get());
        assertEquals(BuffStatus.INACTIVE, state.buffs().pestRepellent().get());
        assertEquals(BuffStatus.INACTIVE, state.buffs().pestHunter().get());
        assertEquals(BuffStatus.INACTIVE, state.buffs().sprayonator().get());
        assertEquals(BuffStatus.INACTIVE, state.buffs().composter().get());
    }

    @Test
    void distinguishesAbsentMalformedConflictingAndOverflowingFields() {
        GameStateSnapshot absent = parser.parse(multiplayer(), raw(
                "SKYBLOCK", List.of(), List.of("Area: Garden"), List.of("Active Effects"),
                List.of(), List.of(), PlayerFacts.unknown(), Observation.present(WorldTransition.STABLE))).snapshot();
        assertTrue(absent.economy().bits().isAbsent());
        assertTrue(absent.garden().totalPests().isAbsent());
        assertTrue(absent.garden().vacuumPests().isAbsent());

        RawGameTextSnapshot malformed = raw(
                "SKYBLOCK",
                List.of("Bits: 12,34,567", "Copper: 1", "Copper: 2", "The Garden ൠ xwat"),
                List.of("Area: Garden", "Organic Matter: 2.5k"),
                List.of("footer truncated"),
                List.of("Vacuum Bag: 999999999999 Pests"), List.of(),
                new PlayerFacts(Observation.present(false), Observation.present(1), Observation.present(0.1005D)),
                Observation.present(WorldTransition.STABLE));
        GameStateParseResult result = parser.parse(multiplayer(), malformed);
        assertTrue(result.snapshot().economy().bits().isUnknown());
        assertTrue(result.snapshot().economy().copper().isUnknown());
        assertTrue(result.snapshot().garden().totalPests().isUnknown());
        assertTrue(result.snapshot().garden().vacuumPests().isUnknown());
        assertEquals(100, result.snapshot().economy().speed().get());
        assertTrue(result.snapshot().buffs().cookie().isUnknown());
        assertEquals(2_500L, result.snapshot().garden().composterOrganicMatter().get());
        assertTrue(result.snapshot().garden().composterFuel().isAbsent());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code() == ParseDiagnosticCode.MALFORMED));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code() == ParseDiagnosticCode.CONFLICT));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code() == ParseDiagnosticCode.OVERFLOW));

        GameStateParseResult overflow = parser.parse(multiplayer(), raw(
                "SKYBLOCK", List.of(
                        "Purse: 9,223,372,036,854,775,808",
                        "Bits: 9,223,372,036,854,775,808",
                        "Server closing: 999999999:59 soon"),
                List.of("Area: Garden"), List.of("Active Effects"), List.of(), List.of(),
                PlayerFacts.unknown(), Observation.present(WorldTransition.STABLE)));
        assertTrue(overflow.snapshot().economy().purse().isUnknown());
        assertTrue(overflow.snapshot().economy().bits().isUnknown());
        assertTrue(overflow.snapshot().serverClosingSeconds().isUnknown());
        assertTrue(overflow.diagnostics().stream().allMatch(d -> d.code() == ParseDiagnosticCode.OVERFLOW));

        GameStateParseResult fractionalInteger = parser.parse(multiplayer(), raw(
                "SKYBLOCK", List.of("Bits: 1.5"), List.of("Area: Garden"),
                List.of("Active Effects"), List.of(), List.of(), PlayerFacts.unknown(),
                Observation.present(WorldTransition.STABLE)));
        assertTrue(fractionalInteger.snapshot().economy().bits().isUnknown());
        assertTrue(fractionalInteger.diagnostics().contains(
                new ParseDiagnostic("economy.bits", ParseDiagnosticCode.MALFORMED)));
    }

    @Test
    void marksTruncatedJacobAndUnknownSourcesWithoutInventingValues() {
        RawGameTextSnapshot truncated = raw(
                "SKYBLOCK", List.of("Jacob's Contest", "Mysterious Crop 2m99s"),
                List.of("Area: Garden", "Starts In: 2m", "Wheat"),
                List.of("Active Effects"), List.of(), List.of(), PlayerFacts.unknown(),
                Observation.present(WorldTransition.STABLE));
        GameStateParseResult result = parser.parse(multiplayer(), truncated);
        JacobContestSnapshot current = result.snapshot().jacob().currentContest().get();
        assertTrue(current.remainingSeconds().isUnknown());
        assertTrue(current.crop().isUnknown());
        assertTrue(current.collected().isAbsent());
        assertTrue(current.medal().isAbsent());
        assertTrue(result.snapshot().jacob().nextContest().get().crops().isUnknown());

        GameStateSnapshot unknown = parser.parse(multiplayer(), RawGameTextSnapshot.unknown(4)).snapshot();
        assertTrue(unknown.economy().purse().isUnknown());
        assertTrue(unknown.jacob().currentContest().isUnknown());
        assertTrue(unknown.buffs().cookie().isUnknown());
        assertTrue(unknown.garden().infestedPlots().isUnknown());
        assertTrue(parser.parse(multiplayer(), RawGameTextSnapshot.unknown(5)).chatSignals().isUnknown());
    }

    @Test
    void conflictingSequenceMakesTheBatchUnknownWithoutLeakingText() {
        String sensitive = "[SkyBlock] HiddenIdentity is visiting Your Garden!";
        RawGameTextSnapshot raw = withChat(
                minimalRaw(List.of("Area: Garden"), Observation.present(WorldTransition.STABLE)),
                List.of(
                        new RawChatMessage(20, sensitive),
                        new RawChatMessage(20, "You were spawned in Limbo."),
                        new RawChatMessage(21, "You bought Pest Repellent")));
        GameStateParseResult result = parser.parse(multiplayer(), raw);

        assertTrue(result.chatSignals().isUnknown());
        assertEquals(List.of(new ParseDiagnostic(
                        "chat.sequence", ParseDiagnosticCode.CHAT_SEQUENCE_CONFLICT)),
                result.diagnostics());
        assertFalse(result.diagnostics().toString().contains("HiddenIdentity"));
        assertFalse(result.diagnostics().toString().contains(sensitive));
    }

    @Test
    void appliesUpstreamSpeedTruncationAndRejectsUnsafeValues() {
        assertEquals(0, speed(0.0D).get());
        assertEquals(100, speed(0.1005D).get());
        assertEquals(251, speed(0.2519D).get());
        double maximum = (double) Integer.MAX_VALUE / 1_000.0D;
        assertEquals(Integer.MAX_VALUE, speed(maximum).get());
        assertTrue(speed(-0.001D).isUnknown());
        assertTrue(speed(Double.NaN).isUnknown());
        assertTrue(speed(Double.POSITIVE_INFINITY).isUnknown());
        assertTrue(speed(Math.nextUp(maximum)).isUnknown());
    }

    @Test
    void ordersContinuousChatAndDiagnosesReorderWithoutChangingSignalOrder() {
        GameStateParseResult result = chatResult(List.of(
                new RawChatMessage(2, "§6Server is restarting! Evacuate!"),
                new RawChatMessage(1, "§aYUCK! A pest appeared.")));

        assertEquals(List.of(
                new GameChatSignal(1, GameChatSignalType.PEST_SPAWNED),
                new GameChatSignal(2, GameChatSignalType.EVACUATION_REQUIRED)),
                result.chatSignals().get());
        assertEquals(List.of(new ParseDiagnostic(
                "chat.sequence", ParseDiagnosticCode.CHAT_SEQUENCE_REORDER)), result.diagnostics());
    }

    @Test
    void rejectsSequenceGapsButAllowsArbitraryContinuousStartingPointsAndExactDuplicates() {
        GameStateParseResult gap = chatResult(List.of(
                new RawChatMessage(1, "YUCK!"),
                new RawChatMessage(3, "Server is restarting! Evacuate!")));
        assertTrue(gap.chatSignals().isUnknown());
        assertEquals(List.of(new ParseDiagnostic(
                "chat.sequence", ParseDiagnosticCode.CHAT_SEQUENCE_GAP)), gap.diagnostics());

        GameStateParseResult continuous = chatResult(List.of(
                new RawChatMessage(5, "YUCK!"),
                new RawChatMessage(6, "Server is restarting! Evacuate!")));
        assertEquals(List.of(
                new GameChatSignal(5, GameChatSignalType.PEST_SPAWNED),
                new GameChatSignal(6, GameChatSignalType.EVACUATION_REQUIRED)),
                continuous.chatSignals().get());
        assertTrue(continuous.diagnostics().isEmpty());

        RawChatMessage duplicate = new RawChatMessage(42, "system", "YUCK!");
        GameStateParseResult deduplicated = chatResult(List.of(duplicate, duplicate));
        assertEquals(List.of(new GameChatSignal(42, GameChatSignalType.PEST_SPAWNED)),
                deduplicated.chatSignals().get());
        assertTrue(deduplicated.diagnostics().isEmpty());

        GameStateParseResult boundary = chatResult(List.of(
                new RawChatMessage(Long.MAX_VALUE - 1, "YUCK!"),
                new RawChatMessage(Long.MAX_VALUE, "Server is restarting! Evacuate!")));
        assertTrue(boundary.chatSignals().isPresent());
        assertEquals(2, boundary.chatSignals().get().size());
        assertTrue(boundary.diagnostics().isEmpty());
    }

    @Test
    void treatsChannelDifferencesAsConflictingDuplicateContent() {
        GameStateParseResult result = chatResult(List.of(
                new RawChatMessage(7, "system", "YUCK!"),
                new RawChatMessage(7, "game_info", "YUCK!")));

        assertTrue(result.chatSignals().isUnknown());
        assertEquals(List.of(new ParseDiagnostic(
                "chat.sequence", ParseDiagnosticCode.CHAT_SEQUENCE_CONFLICT)), result.diagnostics());
    }

    @Test
    void reportsInputLimitsWithoutRetainingRawTextOrParsingOversizedNumbers() {
        String number = "9".repeat(GameTextInputBudget.MAX_NUMERIC_TOKEN_CHARACTERS + 1);
        GameStateParseResult numeric = parser.parse(multiplayer(), raw(
                "SKYBLOCK", List.of("Purse: " + number, "Bits: " + number),
                List.of("Area: Garden"), List.of("Active Effects"), List.of(), List.of(),
                PlayerFacts.unknown(), Observation.present(WorldTransition.STABLE)));
        assertTrue(numeric.snapshot().economy().purse().isUnknown());
        assertTrue(numeric.snapshot().economy().bits().isUnknown());
        assertTrue(numeric.diagnostics().contains(
                new ParseDiagnostic("economy.purse", ParseDiagnosticCode.INPUT_LIMIT)));
        assertTrue(numeric.diagnostics().contains(
                new ParseDiagnostic("economy.bits", ParseDiagnosticCode.INPUT_LIMIT)));
        assertFalse(numeric.diagnostics().toString().contains(number));

        String overLine = "x".repeat(GameTextInputBudget.MAX_LINE_CHARACTERS + 1);
        GameStateParseResult line = parser.parse(multiplayer(), raw(
                "SKYBLOCK", List.of(overLine), List.of("Area: Garden"),
                List.of("Active Effects"), List.of(), List.of(), PlayerFacts.unknown(),
                Observation.present(WorldTransition.STABLE)));
        assertTrue(line.snapshot().economy().bits().isUnknown());
        assertTrue(line.diagnostics().contains(new ParseDiagnostic(
                "input.scoreboard.lines", ParseDiagnosticCode.INPUT_LIMIT)));
        assertFalse(line.diagnostics().toString().contains(overLine));
    }

    @Test
    void stripsOnlyLegalFormattingAndPreservesPestGlyph() {
        assertEquals("ൠ value", GameStateParser.clean("§aൠ\u00a0value"));
        assertEquals("§zkeep", GameStateParser.clean("§zkeep"));
        assertEquals("§xkeep", GameStateParser.clean("§xkeep"));
        assertEquals("RGB", GameStateParser.clean("§x§1§2§3§4§5§6RGB"));
        assertEquals("§", GameStateParser.clean("§"));
        assertEquals("§x", GameStateParser.clean("§x§1§2§3§4§5"));
    }

    private Observation<Integer> speed(double factor) {
        RawGameTextSnapshot raw = raw(
                "SKYBLOCK", List.of(), List.of("Area: Garden"), List.of("Active Effects"),
                List.of(), List.of(),
                new PlayerFacts(Observation.present(false), Observation.present(0), Observation.present(factor)),
                Observation.present(WorldTransition.STABLE));
        return parser.parse(multiplayer(), raw).snapshot().economy().speed();
    }

    private GameStateParseResult chatResult(List<RawChatMessage> chat) {
        return parser.parse(multiplayer(), withChat(
                minimalRaw(List.of("Area: Garden"), Observation.present(WorldTransition.STABLE)), chat));
    }

    private static RawGameTextSnapshot minimalRaw(
            List<String> tab,
            Observation<WorldTransition> transition
    ) {
        return raw("SKYBLOCK", List.of(), tab, List.of("Active Effects"), List.of(), List.of(),
                PlayerFacts.unknown(), transition);
    }

    private static RawGameTextSnapshot withChat(
            RawGameTextSnapshot source,
            List<RawChatMessage> chat
    ) {
        return new RawGameTextSnapshot(
                source.scoreboardTitle(), source.scoreboardLines(), source.tabLines(), source.tabFooter(),
                source.vacuumLore(), Observation.present(chat), source.playerFacts(),
                source.worldTransition(), source.generation());
    }

    private static RawGameTextSnapshot raw(
            String title,
            List<String> scoreboard,
            List<String> tab,
            List<String> footer,
            List<String> lore,
            List<RawChatMessage> chat,
            PlayerFacts facts,
            Observation<WorldTransition> transition
    ) {
        return new RawGameTextSnapshot(
                Observation.present(title), Observation.present(scoreboard), Observation.present(tab),
                Observation.present(footer), Observation.present(lore), Observation.present(chat),
                facts, transition, 1);
    }

    private static ClientSnapshot multiplayer() {
        return multiplayer("overworld");
    }

    private static ClientSnapshot multiplayer(String dimension) {
        return new ClientSnapshot(
                Observation.present(player()), Observation.present(world(dimension)),
                Observation.present(ConnectionSnapshot.multiplayer()), Observation.absent());
    }

    private static ClientSnapshot singleplayer() {
        return new ClientSnapshot(
                Observation.present(player()), Observation.present(world("overworld")),
                Observation.present(ConnectionSnapshot.singleplayer()), Observation.absent());
    }

    private static PlayerSnapshot player() {
        return new PlayerSnapshot(
                Observation.unknown(), Observation.unknown(), Observation.unknown(),
                Observation.unknown(), Observation.unknown());
    }

    private static WorldSnapshot world(String dimension) {
        return new WorldSnapshot(Observation.present(ResourceIdentifier.parse("minecraft:" + dimension)));
    }
}

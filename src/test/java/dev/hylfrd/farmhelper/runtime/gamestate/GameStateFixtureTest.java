package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameStateFixtureTest {
    @Test
    void parsesTheAnonymousFrozenShapeFixture() throws IOException {
        List<String> lines;
        try (var stream = GameStateFixtureTest.class.getResourceAsStream(
                "/dev/hylfrd/farmhelper/runtime/gamestate/s2t5/garden-positive.txt")) {
            lines = new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
        }
        Map<String, List<String>> sections = sections(lines);
        List<String> scoreboard = sections.get("scoreboard");
        RawGameTextSnapshot raw = new RawGameTextSnapshot(
                Observation.present(scoreboard.getFirst()),
                Observation.present(scoreboard.subList(1, scoreboard.size())),
                Observation.present(sections.get("tab")),
                Observation.present(sections.get("footer")),
                Observation.present(sections.get("lore")),
                Observation.present(List.of()),
                new PlayerFacts(Observation.present(false), Observation.present(4), Observation.present(0.1D)),
                Observation.present(WorldTransition.STABLE),
                11);

        GameStateParseResult result = new GameStateParser().parse(multiplayer(), raw);

        assertTrue(result.diagnostics().isEmpty());
        assertEquals(SemanticLocation.GARDEN, result.snapshot().location().get());
        assertEquals(0L, result.snapshot().economy().bits().get());
        assertEquals(0, result.snapshot().garden().totalPests().get());
        assertEquals(0L, result.snapshot().garden().composterFuel().get());
        assertEquals(1_234, result.snapshot().garden().vacuumPests().get());
        assertTrue(result.chatSignals().isPresent());
        assertTrue(result.chatSignals().get().isEmpty());
    }

    private static Map<String, List<String>> sections(List<String> lines) {
        Map<String, List<String>> sections = new LinkedHashMap<>();
        List<String> current = null;
        for (String line : lines) {
            if (line.startsWith("[") && line.endsWith("]")) {
                current = new ArrayList<>();
                sections.put(line.substring(1, line.length() - 1), current);
            } else if (current != null) {
                current.add(line);
            }
        }
        return sections;
    }

    private static ClientSnapshot multiplayer() {
        PlayerSnapshot player = new PlayerSnapshot(
                Observation.unknown(), Observation.unknown(), Observation.unknown(),
                Observation.unknown(), Observation.unknown());
        WorldSnapshot world = new WorldSnapshot(
                Observation.present(ResourceIdentifier.parse("minecraft:overworld")));
        return new ClientSnapshot(
                Observation.present(player), Observation.present(world),
                Observation.present(ConnectionSnapshot.multiplayer()), Observation.absent());
    }
}

package dev.hylfrd.farmhelper.runtime.gamestate;

import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticSinkTest {
    @Test
    void rateLimitsPerStableKeyWithInjectedMonotonicWindow() {
        AtomicLong now = new AtomicLong(100);
        List<ParseDiagnostic> emitted = new ArrayList<>();
        RateLimitedDiagnosticSink sink = new RateLimitedDiagnosticSink(emitted::add, now::get, 50);
        ParseDiagnostic malformedBits = new ParseDiagnostic("economy.bits", ParseDiagnosticCode.MALFORMED);
        ParseDiagnostic overflowBits = new ParseDiagnostic("economy.bits", ParseDiagnosticCode.OVERFLOW);

        sink.accept(malformedBits);
        sink.accept(malformedBits);
        sink.accept(overflowBits);
        now.set(149);
        sink.accept(malformedBits);
        now.set(150);
        sink.accept(malformedBits);

        assertEquals(List.of(malformedBits, overflowBits, malformedBits), emitted);
    }

    @Test
    void toleratesClockRollbackAndRejectsInvalidWindow() {
        AtomicLong now = new AtomicLong(10);
        List<ParseDiagnostic> emitted = new ArrayList<>();
        RateLimitedDiagnosticSink sink = new RateLimitedDiagnosticSink(emitted::add, now::get, 5);
        ParseDiagnostic diagnostic = new ParseDiagnostic("garden.vacuum", ParseDiagnosticCode.MALFORMED);
        sink.accept(diagnostic);
        now.set(1);
        sink.accept(diagnostic);
        assertEquals(List.of(diagnostic), emitted);
        assertThrows(IllegalArgumentException.class,
                () -> new RateLimitedDiagnosticSink(emitted::add, now::get, -1));
    }

    @Test
    void runtimeExceptionFromSinkCannotBreakOrAlterParseResult() {
        AtomicInteger calls = new AtomicInteger();
        GameStateParser parser = new GameStateParser(diagnostic -> {
            calls.incrementAndGet();
            throw new IllegalStateException("sink unavailable");
        });
        GameStateParseResult result = parseMalformedBits(parser);

        assertParseResultPreserved(result);
        assertEquals(1, calls.get());
    }

    @Test
    void assertionErrorFromSinkCannotBreakOrAlterParseResult() {
        AtomicInteger calls = new AtomicInteger();
        GameStateParser parser = new GameStateParser(diagnostic -> {
            calls.incrementAndGet();
            throw new AssertionError("sink unavailable");
        });
        GameStateParseResult result = parseMalformedBits(parser);

        assertParseResultPreserved(result);
        assertEquals(1, calls.get());
    }

    private static GameStateParseResult parseMalformedBits(GameStateParser parser) {
        RawGameTextSnapshot raw = new RawGameTextSnapshot(
                Observation.present("SKYBLOCK"), Observation.present(List.of("Bits: broken")),
                Observation.present(List.of("Area: Garden")), Observation.present(List.of("Active Effects")),
                Observation.present(List.of()), Observation.present(List.of()), PlayerFacts.unknown(),
                Observation.present(WorldTransition.STABLE), 8);

        return parser.parse(multiplayer(), raw);
    }

    private static void assertParseResultPreserved(GameStateParseResult result) {
        assertTrue(result.snapshot().economy().bits().isUnknown());
        assertEquals(List.of(new ParseDiagnostic("economy.bits", ParseDiagnosticCode.MALFORMED)),
                result.diagnostics());
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

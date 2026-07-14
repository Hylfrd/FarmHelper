package dev.hylfrd.farmhelper.runtime.gamestate;

/** Destination for privacy-safe parser diagnostics. */
@FunctionalInterface
public interface DiagnosticSink {
    DiagnosticSink NOOP = diagnostic -> { };

    void accept(ParseDiagnostic diagnostic);
}

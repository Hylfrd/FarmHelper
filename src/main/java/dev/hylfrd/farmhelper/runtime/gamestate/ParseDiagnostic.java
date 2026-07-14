package dev.hylfrd.farmhelper.runtime.gamestate;

import java.util.Objects;

/** A diagnostic key and category with no raw game text or player/server data. */
public record ParseDiagnostic(String field, ParseDiagnosticCode code) {
    public ParseDiagnostic {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(code, "code");
        if (!field.matches("[a-z][a-z0-9.]*")) {
            throw new IllegalArgumentException("field must be a stable parser key");
        }
    }

    public String key() {
        return field + ':' + code.name();
    }
}

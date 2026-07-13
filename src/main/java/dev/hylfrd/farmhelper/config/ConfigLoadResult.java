package dev.hylfrd.farmhelper.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record ConfigLoadResult(
        FarmHelperConfig config,
        ConfigLoadStatus status,
        Path backupPath,
        String diagnostic
) {
    public ConfigLoadResult {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(status, "status");
        diagnostic = diagnostic == null ? "" : diagnostic;
    }

    public Optional<Path> backup() {
        return Optional.ofNullable(backupPath);
    }
}

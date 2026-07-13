package dev.hylfrd.farmhelper.config;

public enum ConfigLoadStatus {
    LOADED,
    CREATED_DEFAULTS,
    MIGRATED,
    RECOVERED_DEFAULTS,
    DEFAULTS_NOT_SAVED
}

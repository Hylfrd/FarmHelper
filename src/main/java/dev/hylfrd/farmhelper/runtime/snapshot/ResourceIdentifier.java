package dev.hylfrd.farmhelper.runtime.snapshot;

import java.util.Objects;
import java.util.regex.Pattern;

/** Minecraft-free namespace and path identifier. */
public record ResourceIdentifier(String namespace, String path) {
    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_./-]+");

    public ResourceIdentifier {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException("Invalid namespace: " + namespace);
        }
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid path: " + path);
        }
    }

    public static ResourceIdentifier parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        if (separator < 0) {
            return new ResourceIdentifier("minecraft", value);
        }
        if (separator != value.lastIndexOf(':')) {
            throw new IllegalArgumentException("Invalid identifier: " + value);
        }
        return new ResourceIdentifier(value.substring(0, separator), value.substring(separator + 1));
    }

    @Override
    public String toString() {
        return namespace + ':' + path;
    }
}

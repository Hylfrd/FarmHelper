package dev.hylfrd.farmhelper.navigation.follow;

import java.util.Objects;

/** Stable platform-supplied identity for one followed target within a client session. */
public record FollowTargetIdentity(String value) {
    public FollowTargetIdentity {
        Objects.requireNonNull(value, "value");
        value = value.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("follow target identity must not be blank");
        }
    }
}

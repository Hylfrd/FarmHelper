package dev.hylfrd.farmhelper.runtime.snapshot;

import java.util.List;
import java.util.Objects;

/** Immutable player details; player existence is represented by the enclosing observation. */
public record PlayerSnapshot(
        Observation<PositionSnapshot> position,
        Observation<MotionSnapshot> motion,
        Observation<RotationSnapshot> rotation,
        Observation<ItemSummary> mainHandItem,
        Observation<List<StatusEffectSummary>> statusEffects
) {
    public PlayerSnapshot {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(rotation, "rotation");
        Objects.requireNonNull(mainHandItem, "mainHandItem");
        Objects.requireNonNull(statusEffects, "statusEffects");
        statusEffects = statusEffects.map(List::copyOf);
    }
}

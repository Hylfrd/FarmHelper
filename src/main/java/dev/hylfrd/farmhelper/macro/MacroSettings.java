package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.runtime.spatial.RewarpPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** P1-local runtime settings. Persistence remains owned by the existing config boundary. */
public final class MacroSettings {
    private VerticalCropMode mode = VerticalCropMode.NORMAL;
    private MacroSpawnPose spawn;
    private final List<RewarpPosition> rewarps = new ArrayList<>();

    public VerticalCropMode mode() {
        return mode;
    }

    public void mode(VerticalCropMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
    }

    public Optional<MacroSpawnPose> spawn() {
        return Optional.ofNullable(spawn);
    }

    public void spawn(RewarpPosition spawn) {
        spawn(new MacroSpawnPose(Objects.requireNonNull(spawn, "spawn"), 0.0F, 0.0F, -1));
    }

    public void spawn(MacroSpawnPose spawn) {
        MacroSpawnPose next = Objects.requireNonNull(spawn, "spawn");
        rewarps.removeIf(position -> position.squaredDistance(next.block()) < 4.0D);
        this.spawn = next;
    }

    public List<RewarpPosition> rewarps() {
        return List.copyOf(rewarps);
    }

    public boolean addRewarp(RewarpPosition position) {
        RewarpPosition next = Objects.requireNonNull(position, "position");
        if (spawn != null && spawn.squaredDistance(next.block()) < 4.0D) {
            return false;
        }
        if (rewarps.stream().anyMatch(existing -> existing.squaredDistance(next.block()) < 4.0D)) {
            return false;
        }
        rewarps.add(next);
        return true;
    }

    public boolean removeNearest(RewarpPosition position, double maximumDistance) {
        Objects.requireNonNull(position, "position");
        if (!Double.isFinite(maximumDistance) || maximumDistance < 0.0D) {
            throw new IllegalArgumentException("maximumDistance must be finite and non-negative");
        }
        Optional<RewarpPosition> nearest = RewarpPosition.nearest(rewarps, position.block());
        return nearest.filter(candidate -> candidate.distance(position.block()) <= maximumDistance)
                .map(rewarps::remove)
                .orElse(false);
    }

    public void clearRewarps() {
        rewarps.clear();
    }

    public void replace(
            VerticalCropMode mode,
            Optional<MacroSpawnPose> spawn,
            List<RewarpPosition> rewarps
    ) {
        VerticalCropMode validatedMode = Objects.requireNonNull(mode, "mode");
        Optional<MacroSpawnPose> validatedSpawn = Objects.requireNonNull(spawn, "spawn");
        List<RewarpPosition> validatedRewarps = List.copyOf(rewarps);
        for (int index = 0; index < validatedRewarps.size(); index++) {
            RewarpPosition candidate = Objects.requireNonNull(validatedRewarps.get(index), "rewarp");
            if (validatedSpawn.filter(value -> value.squaredDistance(candidate.block()) < 4.0D).isPresent()) {
                throw new IllegalArgumentException("rewarp overlaps spawn");
            }
            for (int prior = 0; prior < index; prior++) {
                if (validatedRewarps.get(prior).squaredDistance(candidate.block()) < 4.0D) {
                    throw new IllegalArgumentException("rewarps overlap each other");
                }
            }
        }
        this.mode = validatedMode;
        this.spawn = validatedSpawn.orElse(null);
        this.rewarps.clear();
        this.rewarps.addAll(validatedRewarps);
    }
}

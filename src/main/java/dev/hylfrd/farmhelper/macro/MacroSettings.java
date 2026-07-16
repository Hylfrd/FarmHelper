package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.runtime.spatial.RewarpPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** P1-local runtime settings. Persistence remains owned by the existing config boundary. */
public final class MacroSettings {
    private VerticalCropMode mode = VerticalCropMode.NORMAL;
    private MacroMode macroMode = MacroMode.VERTICAL_NORMAL;
    private MacroSpawnPose spawn;
    private final List<RewarpPosition> rewarps = new ArrayList<>();
    private boolean alwaysHoldW;
    private boolean holdLeftClickWhenChangingRow = true;
    private boolean rotateAfterWarped;
    private boolean rotateAfterDrop;
    private boolean dontFixAfterWarping;
    private boolean customPitch;
    private float customPitchLevel;
    private boolean customYaw;
    private float customYawLevel;

    public VerticalCropMode mode() {
        return mode;
    }

    public void mode(VerticalCropMode mode) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.macroMode = MacroMode.fromCode(mode.code()).orElseThrow();
    }

    public MacroMode macroMode() {
        return macroMode;
    }

    public void macroMode(MacroMode macroMode) {
        MacroMode next = Objects.requireNonNull(macroMode, "macroMode");
        this.macroMode = next;
        next.verticalMode().ifPresent(value -> this.mode = value);
    }

    public boolean alwaysHoldW() {
        return alwaysHoldW;
    }

    public void alwaysHoldW(boolean alwaysHoldW) {
        this.alwaysHoldW = alwaysHoldW;
    }

    public boolean holdLeftClickWhenChangingRow() {
        return holdLeftClickWhenChangingRow;
    }

    public void holdLeftClickWhenChangingRow(boolean holdLeftClickWhenChangingRow) {
        this.holdLeftClickWhenChangingRow = holdLeftClickWhenChangingRow;
    }

    public boolean rotateAfterWarped() {
        return rotateAfterWarped;
    }

    public void rotateAfterWarped(boolean rotateAfterWarped) {
        this.rotateAfterWarped = rotateAfterWarped;
    }

    public boolean rotateAfterDrop() {
        return rotateAfterDrop;
    }

    public void rotateAfterDrop(boolean rotateAfterDrop) {
        this.rotateAfterDrop = rotateAfterDrop;
    }

    public boolean dontFixAfterWarping() {
        return dontFixAfterWarping;
    }

    public void dontFixAfterWarping(boolean dontFixAfterWarping) {
        this.dontFixAfterWarping = dontFixAfterWarping;
    }

    public boolean customPitch() {
        return customPitch;
    }

    public void customPitch(boolean customPitch) {
        this.customPitch = customPitch;
    }

    public float customPitchLevel() {
        return customPitchLevel;
    }

    public void customPitchLevel(float customPitchLevel) {
        this.customPitchLevel = requireRange(customPitchLevel, -90.0F, 90.0F, "customPitchLevel");
    }

    public boolean customYaw() {
        return customYaw;
    }

    public void customYaw(boolean customYaw) {
        this.customYaw = customYaw;
    }

    public float customYawLevel() {
        return customYawLevel;
    }

    public void customYawLevel(float customYawLevel) {
        this.customYawLevel = requireRange(customYawLevel, -180.0F, 180.0F, "customYawLevel");
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
        replace(mode, spawn, rewarps, false, true);
    }

    public void replace(
            VerticalCropMode mode,
            Optional<MacroSpawnPose> spawn,
            List<RewarpPosition> rewarps,
            boolean alwaysHoldW,
            boolean holdLeftClickWhenChangingRow
    ) {
        replace(MacroMode.fromCode(Objects.requireNonNull(mode, "mode").code()).orElseThrow(),
                spawn, rewarps, alwaysHoldW, holdLeftClickWhenChangingRow,
                false, false, false, false, 0.0F, false, 0.0F);
    }

    public void replace(
            MacroMode macroMode,
            Optional<MacroSpawnPose> spawn,
            List<RewarpPosition> rewarps,
            boolean alwaysHoldW,
            boolean holdLeftClickWhenChangingRow,
            boolean rotateAfterWarped,
            boolean rotateAfterDrop,
            boolean dontFixAfterWarping,
            boolean customPitch,
            float customPitchLevel,
            boolean customYaw,
            float customYawLevel
    ) {
        MacroMode validatedMacroMode = Objects.requireNonNull(macroMode, "macroMode");
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
        this.macroMode = validatedMacroMode;
        validatedMacroMode.verticalMode().ifPresent(value -> this.mode = value);
        this.spawn = validatedSpawn.orElse(null);
        this.rewarps.clear();
        this.rewarps.addAll(validatedRewarps);
        this.alwaysHoldW = alwaysHoldW;
        this.holdLeftClickWhenChangingRow = holdLeftClickWhenChangingRow;
        this.rotateAfterWarped = rotateAfterWarped;
        this.rotateAfterDrop = rotateAfterDrop;
        this.dontFixAfterWarping = dontFixAfterWarping;
        this.customPitch = customPitch;
        this.customPitchLevel = requireRange(customPitchLevel, -90.0F, 90.0F, "customPitchLevel");
        this.customYaw = customYaw;
        this.customYawLevel = requireRange(customYawLevel, -180.0F, 180.0F, "customYawLevel");
    }

    private static float requireRange(float value, float min, float max, String name) {
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " must be finite and in [" + min + ", " + max + "]");
        }
        return value;
    }
}

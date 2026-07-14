package dev.hylfrd.farmhelper.control.inventory;

import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.HotbarSelection;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable multi-step transaction definition. */
public record InventoryOperation(
        ControlOwner owner,
        Optional<HotbarSelection> hotbarSelection,
        Optional<ScreenExpectation> screenExpectation,
        List<InventoryStep> steps,
        long timeoutNanos) {
    public InventoryOperation {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(hotbarSelection, "hotbarSelection");
        Objects.requireNonNull(screenExpectation, "screenExpectation");
        Objects.requireNonNull(steps, "steps");
        steps = List.copyOf(steps);
        steps.forEach(step -> Objects.requireNonNull(step, "step"));
        if (steps.isEmpty() && hotbarSelection.isEmpty()) {
            throw new IllegalArgumentException("operation must select a hotbar slot or contain a step");
        }
        if (steps.isEmpty() == screenExpectation.isPresent()) {
            throw new IllegalArgumentException(
                    "click operations require one screen expectation and hotbar-only operations require none");
        }
        if (!steps.isEmpty() && screenExpectation.orElseThrow().exactIdentity().isEmpty()) {
            throw new IllegalArgumentException(
                    "click operations require an exact screen identity");
        }
        if (timeoutNanos < 0L) {
            throw new IllegalArgumentException("timeoutNanos must not be negative");
        }
    }

    public static InventoryOperation clicks(
            ControlOwner owner,
            ScreenExpectation screenExpectation,
            List<InventoryStep> steps,
            long timeoutNanos) {
        return new InventoryOperation(
                owner, Optional.empty(), Optional.of(screenExpectation), steps, timeoutNanos);
    }

    public static InventoryOperation hotbar(
            ControlOwner owner, HotbarSelection selection, long timeoutNanos) {
        return new InventoryOperation(
                owner, Optional.of(selection), Optional.empty(), List.of(), timeoutNanos);
    }
}

package dev.hylfrd.farmhelper.feature.antistuck;

import dev.hylfrd.farmhelper.control.input.InputAction;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable input intent; PRESERVE leaves another owner's action available to arbitration. */
public record AntiStuckInputIntent(Map<InputAction, AntiStuckInputDecision> decisions) {
    public AntiStuckInputIntent {
        Objects.requireNonNull(decisions, "decisions");
        EnumMap<InputAction, AntiStuckInputDecision> copied = new EnumMap<>(InputAction.class);
        for (InputAction action : InputAction.values()) {
            if (!decisions.containsKey(action)) {
                throw new IllegalArgumentException("input intent is incomplete: " + action);
            }
            copied.put(action, Objects.requireNonNull(decisions.get(action), "input decision"));
        }
        if (decisions.size() != InputAction.values().length) {
            throw new IllegalArgumentException("input intent contains an unknown action");
        }
        decisions = Collections.unmodifiableMap(copied);
    }

    public static AntiStuckInputIntent preserveAll() {
        return fromSets(Set.of(), Set.of());
    }

    public static AntiStuckInputIntent fromSets(
            Set<InputAction> held,
            Set<InputAction> released
    ) {
        Objects.requireNonNull(held, "held");
        Objects.requireNonNull(released, "released");
        EnumSet<InputAction> copiedHeld = copy(held);
        EnumSet<InputAction> copiedReleased = copy(released);
        EnumSet<InputAction> overlap = copiedHeld.clone();
        overlap.retainAll(copiedReleased);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("an action cannot be held and released: " + overlap);
        }

        EnumMap<InputAction, AntiStuckInputDecision> decisions =
                new EnumMap<>(InputAction.class);
        for (InputAction action : InputAction.values()) {
            decisions.put(action, AntiStuckInputDecision.PRESERVE);
        }
        copiedHeld.forEach(action -> decisions.put(action, AntiStuckInputDecision.HOLD));
        copiedReleased.forEach(action -> decisions.put(action, AntiStuckInputDecision.RELEASE));
        return new AntiStuckInputIntent(decisions);
    }

    public AntiStuckInputDecision decision(InputAction action) {
        return decisions.get(Objects.requireNonNull(action, "action"));
    }

    public Set<InputAction> heldActions() {
        return actionsWith(AntiStuckInputDecision.HOLD);
    }

    public Set<InputAction> releasedActions() {
        return actionsWith(AntiStuckInputDecision.RELEASE);
    }

    public Set<InputAction> preservedActions() {
        return actionsWith(AntiStuckInputDecision.PRESERVE);
    }

    private Set<InputAction> actionsWith(AntiStuckInputDecision decision) {
        EnumSet<InputAction> actions = EnumSet.noneOf(InputAction.class);
        decisions.forEach((action, actionDecision) -> {
            if (actionDecision == decision) {
                actions.add(action);
            }
        });
        return Collections.unmodifiableSet(actions);
    }

    private static EnumSet<InputAction> copy(Set<InputAction> actions) {
        EnumSet<InputAction> copy = EnumSet.noneOf(InputAction.class);
        for (InputAction action : actions) {
            copy.add(Objects.requireNonNull(action, "input action"));
        }
        return copy;
    }
}

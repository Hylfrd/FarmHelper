package dev.hylfrd.farmhelper.control;

import dev.hylfrd.farmhelper.control.input.InputAction;

/** Logical inputs that can be owned by FarmHelper control services. */
public enum MovementKey {
    FORWARD(InputAction.FORWARD),
    BACK(InputAction.BACKWARD),
    LEFT(InputAction.LEFT),
    RIGHT(InputAction.RIGHT),
    JUMP(InputAction.JUMP),
    SNEAK(InputAction.SNEAK),
    SPRINT(InputAction.SPRINT),
    ATTACK(InputAction.ATTACK);

    private final InputAction action;

    MovementKey(InputAction action) {
        this.action = action;
    }

    public InputAction action() {
        return action;
    }
}

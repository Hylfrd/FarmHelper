package dev.hylfrd.farmhelper.macro;

public enum LaneChangeDirection {
    FORWARD(1),
    BACKWARD(-1);

    private final int sign;

    LaneChangeDirection(int sign) {
        this.sign = sign;
    }

    public int sign() {
        return sign;
    }

    public LaneChangeDirection opposite() {
        return this == FORWARD ? BACKWARD : FORWARD;
    }
}

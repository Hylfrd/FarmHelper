package dev.hylfrd.farmhelper.macro;

public enum RowDirection {
    LEFT(-1),
    RIGHT(1);

    private final int sign;

    RowDirection(int sign) {
        this.sign = sign;
    }

    public int sign() {
        return sign;
    }

    public RowDirection opposite() {
        return this == LEFT ? RIGHT : LEFT;
    }
}

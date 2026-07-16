package dev.hylfrd.farmhelper.control.expectation;

/** Globally unique, never-reused token allocated by one runtime-owned ledger. */
public record ActionToken(long value) {
    public ActionToken {
        if (value <= 0L) {
            throw new IllegalArgumentException("action token must be positive");
        }
    }
}

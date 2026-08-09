package dev.hylfrd.farmhelper.feature.antistuck;

/** Injected inclusive-range source for deterministic AntiStuck delay selection. */
@FunctionalInterface
public interface AntiStuckRandom {
    /** Returns a value in [originInclusive, boundExclusive). */
    int nextInt(int originInclusive, int boundExclusive);
}

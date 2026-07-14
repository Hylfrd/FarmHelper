package dev.hylfrd.farmhelper.runtime.snapshot;

import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** A value whose absence is distinct from a value that has not been observed. */
public final class Observation<T> {
    public enum State {
        PRESENT,
        ABSENT,
        UNKNOWN
    }

    private static final Observation<?> ABSENT = new Observation<>(State.ABSENT, null);
    private static final Observation<?> UNKNOWN = new Observation<>(State.UNKNOWN, null);

    private final State state;
    private final T value;

    private Observation(State state, T value) {
        this.state = Objects.requireNonNull(state, "state");
        this.value = value;
    }

    public static <T> Observation<T> present(T value) {
        return new Observation<>(State.PRESENT, Objects.requireNonNull(value, "value"));
    }

    @SuppressWarnings("unchecked")
    public static <T> Observation<T> absent() {
        return (Observation<T>) ABSENT;
    }

    @SuppressWarnings("unchecked")
    public static <T> Observation<T> unknown() {
        return (Observation<T>) UNKNOWN;
    }

    public State state() {
        return state;
    }

    public boolean isPresent() {
        return state == State.PRESENT;
    }

    public boolean isAbsent() {
        return state == State.ABSENT;
    }

    public boolean isUnknown() {
        return state == State.UNKNOWN;
    }

    public T get() {
        if (!isPresent()) {
            throw new NoSuchElementException("Observation is " + state);
        }
        return value;
    }

    public Optional<T> toOptional() {
        return isPresent() ? Optional.of(value) : Optional.empty();
    }

    public <R> Observation<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper");
        return switch (state) {
            case PRESENT -> present(mapper.apply(value));
            case ABSENT -> absent();
            case UNKNOWN -> unknown();
        };
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Observation<?> observation)) {
            return false;
        }
        return state == observation.state && Objects.equals(value, observation.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, value);
    }

    @Override
    public String toString() {
        return isPresent() ? "Observation[PRESENT, " + value + "]" : "Observation[" + state + "]";
    }
}

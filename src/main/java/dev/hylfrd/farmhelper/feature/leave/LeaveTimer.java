package dev.hylfrd.farmhelper.feature.leave;

import dev.hylfrd.farmhelper.runtime.time.MonotonicClock;
import dev.hylfrd.farmhelper.runtime.time.PausableTimer;

import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;

/**
 * Client-thread, Minecraft-free countdown and delayed-disconnect state machine.
 *
 * <p>The countdown keeps aging while a failsafe or another feature is active, matching upstream's
 * tick gates. Once those gates clear, an elapsed timer stops the exact macro generation and starts
 * the fixed disconnect grace period. Explicit {@link #stop()} and {@link #start(long)} calls cancel
 * any prior phase, including a pending disconnect.</p>
 */
public final class LeaveTimer {
    public static final long DISCONNECT_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(500L);
    public static final String DISCONNECT_REASON = "The timer has ended";

    private final MonotonicClock clock;
    private final LeaveTimerStatusSource statusSource;
    private final LeaveTimerDisconnectPort disconnectPort;

    private LeaveTimerState state = LeaveTimerState.STOPPED;
    private long macroGeneration;
    private PausableTimer countdown;
    private PausableTimer disconnectDelay;

    public LeaveTimer(
            MonotonicClock clock,
            LeaveTimerStatusSource statusSource,
            LeaveTimerDisconnectPort disconnectPort
    ) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.statusSource = Objects.requireNonNull(statusSource, "statusSource");
        this.disconnectPort = Objects.requireNonNull(disconnectPort, "disconnectPort");
    }

    public LeaveTimerState state() {
        return state;
    }

    public long macroGeneration() {
        return macroGeneration;
    }

    /** Returns the remaining time in the current timed phase, if there is one. */
    public OptionalLong remainingNanos() {
        return switch (state) {
            case COUNTING_DOWN -> OptionalLong.of(countdown.remainingNanos());
            case DISCONNECT_PENDING -> OptionalLong.of(disconnectDelay.remainingNanos());
            case STOPPED, COMPLETE -> OptionalLong.empty();
        };
    }

    /** Starts or restarts the countdown for the status source's exact active macro generation. */
    public void start(long durationNanos) {
        if (durationNanos < 0L) {
            throw new IllegalArgumentException("durationNanos must not be negative");
        }
        LeaveTimerStatus status = currentStatus();
        if (!status.macroActive()) {
            throw new IllegalStateException("cannot start a leave timer without an active macro");
        }

        PausableTimer nextCountdown = new PausableTimer(clock, durationNanos);
        macroGeneration = status.macroGeneration();
        countdown = nextCountdown;
        disconnectDelay = null;
        state = LeaveTimerState.COUNTING_DOWN;
    }

    /** Cancels the countdown or delayed disconnect without invoking either action-port method. */
    public void stop() {
        clear(LeaveTimerState.STOPPED);
    }

    /** Advances one state-machine step from the injected status and monotonic clock. */
    public LeaveTimerResult tick() {
        return switch (state) {
            case STOPPED -> LeaveTimerResult.STOPPED;
            case COUNTING_DOWN -> tickCountdown();
            case DISCONNECT_PENDING -> tickDisconnectDelay();
            case COMPLETE -> LeaveTimerResult.COMPLETE;
        };
    }

    private LeaveTimerResult tickCountdown() {
        LeaveTimerStatus status = currentStatus();
        if (!status.macroActive()) {
            clear(LeaveTimerState.STOPPED);
            return LeaveTimerResult.MACRO_INACTIVE;
        }
        if (status.macroGeneration() != macroGeneration) {
            clear(LeaveTimerState.STOPPED);
            return LeaveTimerResult.STALE_MACRO;
        }
        if (status.failsafeActive()) {
            return LeaveTimerResult.FAILSAFE_ACTIVE;
        }
        if (status.otherFeatureActive()) {
            return LeaveTimerResult.OTHER_FEATURE_ACTIVE;
        }
        if (!countdown.elapsed()) {
            return LeaveTimerResult.COUNTING_DOWN;
        }

        PausableTimer nextDisconnectDelay =
                new PausableTimer(clock, DISCONNECT_DELAY_NANOS);
        disconnectPort.stopMacro(macroGeneration);
        countdown = null;
        disconnectDelay = nextDisconnectDelay;
        state = LeaveTimerState.DISCONNECT_PENDING;
        return LeaveTimerResult.MACRO_STOP_REQUESTED;
    }

    private LeaveTimerResult tickDisconnectDelay() {
        if (!disconnectDelay.elapsed()) {
            return LeaveTimerResult.DISCONNECT_PENDING;
        }

        disconnectPort.disconnect(DISCONNECT_REASON);
        disconnectDelay = null;
        state = LeaveTimerState.COMPLETE;
        return LeaveTimerResult.DISCONNECT_REQUESTED;
    }

    private LeaveTimerStatus currentStatus() {
        return Objects.requireNonNull(statusSource.currentStatus(), "statusSource.currentStatus()");
    }

    private void clear(LeaveTimerState nextState) {
        state = nextState;
        macroGeneration = 0L;
        countdown = null;
        disconnectDelay = null;
    }
}

package dev.hylfrd.farmhelper.feature.antistuck;

import dev.hylfrd.farmhelper.control.input.InputAction;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Thread-independent, deterministic AntiStuck state machine.
 *
 * <p>Modern Fabric boundary: immutable client-captured evidence enters through {@link AntiStuckTick}
 * and immutable decisions leave for a later owner. This class never captures state, presses keys,
 * starts a warp, or touches a failsafe queue.</p>
 *
 * <p>The controller owns only state and output decisions. A later client owner arbitrates those
 * decisions with other input owners and performs any Minecraft mutation.</p>
 */
public final class AntiStuckController {
    public static final int INITIAL_DELAY_MIN_MILLIS = 150;
    public static final int INITIAL_DELAY_MAX_MILLIS = 299;
    public static final int NO_TARGET_DELAY_MIN_MILLIS = 100;
    public static final int NO_TARGET_DELAY_MAX_MILLIS = 199;
    public static final int TARGET_DELAY_MIN_MILLIS = 80;
    public static final int TARGET_DELAY_MAX_MILLIS = 159;
    public static final int RELEASE_DELAY_MIN_MILLIS = 50;
    public static final int RELEASE_DELAY_MAX_MILLIS = 99;
    public static final int COME_BACK_DELAY_MIN_MILLIS = 80;
    public static final int COME_BACK_DELAY_MAX_MILLIS = 159;
    public static final int OVERDUE_GRACE_MILLIS = 1_000;

    private static final AntiStuckInputIntent PRESERVE_ALL =
            AntiStuckInputIntent.preserveAll();
    private static final Set<InputAction> STOP_ACTIONS = Set.of(
            InputAction.FORWARD,
            InputAction.BACKWARD,
            InputAction.LEFT,
            InputAction.RIGHT,
            InputAction.JUMP,
            InputAction.SNEAK,
            InputAction.ATTACK);

    private final AntiStuckRandom random;
    private final int retryThreshold;

    private AntiStuckState state = AntiStuckState.NONE;
    private AntiStuckIdentity activeIdentity;
    // Retained after stop/reset so a reused lifecycle cannot accept an older run revision.
    private AntiStuckIdentity lastRunIdentity;
    private AntiStuckRecoveryRequest activeRequest;
    private long dueAtMillis = -1L;
    private int scheduledDelayMillis = -1;
    // Scoped to activeIdentity; equality is accepted and backward readings are stale.
    private long lastTickMillis = -1L;
    private EnumSet<InputAction> oppositeActions = EnumSet.noneOf(InputAction.class);
    private int unstuckTries;
    private int lagBackCounter;
    private boolean terminal;
    private AntiStuckDecision terminalDecision;

    public AntiStuckController(AntiStuckRandom random, int retryThreshold) {
        this.random = Objects.requireNonNull(random, "random");
        if (retryThreshold < 0) {
            throw new IllegalArgumentException("retryThreshold must be non-negative");
        }
        this.retryThreshold = retryThreshold;
    }

    public synchronized AntiStuckState state() {
        return state;
    }

    public synchronized Optional<AntiStuckIdentity> activeIdentity() {
        return Optional.ofNullable(activeIdentity);
    }

    public synchronized int unstuckTries() {
        return unstuckTries;
    }

    public synchronized int lagBackCounter() {
        return lagBackCounter;
    }

    public int retryThreshold() {
        return retryThreshold;
    }

    /**
     * Starts the exact request in NONE; the first tick schedules the upstream initial delay.
     * A terminal run can only be replaced by a strictly newer revision in the same lifecycle.
     */
    public synchronized AntiStuckDecision start(
            AntiStuckRecoveryRequest request,
            long nowMillis
    ) {
        Objects.requireNonNull(request, "request");
        requireNow(nowMillis);
        if (activeIdentity != null) {
            if (activeIdentity.equals(request.identity())) {
                if (terminal) {
                    return terminalDecision;
                }
                if (nowMillis < lastTickMillis) {
                    return staleRequest();
                }
                return currentDecision(
                        AntiStuckDecisionKind.ALREADY_ACTIVE,
                        AntiStuckDecisionReason.ALREADY_ACTIVE,
                        PRESERVE_ALL,
                        false);
            }
            if (!terminal
                    || !activeIdentity.sameLifecycle(request.identity())
                    || request.identity().runRevision() <= activeIdentity.runRevision()) {
                return staleRequest();
            }
        } else if (lastRunIdentity != null
                && lastRunIdentity.sameLifecycle(request.identity())
                && request.identity().runRevision() <= lastRunIdentity.runRevision()) {
            return staleRequest();
        }

        activeRequest = request;
        activeIdentity = request.identity();
        lastRunIdentity = activeIdentity;
        state = AntiStuckState.NONE;
        dueAtMillis = -1L;
        scheduledDelayMillis = -1;
        lastTickMillis = nowMillis;
        oppositeActions.clear();
        unstuckTries = request.unstuckTries();
        lagBackCounter = request.lagBackCounter();
        terminal = false;
        terminalDecision = null;
        return currentDecision(
                AntiStuckDecisionKind.STARTED,
                AntiStuckDecisionReason.START,
                stopIntent(),
                false);
    }

    /**
     * Advances one identity-fenced tick at the explicit monotonic time in the tick.
     * Backward readings return {@link AntiStuckDecisionKind#STALE_TICK} without mutation; equality
     * is a valid exact-boundary reading.
     */
    public synchronized AntiStuckDecision tick(AntiStuckTick tick) {
        Objects.requireNonNull(tick, "tick");
        if (!matches(tick.identity())) {
            return staleTick();
        }
        if (terminal) {
            return terminalDecision;
        }

        long nowMillis = tick.nowMillis();
        if (nowMillis < lastTickMillis) {
            return staleTick();
        }
        lastTickMillis = nowMillis;
        if (dueAtMillis >= 0L) {
            if (isOverdue(nowMillis, dueAtMillis)) {
                return failClosed(AntiStuckDecisionReason.OVERDUE);
            }
            if (nowMillis < dueAtMillis) {
                return currentDecision(
                        AntiStuckDecisionKind.WAITING,
                        AntiStuckDecisionReason.DELAY,
                        PRESERVE_ALL,
                        false);
            }
            clearSchedule();
        }

        return switch (state) {
            case NONE -> initialDelay(nowMillis);
            case PRESS -> press(nowMillis, tick.evidence());
            case RELEASE -> release(nowMillis);
            case COME_BACK -> comeBack(nowMillis);
            case DISABLE -> complete();
        };
    }

    /** Records a lag-back observation without changing the recovery input intent or phase. */
    public synchronized AntiStuckDecision recordLagBack(AntiStuckIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (!matches(identity)) {
            return staleRequest();
        }
        if (terminal) {
            return terminalDecision;
        }
        lagBackCounter = increment(lagBackCounter);
        return currentDecision(
                AntiStuckDecisionKind.ADVANCED,
                AntiStuckDecisionReason.LAG_BACK_RECORDED,
                PRESERVE_ALL,
                false);
    }

    /** Explicit owner-scoped stop; stale owners cannot disable a newer run. */
    public synchronized AntiStuckDecision stop(AntiStuckIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (!matches(identity)) {
            return staleRequest();
        }
        if (terminal) {
            return terminalDecision;
        }
        unstuckTries = increment(unstuckTries);
        state = AntiStuckState.NONE;
        clearSchedule();
        oppositeActions.clear();
        activeIdentity = null;
        activeRequest = null;
        lastTickMillis = -1L;
        return new AntiStuckDecision(
                AntiStuckDecisionKind.STOPPED,
                AntiStuckDecisionReason.STOPPED,
                state,
                stopIntent(),
                OptionalLong.empty(),
                OptionalInt.empty(),
                false,
                unstuckTries,
                lagBackCounter);
    }

    /** Lifecycle reset equivalent to the upstream macro-disabled counter reset. */
    public synchronized AntiStuckDecision reset() {
        state = AntiStuckState.NONE;
        activeIdentity = null;
        activeRequest = null;
        clearSchedule();
        oppositeActions.clear();
        lastTickMillis = -1L;
        unstuckTries = 0;
        lagBackCounter = 0;
        terminal = false;
        terminalDecision = null;
        return new AntiStuckDecision(
                AntiStuckDecisionKind.STOPPED,
                AntiStuckDecisionReason.STOPPED,
                state,
                stopIntent(),
                OptionalLong.empty(),
                OptionalInt.empty(),
                false,
                unstuckTries,
                lagBackCounter);
    }

    private AntiStuckDecision initialDelay(long nowMillis) {
        state = AntiStuckState.PRESS;
        schedule(nowMillis, INITIAL_DELAY_MIN_MILLIS, INITIAL_DELAY_MAX_MILLIS);
        return currentDecision(
                AntiStuckDecisionKind.ADVANCED,
                AntiStuckDecisionReason.INITIAL_DELAY,
                stopIntent(),
                false);
    }

    private AntiStuckDecision press(
            long nowMillis,
            AntiStuckRecoveryEvidence evidence
    ) {
        if (unstuckTries > retryThreshold) {
            return rewarp();
        }

        AntiStuckDecisionReason evidenceFailure = evidenceFailure(evidence);
        if (evidenceFailure != null) {
            return failClosed(evidenceFailure);
        }

        AntiStuckTargetEvidence intersection = evidence.intersectionTarget();
        if (intersection.isPresent()) {
            BlockPosition obstacle = intersection.position().orElseThrow();
            EnumSet<AntiStuckSide> clearSides = EnumSet.noneOf(AntiStuckSide.class);
            for (AntiStuckSide side : AntiStuckSide.values()) {
                if (evidence.sideEvidence().get(side)
                        == AntiStuckRecoveryEvidence.SideStatus.CLEAR) {
                    clearSides.add(side);
                }
            }
            Optional<AntiStuckSide> side = AntiStuckGeometry.nearestClearSide(
                    obstacle, evidence.player().orElseThrow(), clearSides);
            if (side.isEmpty()) {
                return noTarget(nowMillis);
            }
            return target(
                    nowMillis,
                    side.get().movementTarget(obstacle),
                    evidence.player().orElseThrow());
        }

        if (activeRequest.directionTarget().isPresent()) {
            return target(
                    nowMillis,
                    AntiStuckGeometry.blockCenter(
                            activeRequest.directionTarget().position().orElseThrow()),
                    evidence.player().orElseThrow());
        }
        return noTarget(nowMillis);
    }

    private AntiStuckDecision target(
            long nowMillis,
            AntiStuckPoint target,
            AntiStuckPlayerPose player
    ) {
        Set<InputAction> movement = AntiStuckGeometry.neededMovementKeys(player, target);
        oppositeActions = oppositeOf(movement);
        oppositeActions.add(InputAction.SNEAK);
        oppositeActions.add(InputAction.ATTACK);
        state = AntiStuckState.RELEASE;
        schedule(nowMillis, TARGET_DELAY_MIN_MILLIS, TARGET_DELAY_MAX_MILLIS);

        EnumSet<InputAction> held = EnumSet.noneOf(InputAction.class);
        held.addAll(movement);
        held.add(InputAction.SNEAK);
        held.add(InputAction.ATTACK);
        return currentDecision(
                AntiStuckDecisionKind.ADVANCED,
                AntiStuckDecisionReason.TARGET_SELECTED,
                AntiStuckInputIntent.fromSets(held, Set.of()),
                false);
    }

    private AntiStuckDecision noTarget(long nowMillis) {
        oppositeActions.clear();
        state = AntiStuckState.RELEASE;
        schedule(nowMillis, NO_TARGET_DELAY_MIN_MILLIS, NO_TARGET_DELAY_MAX_MILLIS);
        return currentDecision(
                AntiStuckDecisionKind.ADVANCED,
                AntiStuckDecisionReason.NO_TARGET,
                AntiStuckInputIntent.fromSets(
                        Set.of(InputAction.BACKWARD, InputAction.SNEAK),
                        Set.of()),
                false);
    }

    private AntiStuckDecision release(long nowMillis) {
        boolean directionTargetPresent = activeRequest.directionTarget().isPresent();
        state = directionTargetPresent ? AntiStuckState.DISABLE : AntiStuckState.COME_BACK;
        schedule(nowMillis, RELEASE_DELAY_MIN_MILLIS, RELEASE_DELAY_MAX_MILLIS);
        return currentDecision(
                AntiStuckDecisionKind.ADVANCED,
                AntiStuckDecisionReason.RELEASE,
                stopIntent(),
                false);
    }

    private AntiStuckDecision comeBack(long nowMillis) {
        state = AntiStuckState.DISABLE;
        schedule(nowMillis, COME_BACK_DELAY_MIN_MILLIS, COME_BACK_DELAY_MAX_MILLIS);
        return currentDecision(
                AntiStuckDecisionKind.ADVANCED,
                AntiStuckDecisionReason.COME_BACK,
                AntiStuckInputIntent.fromSets(oppositeActions, Set.of()),
                false);
    }

    private AntiStuckDecision complete() {
        unstuckTries = increment(unstuckTries);
        terminal = true;
        clearSchedule();
        oppositeActions.clear();
        terminalDecision = currentDecision(
                AntiStuckDecisionKind.STOPPED,
                AntiStuckDecisionReason.COMPLETE,
                stopIntent(),
                false);
        return terminalDecision;
    }

    private AntiStuckDecision rewarp() {
        unstuckTries = 0;
        terminal = true;
        state = AntiStuckState.DISABLE;
        clearSchedule();
        oppositeActions.clear();
        terminalDecision = currentDecision(
                AntiStuckDecisionKind.REWARP,
                AntiStuckDecisionReason.RETRY_LIMIT,
                stopIntent(),
                true);
        return terminalDecision;
    }

    private AntiStuckDecision failClosed(AntiStuckDecisionReason reason) {
        unstuckTries = increment(unstuckTries);
        terminal = true;
        state = AntiStuckState.DISABLE;
        clearSchedule();
        oppositeActions.clear();
        terminalDecision = currentDecision(
                AntiStuckDecisionKind.FAIL_CLOSED,
                reason,
                stopIntent(),
                false);
        return terminalDecision;
    }

    private AntiStuckDecisionReason evidenceFailure(AntiStuckRecoveryEvidence evidence) {
        if (evidence.status() == AntiStuckRecoveryEvidence.Status.UNKNOWN) {
            return AntiStuckDecisionReason.UNKNOWN_EVIDENCE;
        }
        if (evidence.status() == AntiStuckRecoveryEvidence.Status.ERROR) {
            return AntiStuckDecisionReason.ERROR_EVIDENCE;
        }
        if (activeRequest.directionTarget().isUnknown()
                || evidence.intersectionTarget().isUnknown()) {
            return AntiStuckDecisionReason.UNKNOWN_EVIDENCE;
        }
        if (activeRequest.directionTarget().isError()
                || evidence.intersectionTarget().isError()) {
            return AntiStuckDecisionReason.ERROR_EVIDENCE;
        }
        for (AntiStuckRecoveryEvidence.SideStatus sideStatus : evidence.sideEvidence().values()) {
            if (sideStatus == AntiStuckRecoveryEvidence.SideStatus.UNKNOWN) {
                return AntiStuckDecisionReason.UNKNOWN_EVIDENCE;
            }
            if (sideStatus == AntiStuckRecoveryEvidence.SideStatus.ERROR) {
                return AntiStuckDecisionReason.ERROR_EVIDENCE;
            }
        }
        return null;
    }

    private AntiStuckDecision currentDecision(
            AntiStuckDecisionKind kind,
            AntiStuckDecisionReason reason,
            AntiStuckInputIntent inputIntent,
            boolean rewarpRequested
    ) {
        OptionalLong due = dueAtMillis < 0L
                ? OptionalLong.empty()
                : OptionalLong.of(dueAtMillis);
        OptionalInt delay = scheduledDelayMillis < 0
                ? OptionalInt.empty()
                : OptionalInt.of(scheduledDelayMillis);
        return new AntiStuckDecision(
                kind,
                reason,
                state,
                inputIntent,
                due,
                delay,
                rewarpRequested,
                unstuckTries,
                lagBackCounter);
    }

    private AntiStuckDecision staleRequest() {
        return currentDecision(
                AntiStuckDecisionKind.STALE_REQUEST,
                AntiStuckDecisionReason.STALE_REQUEST,
                PRESERVE_ALL,
                false);
    }

    private AntiStuckDecision staleTick() {
        return currentDecision(
                AntiStuckDecisionKind.STALE_TICK,
                AntiStuckDecisionReason.STALE_TICK,
                PRESERVE_ALL,
                false);
    }

    private void schedule(long nowMillis, int minimum, int maximum) {
        int delay = random.nextInt(minimum, maximum + 1);
        if (delay < minimum || delay > maximum) {
            throw new IllegalStateException(
                    "AntiStuck random returned " + delay + " outside [" + minimum + ", "
                            + maximum + "]");
        }
        scheduledDelayMillis = delay;
        dueAtMillis = saturatedAdd(nowMillis, delay);
    }

    private void clearSchedule() {
        dueAtMillis = -1L;
        scheduledDelayMillis = -1;
    }

    private boolean matches(AntiStuckIdentity identity) {
        return activeIdentity != null && activeIdentity.equals(identity);
    }

    private static boolean isOverdue(long nowMillis, long dueAtMillis) {
        return nowMillis > dueAtMillis && nowMillis - dueAtMillis > OVERDUE_GRACE_MILLIS;
    }

    private static EnumSet<InputAction> oppositeOf(Set<InputAction> movement) {
        EnumSet<InputAction> opposite = EnumSet.noneOf(InputAction.class);
        for (InputAction action : movement) {
            switch (action) {
                case FORWARD -> opposite.add(InputAction.BACKWARD);
                case BACKWARD -> opposite.add(InputAction.FORWARD);
                case LEFT -> opposite.add(InputAction.RIGHT);
                case RIGHT -> opposite.add(InputAction.LEFT);
                default -> {
                    // Only movement keys are eligible for the return vector.
                }
            }
        }
        return opposite;
    }

    private static AntiStuckInputIntent stopIntent() {
        return AntiStuckInputIntent.fromSets(Set.of(), STOP_ACTIONS);
    }

    private static int increment(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    private static long saturatedAdd(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static void requireNow(long nowMillis) {
        if (nowMillis < 0L) {
            throw new IllegalArgumentException("nowMillis must be non-negative");
        }
    }
}

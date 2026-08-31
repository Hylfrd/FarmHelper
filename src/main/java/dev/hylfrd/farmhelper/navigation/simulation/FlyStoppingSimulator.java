package dev.hylfrd.farmhelper.navigation.simulation;

import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** Pure, bounded zero-input reproduction of the fixed upstream stopping calculation. */
public final class FlyStoppingSimulator {
    public static final int MAX_TICKS = 30;
    public static final double STOP_HORIZONTAL_SPEED = 0.01D;
    public static final double COMPONENT_CLAMP_SPEED = 0.005D;
    public static final double AIR_HORIZONTAL_FRICTION = 0.91F;
    public static final double FLYING_VERTICAL_DECAY = 0.6D;
    public static final double SNEAK_SHRINK = 0.05D;

    private static final int MAX_EDGE_PROBES = 1_024;

    private FlyStoppingSimulator() {
    }

    public static FlyStoppingPrediction predict(
            FlyStoppingState initial,
            FlyStoppingWorldPort world
    ) {
        Objects.requireNonNull(initial, "initial");
        Objects.requireNonNull(world, "world");
        if (!coherent(initial)) {
            return FlyStoppingPrediction.unknown(FlyStoppingFailure.INVALID_STATE, 0);
        }

        FlyStoppingState current = initial;
        for (int completedTicks = 0; completedTicks < MAX_TICKS; completedTicks++) {
            Attempt<FlyStoppingState> next;
            try {
                next = advance(current, world);
            } catch (ArithmeticException | IllegalArgumentException exception) {
                return FlyStoppingPrediction.unknown(
                        FlyStoppingFailure.INVALID_STATE, completedTicks);
            }
            if (next.isUnknown()) {
                return FlyStoppingPrediction.unknown(next.failure(), completedTicks);
            }
            current = next.value();
            int ticks = completedTicks + 1;
            if (belowStopThreshold(current.motion())) {
                return FlyStoppingPrediction.stopped(current, ticks);
            }
        }
        return FlyStoppingPrediction.tickLimit(current);
    }

    private static Attempt<FlyStoppingState> advance(
            FlyStoppingState current,
            FlyStoppingWorldPort world
    ) {
        MotionSnapshot clamped = new MotionSnapshot(
                clamp(current.motion().x()),
                clamp(current.motion().y()),
                clamp(current.motion().z()));
        FlyStoppingState prepared = replaceMotionAndInput(
                current, clamped, current.input().damped());

        Attempt<Double> friction = horizontalFriction(prepared, world);
        if (friction.isUnknown()) {
            return Attempt.unknown(friction.failure());
        }

        double verticalBeforeMove = prepared.motion().y();
        Attempt<FlyStoppingState> moved = move(prepared, world);
        if (moved.isUnknown()) {
            return moved;
        }

        FlyStoppingState afterMove = moved.value();
        double motionX = afterMove.motion().x() * friction.value();
        double motionY = verticalBeforeMove * FLYING_VERTICAL_DECAY;
        double motionZ = afterMove.motion().z() * friction.value();
        if (!Double.isFinite(motionX)
                || !Double.isFinite(motionY)
                || !Double.isFinite(motionZ)) {
            return Attempt.unknown(FlyStoppingFailure.INVALID_STATE);
        }
        return Attempt.known(replaceMotionAndInput(afterMove,
                new MotionSnapshot(motionX, motionY, motionZ), afterMove.input()));
    }

    private static Attempt<Double> horizontalFriction(
            FlyStoppingState state,
            FlyStoppingWorldPort world
    ) {
        if (!state.onGround()) {
            return Attempt.known(AIR_HORIZONTAL_FRICTION);
        }
        FlyStoppingEvidence<Double> evidence = readEvidence(
                () -> world.groundSlipperiness(state));
        if (evidence.isUnknown()) {
            return Attempt.unknown(evidence.failure().orElse(
                    FlyStoppingFailure.INCOMPLETE_EVIDENCE));
        }
        Double supplied = evidence.value().orElse(null);
        if (supplied == null || !Double.isFinite(supplied) || supplied < 0.0D) {
            return Attempt.unknown(FlyStoppingFailure.INCOMPLETE_EVIDENCE);
        }
        float rawSlipperiness = supplied.floatValue();
        if (!Float.isFinite(rawSlipperiness) || rawSlipperiness < 0.0F) {
            return Attempt.unknown(FlyStoppingFailure.INCOMPLETE_EVIDENCE);
        }
        return Attempt.known((double) (rawSlipperiness * 0.91F));
    }

    private static Attempt<FlyStoppingState> move(
            FlyStoppingState state,
            FlyStoppingWorldPort world
    ) {
        double requestedX = state.motion().x();
        double requestedY = state.motion().y();
        double requestedZ = state.motion().z();
        if (state.noClip()) {
            return Attempt.known(stateAtBody(
                    state, state.body().move(requestedX, requestedY, requestedZ),
                    state.motion(), state.onGround(), state.fallDistance(),
                    state.collided(), state.collidedHorizontally(),
                    state.collidedVertically()));
        }

        double desiredX = requestedX;
        double desiredZ = requestedZ;
        BoxSnapshot originalBody = state.body();
        if (state.onGround() && state.sneaking()) {
            Attempt<Double> xAtEdge = supportedAxisDelta(
                    world, originalBody, desiredX, Axis.X);
            if (xAtEdge.isUnknown()) {
                return Attempt.unknown(xAtEdge.failure());
            }
            desiredX = xAtEdge.value();

            Attempt<Double> zAtEdge = supportedAxisDelta(
                    world, originalBody, desiredZ, Axis.Z);
            if (zAtEdge.isUnknown()) {
                return Attempt.unknown(zAtEdge.failure());
            }
            desiredZ = zAtEdge.value();

            Attempt<HorizontalDelta> diagonal = supportedDiagonalDelta(
                    world, originalBody, desiredX, desiredZ);
            if (diagonal.isUnknown()) {
                return Attempt.unknown(diagonal.failure());
            }
            desiredX = diagonal.value().x();
            desiredZ = diagonal.value().z();
        }

        Attempt<List<BoxSnapshot>> queried = collisionBoxes(
                world, movementEnvelope(originalBody, desiredX, requestedY, desiredZ));
        if (queried.isUnknown()) {
            return Attempt.unknown(queried.failure());
        }
        List<BoxSnapshot> colliders = queried.value();

        double resolvedY = clip(colliders, originalBody, requestedY, Axis.Y);
        BoxSnapshot resolvedBody = originalBody.move(0.0D, resolvedY, 0.0D);
        boolean canStep = state.onGround()
                || requestedY != resolvedY && requestedY < 0.0D;
        double resolvedX = clip(colliders, resolvedBody, desiredX, Axis.X);
        resolvedBody = resolvedBody.move(resolvedX, 0.0D, 0.0D);
        double resolvedZ = clip(colliders, resolvedBody, desiredZ, Axis.Z);
        resolvedBody = resolvedBody.move(0.0D, 0.0D, resolvedZ);
        Movement normal = new Movement(resolvedX, resolvedY, resolvedZ, resolvedBody);

        Movement selected = normal;
        if (state.stepHeight() > 0.0D
                && canStep
                && (desiredX != resolvedX || desiredZ != resolvedZ)) {
            Attempt<Movement> stepped = stepUp(
                    world, originalBody, desiredX, desiredZ, normal, state.stepHeight());
            if (stepped.isUnknown()) {
                return Attempt.unknown(stepped.failure());
            }
            selected = stepped.value();
        }

        boolean horizontalCollision = desiredX != selected.x() || desiredZ != selected.z();
        boolean verticalCollision = requestedY != selected.y();
        boolean onGround = verticalCollision && requestedY < 0.0D;
        double fallDistance = updateFallDistance(
                state.fallDistance(), selected.y(), onGround);
        MotionSnapshot collisionMotion = new MotionSnapshot(
                desiredX != selected.x() ? 0.0D : state.motion().x(),
                requestedY != selected.y() ? 0.0D : state.motion().y(),
                desiredZ != selected.z() ? 0.0D : state.motion().z());
        return Attempt.known(stateAtBody(
                state, selected.body(), collisionMotion, onGround, fallDistance,
                horizontalCollision || verticalCollision,
                horizontalCollision, verticalCollision));
    }

    private static Attempt<Double> supportedAxisDelta(
            FlyStoppingWorldPort world,
            BoxSnapshot body,
            double initial,
            Axis axis
    ) {
        double candidate = initial;
        for (int probes = 0; candidate != 0.0D; probes++) {
            if (probes >= MAX_EDGE_PROBES) {
                return Attempt.unknown(FlyStoppingFailure.INVALID_STATE);
            }
            BoxSnapshot probe = axis == Axis.X
                    ? body.move(candidate, -1.0D, 0.0D)
                    : body.move(0.0D, -1.0D, candidate);
            Attempt<List<BoxSnapshot>> collisions = collisionBoxes(world, probe);
            if (collisions.isUnknown()) {
                return Attempt.unknown(collisions.failure());
            }
            if (!collisions.value().isEmpty()) {
                return Attempt.known(candidate);
            }
            double reduced = shrinkTowardZero(candidate);
            if (reduced == candidate) {
                return Attempt.unknown(FlyStoppingFailure.INVALID_STATE);
            }
            candidate = reduced;
        }
        return Attempt.known(0.0D);
    }

    private static Attempt<HorizontalDelta> supportedDiagonalDelta(
            FlyStoppingWorldPort world,
            BoxSnapshot body,
            double initialX,
            double initialZ
    ) {
        double x = initialX;
        double z = initialZ;
        for (int probes = 0; x != 0.0D && z != 0.0D; probes++) {
            if (probes >= MAX_EDGE_PROBES) {
                return Attempt.unknown(FlyStoppingFailure.INVALID_STATE);
            }
            Attempt<List<BoxSnapshot>> collisions = collisionBoxes(
                    world, body.move(x, -1.0D, z));
            if (collisions.isUnknown()) {
                return Attempt.unknown(collisions.failure());
            }
            if (!collisions.value().isEmpty()) {
                break;
            }
            double reducedX = shrinkTowardZero(x);
            double reducedZ = shrinkTowardZero(z);
            if (reducedX == x || reducedZ == z) {
                return Attempt.unknown(FlyStoppingFailure.INVALID_STATE);
            }
            x = reducedX;
            z = reducedZ;
        }
        return Attempt.known(new HorizontalDelta(x, z));
    }

    private static Attempt<Movement> stepUp(
            FlyStoppingWorldPort world,
            BoxSnapshot originalBody,
            double desiredX,
            double desiredZ,
            Movement normal,
            double stepHeight
    ) {
        Attempt<List<BoxSnapshot>> queried = collisionBoxes(
                world, movementEnvelope(originalBody, desiredX, stepHeight, desiredZ));
        if (queried.isUnknown()) {
            return Attempt.unknown(queried.failure());
        }
        List<BoxSnapshot> colliders = queried.value();

        StepCandidate horizontalFirst = stepCandidate(
                colliders, originalBody,
                movementEnvelope(originalBody, desiredX, 0.0D, desiredZ),
                desiredX, desiredZ, stepHeight);
        StepCandidate verticalFirst = stepCandidate(
                colliders, originalBody, originalBody,
                desiredX, desiredZ, stepHeight);
        StepCandidate candidate = horizontalFirst.horizontalDistanceSquared()
                > verticalFirst.horizontalDistanceSquared()
                ? horizontalFirst : verticalFirst;

        double down = clip(colliders, candidate.body(), -candidate.up(), Axis.Y);
        Movement stepped = new Movement(
                candidate.x(), down, candidate.z(),
                candidate.body().move(0.0D, down, 0.0D));
        if (normal.horizontalDistanceSquared() >= stepped.horizontalDistanceSquared()) {
            return Attempt.known(normal);
        }
        return Attempt.known(stepped);
    }

    private static StepCandidate stepCandidate(
            List<BoxSnapshot> colliders,
            BoxSnapshot originalBody,
            BoxSnapshot upwardProbe,
            double desiredX,
            double desiredZ,
            double stepHeight
    ) {
        double up = clip(colliders, upwardProbe, stepHeight, Axis.Y);
        BoxSnapshot body = originalBody.move(0.0D, up, 0.0D);
        double x = clip(colliders, body, desiredX, Axis.X);
        body = body.move(x, 0.0D, 0.0D);
        double z = clip(colliders, body, desiredZ, Axis.Z);
        return new StepCandidate(x, z, up, body.move(0.0D, 0.0D, z));
    }

    private static Attempt<List<BoxSnapshot>> collisionBoxes(
            FlyStoppingWorldPort world,
            BoxSnapshot query
    ) {
        FlyStoppingEvidence<List<BoxSnapshot>> evidence = readEvidence(
                () -> world.collisions(query));
        if (evidence.isUnknown()) {
            return Attempt.unknown(evidence.failure().orElse(
                    FlyStoppingFailure.INCOMPLETE_EVIDENCE));
        }
        List<BoxSnapshot> supplied = evidence.value().orElse(null);
        if (supplied == null) {
            return Attempt.unknown(FlyStoppingFailure.INCOMPLETE_EVIDENCE);
        }
        try {
            List<BoxSnapshot> copy = new ArrayList<>(supplied.size());
            for (BoxSnapshot collider : supplied) {
                if (collider == null || !collider.hasPositiveVolume()) {
                    return Attempt.unknown(FlyStoppingFailure.INCOMPLETE_EVIDENCE);
                }
                copy.add(collider);
            }
            return Attempt.known(List.copyOf(copy));
        } catch (RuntimeException exception) {
            return Attempt.unknown(FlyStoppingFailure.INCOMPLETE_EVIDENCE);
        }
    }

    private static <T> FlyStoppingEvidence<T> readEvidence(
            Supplier<FlyStoppingEvidence<T>> query
    ) {
        try {
            FlyStoppingEvidence<T> evidence = query.get();
            return evidence == null
                    ? FlyStoppingEvidence.unknown(FlyStoppingFailure.INCOMPLETE_EVIDENCE)
                    : evidence;
        } catch (RuntimeException exception) {
            return FlyStoppingEvidence.unknown(FlyStoppingFailure.INCOMPLETE_EVIDENCE);
        }
    }

    private static double clip(
            List<BoxSnapshot> colliders,
            BoxSnapshot body,
            double requested,
            Axis axis
    ) {
        if (requested == 0.0D) {
            return 0.0D;
        }
        double resolved = requested;
        for (BoxSnapshot collider : colliders) {
            if (!overlapsOtherAxes(body, collider, axis)) {
                continue;
            }
            if (resolved > 0.0D && maximum(body, axis) <= minimum(collider, axis)) {
                resolved = Math.min(minimum(collider, axis) - maximum(body, axis), resolved);
            } else if (resolved < 0.0D && minimum(body, axis) >= maximum(collider, axis)) {
                resolved = Math.max(maximum(collider, axis) - minimum(body, axis), resolved);
            }
        }
        return resolved;
    }

    private static boolean overlapsOtherAxes(
            BoxSnapshot first,
            BoxSnapshot second,
            Axis movementAxis
    ) {
        return switch (movementAxis) {
            case X -> first.maxY() > second.minY() && first.minY() < second.maxY()
                    && first.maxZ() > second.minZ() && first.minZ() < second.maxZ();
            case Y -> first.maxX() > second.minX() && first.minX() < second.maxX()
                    && first.maxZ() > second.minZ() && first.minZ() < second.maxZ();
            case Z -> first.maxX() > second.minX() && first.minX() < second.maxX()
                    && first.maxY() > second.minY() && first.minY() < second.maxY();
        };
    }

    private static double minimum(BoxSnapshot box, Axis axis) {
        return switch (axis) {
            case X -> box.minX();
            case Y -> box.minY();
            case Z -> box.minZ();
        };
    }

    private static double maximum(BoxSnapshot box, Axis axis) {
        return switch (axis) {
            case X -> box.maxX();
            case Y -> box.maxY();
            case Z -> box.maxZ();
        };
    }

    private static BoxSnapshot movementEnvelope(
            BoxSnapshot body,
            double x,
            double y,
            double z
    ) {
        return new BoxSnapshot(
                body.minX() + Math.min(x, 0.0D),
                body.minY() + Math.min(y, 0.0D),
                body.minZ() + Math.min(z, 0.0D),
                body.maxX() + Math.max(x, 0.0D),
                body.maxY() + Math.max(y, 0.0D),
                body.maxZ() + Math.max(z, 0.0D));
    }

    private static FlyStoppingState replaceMotionAndInput(
            FlyStoppingState state,
            MotionSnapshot motion,
            FlyStoppingInput input
    ) {
        return new FlyStoppingState(
                state.position(), motion, state.body(), state.onGround(), state.noClip(),
                state.sneaking(), state.stepHeight(), state.fallDistance(), input,
                state.collided(), state.collidedHorizontally(), state.collidedVertically());
    }

    private static FlyStoppingState stateAtBody(
            FlyStoppingState template,
            BoxSnapshot body,
            MotionSnapshot motion,
            boolean onGround,
            double fallDistance,
            boolean collided,
            boolean collidedHorizontally,
            boolean collidedVertically
    ) {
        PositionSnapshot position = new PositionSnapshot(
                (body.minX() + body.maxX()) / 2.0D,
                body.minY(),
                (body.minZ() + body.maxZ()) / 2.0D);
        return new FlyStoppingState(
                position, motion, body, onGround, template.noClip(), template.sneaking(),
                template.stepHeight(), fallDistance, template.input(), collided,
                collidedHorizontally, collidedVertically);
    }

    private static double updateFallDistance(
            double previous,
            double resolvedY,
            boolean onGround
    ) {
        if (onGround) {
            return previous > 0.0D ? 0.0D : previous;
        }
        return resolvedY < 0.0D ? previous - resolvedY : previous;
    }

    private static double shrinkTowardZero(double value) {
        if (value < SNEAK_SHRINK && value >= -SNEAK_SHRINK) {
            return 0.0D;
        }
        return value > 0.0D ? value - SNEAK_SHRINK : value + SNEAK_SHRINK;
    }

    private static double clamp(double value) {
        return Math.abs(value) < COMPONENT_CLAMP_SPEED ? 0.0D : value;
    }

    private static boolean belowStopThreshold(MotionSnapshot motion) {
        return Math.abs(motion.x()) < STOP_HORIZONTAL_SPEED
                && Math.abs(motion.z()) < STOP_HORIZONTAL_SPEED;
    }

    private static boolean coherent(FlyStoppingState state) {
        BoxSnapshot body = state.body();
        PositionSnapshot position = state.position();
        return coordinateMatches(position.x(), midpoint(body.minX(), body.maxX()))
                && coordinateMatches(position.y(), body.minY())
                && coordinateMatches(position.z(), midpoint(body.minZ(), body.maxZ()))
                && state.collided()
                == (state.collidedHorizontally() || state.collidedVertically());
    }

    private static double midpoint(double first, double second) {
        return first / 2.0D + second / 2.0D;
    }

    private static boolean coordinateMatches(double actual, double expected) {
        double tolerance = Math.max(4.0D * Math.ulp(expected), 1.0E-12D);
        return Math.abs(actual - expected) <= tolerance;
    }

    private enum Axis {
        X,
        Y,
        Z
    }

    private record HorizontalDelta(double x, double z) {
    }

    private record Movement(double x, double y, double z, BoxSnapshot body) {
        double horizontalDistanceSquared() {
            return x * x + z * z;
        }
    }

    private record StepCandidate(double x, double z, double up, BoxSnapshot body) {
        double horizontalDistanceSquared() {
            return x * x + z * z;
        }
    }

    private record Attempt<T>(T value, FlyStoppingFailure failure) {
        Attempt {
            if ((value == null) == (failure == null)) {
                throw new IllegalArgumentException("attempt must be known or unknown");
            }
        }

        static <T> Attempt<T> known(T value) {
            return new Attempt<>(Objects.requireNonNull(value, "value"), null);
        }

        static <T> Attempt<T> unknown(FlyStoppingFailure failure) {
            return new Attempt<>(null, Objects.requireNonNull(failure, "failure"));
        }

        boolean isUnknown() {
            return failure != null;
        }
    }
}

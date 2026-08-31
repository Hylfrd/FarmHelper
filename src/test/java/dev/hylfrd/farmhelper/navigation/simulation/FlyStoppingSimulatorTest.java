package dev.hylfrd.farmhelper.navigation.simulation;

import dev.hylfrd.farmhelper.runtime.snapshot.MotionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlyStoppingSimulatorTest {
    private static final double SLIPPERINESS = 0.6D;
    private static final double EPSILON = 1.0E-12D;

    @Test
    void requiresZeroInputAndAdvancesStationaryStateOnceBeforeStopping() {
        assertThrows(IllegalArgumentException.class, () -> newState(
                0.0D, 0.0D, 0.0D,
                0.0D, 0.0D, 0.0D,
                false, false, false, 0.0D, 0.0D,
                new FlyStoppingInput(0.1D, 0.0D)));

        FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                newState(0.0D, 0.0D, 0.0D,
                        0.0D, 0.0D, 0.0D,
                        false, false, false, 0.0D, 0.0D),
                emptyWorld());

        assertEquals(FlyStoppingPredictionOutcome.STOPPED, prediction.outcome());
        assertEquals(1, prediction.ticks());
        assertEquals(FlyStoppingInput.ZERO, prediction.state().orElseThrow().input());
    }

    @Test
    void clampsComponentsStrictlyBelowPointZeroZeroFive() {
        FlyStoppingPrediction below = FlyStoppingSimulator.predict(
                newState(0.0D, 0.0D, 0.0D,
                        0.004D, 0.0D, -0.004D,
                        false, false, false, 0.0D, 0.0D),
                emptyWorld());
        assertEquals(0.3D, below.position().orElseThrow().x(), EPSILON);
        assertEquals(0.3D, below.position().orElseThrow().z(), EPSILON);

        FlyStoppingPrediction boundary = FlyStoppingSimulator.predict(
                newState(0.0D, 0.0D, 0.0D,
                        0.005D, 0.0D, 0.0D,
                        false, false, false, 0.0D, 0.0D),
                emptyWorld());
        assertEquals(0.305D, boundary.position().orElseThrow().x(), EPSILON);
    }

    @Test
    void appliesWidenedFloatAirFrictionAndFlyingVerticalDecay() {
        FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                newState(0.0D, 0.0D, 0.0D,
                        0.01D, 2.0D, 0.0D,
                        false, false, false, 0.0D, 0.0D),
                emptyWorld());

        FlyStoppingState result = prediction.state().orElseThrow();
        assertTrue(prediction.isStopped());
        assertEquals(0.31D, result.position().x(), EPSILON);
        assertEquals(2.0D, result.position().y(), EPSILON);
        assertEquals(0.01D * (double) 0.91F, result.motion().x(), 0.0D);
        assertEquals(1.2D, result.motion().y(), 0.0D);
    }

    @Test
    void samplesGroundSlipperinessFromThePreMoveState() {
        AtomicReference<FlyStoppingState> sampled = new AtomicReference<>();
        FlyStoppingWorldPort world = new FlyStoppingWorldPort() {
            @Override
            public FlyStoppingEvidence<List<BoxSnapshot>> collisions(BoxSnapshot query) {
                return FlyStoppingEvidence.known(List.of());
            }

            @Override
            public FlyStoppingEvidence<Double> groundSlipperiness(FlyStoppingState state) {
                sampled.set(state);
                return FlyStoppingEvidence.known(SLIPPERINESS);
            }
        };

        FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                newState(0.0D, 0.0D, 0.0D,
                        0.01D, 0.0D, 0.0D,
                        true, false, false, 0.0D, 0.0D), world);

        assertEquals(0.3D, sampled.get().position().x(), EPSILON);
        assertEquals(0.01D * (double) (0.6F * 0.91F),
                prediction.state().orElseThrow().motion().x(), 0.0D);
    }

    @Test
    void returnsKnownTickLimitAfterExactlyThirtyUpdates() {
        FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                newState(0.0D, 0.0D, 0.0D,
                        1.0D, 0.0D, 0.0D,
                        false, false, false, 0.0D, 0.0D),
                emptyWorld());

        assertEquals(FlyStoppingPredictionOutcome.TICK_LIMIT, prediction.outcome());
        assertEquals(FlyStoppingSimulator.MAX_TICKS, prediction.ticks());
        assertTrue(prediction.isKnown());
        assertTrue(prediction.state().orElseThrow().motion().x()
                > FlyStoppingSimulator.STOP_HORIZONTAL_SPEED);
    }

    @Test
    void clipsMovementInAxisOrderAndZerosTheBlockedHorizontalComponent() {
        BoxSnapshot wall = new BoxSnapshot(0.8D, 0.0D, -1.0D, 1.8D, 2.0D, 1.0D);
        FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                newState(0.0D, 0.0D, 0.0D,
                        1.0D, 0.0D, 0.0D,
                        false, false, false, 0.0D, 0.0D),
                collisionWorld(List.of(wall)));

        FlyStoppingState result = prediction.state().orElseThrow();
        assertEquals(0.2D, result.body().minX(), EPSILON);
        assertEquals(0.0D, result.body().minZ(), EPSILON);
        assertEquals(0.0D, result.motion().x(), 0.0D);
        assertTrue(result.collided());
        assertTrue(result.collidedHorizontally());
        assertFalse(result.collidedVertically());
    }

    @Test
    void clipsNegativeHorizontalMovementAgainstTheOppositeFace() {
        BoxSnapshot wall = new BoxSnapshot(-1.8D, 0.0D, -1.0D, -0.8D, 2.0D, 1.0D);
        FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                newState(0.0D, 0.0D, 0.0D,
                        -1.0D, 0.0D, 0.0D,
                        false, false, false, 0.0D, 0.0D),
                collisionWorld(List.of(wall)));

        FlyStoppingState result = prediction.state().orElseThrow();
        assertEquals(-0.8D, result.body().minX(), EPSILON);
        assertEquals(0.0D, result.motion().x(), 0.0D);
        assertTrue(result.collidedHorizontally());
    }

    @Test
    void verticalCollisionClipsPositionButFlyingWrapperRestoresDecayedVelocity() {
        BoxSnapshot ceiling = new BoxSnapshot(-1.0D, 2.0D, -1.0D, 1.0D, 3.0D, 1.0D);
        FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                newState(0.0D, 0.0D, 0.0D,
                        0.0D, 1.0D, 0.0D,
                        false, false, false, 0.0D, 0.0D),
                collisionWorld(List.of(ceiling)));

        FlyStoppingState result = prediction.state().orElseThrow();
        assertEquals(0.2D, result.body().minY(), EPSILON);
        assertEquals(0.6D, result.motion().y(), 0.0D);
        assertTrue(result.collidedVertically());
        assertFalse(result.onGround());
    }

    @Test
    void groundedCollisionClearsPositiveFallDistance() {
        BoxSnapshot floor = new BoxSnapshot(-2.0D, -1.0D, -2.0D, 2.0D, 0.0D, 2.0D);
        FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                newState(0.0D, 0.0D, 0.0D,
                        0.0D, -1.0D, 0.0D,
                        false, false, false, 0.0D, 3.0D),
                collisionWorld(List.of(floor)));

        FlyStoppingState result = prediction.state().orElseThrow();
        assertTrue(result.onGround());
        assertTrue(result.collidedVertically());
        assertEquals(0.0D, result.fallDistance(), 0.0D);
        assertEquals(-0.6D, result.motion().y(), 0.0D);
    }

    @Test
    void selectsStepOnlyWhenItStrictlyImprovesHorizontalMovement() {
        BoxSnapshot floor = new BoxSnapshot(-2.0D, -1.0D, -2.0D, 4.0D, 0.0D, 2.0D);
        BoxSnapshot lowWall = new BoxSnapshot(0.605D, 0.0D, -1.0D, 1.8D, 1.0D, 1.0D);
        FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                newState(0.0D, 0.0D, 0.0D,
                        0.01D, -0.1D, 0.0D,
                        true, false, false, 1.0D, 4.0D),
                collisionWorld(List.of(floor, lowWall)));

        FlyStoppingState result = prediction.state().orElseThrow();
        assertEquals(0.01D, result.body().minX(), EPSILON);
        assertEquals(1.0D, result.body().minY(), EPSILON);
        assertFalse(result.collidedHorizontally());
        assertTrue(result.onGround());
        assertEquals(0.0D, result.fallDistance(), 0.0D);
    }

    @Test
    void sneakEdgeProtectionShrinksIndependentAndDiagonalUnsupportedMotion() {
        FlyStoppingWorldPort supportOnlyOnSingleAxis = new FlyStoppingWorldPort() {
            @Override
            public FlyStoppingEvidence<List<BoxSnapshot>> collisions(BoxSnapshot query) {
                if (query.minY() < 0.0D) {
                    boolean shiftedX = query.minX() != 0.0D;
                    boolean shiftedZ = query.minZ() != 0.0D;
                    if (shiftedX != shiftedZ) {
                        return FlyStoppingEvidence.known(List.of(query));
                    }
                }
                return FlyStoppingEvidence.known(List.of());
            }

            @Override
            public FlyStoppingEvidence<Double> groundSlipperiness(FlyStoppingState state) {
                return FlyStoppingEvidence.known(0.1D);
            }
        };
        FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                newState(0.0D, 0.0D, 0.0D,
                        0.1D, 0.0D, 0.1D,
                        true, false, true, 0.0D, 0.0D),
                supportOnlyOnSingleAxis);

        FlyStoppingState result = prediction.state().orElseThrow();
        assertEquals(0.0D, result.body().minX(), EPSILON);
        assertEquals(0.0D, result.body().minZ(), EPSILON);
        assertFalse(result.collidedHorizontally());
    }

    @Test
    void noClipSkipsCollisionEvidenceAndPreservesMovementFlags() {
        AtomicInteger collisionQueries = new AtomicInteger();
        FlyStoppingWorldPort world = new FlyStoppingWorldPort() {
            @Override
            public FlyStoppingEvidence<List<BoxSnapshot>> collisions(BoxSnapshot query) {
                collisionQueries.incrementAndGet();
                return FlyStoppingEvidence.unknown(FlyStoppingFailure.MISSING_EVIDENCE);
            }

            @Override
            public FlyStoppingEvidence<Double> groundSlipperiness(FlyStoppingState state) {
                return FlyStoppingEvidence.known(SLIPPERINESS);
            }
        };
        FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                newState(0.0D, 0.0D, 0.0D,
                        0.01D, -1.0D, 0.0D,
                        false, true, false, 0.0D, 7.0D), world);

        FlyStoppingState result = prediction.state().orElseThrow();
        assertEquals(0, collisionQueries.get());
        assertEquals(0.01D, result.body().minX(), EPSILON);
        assertEquals(-1.0D, result.body().minY(), EPSILON);
        assertEquals(7.0D, result.fallDistance(), 0.0D);
        assertFalse(result.collided());
    }

    @Test
    void propagatesEveryEvidenceFailureWithoutReturningPartialState() {
        for (FlyStoppingFailure failure : List.of(
                FlyStoppingFailure.MISSING_EVIDENCE,
                FlyStoppingFailure.STALE_EVIDENCE,
                FlyStoppingFailure.UNLOADED_EVIDENCE,
                FlyStoppingFailure.INCOMPLETE_EVIDENCE)) {
            FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                    newState(0.0D, 0.0D, 0.0D,
                            0.2D, 0.0D, 0.0D,
                            false, false, false, 0.0D, 0.0D),
                    unknownCollisionWorld(failure));

            assertEquals(FlyStoppingPredictionOutcome.UNKNOWN, prediction.outcome());
            assertEquals(failure, prediction.failure().orElseThrow());
            assertEquals(0, prediction.ticks());
            assertTrue(prediction.state().isEmpty());
            assertTrue(prediction.position().isEmpty());
        }
    }

    @Test
    void unknownAfterOneSuccessfulUpdateReportsOnlyCompletedTicks() {
        AtomicInteger queries = new AtomicInteger();
        FlyStoppingWorldPort world = new FlyStoppingWorldPort() {
            @Override
            public FlyStoppingEvidence<List<BoxSnapshot>> collisions(BoxSnapshot query) {
                return queries.getAndIncrement() == 0
                        ? FlyStoppingEvidence.known(List.of())
                        : FlyStoppingEvidence.unknown(FlyStoppingFailure.STALE_EVIDENCE);
            }

            @Override
            public FlyStoppingEvidence<Double> groundSlipperiness(FlyStoppingState state) {
                return FlyStoppingEvidence.known(SLIPPERINESS);
            }
        };

        FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                newState(0.0D, 0.0D, 0.0D,
                        0.2D, 0.0D, 0.0D,
                        false, false, false, 0.0D, 0.0D), world);

        assertEquals(FlyStoppingPredictionOutcome.UNKNOWN, prediction.outcome());
        assertEquals(FlyStoppingFailure.STALE_EVIDENCE,
                prediction.failure().orElseThrow());
        assertEquals(1, prediction.ticks());
    }

    @Test
    void unknownGroundFrictionIsNotReplacedWithAirFriction() {
        AtomicInteger collisionQueries = new AtomicInteger();
        FlyStoppingWorldPort world = new FlyStoppingWorldPort() {
            @Override
            public FlyStoppingEvidence<List<BoxSnapshot>> collisions(BoxSnapshot query) {
                collisionQueries.incrementAndGet();
                return FlyStoppingEvidence.known(List.of());
            }

            @Override
            public FlyStoppingEvidence<Double> groundSlipperiness(FlyStoppingState state) {
                return FlyStoppingEvidence.unknown(FlyStoppingFailure.UNLOADED_EVIDENCE);
            }
        };

        FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                newState(0.0D, 0.0D, 0.0D,
                        0.2D, 0.0D, 0.0D,
                        true, false, false, 0.0D, 0.0D), world);

        assertEquals(FlyStoppingPredictionOutcome.UNKNOWN, prediction.outcome());
        assertEquals(FlyStoppingFailure.UNLOADED_EVIDENCE,
                prediction.failure().orElseThrow());
        assertEquals(0, collisionQueries.get());
    }

    @Test
    void malformedOrThrowingWorldAnswersBecomeIncompleteEvidence() {
        List<BoxSnapshot> malformed = new ArrayList<>();
        malformed.add(null);
        for (FlyStoppingWorldPort world : List.of(
                collisionPort(query -> null),
                collisionPort(query -> {
                    throw new IllegalStateException("capture failed");
                }),
                collisionPort(query -> FlyStoppingEvidence.known(malformed)))) {
            FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                    newState(0.0D, 0.0D, 0.0D,
                            0.2D, 0.0D, 0.0D,
                            false, false, false, 0.0D, 0.0D), world);

            assertEquals(FlyStoppingPredictionOutcome.UNKNOWN, prediction.outcome());
            assertEquals(FlyStoppingFailure.INCOMPLETE_EVIDENCE,
                    prediction.failure().orElseThrow());
        }
    }

    @Test
    void edgeProbeBudgetKeepsExtremeSneakingMotionBounded() {
        AtomicInteger queries = new AtomicInteger();
        FlyStoppingWorldPort world = new FlyStoppingWorldPort() {
            @Override
            public FlyStoppingEvidence<List<BoxSnapshot>> collisions(BoxSnapshot query) {
                queries.incrementAndGet();
                return FlyStoppingEvidence.known(List.of());
            }

            @Override
            public FlyStoppingEvidence<Double> groundSlipperiness(FlyStoppingState state) {
                return FlyStoppingEvidence.known(SLIPPERINESS);
            }
        };

        FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                newState(0.0D, 0.0D, 0.0D,
                        100.0D, 0.0D, 0.0D,
                        true, false, true, 0.0D, 0.0D), world);

        assertEquals(FlyStoppingPredictionOutcome.UNKNOWN, prediction.outcome());
        assertEquals(FlyStoppingFailure.INVALID_STATE,
                prediction.failure().orElseThrow());
        assertEquals(1_024, queries.get());
        assertEquals(0, prediction.ticks());
    }

    @Test
    void evidenceRejectsInvalidStateFailureAsAWorldAnswer() {
        assertThrows(IllegalArgumentException.class,
                () -> FlyStoppingEvidence.unknown(FlyStoppingFailure.INVALID_STATE));
        assertTrue(FlyStoppingEvidence.known(List.of()).isKnown());
    }

    @Test
    void predictionConstructorRejectsImpossibleOutcomeTickCombinations() {
        FlyStoppingState state = newState(0.0D, 0.0D, 0.0D,
                0.0D, 0.0D, 0.0D,
                false, false, false, 0.0D, 0.0D);
        assertThrows(IllegalArgumentException.class, () -> new FlyStoppingPrediction(
                FlyStoppingPredictionOutcome.STOPPED,
                Optional.of(state), 0, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new FlyStoppingPrediction(
                FlyStoppingPredictionOutcome.TICK_LIMIT,
                Optional.of(state), FlyStoppingSimulator.MAX_TICKS - 1, Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> new FlyStoppingPrediction(
                FlyStoppingPredictionOutcome.UNKNOWN,
                Optional.empty(), FlyStoppingSimulator.MAX_TICKS,
                Optional.of(FlyStoppingFailure.INCOMPLETE_EVIDENCE)));
    }

    @Test
    void incoherentPositionOrCollisionFlagsReturnTypedInvalidState() {
        FlyStoppingState base = newState(0.0D, 0.0D, 0.0D,
                0.2D, 0.0D, 0.0D,
                false, false, false, 0.0D, 0.0D);
        FlyStoppingState mismatchedPosition = new FlyStoppingState(
                new PositionSnapshot(2.0D, 0.0D, 0.3D),
                base.motion(), base.body(), base.onGround(), base.noClip(), base.sneaking(),
                base.stepHeight(), base.fallDistance(), base.input(),
                base.collided(), base.collidedHorizontally(), base.collidedVertically());
        FlyStoppingState mismatchedFlags = new FlyStoppingState(
                base.position(), base.motion(), base.body(), base.onGround(), base.noClip(),
                base.sneaking(), base.stepHeight(), base.fallDistance(), base.input(),
                false, true, false);

        for (FlyStoppingState invalid : List.of(mismatchedPosition, mismatchedFlags)) {
            FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                    invalid, emptyWorld());
            assertEquals(FlyStoppingPredictionOutcome.UNKNOWN, prediction.outcome());
            assertEquals(FlyStoppingFailure.INVALID_STATE,
                    prediction.failure().orElseThrow());
            assertEquals(0, prediction.ticks());
        }
    }

    @Test
    void malformedOrThrowingGroundFrictionBecomesIncompleteEvidence() {
        for (FlyStoppingWorldPort world : List.of(
                groundPort(state -> null),
                groundPort(state -> {
                    throw new IllegalStateException("capture failed");
                }),
                groundPort(state -> FlyStoppingEvidence.known(Double.NaN)),
                groundPort(state -> FlyStoppingEvidence.known(Double.POSITIVE_INFINITY)),
                groundPort(state -> FlyStoppingEvidence.known(-0.1D)))) {
            FlyStoppingPrediction prediction = FlyStoppingSimulator.predict(
                    newState(0.0D, 0.0D, 0.0D,
                            0.2D, 0.0D, 0.0D,
                            true, false, false, 0.0D, 0.0D), world);

            assertEquals(FlyStoppingPredictionOutcome.UNKNOWN, prediction.outcome());
            assertEquals(FlyStoppingFailure.INCOMPLETE_EVIDENCE,
                    prediction.failure().orElseThrow());
            assertEquals(0, prediction.ticks());
        }
    }

    private static FlyStoppingWorldPort emptyWorld() {
        return FlyStoppingWorldPort.knownEmpty(SLIPPERINESS);
    }

    private static FlyStoppingWorldPort collisionWorld(List<BoxSnapshot> colliders) {
        List<BoxSnapshot> snapshot = List.copyOf(colliders);
        return collisionPort(query -> FlyStoppingEvidence.known(snapshot.stream()
                .filter(query::intersects)
                .toList()));
    }

    private static FlyStoppingWorldPort unknownCollisionWorld(FlyStoppingFailure failure) {
        return collisionPort(query -> FlyStoppingEvidence.unknown(failure));
    }

    private static FlyStoppingWorldPort collisionPort(CollisionAnswer answer) {
        return new FlyStoppingWorldPort() {
            @Override
            public FlyStoppingEvidence<List<BoxSnapshot>> collisions(BoxSnapshot query) {
                return answer.answer(query);
            }

            @Override
            public FlyStoppingEvidence<Double> groundSlipperiness(FlyStoppingState state) {
                return FlyStoppingEvidence.known(SLIPPERINESS);
            }
        };
    }

    private static FlyStoppingWorldPort groundPort(GroundAnswer answer) {
        return new FlyStoppingWorldPort() {
            @Override
            public FlyStoppingEvidence<List<BoxSnapshot>> collisions(BoxSnapshot query) {
                return FlyStoppingEvidence.known(List.of());
            }

            @Override
            public FlyStoppingEvidence<Double> groundSlipperiness(FlyStoppingState state) {
                return answer.answer(state);
            }
        };
    }

    private static FlyStoppingState newState(
            double x,
            double y,
            double z,
            double motionX,
            double motionY,
            double motionZ,
            boolean onGround,
            boolean noClip,
            boolean sneaking,
            double stepHeight,
            double fallDistance
    ) {
        return newState(x, y, z, motionX, motionY, motionZ,
                onGround, noClip, sneaking, stepHeight, fallDistance, FlyStoppingInput.ZERO);
    }

    private static FlyStoppingState newState(
            double x,
            double y,
            double z,
            double motionX,
            double motionY,
            double motionZ,
            boolean onGround,
            boolean noClip,
            boolean sneaking,
            double stepHeight,
            double fallDistance,
            FlyStoppingInput input
    ) {
        BoxSnapshot body = new BoxSnapshot(x, y, z, x + 0.6D, y + 1.8D, z + 0.6D);
        return new FlyStoppingState(
                new PositionSnapshot(x + 0.3D, y, z + 0.3D),
                new MotionSnapshot(motionX, motionY, motionZ),
                body,
                onGround,
                noClip,
                sneaking,
                stepHeight,
                fallDistance,
                input,
                false,
                false,
                false);
    }

    @FunctionalInterface
    private interface CollisionAnswer {
        FlyStoppingEvidence<List<BoxSnapshot>> answer(BoxSnapshot query);
    }

    @FunctionalInterface
    private interface GroundAnswer {
        FlyStoppingEvidence<Double> answer(FlyStoppingState state);
    }
}

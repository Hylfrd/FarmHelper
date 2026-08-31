package dev.hylfrd.farmhelper.feature.reconnect;

import dev.hylfrd.farmhelper.runtime.gamestate.BuffSnapshot;
import dev.hylfrd.farmhelper.runtime.gamestate.EconomySnapshot;
import dev.hylfrd.farmhelper.runtime.gamestate.GameStateSnapshot;
import dev.hylfrd.farmhelper.runtime.gamestate.GardenStateSnapshot;
import dev.hylfrd.farmhelper.runtime.gamestate.JacobStateSnapshot;
import dev.hylfrd.farmhelper.runtime.gamestate.SemanticLocation;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutoReconnectControllerTest {
    private static final long DEFAULT_TIMEOUT_MILLIS = 300_000L;

    @Test
    void connectedStartWaitsForObservedDisconnectBeforeConnecting() {
        Harness harness = harness();

        ReconnectDecision started = harness.controller.start(snapshot(
                0L, multiplayer(), Observation.absent(), unknownGame(0L)), 5_000L);

        assertDecision(started, ReconnectOutcome.RUNNING, ReconnectReason.STARTED,
                ReconnectState.CONNECTING, ReconnectAction.DISCONNECT,
                ReconnectActionStatus.ACCEPTED);
        assertEquals(5_000L, started.dueAtMillis().orElseThrow());
        assertEquals(DEFAULT_TIMEOUT_MILLIS, started.deadlineAtMillis().orElseThrow());

        ReconnectDecision stillConnected = harness.controller.tick(snapshot(
                5_000L, multiplayer(), Observation.absent(), unknownGame(1L)));
        assertDecision(stillConnected, ReconnectOutcome.RUNNING,
                ReconnectReason.INITIAL_DISCONNECT_PENDING,
                ReconnectState.CONNECTING, ReconnectAction.NONE,
                ReconnectActionStatus.NOT_REQUESTED);
        assertEquals(List.of(ReconnectAction.DISCONNECT), harness.ports.actions);

        ReconnectDecision connecting = harness.controller.tick(snapshot(
                5_000L, Observation.absent(), disconnectedScreen(), unknownGame(2L)));
        assertDecision(connecting, ReconnectOutcome.RUNNING,
                ReconnectReason.CONNECT_ATTEMPTED,
                ReconnectState.LOBBY, ReconnectAction.CONNECT,
                ReconnectActionStatus.ACCEPTED);
        assertEquals(12_500L, connecting.dueAtMillis().orElseThrow());
        assertEquals(1, connecting.connectionAttempts());
        assertEquals(List.of(ReconnectAction.DISCONNECT, ReconnectAction.CONNECT),
                harness.ports.actions);
    }

    @Test
    void initialDelayAndRejectedConnectionAttemptUseExactUpstreamRetryDelays() {
        Harness harness = harness();
        harness.ports.connectResults.add(false);
        harness.ports.connectResults.add(true);

        ReconnectDecision started = harness.controller.start(snapshot(
                0L, Observation.absent(), disconnectedScreen(), unknownGame(0L)), 5_000L);
        assertDecision(started, ReconnectOutcome.RUNNING, ReconnectReason.STARTED,
                ReconnectState.CONNECTING, ReconnectAction.NONE,
                ReconnectActionStatus.NOT_REQUESTED);

        ReconnectDecision early = harness.controller.tick(snapshot(
                4_999L, Observation.absent(), disconnectedScreen(), unknownGame(1L)));
        assertEquals(ReconnectReason.DELAY_PENDING, early.reason());
        assertTrue(harness.ports.actions.isEmpty());

        ReconnectDecision rejected = harness.controller.tick(snapshot(
                5_000L, Observation.absent(), disconnectedScreen(), unknownGame(2L)));
        assertDecision(rejected, ReconnectOutcome.RUNNING,
                ReconnectReason.CONNECT_ATTEMPTED,
                ReconnectState.CONNECTING, ReconnectAction.CONNECT,
                ReconnectActionStatus.REJECTED);
        assertEquals(10_000L, rejected.dueAtMillis().orElseThrow());
        assertEquals(1, rejected.connectionAttempts());

        harness.controller.tick(snapshot(
                9_999L, Observation.absent(), disconnectedScreen(), unknownGame(3L)));
        assertEquals(1, harness.ports.actions.size());

        ReconnectDecision accepted = harness.controller.tick(snapshot(
                10_000L, Observation.absent(), disconnectedScreen(), unknownGame(4L)));
        assertDecision(accepted, ReconnectOutcome.RUNNING,
                ReconnectReason.CONNECT_ATTEMPTED,
                ReconnectState.LOBBY, ReconnectAction.CONNECT,
                ReconnectActionStatus.ACCEPTED);
        assertEquals(17_500L, accepted.dueAtMillis().orElseThrow());
        assertEquals(2, accepted.connectionAttempts());
    }

    @Test
    void repeatedStartIsIdempotentAndAnExternalConnectionIsNotDuplicated() {
        Harness harness = harness();
        ReconnectDecision started = harness.controller.start(snapshot(
                0L, Observation.absent(), disconnectedScreen(), unknownGame(0L)), 5_000L);

        ReconnectDecision duplicate = harness.controller.start(snapshot(
                1L, multiplayer(), Observation.absent(), unknownGame(1L)), 0L);
        assertDecision(duplicate, ReconnectOutcome.RUNNING,
                ReconnectReason.ALREADY_RUNNING,
                ReconnectState.CONNECTING, ReconnectAction.NONE,
                ReconnectActionStatus.NOT_REQUESTED);
        assertEquals(started.deadlineAtMillis(), duplicate.deadlineAtMillis());
        assertTrue(harness.ports.actions.isEmpty());

        ReconnectDecision established = harness.controller.tick(snapshot(
                2L, multiplayer(), Observation.absent(), unknownGame(2L)));
        assertDecision(established, ReconnectOutcome.RUNNING,
                ReconnectReason.CONNECTION_ESTABLISHED,
                ReconnectState.LOBBY, ReconnectAction.NONE,
                ReconnectActionStatus.NOT_REQUESTED);
        assertEquals(7_502L, established.dueAtMillis().orElseThrow());
        assertTrue(harness.ports.actions.isEmpty());
    }

    @Test
    void freshLobbyAndOutsideEvidenceRouteToGardenThenComplete() {
        Harness harness = connectedHarness();

        ReconnectDecision stale = harness.controller.tick(snapshot(
                7_500L, multiplayer(), Observation.absent(), game(
                        0L, Observation.present(SemanticLocation.LOBBY),
                        Observation.present(false))));
        assertEquals(ReconnectReason.GAME_STATE_NOT_FRESH, stale.reason());
        assertEquals(List.of(ReconnectAction.CONNECT), harness.ports.actions);

        ReconnectDecision skyBlock = harness.controller.tick(snapshot(
                7_501L, multiplayer(), Observation.absent(), game(
                        1L, Observation.present(SemanticLocation.LOBBY),
                        Observation.present(false))));
        assertDecision(skyBlock, ReconnectOutcome.RUNNING,
                ReconnectReason.LOBBY_OBSERVED,
                ReconnectState.GARDEN, ReconnectAction.ENTER_SKYBLOCK,
                ReconnectActionStatus.ACCEPTED);
        assertEquals(12_501L, skyBlock.dueAtMillis().orElseThrow());

        ReconnectDecision warp = harness.controller.tick(snapshot(
                12_501L, multiplayer(), Observation.absent(), game(
                        2L, Observation.present(SemanticLocation.HUB),
                        Observation.present(false))));
        assertDecision(warp, ReconnectOutcome.RUNNING,
                ReconnectReason.OUTSIDE_GARDEN,
                ReconnectState.GARDEN, ReconnectAction.WARP_GARDEN,
                ReconnectActionStatus.ACCEPTED);
        assertEquals(17_501L, warp.dueAtMillis().orElseThrow());

        ReconnectDecision completed = harness.controller.tick(snapshot(
                17_501L, multiplayer(), Observation.absent(), game(
                        3L, Observation.present(SemanticLocation.GARDEN),
                        Observation.present(true))));
        assertDecision(completed, ReconnectOutcome.SUCCEEDED,
                ReconnectReason.GARDEN_REACHED,
                ReconnectState.STOPPED, ReconnectAction.NONE,
                ReconnectActionStatus.NOT_REQUESTED);
        assertFalse(harness.controller.active());
        assertTrue(harness.controller.dueAtMillis().isEmpty());
        assertEquals(List.of(
                ReconnectAction.CONNECT,
                ReconnectAction.ENTER_SKYBLOCK,
                ReconnectAction.WARP_GARDEN), harness.ports.actions);
    }

    @Test
    void limboReturnsToLobbyAndGardenLobbyBounceKeepsSixtySecondCooldown() {
        Harness limbo = connectedHarness();
        ReconnectDecision lobbyReturn = limbo.controller.tick(snapshot(
                7_500L, multiplayer(), Observation.absent(), game(
                        1L, Observation.present(SemanticLocation.LIMBO),
                        Observation.present(false))));
        assertDecision(lobbyReturn, ReconnectOutcome.RUNNING,
                ReconnectReason.LIMBO_OBSERVED,
                ReconnectState.LOBBY, ReconnectAction.RETURN_TO_LOBBY,
                ReconnectActionStatus.ACCEPTED);
        assertEquals(12_500L, lobbyReturn.dueAtMillis().orElseThrow());

        Harness bounce = connectedHarness();
        bounce.controller.tick(snapshot(
                7_500L, multiplayer(), Observation.absent(), game(
                        1L, Observation.present(SemanticLocation.LOBBY),
                        Observation.present(false))));
        ReconnectDecision bounced = bounce.controller.tick(snapshot(
                12_500L, multiplayer(), Observation.absent(), game(
                        2L, Observation.present(SemanticLocation.LOBBY),
                        Observation.present(false))));

        assertDecision(bounced, ReconnectOutcome.RUNNING,
                ReconnectReason.LOBBY_OBSERVED,
                ReconnectState.LOBBY, ReconnectAction.NONE,
                ReconnectActionStatus.NOT_REQUESTED);
        assertEquals(72_500L, bounced.dueAtMillis().orElseThrow());
        assertEquals(List.of(ReconnectAction.CONNECT, ReconnectAction.ENTER_SKYBLOCK),
                bounce.ports.actions);

        bounce.controller.tick(snapshot(
                72_499L, multiplayer(), Observation.absent(), game(
                        3L, Observation.present(SemanticLocation.LOBBY),
                        Observation.present(false))));
        assertEquals(2, bounce.ports.actions.size());

        ReconnectDecision reentered = bounce.controller.tick(snapshot(
                72_500L, multiplayer(), Observation.absent(), game(
                        4L, Observation.present(SemanticLocation.LOBBY),
                        Observation.present(false))));
        assertEquals(ReconnectAction.ENTER_SKYBLOCK, reentered.action());
        assertEquals(ReconnectState.GARDEN, reentered.state());
    }

    @Test
    void connectionLossFromTravelStateSchedulesANewAttempt() {
        Harness harness = connectedHarness();

        ReconnectDecision lost = harness.controller.tick(snapshot(
                7_500L, Observation.absent(), disconnectedScreen(), unknownGame(1L)));
        assertDecision(lost, ReconnectOutcome.RUNNING,
                ReconnectReason.CONNECTION_LOST,
                ReconnectState.CONNECTING, ReconnectAction.NONE,
                ReconnectActionStatus.NOT_REQUESTED);
        assertEquals(12_500L, lost.dueAtMillis().orElseThrow());

        harness.controller.tick(snapshot(
                12_499L, Observation.absent(), disconnectedScreen(), unknownGame(2L)));
        assertEquals(List.of(ReconnectAction.CONNECT), harness.ports.actions);

        ReconnectDecision retry = harness.controller.tick(snapshot(
                12_500L, Observation.absent(), disconnectedScreen(), unknownGame(3L)));
        assertEquals(ReconnectAction.CONNECT, retry.action());
        assertEquals(2, retry.connectionAttempts());
    }

    @Test
    void unknownStaleTransitioningAndContradictoryEvidenceNeverTravels() {
        Harness rejected = harness();
        ReconnectDecision unknownStart = rejected.controller.start(snapshot(
                0L, Observation.unknown(), Observation.unknown(), unknownGame(0L)), 0L);
        assertDecision(unknownStart, ReconnectOutcome.REJECTED,
                ReconnectReason.CONNECTION_UNKNOWN,
                ReconnectState.STOPPED, ReconnectAction.NONE,
                ReconnectActionStatus.NOT_REQUESTED);

        Harness harness = connectedHarness();
        assertEquals(ReconnectReason.CONNECTION_UNKNOWN, harness.controller.tick(snapshot(
                7_500L, Observation.unknown(), Observation.unknown(), unknownGame(1L))).reason());
        assertEquals(ReconnectReason.GAME_STATE_UNKNOWN, harness.controller.tick(snapshot(
                7_501L, multiplayer(), Observation.absent(), unknownGame(1L))).reason());
        assertEquals(ReconnectReason.GAME_STATE_CONTRADICTORY,
                harness.controller.tick(snapshot(
                        7_502L, multiplayer(), Observation.absent(), game(
                                2L, Observation.present(SemanticLocation.GARDEN),
                                Observation.present(false)))).reason());
        assertEquals(ReconnectReason.GAME_STATE_TRANSITIONING,
                harness.controller.tick(snapshot(
                        7_503L, multiplayer(), Observation.absent(), game(
                                3L, Observation.present(SemanticLocation.TELEPORTING),
                                Observation.present(false)))).reason());
        assertEquals(List.of(ReconnectAction.CONNECT), harness.ports.actions);
    }

    @Test
    void rejectedTravelActionRetriesWithoutAdvancingItsState() {
        Harness harness = connectedHarness();
        harness.ports.enterSkyBlockResults.add(false);
        harness.ports.enterSkyBlockResults.add(true);

        ReconnectDecision rejected = harness.controller.tick(snapshot(
                7_500L, multiplayer(), Observation.absent(), game(
                        1L, Observation.present(SemanticLocation.LOBBY),
                        Observation.present(false))));
        assertDecision(rejected, ReconnectOutcome.RUNNING,
                ReconnectReason.LOBBY_OBSERVED,
                ReconnectState.LOBBY, ReconnectAction.ENTER_SKYBLOCK,
                ReconnectActionStatus.REJECTED);
        assertEquals(12_500L, rejected.dueAtMillis().orElseThrow());

        ReconnectDecision accepted = harness.controller.tick(snapshot(
                12_500L, multiplayer(), Observation.absent(), game(
                        2L, Observation.present(SemanticLocation.LOBBY),
                        Observation.present(false))));
        assertDecision(accepted, ReconnectOutcome.RUNNING,
                ReconnectReason.LOBBY_OBSERVED,
                ReconnectState.GARDEN, ReconnectAction.ENTER_SKYBLOCK,
                ReconnectActionStatus.ACCEPTED);
    }

    @Test
    void singleplayerIsRejectedBeforeStartAndFailsAnActiveRun() {
        Harness rejected = harness();
        ReconnectDecision start = rejected.controller.start(snapshot(
                0L, Observation.present(ConnectionSnapshot.singleplayer()),
                Observation.absent(), unknownGame(0L)), 0L);
        assertDecision(start, ReconnectOutcome.REJECTED,
                ReconnectReason.SINGLEPLAYER_UNSUPPORTED,
                ReconnectState.STOPPED, ReconnectAction.NONE,
                ReconnectActionStatus.NOT_REQUESTED);
        assertTrue(rejected.ports.actions.isEmpty());

        Harness active = connectedHarness();
        ReconnectDecision failed = active.controller.tick(snapshot(
                7_500L, Observation.present(ConnectionSnapshot.singleplayer()),
                Observation.absent(), unknownGame(1L)));
        assertDecision(failed, ReconnectOutcome.FAILED,
                ReconnectReason.SINGLEPLAYER_UNSUPPORTED,
                ReconnectState.STOPPED, ReconnectAction.NONE,
                ReconnectActionStatus.NOT_REQUESTED);
    }

    @Test
    void manualCancelRequiresDisconnectedScreenAndStopsDespiteUiFailure() {
        Harness harness = harness();
        harness.controller.start(snapshot(
                0L, Observation.absent(), Observation.unknown(), unknownGame(0L)), 20_000L);

        ReconnectDecision unknown = harness.controller.cancelFromDisconnectedScreen(snapshot(
                1L, Observation.absent(), Observation.unknown(), unknownGame(1L)));
        assertEquals(ReconnectReason.CANCEL_SCREEN_UNAVAILABLE, unknown.reason());
        assertTrue(harness.controller.active());

        ReconnectDecision wrong = harness.controller.cancelFromDisconnectedScreen(snapshot(
                2L, Observation.absent(), screen("net.minecraft.client.gui.screens.TitleScreen"),
                unknownGame(2L)));
        assertEquals(ReconnectReason.CANCEL_SCREEN_UNAVAILABLE, wrong.reason());

        harness.ports.showTitleScreenResult = false;
        ReconnectDecision cancelled = harness.controller.cancelFromDisconnectedScreen(snapshot(
                3L, Observation.absent(), disconnectedScreen(), unknownGame(3L)));
        assertDecision(cancelled, ReconnectOutcome.CANCELLED,
                ReconnectReason.MANUAL_CANCEL,
                ReconnectState.STOPPED, ReconnectAction.SHOW_TITLE_SCREEN,
                ReconnectActionStatus.REJECTED);
        assertFalse(harness.controller.active());

        Harness throwing = harness();
        throwing.controller.start(snapshot(
                3L, Observation.absent(), disconnectedScreen(), unknownGame(0L)), 1L);
        throwing.ports.throwingAction = ReconnectAction.SHOW_TITLE_SCREEN;
        ReconnectDecision failedUi = throwing.controller.cancelFromDisconnectedScreen(snapshot(
                4L, Observation.absent(), disconnectedScreen(), unknownGame(1L)));
        assertEquals(ReconnectOutcome.CANCELLED, failedUi.outcome());
        assertEquals(ReconnectActionStatus.FAILED, failedUi.actionStatus());
        assertTrue(throwing.controller.lastPortFailure().isPresent());
        assertFalse(throwing.controller.active());
    }

    @Test
    void explicitStopIsIdempotentAndNeverTouchesAPlatformPort() {
        Harness harness = harness();
        harness.controller.start(snapshot(
                0L, Observation.absent(), disconnectedScreen(), unknownGame(0L)), 20_000L);

        ReconnectDecision stopped = harness.controller.stop();
        assertDecision(stopped, ReconnectOutcome.CANCELLED,
                ReconnectReason.STOP_REQUESTED,
                ReconnectState.STOPPED, ReconnectAction.NONE,
                ReconnectActionStatus.NOT_REQUESTED);
        assertTrue(harness.ports.actions.isEmpty());

        ReconnectDecision duplicate = harness.controller.stop();
        assertDecision(duplicate, ReconnectOutcome.IDLE,
                ReconnectReason.IDLE,
                ReconnectState.STOPPED, ReconnectAction.NONE,
                ReconnectActionStatus.NOT_REQUESTED);
        assertTrue(harness.ports.actions.isEmpty());
    }

    @Test
    void exactDeadlinePreventsAnotherAttemptAndDeadlineAdditionSaturates() {
        Harness deadline = harness(ReconnectPolicy.upstreamDurations(10_000L));
        deadline.controller.start(snapshot(
                0L, Observation.absent(), disconnectedScreen(), unknownGame(0L)), 10_000L);
        assertEquals(ReconnectReason.DELAY_PENDING, deadline.controller.tick(snapshot(
                9_999L, Observation.absent(), disconnectedScreen(), unknownGame(1L))).reason());

        ReconnectDecision timedOut = deadline.controller.tick(snapshot(
                10_000L, Observation.absent(), disconnectedScreen(), unknownGame(2L)));
        assertDecision(timedOut, ReconnectOutcome.TIMED_OUT,
                ReconnectReason.DEADLINE_REACHED,
                ReconnectState.STOPPED, ReconnectAction.NONE,
                ReconnectActionStatus.NOT_REQUESTED);
        assertTrue(deadline.ports.actions.isEmpty());

        Harness saturated = harness(ReconnectPolicy.upstreamDurations(10L));
        long start = Long.MAX_VALUE - 5L;
        ReconnectDecision started = saturated.controller.start(snapshot(
                start, Observation.absent(), disconnectedScreen(), unknownGame(0L)),
                Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, started.dueAtMillis().orElseThrow());
        assertEquals(Long.MAX_VALUE, started.deadlineAtMillis().orElseThrow());
        assertEquals(ReconnectReason.DELAY_PENDING, saturated.controller.tick(snapshot(
                Long.MAX_VALUE - 1L, Observation.absent(), disconnectedScreen(),
                unknownGame(1L))).reason());
        assertEquals(ReconnectOutcome.TIMED_OUT, saturated.controller.tick(snapshot(
                Long.MAX_VALUE, Observation.absent(), disconnectedScreen(),
                unknownGame(2L))).outcome());
    }

    @Test
    void adapterExceptionTerminatesBecauseItsSideEffectIsUncertain() {
        Harness harness = harness();
        harness.controller.start(snapshot(
                0L, Observation.absent(), disconnectedScreen(), unknownGame(0L)), 0L);
        harness.ports.throwingAction = ReconnectAction.CONNECT;

        ReconnectDecision failed = harness.controller.tick(snapshot(
                0L, Observation.absent(), disconnectedScreen(), unknownGame(1L)));

        assertDecision(failed, ReconnectOutcome.FAILED,
                ReconnectReason.PORT_FAILURE,
                ReconnectState.STOPPED, ReconnectAction.CONNECT,
                ReconnectActionStatus.FAILED);
        assertTrue(harness.controller.lastPortFailure().isPresent());
        assertFalse(harness.controller.active());
    }

    @Test
    void validatesPolicyInputDecisionAndMonotonicTimeContracts() {
        assertThrows(IllegalArgumentException.class,
                () -> ReconnectPolicy.upstreamDurations(0L));
        assertThrows(IllegalArgumentException.class,
                () -> new ReconnectPolicy(-1L, 0L, 0L, 0L, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new ReconnectSnapshot(
                        -1L, Observation.absent(), Observation.absent(), unknownGame(0L)));
        assertThrows(IllegalArgumentException.class,
                () -> new ReconnectDecision(
                        ReconnectOutcome.RUNNING,
                        ReconnectReason.STARTED,
                        ReconnectState.STOPPED,
                        ReconnectAction.NONE,
                        ReconnectActionStatus.NOT_REQUESTED,
                        java.util.OptionalLong.of(1L),
                        java.util.OptionalLong.of(2L),
                        0));
        assertThrows(IllegalArgumentException.class,
                () -> new ReconnectDecision(
                        ReconnectOutcome.RUNNING,
                        ReconnectReason.STARTED,
                        ReconnectState.CONNECTING,
                        ReconnectAction.NONE,
                        ReconnectActionStatus.NOT_REQUESTED,
                        java.util.OptionalLong.of(-1L),
                        java.util.OptionalLong.of(2L),
                        0));

        Harness harness = harness();
        harness.controller.start(snapshot(
                10L, Observation.absent(), disconnectedScreen(), unknownGame(0L)), 1L);
        assertThrows(IllegalArgumentException.class, () -> harness.controller.tick(snapshot(
                9L, Observation.absent(), disconnectedScreen(), unknownGame(1L))));
        assertThrows(IllegalArgumentException.class, () -> harness.controller.start(snapshot(
                10L, Observation.absent(), disconnectedScreen(), unknownGame(2L)), -1L));
    }

    private static Harness connectedHarness() {
        Harness harness = harness();
        harness.controller.start(snapshot(
                0L, Observation.absent(), disconnectedScreen(), unknownGame(0L)), 0L);
        ReconnectDecision connected = harness.controller.tick(snapshot(
                0L, Observation.absent(), disconnectedScreen(), unknownGame(0L)));
        assertEquals(ReconnectState.LOBBY, connected.state());
        return harness;
    }

    private static Harness harness() {
        return harness(ReconnectPolicy.upstreamDurations(DEFAULT_TIMEOUT_MILLIS));
    }

    private static Harness harness(ReconnectPolicy policy) {
        FakePorts ports = new FakePorts();
        return new Harness(
                new AutoReconnectController(policy, ports, ports, ports),
                ports);
    }

    private static void assertDecision(
            ReconnectDecision decision,
            ReconnectOutcome outcome,
            ReconnectReason reason,
            ReconnectState state,
            ReconnectAction action,
            ReconnectActionStatus actionStatus
    ) {
        assertEquals(outcome, decision.outcome());
        assertEquals(reason, decision.reason());
        assertEquals(state, decision.state());
        assertEquals(action, decision.action());
        assertEquals(actionStatus, decision.actionStatus());
    }

    private static Observation<ConnectionSnapshot> multiplayer() {
        return Observation.present(ConnectionSnapshot.multiplayer());
    }

    private static Observation<ScreenSnapshot> disconnectedScreen() {
        return screen(AutoReconnectController.DISCONNECTED_SCREEN_TYPE);
    }

    private static Observation<ScreenSnapshot> screen(String type) {
        return Observation.present(new ScreenSnapshot(
                1L, Observation.present(type), Observation.present("screen")));
    }

    private static ReconnectSnapshot snapshot(
            long nowMillis,
            Observation<ConnectionSnapshot> connection,
            Observation<ScreenSnapshot> screen,
            GameStateSnapshot gameState
    ) {
        return new ReconnectSnapshot(nowMillis, connection, screen, gameState);
    }

    private static GameStateSnapshot unknownGame(long generation) {
        return game(generation, Observation.unknown(), Observation.unknown());
    }

    private static GameStateSnapshot game(
            long generation,
            Observation<SemanticLocation> location,
            Observation<Boolean> inGarden
    ) {
        return new GameStateSnapshot(
                generation,
                location,
                Observation.unknown(),
                inGarden,
                Observation.unknown(),
                new EconomySnapshot(
                        Observation.unknown(), Observation.unknown(),
                        Observation.unknown(), Observation.unknown()),
                new JacobStateSnapshot(Observation.unknown(), Observation.unknown()),
                new BuffSnapshot(
                        Observation.unknown(), Observation.unknown(), Observation.unknown(),
                        Observation.unknown(), Observation.unknown(), Observation.unknown()),
                new GardenStateSnapshot(
                        Observation.unknown(), Observation.unknown(), Observation.unknown(),
                        Observation.unknown(), Observation.unknown(), Observation.unknown(),
                        Observation.unknown()));
    }

    private record Harness(AutoReconnectController controller, FakePorts ports) {
    }

    private static final class FakePorts implements
            ReconnectConnectionPort, ReconnectTravelPort, ReconnectScreenPort {
        private final List<ReconnectAction> actions = new ArrayList<>();
        private final Deque<Boolean> disconnectResults = new ArrayDeque<>();
        private final Deque<Boolean> connectResults = new ArrayDeque<>();
        private final Deque<Boolean> enterSkyBlockResults = new ArrayDeque<>();
        private final Deque<Boolean> returnToLobbyResults = new ArrayDeque<>();
        private final Deque<Boolean> warpToGardenResults = new ArrayDeque<>();
        private boolean showTitleScreenResult = true;
        private ReconnectAction throwingAction;

        @Override
        public boolean disconnect() {
            return invoke(ReconnectAction.DISCONNECT, disconnectResults);
        }

        @Override
        public boolean connect() {
            return invoke(ReconnectAction.CONNECT, connectResults);
        }

        @Override
        public boolean enterSkyBlock() {
            return invoke(ReconnectAction.ENTER_SKYBLOCK, enterSkyBlockResults);
        }

        @Override
        public boolean returnToLobby() {
            return invoke(ReconnectAction.RETURN_TO_LOBBY, returnToLobbyResults);
        }

        @Override
        public boolean warpToGarden() {
            return invoke(ReconnectAction.WARP_GARDEN, warpToGardenResults);
        }

        @Override
        public boolean showTitleScreen() {
            actions.add(ReconnectAction.SHOW_TITLE_SCREEN);
            throwIfRequested(ReconnectAction.SHOW_TITLE_SCREEN);
            return showTitleScreenResult;
        }

        private boolean invoke(ReconnectAction action, Deque<Boolean> results) {
            actions.add(action);
            throwIfRequested(action);
            return results.isEmpty() || results.remove();
        }

        private void throwIfRequested(ReconnectAction action) {
            if (throwingAction == action) {
                throw new IllegalStateException("scripted " + action + " failure");
            }
        }
    }
}

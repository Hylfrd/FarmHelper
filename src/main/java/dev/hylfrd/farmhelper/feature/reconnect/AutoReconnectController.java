package dev.hylfrd.farmhelper.feature.reconnect;

import dev.hylfrd.farmhelper.runtime.gamestate.GameStateSnapshot;
import dev.hylfrd.farmhelper.runtime.gamestate.SemanticLocation;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ScreenSnapshot;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.BooleanSupplier;

/**
 * Minecraft-free reconnect policy derived from AutoReconnect lines 43-237 at pinned upstream
 * commit {@code eacb323fbde3eff94d4f2ee7baacb059d84b8e3a}.
 *
 * <p>The controller owns no Macro, Failsafe, Feature, Scheduler, or mouse lifecycle. Those
 * composition concerns are intentionally deferred. Every client mutation is expressed through a
 * narrow port, and routing waits for a game-state generation newer than the preceding action.</p>
 */
public final class AutoReconnectController {
    public static final String DISCONNECTED_SCREEN_TYPE =
            "net.minecraft.client.gui.screens.DisconnectedScreen";

    private final ReconnectPolicy policy;
    private final ReconnectConnectionPort connectionPort;
    private final ReconnectTravelPort travelPort;
    private final ReconnectScreenPort screenPort;

    private ReconnectState state = ReconnectState.STOPPED;
    private long dueAtMillis = -1L;
    private long deadlineAtMillis = -1L;
    private long gameStateGenerationFloor = -1L;
    private long lastNowMillis = -1L;
    private int connectionAttempts;
    private boolean awaitingInitialDisconnect;
    private Throwable lastPortFailure;

    public AutoReconnectController(
            ReconnectPolicy policy,
            ReconnectConnectionPort connectionPort,
            ReconnectTravelPort travelPort,
            ReconnectScreenPort screenPort
    ) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.connectionPort = Objects.requireNonNull(connectionPort, "connectionPort");
        this.travelPort = Objects.requireNonNull(travelPort, "travelPort");
        this.screenPort = Objects.requireNonNull(screenPort, "screenPort");
    }

    public synchronized ReconnectState state() {
        return state;
    }

    public synchronized boolean active() {
        return state != ReconnectState.STOPPED;
    }

    public synchronized OptionalLong dueAtMillis() {
        return active() ? OptionalLong.of(dueAtMillis) : OptionalLong.empty();
    }

    public synchronized OptionalLong deadlineAtMillis() {
        return active() ? OptionalLong.of(deadlineAtMillis) : OptionalLong.empty();
    }

    public synchronized int connectionAttempts() {
        return connectionAttempts;
    }

    /** The adapter failure that terminated the latest run, if one occurred. */
    public synchronized Optional<Throwable> lastPortFailure() {
        return Optional.ofNullable(lastPortFailure);
    }

    /**
     * Starts a bounded run. An active multiplayer connection is first asked to disconnect;
     * an absent connection proceeds directly to the configured initial delay.
     */
    public synchronized ReconnectDecision start(
            ReconnectSnapshot snapshot,
            long initialDelayMillis
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (initialDelayMillis < 0L) {
            throw new IllegalArgumentException("initialDelayMillis must be non-negative");
        }
        observeNow(snapshot.nowMillis());
        if (active()) {
            return running(ReconnectReason.ALREADY_RUNNING);
        }

        lastPortFailure = null;
        Observation<ConnectionSnapshot> connection = snapshot.connection();
        if (connection.isUnknown()) {
            return inactive(ReconnectOutcome.REJECTED, ReconnectReason.CONNECTION_UNKNOWN);
        }
        if (isSingleplayer(connection)) {
            return inactive(ReconnectOutcome.REJECTED,
                    ReconnectReason.SINGLEPLAYER_UNSUPPORTED);
        }

        state = ReconnectState.CONNECTING;
        dueAtMillis = saturatedAdd(snapshot.nowMillis(), initialDelayMillis);
        deadlineAtMillis = saturatedAdd(snapshot.nowMillis(), policy.timeoutMillis());
        gameStateGenerationFloor = snapshot.gameState().generation();
        connectionAttempts = 0;
        awaitingInitialDisconnect = connection.isPresent();

        if (!awaitingInitialDisconnect) {
            return running(ReconnectReason.STARTED);
        }

        PortInvocation invocation = invoke(connectionPort::disconnect);
        if (invocation.failed()) {
            return finish(ReconnectOutcome.FAILED, ReconnectReason.PORT_FAILURE,
                    ReconnectAction.DISCONNECT, ReconnectActionStatus.FAILED);
        }
        return running(
                ReconnectReason.STARTED,
                ReconnectAction.DISCONNECT,
                invocation.status());
    }

    /** Advances one client-thread observation without owning a scheduler or a clock. */
    public synchronized ReconnectDecision tick(ReconnectSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        observeNow(snapshot.nowMillis());
        if (!active()) {
            return inactive(ReconnectOutcome.IDLE, ReconnectReason.IDLE);
        }
        if (snapshot.nowMillis() >= deadlineAtMillis) {
            return finish(ReconnectOutcome.TIMED_OUT, ReconnectReason.DEADLINE_REACHED,
                    ReconnectAction.NONE, ReconnectActionStatus.NOT_REQUESTED);
        }
        return switch (state) {
            case STOPPED -> throw new IllegalStateException("active controller is stopped");
            case CONNECTING -> tickConnecting(snapshot);
            case LOBBY, GARDEN -> tickTravel(snapshot);
        };
    }

    /** Stops the active run without invoking a platform port; repeated stops are idempotent. */
    public synchronized ReconnectDecision stop() {
        if (!active()) {
            return inactive(ReconnectOutcome.IDLE, ReconnectReason.IDLE);
        }
        return finish(ReconnectOutcome.CANCELLED, ReconnectReason.STOP_REQUESTED,
                ReconnectAction.NONE, ReconnectActionStatus.NOT_REQUESTED);
    }

    /**
     * Cancels only from the exact modern disconnected screen. Automation stops even when showing
     * the title screen is rejected or throws, so a failed UI action cannot keep reconnecting.
     */
    public synchronized ReconnectDecision cancelFromDisconnectedScreen(
            ReconnectSnapshot snapshot
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        observeNow(snapshot.nowMillis());
        if (!active()) {
            return inactive(ReconnectOutcome.IDLE, ReconnectReason.IDLE);
        }
        if (!isDisconnectedScreen(snapshot.screen())) {
            return running(ReconnectReason.CANCEL_SCREEN_UNAVAILABLE);
        }

        int attempts = connectionAttempts;
        clearActiveState();
        PortInvocation invocation = invoke(screenPort::showTitleScreen);
        return new ReconnectDecision(
                ReconnectOutcome.CANCELLED,
                ReconnectReason.MANUAL_CANCEL,
                ReconnectState.STOPPED,
                ReconnectAction.SHOW_TITLE_SCREEN,
                invocation.status(),
                OptionalLong.empty(),
                OptionalLong.empty(),
                attempts);
    }

    private ReconnectDecision tickConnecting(ReconnectSnapshot snapshot) {
        Observation<ConnectionSnapshot> connection = snapshot.connection();
        if (connection.isUnknown()) {
            return running(ReconnectReason.CONNECTION_UNKNOWN);
        }
        if (isSingleplayer(connection)) {
            return finish(ReconnectOutcome.FAILED,
                    ReconnectReason.SINGLEPLAYER_UNSUPPORTED,
                    ReconnectAction.NONE,
                    ReconnectActionStatus.NOT_REQUESTED);
        }
        if (connection.isPresent()) {
            if (awaitingInitialDisconnect) {
                return running(ReconnectReason.INITIAL_DISCONNECT_PENDING);
            }
            state = ReconnectState.LOBBY;
            dueAtMillis = saturatedAdd(
                    snapshot.nowMillis(), policy.connectionSettleDelayMillis());
            gameStateGenerationFloor = snapshot.gameState().generation();
            return running(ReconnectReason.CONNECTION_ESTABLISHED);
        }

        awaitingInitialDisconnect = false;
        if (snapshot.nowMillis() < dueAtMillis) {
            return running(ReconnectReason.DELAY_PENDING);
        }

        connectionAttempts = increment(connectionAttempts);
        PortInvocation invocation = invoke(connectionPort::connect);
        if (invocation.failed()) {
            return finish(ReconnectOutcome.FAILED, ReconnectReason.PORT_FAILURE,
                    ReconnectAction.CONNECT, ReconnectActionStatus.FAILED);
        }
        if (invocation.accepted()) {
            state = ReconnectState.LOBBY;
            dueAtMillis = saturatedAdd(
                    snapshot.nowMillis(), policy.connectionSettleDelayMillis());
            gameStateGenerationFloor = snapshot.gameState().generation();
        } else {
            dueAtMillis = saturatedAdd(snapshot.nowMillis(), policy.retryDelayMillis());
        }
        return running(ReconnectReason.CONNECT_ATTEMPTED,
                ReconnectAction.CONNECT, invocation.status());
    }

    private ReconnectDecision tickTravel(ReconnectSnapshot snapshot) {
        if (snapshot.nowMillis() < dueAtMillis) {
            return running(ReconnectReason.DELAY_PENDING);
        }

        Observation<ConnectionSnapshot> connection = snapshot.connection();
        if (connection.isUnknown()) {
            return running(ReconnectReason.CONNECTION_UNKNOWN);
        }
        if (isSingleplayer(connection)) {
            return finish(ReconnectOutcome.FAILED,
                    ReconnectReason.SINGLEPLAYER_UNSUPPORTED,
                    ReconnectAction.NONE,
                    ReconnectActionStatus.NOT_REQUESTED);
        }
        if (connection.isAbsent()) {
            state = ReconnectState.CONNECTING;
            awaitingInitialDisconnect = false;
            dueAtMillis = saturatedAdd(snapshot.nowMillis(), policy.retryDelayMillis());
            gameStateGenerationFloor = snapshot.gameState().generation();
            return running(ReconnectReason.CONNECTION_LOST);
        }

        GameStateSnapshot gameState = snapshot.gameState();
        if (gameState.generation() <= gameStateGenerationFloor) {
            return running(ReconnectReason.GAME_STATE_NOT_FRESH);
        }

        return switch (route(gameState)) {
            case UNKNOWN -> running(ReconnectReason.GAME_STATE_UNKNOWN);
            case CONTRADICTORY -> running(ReconnectReason.GAME_STATE_CONTRADICTORY);
            case TRANSITIONING -> running(ReconnectReason.GAME_STATE_TRANSITIONING);
            case GARDEN -> finish(
                    ReconnectOutcome.SUCCEEDED,
                    ReconnectReason.GARDEN_REACHED,
                    ReconnectAction.NONE,
                    ReconnectActionStatus.NOT_REQUESTED);
            case LOBBY -> routeFromLobby(snapshot);
            case LIMBO -> invokeTravel(
                    snapshot,
                    ReconnectReason.LIMBO_OBSERVED,
                    ReconnectAction.RETURN_TO_LOBBY,
                    travelPort::returnToLobby,
                    ReconnectState.LOBBY,
                    policy.travelDelayMillis());
            case OUTSIDE -> invokeTravel(
                    snapshot,
                    ReconnectReason.OUTSIDE_GARDEN,
                    ReconnectAction.WARP_GARDEN,
                    travelPort::warpToGarden,
                    state,
                    policy.travelDelayMillis());
        };
    }

    private ReconnectDecision routeFromLobby(ReconnectSnapshot snapshot) {
        if (state == ReconnectState.GARDEN) {
            state = ReconnectState.LOBBY;
            dueAtMillis = saturatedAdd(
                    snapshot.nowMillis(), policy.lobbyReturnDelayMillis());
            gameStateGenerationFloor = snapshot.gameState().generation();
            return running(ReconnectReason.LOBBY_OBSERVED);
        }
        return invokeTravel(
                snapshot,
                ReconnectReason.LOBBY_OBSERVED,
                ReconnectAction.ENTER_SKYBLOCK,
                travelPort::enterSkyBlock,
                ReconnectState.GARDEN,
                policy.travelDelayMillis());
    }

    private ReconnectDecision invokeTravel(
            ReconnectSnapshot snapshot,
            ReconnectReason reason,
            ReconnectAction action,
            BooleanSupplier request,
            ReconnectState acceptedState,
            long acceptedDelayMillis
    ) {
        PortInvocation invocation = invoke(request);
        if (invocation.failed()) {
            return finish(ReconnectOutcome.FAILED, ReconnectReason.PORT_FAILURE,
                    action, ReconnectActionStatus.FAILED);
        }
        if (invocation.accepted()) {
            state = acceptedState;
            dueAtMillis = saturatedAdd(snapshot.nowMillis(), acceptedDelayMillis);
            gameStateGenerationFloor = snapshot.gameState().generation();
        } else {
            dueAtMillis = saturatedAdd(snapshot.nowMillis(), policy.retryDelayMillis());
        }
        return running(reason, action, invocation.status());
    }

    private ReconnectDecision running(ReconnectReason reason) {
        return running(reason, ReconnectAction.NONE, ReconnectActionStatus.NOT_REQUESTED);
    }

    private ReconnectDecision running(
            ReconnectReason reason,
            ReconnectAction action,
            ReconnectActionStatus actionStatus
    ) {
        return new ReconnectDecision(
                ReconnectOutcome.RUNNING,
                reason,
                state,
                action,
                actionStatus,
                OptionalLong.of(dueAtMillis),
                OptionalLong.of(deadlineAtMillis),
                connectionAttempts);
    }

    private ReconnectDecision inactive(
            ReconnectOutcome outcome,
            ReconnectReason reason
    ) {
        return new ReconnectDecision(
                outcome,
                reason,
                ReconnectState.STOPPED,
                ReconnectAction.NONE,
                ReconnectActionStatus.NOT_REQUESTED,
                OptionalLong.empty(),
                OptionalLong.empty(),
                0);
    }

    private ReconnectDecision finish(
            ReconnectOutcome outcome,
            ReconnectReason reason,
            ReconnectAction action,
            ReconnectActionStatus actionStatus
    ) {
        int attempts = connectionAttempts;
        clearActiveState();
        return new ReconnectDecision(
                outcome,
                reason,
                ReconnectState.STOPPED,
                action,
                actionStatus,
                OptionalLong.empty(),
                OptionalLong.empty(),
                attempts);
    }

    private void clearActiveState() {
        state = ReconnectState.STOPPED;
        dueAtMillis = -1L;
        deadlineAtMillis = -1L;
        gameStateGenerationFloor = -1L;
        connectionAttempts = 0;
        awaitingInitialDisconnect = false;
    }

    private PortInvocation invoke(BooleanSupplier request) {
        try {
            return request.getAsBoolean()
                    ? PortInvocation.acceptedInvocation()
                    : PortInvocation.rejectedInvocation();
        } catch (RuntimeException | Error failure) {
            lastPortFailure = failure;
            return PortInvocation.failedInvocation();
        }
    }

    private void observeNow(long nowMillis) {
        if (lastNowMillis >= 0L && nowMillis < lastNowMillis) {
            throw new IllegalArgumentException("nowMillis must not move backwards");
        }
        lastNowMillis = nowMillis;
    }

    private static boolean isSingleplayer(Observation<ConnectionSnapshot> connection) {
        return connection.isPresent()
                && connection.get().mode() == ConnectionSnapshot.Mode.SINGLEPLAYER;
    }

    private static boolean isDisconnectedScreen(Observation<ScreenSnapshot> screen) {
        return screen.isPresent()
                && screen.get().type().isPresent()
                && DISCONNECTED_SCREEN_TYPE.equals(screen.get().type().get());
    }

    private static Route route(GameStateSnapshot gameState) {
        Observation<Boolean> inGarden = gameState.inGarden();
        if (!inGarden.isPresent()) {
            return Route.UNKNOWN;
        }
        if (inGarden.get()) {
            return Route.GARDEN;
        }

        Observation<SemanticLocation> location = gameState.location();
        if (!location.isPresent()) {
            return Route.UNKNOWN;
        }
        return switch (location.get()) {
            case GARDEN -> Route.CONTRADICTORY;
            case TELEPORTING -> Route.TRANSITIONING;
            case LOBBY -> Route.LOBBY;
            case LIMBO -> Route.LIMBO;
            default -> Route.OUTSIDE;
        };
    }

    private static int increment(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    private static long saturatedAdd(long left, long right) {
        return right > Long.MAX_VALUE - left ? Long.MAX_VALUE : left + right;
    }

    private enum Route {
        UNKNOWN,
        CONTRADICTORY,
        TRANSITIONING,
        GARDEN,
        LOBBY,
        LIMBO,
        OUTSIDE
    }

    private record PortInvocation(ReconnectActionStatus status) {
        private boolean accepted() {
            return status == ReconnectActionStatus.ACCEPTED;
        }

        private boolean failed() {
            return status == ReconnectActionStatus.FAILED;
        }

        private static PortInvocation acceptedInvocation() {
            return new PortInvocation(ReconnectActionStatus.ACCEPTED);
        }

        private static PortInvocation rejectedInvocation() {
            return new PortInvocation(ReconnectActionStatus.REJECTED);
        }

        private static PortInvocation failedInvocation() {
            return new PortInvocation(ReconnectActionStatus.FAILED);
        }
    }
}

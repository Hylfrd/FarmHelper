package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.control.RotationTask;
import dev.hylfrd.farmhelper.control.input.ReleaseReason;
import dev.hylfrd.farmhelper.control.rotation.RotationCancelReason;
import dev.hylfrd.farmhelper.macro.FarmingContext;
import dev.hylfrd.farmhelper.macro.MacroControlOwner;
import dev.hylfrd.farmhelper.macro.MacroDecision;
import dev.hylfrd.farmhelper.macro.MacroRotationRequest;
import dev.hylfrd.farmhelper.macro.MacroRotationDisposition;
import dev.hylfrd.farmhelper.macro.MacroState;
import dev.hylfrd.farmhelper.macro.PlayerPosture;
import dev.hylfrd.farmhelper.platform.FarmHelper;
import dev.hylfrd.farmhelper.runtime.ClientTickPipeline;
import dev.hylfrd.farmhelper.runtime.gamestate.GameStateParseResult;
import dev.hylfrd.farmhelper.runtime.gamestate.GameTextInputBudget;
import dev.hylfrd.farmhelper.runtime.gamestate.RawGameTextSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.RotationSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.Optional;

/** One ordered client-thread adapter for every active P0 runtime consumer. */
public final class ClientTickAdapter implements ClientTickPipeline.Actions {
    private final Minecraft client;
    private final FarmHelperClientRuntime runtime;
    private final ClientSnapshotCapture snapshots;
    private final ClientGameTextSource gameText;
    private final ClientTickPipeline pipeline;
    private final ClientLifecycleSequencer clientLifecycle;
    private final ClientCommandScreenCloseGuard commandScreenClose;

    ClientTickAdapter(
            Minecraft client,
            FarmHelperClientRuntime runtime,
            ClientSnapshotCapture snapshots,
            ClientGameTextSource gameText,
            ClientTickPipeline pipeline,
            ClientCommandScreenCloseGuard commandScreenClose) {
        this.client = Objects.requireNonNull(client, "client");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.gameText = Objects.requireNonNull(gameText, "gameText");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        this.commandScreenClose = Objects.requireNonNull(commandScreenClose, "commandScreenClose");
        clientLifecycle = new ClientLifecycleSequencer(runtime, gameText, commandScreenClose::clear);
    }

    public static void register(
            FarmHelperClientRuntime runtime,
            ClientCommandScreenCloseGuard commandScreenClose
    ) {
        Minecraft client = Minecraft.getInstance();
        ClientTickAdapter adapter = new ClientTickAdapter(
                client, runtime, new ClientSnapshotCapture(),
                new MinecraftGameTextSnapshotSource(client), new ClientTickPipeline(),
                commandScreenClose);

        ClientTickEvents.END_CLIENT_TICK.register(ignored -> adapter.tick());
        ClientLevelEvents.AFTER_CLIENT_LEVEL_CHANGE.register(
                (ignored, level) -> adapter.dispatch(() ->
                        adapter.clientLifecycle.observeLevel(level, true)));
        ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, ignored) -> adapter.dispatch(adapter.clientLifecycle::disconnect));
        ClientReceiveMessageEvents.CHAT.register((message, signed, profile, parameters, time) ->
                adapter.dispatch(() -> adapter.acceptChat("chat", message)));
        ClientReceiveMessageEvents.GAME.register((message, overlay) ->
                adapter.dispatch(() -> adapter.acceptChat(overlay ? "overlay" : "game", message)));
        ClientLifecycleEvents.CLIENT_STOPPING.register(
                ignored -> adapter.dispatch(adapter.clientLifecycle::clientStopping));
    }

    private void dispatch(Runnable action) {
        if (client.isSameThread()) {
            runBoundary(action);
        } else {
            client.execute(() -> runBoundary(action));
        }
    }

    private void runBoundary(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | Error failure) {
            fail(runtime, commandScreenClose);
            FarmHelper.LOGGER.error("FarmHelper client event failed", failure);
        }
    }

    private void tick() {
        if (!client.isSameThread()) {
            dispatch(this::tick);
            return;
        }
        pipeline.tick(this).ifPresent(failure -> FarmHelper.LOGGER.error(
                "FarmHelper tick failed during {}", failure.stage(), failure.cause()));
    }

    private void acceptChat(String channel, Component message) {
        String bounded = message.getString(GameTextInputBudget.MAX_LINE_CHARACTERS + 1);
        gameText.acceptChat(channel, bounded);
    }

    @Override
    public void observeClientLifecycle() {
        clientLifecycle.observeLevel(client.level, false);
    }

    @Override
    public ClientSnapshot captureClientSnapshot() {
        return snapshots.capture(client, runtime.lifecycle().worldEpoch());
    }

    @Override
    public void observeSnapshotLifecycle(ClientSnapshot snapshot) {
        runtime.observeMacroScreen(snapshot.screen());
        clientLifecycle.observeConnection(snapshot.connection());
        commandScreenClose.observeScreen(
                snapshot.screen(), client.screen, client.screen instanceof ChatScreen,
                runtime.lifecycle(), runtime.ownershipGeneration());
    }

    @Override
    public RawGameTextSnapshot captureGameText(ClientSnapshot snapshot) {
        return gameText.snapshot(snapshot);
    }

    @Override
    public GameStateParseResult parseGameState(ClientSnapshot snapshot, RawGameTextSnapshot raw) {
        return runtime.core().parseGameState(snapshot, raw);
    }

    @Override
    public void advanceTaskQueue() {
        runtime.core().taskQueue().advance();
    }

    @Override
    public void deliverRuntimeTick(ClientSnapshot snapshot, GameStateParseResult gameState) {
        Observation<SpatialSnapshot> spatial = captureMacroSpatial(runtime, snapshot);
        boolean developmentGarden = developmentGarden();
        Observation<Boolean> inGarden = developmentGarden
                ? Observation.present(true)
                : gameState.snapshot().inGarden();
        MacroDecision decision = runtime.core().macroManager().tick(snapshot, new FarmingContext(
                runtime.core().nowNanos(),
                runtime.lifecycle().worldEpoch(),
                snapshot.player(),
                spatial,
                inGarden,
                developmentGarden,
                runtime.core().serverResponsiveness(snapshot.connection().isPresent()),
                capturePlayerPosture(client)));
        applyDecision(snapshot, decision);
    }

    static Observation<PlayerPosture> capturePlayerPosture(Minecraft client) {
        Objects.requireNonNull(client, "client");
        if (client.player == null || client.level == null) {
            return Observation.unknown();
        }
        try {
            boolean suffocating = client.level.getBlockCollisions(
                    client.player, client.player.getBoundingBox().contract(
                            0.15D, 0.15D, 0.15D)).iterator().hasNext();
            return Observation.present(new PlayerPosture(
                    client.player.getAbilities().flying, client.player.onGround(), suffocating));
        } catch (RuntimeException exception) {
            return Observation.unknown();
        }
    }

    static Observation<SpatialSnapshot> captureMacroSpatial(
            FarmHelperClientRuntime runtime,
            ClientSnapshot snapshot
    ) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.player().isPresent()) {
            return Observation.unknown();
        }
        Optional<SpatialCaptureRequest> request = runtime.core().macroManager()
                .spatialRequest(snapshot.player().get(), runtime.lifecycle().worldEpoch());
        return request.isPresent()
                ? runtime.spatialSnapshots().capture(request.orElseThrow())
                : Observation.unknown();
    }

    private void applyDecision(ClientSnapshot snapshot, MacroDecision decision) {
        applyManagedDecision(runtime, decision);
        decision.rotation().ifPresent(rotation -> startRotation(runtime, snapshot, rotation));
        decision.warp().ifPresent(request -> {
            if (client.getConnection() == null) {
                throw new IllegalStateException("Cannot warp without a client connection");
            }
            String command = warpCommand(request);
            client.getConnection().sendCommand(command);
        });
    }

    static void applyManagedDecision(
            FarmHelperClientRuntime runtime,
            MacroDecision decision
    ) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(decision, "decision");
        if (decision.inputs().isEmpty()) {
            runtime.input().release(MacroControlOwner.S_SHAPE);
        } else {
            runtime.input().replace(MacroControlOwner.S_SHAPE, decision.inputs());
        }
        applyMacroRotationDisposition(runtime, decision);
    }

    static void applyMacroRotationDisposition(
            FarmHelperClientRuntime runtime,
            MacroDecision decision
    ) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(decision, "decision");
        if (decision.rotationDisposition() == MacroRotationDisposition.RELEASE
                && runtime.rotation().snapshot().owner()
                .filter(MacroControlOwner.S_SHAPE::equals).isPresent()) {
            runtime.rotation().cancel(RotationCancelReason.OWNER_CANCELLED);
        }
    }

    static void startRotation(
            FarmHelperClientRuntime runtime,
            ClientSnapshot snapshot,
            MacroRotationRequest request
    ) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(request, "request");
        if (!snapshot.player().isPresent() || !snapshot.player().get().rotation().isPresent()) {
            return;
        }
        if (runtime.rotation().rotating()) {
            var active = runtime.rotation().snapshot();
            if (active.owner().filter(owner -> !MacroControlOwner.S_SHAPE.equals(owner)).isPresent()) {
                return;
            }
            float normalizedTargetYaw = RotationTask.normalizeYaw(request.yaw());
            if (active.targetYaw().filter(target -> Float.compare(target, normalizedTargetYaw) == 0)
                    .isPresent()
                    && active.targetPitch().filter(target -> Float.compare(target, request.pitch()) == 0)
                    .isPresent()
                    && runtime.rotation().task().filter(task ->
                            task.profile() == request.profile()
                                    && Float.compare(task.backModifier(), request.backModifier()) == 0)
                    .isPresent()) {
                return;
            }
        }
        RotationSnapshot current = snapshot.player().get().rotation().get();
        runtime.rotation().start(MacroControlOwner.S_SHAPE, current.yaw(), current.pitch(),
                request.yaw(), request.pitch(), request.durationMillis(), request.profile(),
                request.backModifier());
    }

    private boolean developmentGarden() {
        return developmentGarden(
                FabricLoader.getInstance().isDevelopmentEnvironment(),
                client.hasSingleplayerServer(),
                Boolean.getBoolean("farmhelper.developmentWorld"));
    }

    static boolean developmentGarden(
            boolean developmentEnvironment,
            boolean integratedServer,
            boolean enabledProperty
    ) {
        return developmentEnvironment && integratedServer && enabledProperty;
    }

    static String warpCommand(dev.hylfrd.farmhelper.macro.MacroWarpRequest request) {
        return request.developmentWorld()
                ? "tp " + request.spawn().position().x() + " " + request.spawn().position().y()
                + " " + request.spawn().position().z() + " " + request.spawn().yaw()
                + " " + request.spawn().pitch()
                : "warp garden";
    }

    @Override
    public void tickRotation() {
        runtime.rotation().tick(client);
    }

    @Override
    public void enforceInputSafety(ClientSnapshot snapshot) {
        enforceInputSafety(runtime, snapshot);
    }

    static void enforceInputSafety(
            FarmHelperClientRuntime runtime,
            ClientSnapshot snapshot
    ) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(snapshot, "snapshot");
        if (runtime.input().snapshot().emptyState()) {
            return;
        }
        if (runtime.core().macroManager().state() != MacroState.RUNNING
                && snapshot.screen().isAbsent()
                && snapshot.world().isPresent()
                && snapshot.player().isPresent()
                && snapshot.connection().isPresent()) {
            runtime.input().release(MacroControlOwner.S_SHAPE);
            return;
        }
        ReleaseReason reason = unsafeReason(runtime, snapshot);
        if (reason != null) {
            runtime.input().releaseAll(reason);
        }
    }

    @Override
    public void onFailure(ClientTickPipeline.Failure failure) {
        fail(runtime, commandScreenClose);
    }

    static void fail(
            FarmHelperClientRuntime runtime,
            ClientCommandScreenCloseGuard commandScreenClose
    ) {
        commandScreenClose.clear();
        runtime.failed();
    }

    private static ReleaseReason unsafeReason(
            FarmHelperClientRuntime runtime,
            ClientSnapshot snapshot
    ) {
        if (!snapshot.screen().isAbsent()) {
            return ReleaseReason.SCREEN;
        }
        if (!snapshot.world().isPresent() || !snapshot.player().isPresent()
                || !snapshot.connection().isPresent()) {
            return ReleaseReason.WORLD_CHANGE;
        }
        if (runtime.rotation().rotating()) {
            return ReleaseReason.ROTATION_CONFLICT;
        }
        return null;
    }
}

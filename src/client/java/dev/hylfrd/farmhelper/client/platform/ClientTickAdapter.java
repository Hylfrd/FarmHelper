package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.client.runtime.FarmHelperClientRuntime;
import dev.hylfrd.farmhelper.control.input.ReleaseReason;
import dev.hylfrd.farmhelper.macro.MacroContext;
import dev.hylfrd.farmhelper.macro.MacroState;
import dev.hylfrd.farmhelper.macro.PauseReason;
import dev.hylfrd.farmhelper.macro.WorldMode;
import dev.hylfrd.farmhelper.platform.FarmHelper;
import dev.hylfrd.farmhelper.runtime.ClientTickPipeline;
import dev.hylfrd.farmhelper.runtime.gamestate.GameStateParseResult;
import dev.hylfrd.farmhelper.runtime.gamestate.GameTextInputBudget;
import dev.hylfrd.farmhelper.runtime.gamestate.RawGameTextSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.PositionSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.RotationSnapshot;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLevelEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.network.chat.Component;

import java.util.Objects;

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
        runtime.core().macroManager().tick(snapshot, macroContext(snapshot));
    }

    @Override
    public void tickRotation() {
        runtime.rotation().tick(client);
    }

    @Override
    public void enforceInputSafety(ClientSnapshot snapshot) {
        ReleaseReason reason = unsafeReason(snapshot);
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

    static MacroContext macroContext(ClientSnapshot snapshot) {
        boolean worldReady = snapshot.world().isPresent();
        boolean playerReady = snapshot.player().isPresent();
        boolean connectionReady = snapshot.connection().isPresent();
        boolean screenOpenOrUnknown = !snapshot.screen().isAbsent();
        PauseReason pauseReason = !worldReady ? PauseReason.NO_WORLD
                : !playerReady ? PauseReason.NO_PLAYER
                : !connectionReady ? PauseReason.NO_CONNECTION
                : screenOpenOrUnknown ? PauseReason.SCREEN_OPEN
                : PauseReason.NONE;
        WorldMode worldMode = snapshot.connection().isPresent()
                ? switch (snapshot.connection().get().mode()) {
                    case SINGLEPLAYER -> WorldMode.SINGLEPLAYER;
                    case MULTIPLAYER -> WorldMode.MULTIPLAYER;
                }
                : WorldMode.NONE;
        return new MacroContext(playerReady && connectionReady, screenOpenOrUnknown, pauseReason, worldMode,
                legacyPlayer(snapshot.player().isPresent() ? snapshot.player().get() : null));
    }

    private static dev.hylfrd.farmhelper.macro.PlayerSnapshot legacyPlayer(PlayerSnapshot player) {
        if (player == null) {
            return dev.hylfrd.farmhelper.macro.PlayerSnapshot.absent();
        }
        if (!player.position().isPresent() || !player.rotation().isPresent()) {
            return dev.hylfrd.farmhelper.macro.PlayerSnapshot.unknown();
        }
        PositionSnapshot position = player.position().get();
        RotationSnapshot rotation = player.rotation().get();
        return new dev.hylfrd.farmhelper.macro.PlayerSnapshot(
                position.x(), position.y(), position.z(), rotation.yaw(), rotation.pitch());
    }

    private ReleaseReason unsafeReason(ClientSnapshot snapshot) {
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
        if (runtime.core().macroManager().state() != MacroState.RUNNING) {
            return ReleaseReason.PAUSE;
        }
        return null;
    }
}

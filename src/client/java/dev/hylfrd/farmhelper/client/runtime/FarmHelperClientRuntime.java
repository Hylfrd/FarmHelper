package dev.hylfrd.farmhelper.client.runtime;

import dev.hylfrd.farmhelper.client.control.ClientInputController;
import dev.hylfrd.farmhelper.client.control.ClientRotationController;
import dev.hylfrd.farmhelper.client.control.inventory.ClientInventoryHotbarPort;
import dev.hylfrd.farmhelper.client.control.inventory.MinecraftInventoryPort;
import dev.hylfrd.farmhelper.client.platform.spatial.ClientSpatialSnapshotCapture;
import dev.hylfrd.farmhelper.config.ConfigLoadResult;
import dev.hylfrd.farmhelper.config.ConfigLoadStatus;
import dev.hylfrd.farmhelper.config.FarmHelperConfig;
import dev.hylfrd.farmhelper.config.FarmHelperConfigKey;
import dev.hylfrd.farmhelper.config.FarmHelperConfigStore;
import dev.hylfrd.farmhelper.platform.FarmHelper;
import dev.hylfrd.farmhelper.control.input.ReleaseReason;
import dev.hylfrd.farmhelper.control.inventory.InventoryCancelReason;
import dev.hylfrd.farmhelper.control.inventory.InventoryClickGuard;
import dev.hylfrd.farmhelper.control.inventory.InventoryController;
import dev.hylfrd.farmhelper.control.inventory.InventoryDiagnostic;
import dev.hylfrd.farmhelper.control.inventory.InventoryExecutionResult;
import dev.hylfrd.farmhelper.control.inventory.InventoryOperationToken;
import dev.hylfrd.farmhelper.control.inventory.InventoryPort;
import dev.hylfrd.farmhelper.control.inventory.InventoryScreenSnapshot;
import dev.hylfrd.farmhelper.control.inventory.ScreenIdentity;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.rotation.RotationCancelReason;
import dev.hylfrd.farmhelper.runtime.FarmHelperRuntime;
import dev.hylfrd.farmhelper.runtime.lifecycle.ClientCancellationFanout;
import dev.hylfrd.farmhelper.runtime.lifecycle.ClientCancellationReason;
import dev.hylfrd.farmhelper.runtime.lifecycle.ClientOwnershipFence;
import dev.hylfrd.farmhelper.runtime.lifecycle.ClientRuntimeLifecycle;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshotCapturePort;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/** Composition root and sole owner of mutable services for one client session. */
public final class FarmHelperClientRuntime {
    private final FarmHelperConfigStore configStore;
    private final ConfigLoadResult configLoadResult;
    private final Minecraft attachedClient;
    private final ClientOwnershipFence ownershipFence;
    private final FarmHelperRuntime core;
    private final ClientInputController input;
    private final ClientRotationController rotation;
    private final InventoryController inventory;
    private final ClientCancellationFanout cancellationFanout;
    private final ClientRuntimeLifecycle lifecycle;
    private final SpatialSnapshotCapturePort spatialSnapshots;

    public FarmHelperClientRuntime() {
        this(FabricLoader.getInstance().getConfigDir().resolve(FarmHelper.MOD_ID + ".json"),
                Minecraft.getInstance(), null, null);
    }

    FarmHelperClientRuntime(Path configPath) {
        this(configPath, null, UnavailableInventoryPort.INSTANCE, defaultDiagnostics());
    }

    FarmHelperClientRuntime(
            Path configPath,
            InventoryPort inventoryPort,
            Consumer<InventoryDiagnostic> diagnostics) {
        this(configPath, null, inventoryPort, diagnostics);
    }

    private FarmHelperClientRuntime(
            Path configPath,
            Minecraft client,
            InventoryPort suppliedInventoryPort,
            Consumer<InventoryDiagnostic> suppliedDiagnostics) {
        attachedClient = client;
        configStore = new FarmHelperConfigStore(configPath);
        configLoadResult = configStore.load();
        ownershipFence = new ClientOwnershipFence();
        core = new FarmHelperRuntime(configLoadResult.config(), ownershipFence::requireAcquisitionAllowed);
        input = client == null
                ? ClientInputController.detached(ownershipFence::requireAcquisitionAllowed)
                : new ClientInputController(ownershipFence::requireAcquisitionAllowed);
        rotation = new ClientRotationController(ownershipFence::requireAcquisitionAllowed);
        InventoryPort inventoryPort = suppliedInventoryPort != null
                ? suppliedInventoryPort
                : new MinecraftInventoryPort(Objects.requireNonNull(client, "client"));
        Consumer<InventoryDiagnostic> diagnostics = suppliedDiagnostics != null
                ? suppliedDiagnostics
                : defaultDiagnostics();
        inventory = new InventoryController(
                core.taskQueue(), new ClientInventoryHotbarPort(input), inventoryPort,
                diagnostics, ownershipFence::requireAcquisitionAllowed);
        cancellationFanout = new ClientCancellationFanout(
                ignored -> core.macroManager().stop(),
                ignored -> core.invalidateGameState(),
                reason -> inventory.cancelActive(inventoryReason(reason)),
                reason -> rotation.cancel(rotationReason(reason)),
                reason -> input.releaseAll(inputReason(reason)),
                ignored -> core.taskQueue().cancelAll());
        lifecycle = new ClientRuntimeLifecycle(this::cancelOwnership);
        spatialSnapshots = client == null
                ? request -> Observation.unknown()
                : new ClientSpatialSnapshotCapture(client, lifecycle::worldEpoch);
        logConfigLoadResult(configLoadResult);
    }

    public FarmHelperRuntime core() {
        return core;
    }

    public ClientInputController input() {
        return input;
    }

    public ClientRotationController rotation() {
        return rotation;
    }

    public InventoryController inventory() {
        return inventory;
    }

    public SpatialSnapshotCapturePort spatialSnapshots() {
        return spatialSnapshots;
    }

    public ClientRuntimeLifecycle lifecycle() {
        return lifecycle;
    }

    public ConfigLoadResult configLoadResult() {
        return configLoadResult;
    }

    public boolean setTargetYaw(float value) {
        return updateConfig(config -> config.setTargetYaw(value));
    }

    public boolean setTargetPitch(float value) {
        return updateConfig(config -> config.setTargetPitch(value));
    }

    public float configValue(FarmHelperConfigKey key) {
        return key.read(core.config());
    }

    public boolean setConfig(FarmHelperConfigKey key, float value) {
        return updateConfig(config -> key.write(config, value));
    }

    public boolean resetConfig(FarmHelperConfigKey key) {
        return updateConfig(key::reset);
    }

    public boolean resetConfig() {
        return updateConfig(FarmHelperConfig::reset);
    }

    public FarmHelperConfig configSnapshot() {
        return core.config().copy();
    }

    public boolean saveConfig(FarmHelperConfig replacement) {
        FarmHelperConfig snapshot = replacement.copy();
        return updateConfig(config -> config.replaceWith(snapshot));
    }

    public void stop(Minecraft client) {
        requireClientThread(client);
        cancelOwnership(ClientCancellationReason.MANUAL_STOP);
    }

    /** User-visible toggle; disabling always crosses the global MANUAL_STOP boundary. */
    public boolean toggle() {
        requireAttachedClientThread();
        if (core.macroManager().enabled()) {
            cancelOwnership(ClientCancellationReason.MANUAL_STOP);
            return false;
        }
        core.macroManager().start();
        return true;
    }

    public boolean reset(Minecraft client) {
        stop(client);
        return resetConfig();
    }

    private boolean updateConfig(Consumer<FarmHelperConfig> update) {
        FarmHelperConfig before = core.config().copy();
        try {
            update.accept(core.config());
            configStore.save(core.config());
            return true;
        } catch (IOException | RuntimeException exception) {
            core.config().replaceWith(before);
            FarmHelper.LOGGER.error("Failed to update or save FarmHelper configuration: {}", exception.toString());
            return false;
        }
    }

    public void worldLoaded() {
        requireAttachedClientThread();
        ownershipFence.setAutomationReady(false);
        lifecycle.worldLoaded();
    }

    public void worldUnloaded() {
        requireAttachedClientThread();
        ownershipFence.setAutomationReady(false);
        lifecycle.worldUnloaded();
    }

    public void disconnected() {
        requireAttachedClientThread();
        ownershipFence.setAutomationReady(false);
        lifecycle.disconnected();
    }

    public void clientStopping() {
        requireAttachedClientThread();
        ownershipFence.setAutomationReady(false);
        lifecycle.clientStopping();
    }

    public void failed() {
        requireAttachedClientThread();
        ownershipFence.setAutomationReady(false);
        lifecycle.failed();
    }

    /** Updates the persistent acquisition gate before the ordered runtime stages execute. */
    public void observeConnection(Observation<?> connection) {
        requireAttachedClientThread();
        Objects.requireNonNull(connection, "connection");
        ownershipFence.setAutomationReady(connection.isPresent());
        lifecycle.observeConnection(connection);
    }

    private void cancelOwnership(ClientCancellationReason reason) {
        ClientOwnershipFence.Boundary boundary = ownershipFence.beginCancellation();
        if (!boundary.owner()) {
            return;
        }
        try {
            cancellationFanout.cancel(reason).ifPresent(failure ->
                    FarmHelper.LOGGER.error("FarmHelper cancellation {} completed with failures", reason, failure));
        } finally {
            ownershipFence.endCancellation(boundary);
        }
    }

    private static InventoryCancelReason inventoryReason(ClientCancellationReason reason) {
        return switch (reason) {
            case MANUAL_STOP -> InventoryCancelReason.REQUESTED;
            case SCREEN_CHANGED -> InventoryCancelReason.SCREEN_CHANGED;
            case WORLD_LOAD, WORLD_UNLOAD -> InventoryCancelReason.WORLD_CHANGED;
            case DISCONNECT, CONNECTION_UNAVAILABLE -> InventoryCancelReason.DISCONNECTED;
            case EXCEPTION -> InventoryCancelReason.EXCEPTION;
            case CLIENT_STOP -> InventoryCancelReason.CLIENT_SHUTDOWN;
        };
    }

    private static RotationCancelReason rotationReason(ClientCancellationReason reason) {
        return switch (reason) {
            case MANUAL_STOP -> RotationCancelReason.STOPPED;
            case SCREEN_CHANGED -> RotationCancelReason.SCREEN_CHANGED;
            case WORLD_LOAD, WORLD_UNLOAD -> RotationCancelReason.WORLD_CHANGED;
            case DISCONNECT, CONNECTION_UNAVAILABLE -> RotationCancelReason.DISCONNECTED;
            case EXCEPTION -> RotationCancelReason.EXCEPTION;
            case CLIENT_STOP -> RotationCancelReason.CLIENT_SHUTDOWN;
        };
    }

    private static ReleaseReason inputReason(ClientCancellationReason reason) {
        return switch (reason) {
            case MANUAL_STOP -> ReleaseReason.STOP;
            case SCREEN_CHANGED -> ReleaseReason.SCREEN;
            case WORLD_LOAD, WORLD_UNLOAD -> ReleaseReason.WORLD_CHANGE;
            case DISCONNECT, CONNECTION_UNAVAILABLE -> ReleaseReason.DISCONNECT;
            case EXCEPTION -> ReleaseReason.EXCEPTION;
            case CLIENT_STOP -> ReleaseReason.EXIT;
        };
    }

    private static void logConfigLoadResult(ConfigLoadResult result) {
        if (result.status() == ConfigLoadStatus.LOADED) {
            FarmHelper.LOGGER.info("Loaded FarmHelper configuration schema {}.", result.config().schemaVersion());
            return;
        }
        if (result.status() == ConfigLoadStatus.DEFAULTS_NOT_SAVED) {
            FarmHelper.LOGGER.error("FarmHelper is using unsaved default configuration: {}", result.diagnostic());
            return;
        }
        FarmHelper.LOGGER.warn("FarmHelper configuration status {}: {}", result.status(), result.diagnostic());
    }

    private static Consumer<InventoryDiagnostic> defaultDiagnostics() {
        return diagnostic -> FarmHelper.LOGGER.warn("Inventory cancelled: {}", diagnostic.reason());
    }

    private static void requireClientThread(Minecraft client) {
        Objects.requireNonNull(client, "client");
        if (!client.isSameThread()) {
            throw new IllegalStateException("Runtime mutation must run on the client thread");
        }
    }

    private void requireAttachedClientThread() {
        if (attachedClient != null) {
            requireClientThread(attachedClient);
        }
    }

    private enum UnavailableInventoryPort implements InventoryPort {
        INSTANCE;

        @Override
        public Observation<InventoryScreenSnapshot> observe(
                InventoryOperationToken token, ControlOwner owner) {
            return Observation.unknown();
        }

        @Override
        public InventoryExecutionResult executeGuardedClick(InventoryClickGuard guard) {
            return InventoryExecutionResult.rejected(InventoryCancelReason.ADAPTER_EXCEPTION);
        }

        @Override
        public void releaseOperation(InventoryOperationToken token, ControlOwner owner) {
        }

        @Override
        public Optional<InventoryCancelReason> closeScreen(ScreenIdentity expected) {
            return Optional.of(InventoryCancelReason.ADAPTER_EXCEPTION);
        }
    }
}

package dev.hylfrd.farmhelper.client.runtime;

import dev.hylfrd.farmhelper.config.FarmHelperConfigKey;
import dev.hylfrd.farmhelper.control.input.ControlOwner;
import dev.hylfrd.farmhelper.control.input.ReleaseReason;
import dev.hylfrd.farmhelper.control.inventory.InventoryCancelReason;
import dev.hylfrd.farmhelper.control.inventory.InventoryClick;
import dev.hylfrd.farmhelper.control.inventory.InventoryOperation;
import dev.hylfrd.farmhelper.control.inventory.InventoryPostcondition;
import dev.hylfrd.farmhelper.control.inventory.InventoryQuery;
import dev.hylfrd.farmhelper.control.inventory.InventoryStep;
import dev.hylfrd.farmhelper.control.inventory.ScreenExpectation;
import dev.hylfrd.farmhelper.control.inventory.ScreenIdentity;
import dev.hylfrd.farmhelper.control.rotation.RotationTerminalReason;
import dev.hylfrd.farmhelper.runtime.gamestate.RawGameTextSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.time.TaskHandle;
import dev.hylfrd.farmhelper.runtime.time.TaskOwner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmHelperClientRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void configCommandOperationsPersistAndResetOnlyTheRequestedKey() {
        Path configPath = temporaryDirectory.resolve("farmhelper.json");
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(configPath);

        assertTrue(runtime.setConfig(FarmHelperConfigKey.TARGET_YAW, 45.0F));
        assertTrue(runtime.setConfig(FarmHelperConfigKey.TARGET_PITCH, 30.0F));
        assertTrue(runtime.resetConfig(FarmHelperConfigKey.TARGET_YAW));

        FarmHelperClientRuntime reloaded = new FarmHelperClientRuntime(configPath);
        assertEquals(0.0F, reloaded.configValue(FarmHelperConfigKey.TARGET_YAW));
        assertEquals(30.0F, reloaded.configValue(FarmHelperConfigKey.TARGET_PITCH));
    }

    @Test
    void rejectedConfigUpdateRestoresThePreviousValue() {
        Path configPath = temporaryDirectory.resolve("farmhelper.json");
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(configPath);
        assertTrue(runtime.setConfig(FarmHelperConfigKey.TARGET_YAW, 60.0F));

        assertFalse(runtime.setConfig(FarmHelperConfigKey.TARGET_YAW, Float.NaN));

        assertEquals(60.0F, runtime.configValue(FarmHelperConfigKey.TARGET_YAW));
        FarmHelperClientRuntime reloaded = new FarmHelperClientRuntime(configPath);
        assertEquals(60.0F, reloaded.configValue(FarmHelperConfigKey.TARGET_YAW));
    }

    @Test
    void disconnectCompositionCancelsEveryOwnedServiceAndInvalidatesPublishedState() {
        FarmHelperClientRuntime runtime = new FarmHelperClientRuntime(
                temporaryDirectory.resolve("farmhelper.json"));
        ControlOwner owner = new ControlOwner("composition-test");
        TaskHandle task = runtime.core().taskQueue().schedule(
                new TaskOwner("composition-test"), 0L, () -> { });
        runtime.core().macroManager().start();
        runtime.core().parseGameState(ClientSnapshot.unknown(), RawGameTextSnapshot.unknown(1L));
        runtime.rotation().start(owner, 0F, 0F, 90F, 10F, 1_000L);

        List<InventoryCancelReason> inventoryReasons = new ArrayList<>();
        ScreenIdentity identity = new ScreenIdentity(1L, 1, "minecraft:generic_9x3");
        InventoryOperation operation = InventoryOperation.clicks(
                owner,
                ScreenExpectation.exact(identity, identity.type(), "Container", 1, List.of()),
                List.of(new InventoryStep(InventoryQuery.slot(0), new InventoryClick.QuickMove(),
                        InventoryPostcondition.TARGET_SLOT_CHANGED)),
                1_000L);
        runtime.inventory().start(operation,
                outcome -> inventoryReasons.add(outcome.reason().orElseThrow()));

        runtime.disconnected();

        assertFalse(runtime.core().macroManager().enabled());
        assertTrue(task.cancelled());
        assertEquals(0, runtime.core().taskQueue().pendingTaskCount());
        assertTrue(runtime.inventory().activeToken().isEmpty());
        assertEquals(List.of(InventoryCancelReason.DISCONNECTED), inventoryReasons);
        assertFalse(runtime.rotation().rotating());
        assertEquals(RotationTerminalReason.DISCONNECTED,
                runtime.rotation().snapshot().terminalReason().orElseThrow());
        assertEquals(ReleaseReason.DISCONNECT,
                runtime.input().snapshot().releaseReason().orElseThrow());
        assertTrue(runtime.core().currentGameState().isEmpty());
    }
}

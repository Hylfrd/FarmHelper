package dev.hylfrd.farmhelper.client.platform;

import dev.hylfrd.farmhelper.macro.MacroContext;
import dev.hylfrd.farmhelper.macro.MacroManager;
import dev.hylfrd.farmhelper.macro.MacroState;
import dev.hylfrd.farmhelper.macro.PauseReason;
import dev.hylfrd.farmhelper.runtime.snapshot.ClientSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.PlayerSnapshot;
import dev.hylfrd.farmhelper.runtime.snapshot.WorldSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClientTickAdapterTest {
    @Test
    void absentAndUnknownConnectionsPauseBeforeMacroDelivery() {
        assertConnectionUnavailable(Observation.absent());
        assertConnectionUnavailable(Observation.unknown());
    }

    private static void assertConnectionUnavailable(
            Observation<dev.hylfrd.farmhelper.runtime.snapshot.ConnectionSnapshot> connection) {
        ClientSnapshot snapshot = new ClientSnapshot(
                Observation.present(new PlayerSnapshot(
                        Observation.unknown(), Observation.unknown(), Observation.unknown(),
                        Observation.unknown(), Observation.unknown())),
                Observation.present(new WorldSnapshot(1L, Observation.unknown())),
                connection,
                Observation.absent());
        MacroContext context = ClientTickAdapter.macroContext(snapshot);
        MacroManager manager = new MacroManager();
        manager.start();

        manager.tick(snapshot, context);

        assertFalse(context.playerReady());
        assertEquals(PauseReason.NO_CONNECTION, context.pauseReason());
        assertEquals(MacroState.PAUSED, manager.state());
        assertEquals(0L, manager.runningTicks());
    }
}

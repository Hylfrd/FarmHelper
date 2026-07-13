package dev.hylfrd.farmhelper.ui.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.hylfrd.farmhelper.config.FarmHelperConfigKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FarmHelperCommandTreeTest {
    private final FakeCommandService service = new FakeCommandService();
    private final List<String> feedback = new ArrayList<>();
    private CommandDispatcher<String> dispatcher;

    @BeforeEach
    void registerTree() {
        dispatcher = new CommandDispatcher<>();
        var root = dispatcher.register(FarmHelperCommandTree.root(
                "farmhelper", service, (source, message) -> feedback.add(message)));
        dispatcher.register(FarmHelperCommandTree.alias(
                "fh", root, service, (source, message) -> feedback.add(message)));
    }

    @Test
    void completeTreeAndAliasDelegateToServices() throws CommandSyntaxException {
        assertEquals(1, execute("farmhelper"));
        assertEquals(1, execute("fh status"));
        assertEquals(1, execute("farmhelper toggle"));
        assertEquals(1, execute("farmhelper stop"));
        assertEquals(1, execute("farmhelper reset"));
        assertEquals(1, execute("farmhelper config get"));
        assertEquals(1, execute("fh config get targetYaw"));
        assertEquals(1, execute("farmhelper config set targetYaw 45"));
        assertEquals(1, execute("farmhelper config reset targetPitch"));
        assertEquals(1, execute("farmhelper config reset"));
        assertEquals(1, execute("farmhelper open"));
        assertEquals(1, execute("farmhelper config open"));
        assertEquals(1, execute("farmhelper rotation test -90 30 250"));
        assertEquals(1, execute("farmhelper input release"));
        assertEquals(1, execute("farmhelper diagnostics"));

        assertTrue(service.calls.contains("status"));
        assertTrue(service.calls.contains("toggle"));
        assertTrue(service.calls.contains("stop"));
        assertTrue(service.calls.contains("reset"));
        assertTrue(service.calls.contains("set:targetYaw:45.0"));
        assertTrue(service.calls.contains("reset:targetPitch"));
        assertTrue(service.calls.contains("reset-config"));
        assertEquals(2, service.calls.stream().filter("open"::equals).count());
        assertTrue(service.calls.contains("rotation:-90.0:30.0:250"));
        assertTrue(service.calls.contains("release-input"));
        assertTrue(service.calls.contains("diagnostics"));
    }

    @Test
    void invalidArgumentsNeverReachAServiceOrLeavePartialState() {
        int callsBefore = service.calls.size();

        assertThrows(CommandSyntaxException.class,
                () -> execute("farmhelper config set targetPitch 91"));
        assertThrows(CommandSyntaxException.class,
                () -> execute("farmhelper config set targetYaw 181"));
        assertThrows(CommandSyntaxException.class,
                () -> execute("farmhelper rotation test 0 0 49"));
        assertThrows(CommandSyntaxException.class,
                () -> execute("farmhelper config set targetYaw nope"));

        assertEquals(callsBefore, service.calls.size());
        assertEquals(0.0F, service.values.get(FarmHelperConfigKey.TARGET_YAW));
        assertEquals(0.0F, service.values.get(FarmHelperConfigKey.TARGET_PITCH));
    }

    @Test
    void completionIncludesStableConfigKeysAndSubcommands() {
        var getSuggestions = dispatcher.getCompletionSuggestions(
                dispatcher.parse("farmhelper config get target", "source")).join().getList();
        var rootSuggestions = dispatcher.getCompletionSuggestions(
                dispatcher.parse("fh diag", "source")).join().getList();

        assertEquals(List.of("targetPitch", "targetYaw"), getSuggestions.stream()
                .map(suggestion -> suggestion.getText())
                .sorted()
                .toList());
        assertEquals(List.of("diagnostics"), rootSuggestions.stream()
                .map(suggestion -> suggestion.getText())
                .toList());
    }

    @Test
    void serviceFailureReturnsZeroAndClearLocalFeedback() throws CommandSyntaxException {
        service.openSuccessful = false;

        assertEquals(0, execute("farmhelper config open"));
        assertEquals("unavailable", feedback.getLast());
    }

    @Test
    void removedRemoteAndUpdaterCommandsAreAbsent() {
        assertThrows(CommandSyntaxException.class, () -> execute("farmhelper remote"));
        assertThrows(CommandSyntaxException.class, () -> execute("farmhelper update"));
        assertThrows(CommandSyntaxException.class, () -> execute("farmhelper discord"));
        assertFalse(service.calls.stream().anyMatch(call -> call.contains("remote")));
    }

    private int execute(String command) throws CommandSyntaxException {
        return dispatcher.execute(command, "source");
    }

    private static final class FakeCommandService implements FarmHelperCommandService {
        private final List<String> calls = new ArrayList<>();
        private final EnumMap<FarmHelperConfigKey, Float> values = new EnumMap<>(FarmHelperConfigKey.class);
        private boolean openSuccessful = true;

        private FakeCommandService() {
            for (FarmHelperConfigKey key : FarmHelperConfigKey.values()) {
                values.put(key, 0.0F);
            }
        }

        @Override
        public List<String> status() {
            calls.add("status");
            return List.of("status");
        }

        @Override
        public CommandActionResult toggle() {
            return action("toggle");
        }

        @Override
        public CommandActionResult stop() {
            return action("stop");
        }

        @Override
        public CommandActionResult reset() {
            return action("reset");
        }

        @Override
        public float configValue(FarmHelperConfigKey key) {
            calls.add("get:" + key.commandName());
            return values.get(key);
        }

        @Override
        public CommandActionResult setConfig(FarmHelperConfigKey key, float value) {
            calls.add("set:" + key.commandName() + ":" + value);
            values.put(key, value);
            return CommandActionResult.success("set");
        }

        @Override
        public CommandActionResult resetConfig(FarmHelperConfigKey key) {
            calls.add("reset:" + key.commandName());
            values.put(key, 0.0F);
            return CommandActionResult.success("reset key");
        }

        @Override
        public CommandActionResult resetConfig() {
            calls.add("reset-config");
            values.replaceAll((key, value) -> 0.0F);
            return CommandActionResult.success("reset config");
        }

        @Override
        public CommandActionResult openConfig() {
            calls.add("open");
            return openSuccessful
                    ? CommandActionResult.success("open")
                    : CommandActionResult.failure("unavailable");
        }

        @Override
        public CommandActionResult testRotation(float yaw, float pitch, int durationMs) {
            return action("rotation:" + yaw + ":" + pitch + ":" + durationMs);
        }

        @Override
        public CommandActionResult releaseInput() {
            return action("release-input");
        }

        @Override
        public List<String> diagnostics() {
            calls.add("diagnostics");
            return List.of("diagnostics");
        }

        private CommandActionResult action(String name) {
            calls.add(name);
            return CommandActionResult.success(name);
        }
    }
}

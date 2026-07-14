package dev.hylfrd.farmhelper.runtime.gamestate;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixturePrivacyTest {
    private static final Pattern UUID = Pattern.compile(
            "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?:\\.(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}\\b");
    private static final Pattern TOKEN = Pattern.compile(
            "(?i)(?:token|authorization|webhook|secret|api[_-]?key)\\s*[:=]");

    @Test
    void fixturesAreAnonymousUtf8AndContainNoSensitiveMaterial()
            throws IOException, URISyntaxException {
        Path root = Path.of(FixturePrivacyTest.class.getResource("/dev/hylfrd/farmhelper/runtime/gamestate/s2t5")
                .toURI());
        List<Path> fixtures;
        try (var paths = Files.walk(root)) {
            fixtures = paths.filter(Files::isRegularFile).toList();
        }
        assertFalse(fixtures.isEmpty());
        for (Path fixture : fixtures) {
            String text = Files.readString(fixture, StandardCharsets.UTF_8);
            String lower = text.toLowerCase(Locale.ROOT);
            assertFalse(UUID.matcher(text).find(), fixture.toString());
            assertFalse(IPV4.matcher(text).find(), fixture.toString());
            assertFalse(TOKEN.matcher(text).find(), fixture.toString());
            assertFalse(lower.contains("c:\\users\\"), fixture.toString());
            assertFalse(lower.contains("/home/"), fixture.toString());
            assertFalse(lower.contains(" is visiting your garden"), fixture.toString());
            assertTrue(text.contains("ൠ"), fixture.toString());
        }
    }
}

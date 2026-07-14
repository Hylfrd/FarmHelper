package dev.hylfrd.farmhelper.runtime.time;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RandomDelaySourceTest {
    @Test
    void producesOnlyValuesInsideTheInclusiveInjectedRange() {
        RandomDelaySource source = new RandomDelaySource(new Random(8560L), 10L, 20L);

        for (int iteration = 0; iteration < 1_000; iteration++) {
            long delay = source.nextDelayNanos();
            assertTrue(delay >= 10L && delay <= 20L);
        }
        assertEquals(10L, source.minimumNanos());
        assertEquals(20L, source.maximumNanos());
    }

    @Test
    void supportsFixedAndFullNonNegativeRanges() {
        assertEquals(7L, new RandomDelaySource(new Random(1L), 7L, 7L).nextDelayNanos());

        RandomDelaySource fullRange = new RandomDelaySource(new Random(2L), 0L, Long.MAX_VALUE);
        for (int iteration = 0; iteration < 100; iteration++) {
            assertTrue(fullRange.nextDelayNanos() >= 0L);
        }
    }

    @Test
    void validatesGeneratorAndBounds() {
        assertThrows(NullPointerException.class, () -> new RandomDelaySource(null, 0L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new RandomDelaySource(new Random(), -1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new RandomDelaySource(new Random(), 2L, 1L));
    }
}

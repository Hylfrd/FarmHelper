package dev.hylfrd.farmhelper.runtime.snapshot;

import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservationTest {
    @Test
    void preservesAllThreeStatesAcrossMapping() {
        Observation<Integer> present = Observation.present(0);
        Observation<Integer> absent = Observation.absent();
        Observation<Integer> unknown = Observation.unknown();

        assertEquals(Observation.State.PRESENT, present.state());
        assertEquals(0, present.get());
        assertTrue(present.isPresent());
        assertFalse(present.isAbsent());
        assertFalse(present.isUnknown());

        assertEquals(Observation.State.ABSENT, absent.state());
        assertTrue(absent.isAbsent());
        assertThrows(NoSuchElementException.class, absent::get);

        assertEquals(Observation.State.UNKNOWN, unknown.state());
        assertTrue(unknown.isUnknown());
        assertThrows(NoSuchElementException.class, unknown::get);
        assertNotEquals(absent, unknown);

        assertEquals(Observation.present("0"), present.map(String::valueOf));
        assertEquals(Observation.absent(), absent.map(String::valueOf));
        assertEquals(Observation.unknown(), unknown.map(String::valueOf));
    }

    @Test
    void refusesNullPresentValues() {
        assertThrows(NullPointerException.class, () -> Observation.present(null));
    }

    @Test
    void exposesNoStateCollapsingOptionalProjection() {
        assertFalse(java.util.Arrays.stream(Observation.class.getMethods())
                .anyMatch(method -> method.getReturnType() == Optional.class));
    }
}

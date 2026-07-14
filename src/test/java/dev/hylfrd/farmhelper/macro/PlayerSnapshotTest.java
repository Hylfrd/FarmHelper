package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlayerSnapshotTest {
    @Test
    void missingAndUnknownSnapshotsCannotExposePlaceholderValues() {
        PlayerSnapshot absent = PlayerSnapshot.absent();
        PlayerSnapshot unknown = PlayerSnapshot.unknown();

        assertEquals(Observation.State.ABSENT, absent.state());
        assertEquals(Observation.State.UNKNOWN, unknown.state());
        assertNotEquals(absent, unknown);

        assertAllValuesUnavailable(absent);
        assertAllValuesUnavailable(unknown);
        assertEquals("Player rotation: absent", absent.rotationDiagnostic());
        assertEquals("Player rotation: unknown", unknown.rotationDiagnostic());
    }

    @Test
    void presentRealZeroValuesRemainLegalAndReadable() {
        PlayerSnapshot zero = new PlayerSnapshot(0.0D, 0.0D, 0.0D, 0.0F, 0.0F);

        assertEquals(Observation.State.PRESENT, zero.state());
        assertEquals(0.0D, zero.x());
        assertEquals(0.0D, zero.y());
        assertEquals(0.0D, zero.z());
        assertEquals(0.0F, zero.yaw());
        assertEquals(0.0F, zero.pitch());
        assertEquals("Player yaw: 0.0, pitch: 0.0", zero.rotationDiagnostic());
    }

    private static void assertAllValuesUnavailable(PlayerSnapshot snapshot) {
        assertThrows(IllegalStateException.class, snapshot::requirePresent);
        assertThrows(IllegalStateException.class, snapshot::x);
        assertThrows(IllegalStateException.class, snapshot::y);
        assertThrows(IllegalStateException.class, snapshot::z);
        assertThrows(IllegalStateException.class, snapshot::yaw);
        assertThrows(IllegalStateException.class, snapshot::pitch);
    }
}

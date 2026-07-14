package dev.hylfrd.farmhelper.macro;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void nonPresentSnapshotsRedactAllValuesFromToString() {
        PlayerSnapshot absent = PlayerSnapshot.absent();
        PlayerSnapshot unknown = PlayerSnapshot.unknown();
        PlayerSnapshot populatedAbsent = new PlayerSnapshot(
                Observation.State.ABSENT,
                12.5D,
                -34.75D,
                98.125D,
                44.5F,
                -12.25F
        );

        assertEquals("PlayerSnapshot[state=ABSENT]", absent.toString());
        assertEquals("PlayerSnapshot[state=UNKNOWN]", unknown.toString());
        assertNotEquals(absent.toString(), unknown.toString());
        assertEquals("PlayerSnapshot[state=ABSENT]", populatedAbsent.toString());

        assertRedacted(absent.toString());
        assertRedacted(unknown.toString());
        assertRedacted(populatedAbsent.toString());
        assertFalse(populatedAbsent.toString().contains("12.5"));
        assertFalse(populatedAbsent.toString().contains("-34.75"));
        assertFalse(populatedAbsent.toString().contains("98.125"));
        assertFalse(populatedAbsent.toString().contains("44.5"));
        assertFalse(populatedAbsent.toString().contains("-12.25"));
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
        assertEquals(
                "PlayerSnapshot[state=PRESENT, x=0.0, y=0.0, z=0.0, yaw=0.0, pitch=0.0]",
                zero.toString()
        );
    }

    private static void assertRedacted(String diagnostic) {
        assertFalse(diagnostic.contains("x="));
        assertFalse(diagnostic.contains("y="));
        assertFalse(diagnostic.contains("z="));
        assertFalse(diagnostic.contains("yaw="));
        assertFalse(diagnostic.contains("pitch="));
        assertFalse(diagnostic.contains("0.0"));
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

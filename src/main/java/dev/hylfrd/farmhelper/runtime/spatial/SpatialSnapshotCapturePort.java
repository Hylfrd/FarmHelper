package dev.hylfrd.farmhelper.runtime.spatial;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;

/** Client boundary for producing a bounded immutable snapshot; T8 will decide when to invoke it. */
@FunctionalInterface
public interface SpatialSnapshotCapturePort {
    Observation<SpatialSnapshot> capture(SpatialCaptureRequest request);
}

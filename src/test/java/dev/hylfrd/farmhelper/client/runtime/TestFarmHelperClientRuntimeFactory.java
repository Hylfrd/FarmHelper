package dev.hylfrd.farmhelper.client.runtime;

import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshotCapturePort;

import java.nio.file.Path;

/** Test-only cross-package access to the detached client runtime constructor. */
public final class TestFarmHelperClientRuntimeFactory {
    private TestFarmHelperClientRuntimeFactory() {
    }

    public static FarmHelperClientRuntime create(Path configPath) {
        return new FarmHelperClientRuntime(configPath);
    }

    public static FarmHelperClientRuntime create(
            Path configPath,
            SpatialSnapshotCapturePort spatialSnapshots
    ) {
        return new FarmHelperClientRuntime(configPath, spatialSnapshots);
    }
}

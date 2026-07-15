package dev.hylfrd.farmhelper.client.runtime;

import java.nio.file.Path;

/** Test-only cross-package access to the detached client runtime constructor. */
public final class TestFarmHelperClientRuntimeFactory {
    private TestFarmHelperClientRuntimeFactory() {
    }

    public static FarmHelperClientRuntime create(Path configPath) {
        return new FarmHelperClientRuntime(configPath);
    }
}

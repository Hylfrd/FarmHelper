package dev.hylfrd.farmhelper.client.control;

/** Test-only access to the same controller tick used by the production client adapter. */
public final class TestClientRotationControllerAccess {
    private TestClientRotationControllerAccess() {
    }

    public static void tick(ClientRotationController controller) {
        controller.tick(new ClientRotationController.RotationView() {
            @Override public boolean playerPresent() { return true; }
            @Override public boolean screenOpen() { return false; }
            @Override public float yaw() { return 0.0F; }
            @Override public float pitch() { return 0.0F; }
            @Override public void apply(float yaw, float pitch) { }
        });
    }
}

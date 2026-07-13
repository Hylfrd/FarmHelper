package dev.hylfrd.farmhelper.macro;

public record PlayerSnapshot(
        double x,
        double y,
        double z,
        float yaw,
        float pitch
) {
    public static PlayerSnapshot empty() {
        return new PlayerSnapshot(0.0D, 0.0D, 0.0D, 0.0F, 0.0F);
    }
}

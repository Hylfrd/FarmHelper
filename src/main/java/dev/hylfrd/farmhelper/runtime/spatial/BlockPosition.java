package dev.hylfrd.farmhelper.runtime.spatial;

/** Minecraft-free integer block coordinates. */
public record BlockPosition(int x, int y, int z) {
    public ChunkPosition chunk() {
        return new ChunkPosition(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
    }

    public BlockPosition offset(int dx, int dy, int dz) {
        return new BlockPosition(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
    }

    public BoxSnapshot unitBox() {
        return new BoxSnapshot(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D);
    }
}

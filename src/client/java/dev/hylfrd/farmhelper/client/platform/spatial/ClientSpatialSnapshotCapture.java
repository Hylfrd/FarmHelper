package dev.hylfrd.farmhelper.client.platform.spatial;

import dev.hylfrd.farmhelper.runtime.snapshot.Observation;
import dev.hylfrd.farmhelper.runtime.snapshot.ResourceIdentifier;
import dev.hylfrd.farmhelper.runtime.spatial.BlockPosition;
import dev.hylfrd.farmhelper.runtime.spatial.BlockStateSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.BoxSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.ChunkPosition;
import dev.hylfrd.farmhelper.runtime.spatial.ChunkSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.CollisionShapeSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialCaptureRequest;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshot;
import dev.hylfrd.farmhelper.runtime.spatial.SpatialSnapshotCapturePort;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * On-demand client-thread capture adapter. It observes only requested blocks in already-loaded FULL
 * chunks and never asks Minecraft to create or load a chunk.
 */
public final class ClientSpatialSnapshotCapture implements SpatialSnapshotCapturePort {
    private final Minecraft client;
    private final LongSupplier currentWorldEpoch;

    public ClientSpatialSnapshotCapture(Minecraft client, LongSupplier currentWorldEpoch) {
        this.client = Objects.requireNonNull(client, "client");
        this.currentWorldEpoch = Objects.requireNonNull(currentWorldEpoch, "currentWorldEpoch");
    }

    @Override
    public Observation<SpatialSnapshot> capture(SpatialCaptureRequest request) {
        Objects.requireNonNull(request, "request");
        if (!client.isSameThread() || client.level == null || client.player == null
                || request.worldEpoch() != currentWorldEpoch.getAsLong()) {
            return Observation.unknown();
        }
        try {
            ClientLevel level = client.level;
            CollisionContext collisionContext = CollisionContext.of(client.player);
            Map<ChunkPosition, List<BlockPosition>> requestedByChunk = groupByChunk(request.blocks());
            Map<ChunkPosition, ChunkSnapshot> chunks = new HashMap<>();
            for (Map.Entry<ChunkPosition, List<BlockPosition>> entry : requestedByChunk.entrySet()) {
                chunks.put(entry.getKey(), captureChunk(level, collisionContext, entry.getKey(), entry.getValue()));
            }
            return Observation.present(capturedSnapshot(
                    request, level.getMinY(), level.getMaxY(),
                    box(client.player.getBoundingBox()), chunks));
        } catch (RuntimeException exception) {
            return Observation.unknown();
        }
    }

    static SpatialSnapshot capturedSnapshot(
            SpatialCaptureRequest request,
            int minY,
            int maxY,
            BoxSnapshot playerBox,
            Map<ChunkPosition, ChunkSnapshot> chunks
    ) {
        Objects.requireNonNull(request, "request");
        return new SpatialSnapshot(
                request.worldEpoch(), request.requestToken(), request.bounds(),
                minY, maxY, playerBox, chunks);
    }

    private static ChunkSnapshot captureChunk(
            ClientLevel level,
            CollisionContext collisionContext,
            ChunkPosition position,
            List<BlockPosition> requested
    ) {
        Map<BlockPosition, Observation<BlockStateSnapshot>> blocks = new LinkedHashMap<>();
        if (!level.hasChunk(position.x(), position.z())) {
            requested.forEach(block -> blocks.put(block, Observation.unknown()));
            return new ChunkSnapshot(position, false, blocks);
        }

        ChunkAccess chunkAccess = level.getChunk(position.x(), position.z(), ChunkStatus.FULL, false);
        if (!(chunkAccess instanceof LevelChunk chunk)) {
            requested.forEach(block -> blocks.put(block, Observation.unknown()));
            return new ChunkSnapshot(position, false, blocks);
        }
        for (BlockPosition positionToCapture : requested) {
            if (positionToCapture.y() < level.getMinY() || positionToCapture.y() >= level.getMaxY()) {
                blocks.put(positionToCapture, Observation.unknown());
                continue;
            }
            blocks.put(positionToCapture, captureBlock(level, chunk, collisionContext, positionToCapture));
        }
        return new ChunkSnapshot(position, true, blocks);
    }

    private static Observation<BlockStateSnapshot> captureBlock(
            ClientLevel level,
            LevelChunk chunk,
            CollisionContext collisionContext,
            BlockPosition position
    ) {
        try {
            BlockPos minecraftPosition = new BlockPos(position.x(), position.y(), position.z());
            BlockState state = chunk.getBlockState(minecraftPosition);
            Identifier blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            Identifier fluidId = BuiltInRegistries.FLUID.getKey(state.getFluidState().getType());
            if (blockId == null || fluidId == null) {
                return Observation.unknown();
            }
            Observation<CollisionShapeSnapshot> collision = captureCollision(
                    state.getCollisionShape(level, minecraftPosition, collisionContext));
            return Observation.present(new BlockStateSnapshot(
                    identifier(blockId),
                    properties(state),
                    identifier(fluidId),
                    collision));
        } catch (RuntimeException exception) {
            return Observation.unknown();
        }
    }

    private static Observation<CollisionShapeSnapshot> captureCollision(VoxelShape shape) {
        if (shape == null) {
            return Observation.unknown();
        }
        try {
            List<BoxSnapshot> boxes = new ArrayList<>();
            for (AABB box : shape.toAabbs()) {
                boxes.add(box(box));
            }
            return Observation.present(new CollisionShapeSnapshot(boxes));
        } catch (RuntimeException exception) {
            return Observation.unknown();
        }
    }

    private static Map<String, String> properties(BlockState state) {
        Map<String, String> properties = new LinkedHashMap<>();
        state.getValues().forEach(value -> properties.put(value.property().getName(), value.valueName()));
        return properties;
    }

    private static Map<ChunkPosition, List<BlockPosition>> groupByChunk(Iterable<BlockPosition> blocks) {
        Map<ChunkPosition, List<BlockPosition>> grouped = new LinkedHashMap<>();
        for (BlockPosition block : blocks) {
            grouped.computeIfAbsent(block.chunk(), ignored -> new ArrayList<>()).add(block);
        }
        return grouped;
    }

    private static ResourceIdentifier identifier(Identifier identifier) {
        return new ResourceIdentifier(identifier.getNamespace(), identifier.getPath());
    }

    private static BoxSnapshot box(AABB box) {
        return new BoxSnapshot(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ);
    }
}

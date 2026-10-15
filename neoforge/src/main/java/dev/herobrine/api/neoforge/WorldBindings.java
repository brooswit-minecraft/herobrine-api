package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.function.Function;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Blocks, chunks and block entities exactly as the client currently holds them. Anything in an unloaded
 * chunk is reported as not loaded (-1 in scans), never guessed. Scans are capped by the maxScanVolume config.
 */
final class WorldBindings {
    private WorldBindings() {}

    static void register(ApiContext ctx) {
        read(ctx, "world.block", "State, properties, light, fluid and biome at one position. Params: x, y, z", q -> {
            ClientLevel level = Game.level();
            BlockPos pos = new BlockPos(q.integer("x"), q.integer("y"), q.integer("z"));
            if (level.isOutsideBuildHeight(pos)) throw ApiException.badRequest("Position is outside the build height");
            if (!level.hasChunkAt(pos)) return Unavailable.of("world.block", Reason.NOT_LOADED);
            BlockState s = level.getBlockState(pos);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("position", List.of(pos.getX(), pos.getY(), pos.getZ()));
            m.put("block", BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString());
            m.put("state", stateString(s));
            m.put("isAir", s.isAir());
            m.put("hardness", s.getDestroySpeed(level, pos));
            m.put("hasBlockEntity", s.hasBlockEntity());
            m.put("fluid", s.getFluidState().isEmpty() ? null : BuiltInRegistries.FLUID.getKey(s.getFluidState().getType()).toString());
            m.put("blockLight", level.getBrightness(LightLayer.BLOCK, pos));
            m.put("skyLight", level.getBrightness(LightLayer.SKY, pos));
            m.put("biome", level.getBiome(pos).unwrapKey().map(k -> k.location().toString()).orElse(null));
            return m;
        });

        read(ctx, "world.blocks",
                "Every block state in a box as a palette plus an index array (-1 = chunk not loaded). Index order: y, then z, then x (x fastest) "
                        + "from the minimum corner. Params: x1, y1, z1, x2, y2, z2",
                q -> {
                    ClientLevel level = Game.level();
                    int[] c = corners(q);
                    Validation.boxVolume(c[0], c[1], c[2], c[3], c[4], c[5], ClientConfig.MAX_SCAN_VOLUME.get());
                    int minX = Math.min(c[0], c[3]), minY = Math.min(c[1], c[4]), minZ = Math.min(c[2], c[5]);
                    int sx = Math.abs(c[0] - c[3]) + 1, sy = Math.abs(c[1] - c[4]) + 1, sz = Math.abs(c[2] - c[5]) + 1;
                    Map<String, Integer> palette = new LinkedHashMap<>();
                    int[] data = new int[sx * sy * sz];
                    BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
                    int i = 0;
                    for (int y = minY; y < minY + sy; y++)
                        for (int z = minZ; z < minZ + sz; z++)
                            for (int x = minX; x < minX + sx; x++) {
                                pos.set(x, y, z);
                                if (level.isOutsideBuildHeight(pos) || !level.hasChunkAt(pos)) { data[i++] = -1; continue; }
                                String state = stateString(level.getBlockState(pos));
                                Integer idx = palette.get(state);
                                if (idx == null) { idx = palette.size(); palette.put(state, idx); }
                                data[i++] = idx;
                            }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("origin", List.of(minX, minY, minZ));
                    m.put("size", List.of(sx, sy, sz));
                    m.put("palette", new ArrayList<>(palette.keySet()));
                    m.put("data", data);
                    return m;
                });

        read(ctx, "world.chunks", "Coordinates of the chunks the client has loaded around you. Params: radius (chunks, default 8, max 32)", q -> {
            ClientLevel level = Game.level();
            int r = Game.inRange(q.integer("radius", 8), 1, 32, "radius");
            ChunkPos center = Game.player().chunkPosition();
            List<int[]> loaded = new ArrayList<>();
            for (int dx = -r; dx <= r; dx++)
                for (int dz = -r; dz <= r; dz++)
                    if (level.hasChunk(center.x + dx, center.z + dz)) loaded.add(new int[]{center.x + dx, center.z + dz});
            return Map.of("center", List.of(center.x, center.z), "radius", r, "count", loaded.size(), "loaded", loaded);
        });

        read(ctx, "world.block_entities",
                "Block entities in a box with the data the server synced to the client (SNBT). Params: x1, y1, z1, x2, y2, z2, offset, limit",
                q -> {
                    ClientLevel level = Game.level();
                    int[] c = corners(q);
                    Validation.boxVolume(c[0], c[1], c[2], c[3], c[4], c[5], ClientConfig.MAX_SCAN_VOLUME.get());
                    int minX = Math.min(c[0], c[3]), maxX = Math.max(c[0], c[3]);
                    int minY = Math.min(c[1], c[4]), maxY = Math.max(c[1], c[4]);
                    int minZ = Math.min(c[2], c[5]), maxZ = Math.max(c[2], c[5]);
                    List<Map<String, Object>> out = new ArrayList<>();
                    for (int cx = minX >> 4; cx <= maxX >> 4; cx++)
                        for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
                            LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                            if (chunk == null) continue;
                            for (Map.Entry<BlockPos, BlockEntity> e : chunk.getBlockEntities().entrySet()) {
                                BlockPos p = e.getKey();
                                if (p.getX() < minX || p.getX() > maxX || p.getY() < minY || p.getY() > maxY || p.getZ() < minZ || p.getZ() > maxZ) continue;
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("position", List.of(p.getX(), p.getY(), p.getZ()));
                                m.put("type", BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(e.getValue().getType()).toString());
                                m.put("nbt", e.getValue().getUpdateTag(level.registryAccess()).toString());
                                out.add(m);
                            }
                        }
                    return Page.of(out, q, 200, 1000);
                });
    }

    /** "minecraft:oak_stairs[facing=north,half=bottom]" - the standard block state notation. */
    static String stateString(BlockState s) {
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
        if (s.getValues().isEmpty()) return id;
        List<Map.Entry<Property<?>, Comparable<?>>> props = new ArrayList<>(s.getValues().entrySet());
        props.sort(java.util.Comparator.comparing(e -> e.getKey().getName()));
        StringJoiner j = new StringJoiner(",", "[", "]");
        for (Map.Entry<Property<?>, Comparable<?>> e : props) j.add(e.getKey().getName() + "=" + valueName(e.getKey(), e.getValue()));
        return id + j;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String valueName(Property<?> p, Comparable<?> v) { return ((Property) p).getName((Comparable) v); }

    private static int[] corners(Params q) {
        return new int[]{q.integer("x1"), q.integer("y1"), q.integer("z1"), q.integer("x2"), q.integer("y2"), q.integer("z2")};
    }

    private static void read(ApiContext ctx, String id, String desc, Function<Params, Object> body) {
        ctx.resources.register(id, new ResourceDef(id, desc, Availability.SERVER_SYNCED, body));
    }
}

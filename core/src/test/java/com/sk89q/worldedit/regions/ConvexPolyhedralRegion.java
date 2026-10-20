package com.sk89q.worldedit.regions;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Test double mirroring the real ConvexPolyhedralRegion's verified shape: single {@code World}-param ctor
 * (world may be null), repeated {@code addVertex}, and {@code isDefined()} only becoming true once enough
 * vertices form a real hull (the real class needs a 4th non-coplanar vertex before any triangle exists;
 * this fake simplifies that to "at least 4 vertices", sufficient to prove the adapter's own validity check
 * rather than re-implementing real 3D convex-hull geometry).
 */
public final class ConvexPolyhedralRegion implements Region {
    private final List<BlockVector3> vertices = new ArrayList<>();

    public ConvexPolyhedralRegion(World world) {}

    public boolean addVertex(BlockVector3 vertex) { return vertices.add(vertex); }
    public boolean isDefined() { return vertices.size() >= 4; }
    public Collection<BlockVector3> getVertices() { return vertices; }

    @Override public BlockVector3 getMinimumPoint() {
        int minX = vertices.stream().mapToInt(BlockVector3::getX).min().orElse(0);
        int minY = vertices.stream().mapToInt(BlockVector3::getY).min().orElse(0);
        int minZ = vertices.stream().mapToInt(BlockVector3::getZ).min().orElse(0);
        return BlockVector3.at(minX, minY, minZ);
    }

    @Override public BlockVector3 getMaximumPoint() {
        int maxX = vertices.stream().mapToInt(BlockVector3::getX).max().orElse(0);
        int maxY = vertices.stream().mapToInt(BlockVector3::getY).max().orElse(0);
        int maxZ = vertices.stream().mapToInt(BlockVector3::getZ).max().orElse(0);
        return BlockVector3.at(maxX, maxY, maxZ);
    }

    @Override public String toString() { return "ConvexPolyhedralRegion(" + vertices + ")"; }
}

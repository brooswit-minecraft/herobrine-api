package com.sk89q.worldedit.regions;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Test double mirroring the real ConvexPolyhedralRegion's verified shape: single {@code World}-param ctor
 * (world may be null), repeated {@code addVertex}, and {@code getTriangles()} - which the adapter checks
 * instead of {@code isDefined()} (verified: the real {@code isDefined()} is just {@code !triangles.isEmpty()},
 * true already at exactly 3 (degenerate, coplanar) vertices - see {@code ReflectiveWorldEdit.convexHullRegion}'s
 * javadoc for the full citation). This fake simplifies the real triangle count (exactly 2 for a 3-vertex
 * degenerate hull, 4 once a real, non-coplanar 4th vertex exists - verified in the real {@code addVertex}'s
 * {@code case 3}/{@code default} branches) to "fewer than 4 vertices total means still degenerate," which is
 * sufficient to prove the adapter reads the triangle count rather than vertex count, without re-implementing
 * real 3D convex-hull/coplanarity geometry.
 */
public final class ConvexPolyhedralRegion implements Region {
    private final List<BlockVector3> vertices = new ArrayList<>();

    public ConvexPolyhedralRegion(World world) {}

    public boolean addVertex(BlockVector3 vertex) { return vertices.add(vertex); }
    public Collection<BlockVector3> getVertices() { return vertices; }

    /** @return a collection sized 0 (0-2 vertices), 2 (exactly 3, degenerate) or 4 (4+, a real hull). */
    public Collection<Object> getTriangles() {
        int n = vertices.size() >= 4 ? 4 : (vertices.size() == 3 ? 2 : 0);
        List<Object> fakeTriangles = new ArrayList<>();
        for (int i = 0; i < n; i++) fakeTriangles.add(new Object());
        return fakeTriangles;
    }

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

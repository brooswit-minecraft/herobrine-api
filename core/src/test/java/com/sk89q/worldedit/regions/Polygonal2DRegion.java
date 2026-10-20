package com.sk89q.worldedit.regions;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test double mirroring the real Polygonal2DRegion's verified 4-arg constructor (World may be null). Its
 * normalization matches the real {@code recalculate()}'s min/max sort, applied once, atomically, from the
 * constructor's own two Y arguments - not via separate setter calls afterward, which the real class's own
 * {@code recalculate()} would silently miscompute from whatever the fields currently hold (see
 * {@code ReflectiveWorldEdit.polygonRegion}'s javadoc for the full citation of why this adapter never calls
 * {@code setMinimumY}/{@code setMaximumY}).
 */
public final class Polygonal2DRegion implements Region {
    private final List<BlockVector2> points;
    private final int minY, maxY;

    public Polygonal2DRegion(World world, List<BlockVector2> points, int minY, int maxY) {
        this.points = new ArrayList<>(points);
        this.minY = Math.min(minY, maxY);
        this.maxY = Math.max(minY, maxY);
    }

    public List<BlockVector2> getPoints() { return Collections.unmodifiableList(points); }
    public int getMinimumY() { return minY; }
    public int getMaximumY() { return maxY; }

    @Override public BlockVector3 getMinimumPoint() {
        int minX = points.stream().mapToInt(BlockVector2::getX).min().orElse(0);
        int minZ = points.stream().mapToInt(BlockVector2::getZ).min().orElse(0);
        return BlockVector3.at(minX, minY, minZ);
    }

    @Override public BlockVector3 getMaximumPoint() {
        int maxX = points.stream().mapToInt(BlockVector2::getX).max().orElse(0);
        int maxZ = points.stream().mapToInt(BlockVector2::getZ).max().orElse(0);
        return BlockVector3.at(maxX, maxY, maxZ);
    }

    @Override public String toString() { return "Polygonal2DRegion(" + points + ",y=" + minY + ".." + maxY + ")"; }
}

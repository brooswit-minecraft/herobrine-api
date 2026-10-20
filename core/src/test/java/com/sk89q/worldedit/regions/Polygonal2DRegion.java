package com.sk89q.worldedit.regions;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Test double mirroring the real Polygonal2DRegion's verified world-independent construction path:
 *  no-arg ctor, addPoint(BlockVector2), setMinimumY/setMaximumY. */
public final class Polygonal2DRegion implements Region {
    private final List<BlockVector2> points = new ArrayList<>();
    private int minY, maxY;

    public void addPoint(BlockVector2 point) { points.add(point); }
    public void setMinimumY(int y) { this.minY = y; }
    public void setMaximumY(int y) { this.maxY = y; }
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

package com.sk89q.worldedit.regions;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector2;
import com.sk89q.worldedit.math.Vector3;

/** Test double mirroring the real CylinderRegion's verified 4-arg ctor and center/radius/Y-extent accessors. */
public final class CylinderRegion implements Region {
    private final BlockVector3 center;
    private final Vector2 radius;
    private final int minY, maxY;

    public CylinderRegion(BlockVector3 center, Vector2 radius, int minY, int maxY) {
        this.center = center;
        this.radius = radius;
        this.minY = minY;
        this.maxY = maxY;
    }

    public Vector3 getCenter() { return Vector3.at(center.getX(), (minY + maxY) / 2.0, center.getZ()); }
    public Vector2 getRadius() { return radius; }
    public int getMinimumY() { return minY; }
    public int getMaximumY() { return maxY; }

    @Override public BlockVector3 getMinimumPoint() {
        return BlockVector3.at((int) Math.floor(center.getX() - radius.getX()), minY, (int) Math.floor(center.getZ() - radius.getZ()));
    }

    @Override public BlockVector3 getMaximumPoint() {
        return BlockVector3.at((int) Math.ceil(center.getX() + radius.getX()), maxY, (int) Math.ceil(center.getZ() + radius.getZ()));
    }

    @Override public String toString() { return "CylinderRegion(center=" + center + ",radius=" + radius + ",y=" + minY + ".." + maxY + ")"; }
}

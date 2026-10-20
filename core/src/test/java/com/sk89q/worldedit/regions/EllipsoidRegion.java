package com.sk89q.worldedit.regions;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.Vector3;

/** Test double mirroring the real EllipsoidRegion's verified world-independent 2-arg constructor
 *  (center, radius - not two corners, confirmed by reading the real ctor body). */
public final class EllipsoidRegion implements Region {
    private final BlockVector3 center;
    private final Vector3 radius;

    public EllipsoidRegion(BlockVector3 center, Vector3 radius) {
        this.center = center;
        this.radius = radius;
    }

    public Vector3 getCenter() { return Vector3.at(center.getX(), center.getY(), center.getZ()); }
    public Vector3 getRadius() { return radius; }

    @Override public BlockVector3 getMinimumPoint() {
        return BlockVector3.at((int) Math.floor(center.getX() - radius.getX()),
                (int) Math.floor(center.getY() - radius.getY()), (int) Math.floor(center.getZ() - radius.getZ()));
    }

    @Override public BlockVector3 getMaximumPoint() {
        return BlockVector3.at((int) Math.ceil(center.getX() + radius.getX()),
                (int) Math.ceil(center.getY() + radius.getY()), (int) Math.ceil(center.getZ() + radius.getZ()));
    }

    @Override public String toString() { return "EllipsoidRegion(center=" + center + ",radius=" + radius + ")"; }
}

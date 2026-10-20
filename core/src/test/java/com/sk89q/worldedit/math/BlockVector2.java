package com.sk89q.worldedit.math;

/** Test double for WorldEdit's immutable 2D (X/Z) int vector, used by Polygonal2DRegion's points. */
public final class BlockVector2 {
    public final int x, z;
    private BlockVector2(int x, int z) { this.x = x; this.z = z; }

    public static BlockVector2 at(int x, int z) { return new BlockVector2(x, z); }

    public int getX() { return x; }
    public int getZ() { return z; }

    @Override public String toString() { return "BV2(" + x + "," + z + ")"; }
}

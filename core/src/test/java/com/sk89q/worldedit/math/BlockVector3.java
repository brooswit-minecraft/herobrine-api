package com.sk89q.worldedit.math;

/** Test double for WorldEdit's immutable block-position vector; real type has both an int and a double factory. */
public final class BlockVector3 {
    public final int x, y, z;
    private BlockVector3(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }

    public static BlockVector3 at(int x, int y, int z) { return new BlockVector3(x, y, z); }
    public static BlockVector3 at(double x, double y, double z) {
        throw new AssertionError("the double overload must never be chosen for integer callers");
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }

    @Override public String toString() { return "BV3(" + x + "," + y + "," + z + ")"; }
}

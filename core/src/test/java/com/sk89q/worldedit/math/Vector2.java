package com.sk89q.worldedit.math;

/** Test double for WorldEdit's immutable 2D (X/Z) double vector, used by CylinderRegion's radius. */
public final class Vector2 {
    public final double x, z;
    private Vector2(double x, double z) { this.x = x; this.z = z; }

    public static Vector2 at(double x, double z) { return new Vector2(x, z); }

    public double getX() { return x; }
    public double getZ() { return z; }

    @Override public String toString() { return "V2(" + x + "," + z + ")"; }
}

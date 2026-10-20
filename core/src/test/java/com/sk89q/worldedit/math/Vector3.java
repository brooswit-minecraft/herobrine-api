package com.sk89q.worldedit.math;

/** Test double for WorldEdit's immutable 3D double vector, used by CylinderRegion.getCenter(). */
public final class Vector3 {
    public final double x, y, z;
    private Vector3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }

    public static Vector3 at(double x, double y, double z) { return new Vector3(x, y, z); }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }

    @Override public String toString() { return "V3(" + x + "," + y + "," + z + ")"; }
}

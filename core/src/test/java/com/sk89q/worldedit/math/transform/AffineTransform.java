package com.sk89q.worldedit.math.transform;

import java.util.ArrayList;
import java.util.List;

/**
 * Test double. Records the sequence of rotate/scale calls applied (rather than computing an actual
 * matrix) so a test can assert exactly what the adapter composed, in order - enough of the real
 * AffineTransform's shape (no-arg constructor, rotateX/Y/Z(double), scale(double,double,double),
 * combine(Transform)) for ReflectiveWorldEdit's reflective calls to resolve.
 */
public final class AffineTransform implements Transform {
    private final List<String> ops = new ArrayList<>();

    public AffineTransform rotateX(double theta) { ops.add("rotateX(" + theta + ")"); return this; }
    public AffineTransform rotateY(double theta) { ops.add("rotateY(" + theta + ")"); return this; }
    public AffineTransform rotateZ(double theta) { ops.add("rotateZ(" + theta + ")"); return this; }
    public AffineTransform scale(double sx, double sy, double sz) { ops.add("scale(" + sx + "," + sy + "," + sz + ")"); return this; }

    /** Mirrors real combine's shape: the receiver's ops happened first, {@code other}'s after. */
    @Override public Transform combine(Transform other) {
        AffineTransform combined = new AffineTransform();
        combined.ops.addAll(this.ops);
        if (other instanceof AffineTransform a) combined.ops.addAll(a.ops);
        return combined;
    }

    @Override public String toString() { return "AffineTransform" + ops; }
}

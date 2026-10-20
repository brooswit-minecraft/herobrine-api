package com.sk89q.worldedit.math.transform;

/** Test double: the default clipboard transform, mirroring the real Identity - combining with another
 *  transform yields that other transform unchanged. */
public final class Identity implements Transform {
    @Override public Transform combine(Transform other) { return other; }
    @Override public String toString() { return "Identity"; }
}

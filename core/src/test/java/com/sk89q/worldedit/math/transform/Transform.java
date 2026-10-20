package com.sk89q.worldedit.math.transform;

/** Test double: mirrors the real Transform interface's one abstract method. */
public interface Transform {
    Transform combine(Transform other);
}

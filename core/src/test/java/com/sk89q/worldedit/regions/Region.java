package com.sk89q.worldedit.regions;
import com.sk89q.worldedit.math.BlockVector3;

/** Test double: the accessors the adapter reads; getVolume is a default method in the real interface too. */
public interface Region {
    BlockVector3 getMinimumPoint();
    BlockVector3 getMaximumPoint();

    default long getVolume() {
        BlockVector3 min = getMinimumPoint(), max = getMaximumPoint();
        return (long) (max.x - min.x + 1) * (max.y - min.y + 1) * (max.z - min.z + 1);
    }
}

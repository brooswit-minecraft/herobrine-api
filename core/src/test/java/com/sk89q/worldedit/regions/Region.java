package com.sk89q.worldedit.regions;
import com.sk89q.worldedit.math.BlockVector3;

/** Test double: only the two accessors the adapter reads. */
public interface Region {
    BlockVector3 getMinimumPoint();
    BlockVector3 getMaximumPoint();
}

package com.sk89q.worldedit.extension.platform;

import com.sk89q.worldedit.extent.Extent;

/** Test double: the one Locatable accessor the adapter reads (the actor's world, as an Extent). */
public interface Locatable {
    Extent getExtent();
}

package com.sk89q.worldedit.function.operation;

import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import dev.herobrine.api.core.worldedit.FakeWorldEdit;

public final class ForwardExtentCopy implements Operation {
    public final Extent source, destination;
    public final Region region;
    public final BlockVector3 to;

    public ForwardExtentCopy(Extent source, Region region, Extent destination, BlockVector3 to) {
        this.source = source;
        this.region = region;
        this.destination = destination;
        this.to = to;
        FakeWorldEdit.INSTANCE.calls.add("ForwardExtentCopy:" + region + "->" + to);
    }
}

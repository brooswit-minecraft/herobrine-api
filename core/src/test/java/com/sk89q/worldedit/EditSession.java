package com.sk89q.worldedit;

import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.regions.Region;
import dev.herobrine.api.core.worldedit.FakeWorldEdit;

public final class EditSession implements Extent, AutoCloseable {
    public final Actor actor;
    public boolean closed;

    public EditSession(Actor actor) { this.actor = actor; }

    public int setBlocks(Region region, Pattern pattern) throws MaxChangedBlocksException {
        return FakeWorldEdit.INSTANCE.setBlocks(this, region, pattern);
    }

    public int replaceBlocks(Region region, Mask mask, Pattern pattern) throws MaxChangedBlocksException {
        return FakeWorldEdit.INSTANCE.replaceBlocks(this, region, mask, pattern);
    }

    public void close() {
        closed = true;
        FakeWorldEdit.INSTANCE.calls.add("close");
    }

    @Override public String toString() { return "EditSession@" + Integer.toHexString(System.identityHashCode(this)); }
}

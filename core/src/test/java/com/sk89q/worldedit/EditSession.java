package com.sk89q.worldedit;

import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.regions.Region;
import dev.herobrine.api.core.worldedit.FakeWorldEdit;

public final class EditSession implements Extent, AutoCloseable {
    public final Actor actor;
    /** -1 = unlimited, carried over from the owning {@code LocalSession.createEditSession(Actor)} call. */
    public final int maxBlocks;
    public boolean closed;

    public EditSession(Actor actor) { this(actor, -1); }
    public EditSession(Actor actor, int maxBlocks) { this.actor = actor; this.maxBlocks = maxBlocks; }

    public int setBlocks(Region region, Pattern pattern) throws MaxChangedBlocksException {
        int changed = FakeWorldEdit.INSTANCE.setBlocks(this, region, pattern);
        enforceLimit(changed);
        return changed;
    }

    public int replaceBlocks(Region region, Mask mask, Pattern pattern) throws MaxChangedBlocksException {
        int changed = FakeWorldEdit.INSTANCE.replaceBlocks(this, region, mask, pattern);
        enforceLimit(changed);
        return changed;
    }

    private void enforceLimit(int changed) throws MaxChangedBlocksException {
        if (maxBlocks >= 0 && changed > maxBlocks) throw new MaxChangedBlocksException(maxBlocks);
    }

    public void close() {
        closed = true;
        FakeWorldEdit.INSTANCE.calls.add("close");
    }

    @Override public String toString() { return "EditSession@" + Integer.toHexString(System.identityHashCode(this)); }
}

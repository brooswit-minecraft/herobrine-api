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
    /**
     * Number of changes actually recorded so far (mirrors the real {@code changeSet.size()}, which backs
     * both {@code LocalSession.remember}'s no-op check and undo/redo). Per-write limiting, not an atomic
     * preflight: verified against the real {@code BlockChangeLimiter.setBlock} (checks {@code count >=
     * limit} and only then delegates to the recording extent), so the first {@code maxBlocks} writes of an
     * over-limit edit are already recorded by the time the exception is thrown.
     */
    public int size;
    public boolean closed;
    /** The block bag an undo/redo replayed this edit with (the real undo builds a fresh session with it). */
    public com.sk89q.worldedit.extent.inventory.BlockBag blockBag;

    public EditSession(Actor actor) { this(actor, -1); }
    public EditSession(Actor actor, int maxBlocks) { this.actor = actor; this.maxBlocks = maxBlocks; }

    public int setBlocks(Region region, Pattern pattern) throws MaxChangedBlocksException {
        return FakeWorldEdit.INSTANCE.setBlocks(this, region, pattern);
    }

    public int replaceBlocks(Region region, Mask mask, Pattern pattern) throws MaxChangedBlocksException {
        return FakeWorldEdit.INSTANCE.replaceBlocks(this, region, mask, pattern);
    }

    public int makeSphere(com.sk89q.worldedit.math.BlockVector3 pos, Pattern block, double radiusX, double radiusY, double radiusZ, boolean filled) throws MaxChangedBlocksException {
        return FakeWorldEdit.INSTANCE.makeSphere(this, pos, block, radiusX, radiusY, radiusZ, filled);
    }

    public int makeCylinder(com.sk89q.worldedit.math.BlockVector3 pos, Pattern block, double radiusX, double radiusZ, int height, boolean filled) throws MaxChangedBlocksException {
        return FakeWorldEdit.INSTANCE.makeCylinder(this, pos, block, radiusX, radiusZ, height, filled);
    }

    public int makePyramid(com.sk89q.worldedit.math.BlockVector3 position, Pattern block, int size, boolean filled) throws MaxChangedBlocksException {
        return FakeWorldEdit.INSTANCE.makePyramid(this, position, block, size, filled);
    }

    /** Applies (and records) writes one at a time, like the real {@code BlockChangeLimiter}. */
    public int applyWithLimit(int attempted) throws MaxChangedBlocksException {
        if (maxBlocks < 0 || attempted <= maxBlocks) {
            size += attempted;
            return attempted;
        }
        size += maxBlocks; // the first maxBlocks writes already succeeded and were recorded
        throw new MaxChangedBlocksException(maxBlocks);
    }

    public void close() {
        closed = true;
        FakeWorldEdit.INSTANCE.calls.add("close");
    }

    @Override public String toString() { return "EditSession@" + Integer.toHexString(System.identityHashCode(this)); }
}

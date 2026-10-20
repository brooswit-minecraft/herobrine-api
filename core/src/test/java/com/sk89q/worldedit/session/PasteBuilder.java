package com.sk89q.worldedit.session;

import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.math.BlockVector3;

public final class PasteBuilder {
    private final Clipboard clipboard;
    private final Extent targetExtent;
    private BlockVector3 to;
    private boolean ignoreAirBlocks;
    private boolean ignoreStructureVoidBlocks;
    private boolean copyEntities = true; // matches the real builder's default (session/PasteBuilder.java:47-51)
    private boolean copyBiomes;

    public PasteBuilder(Clipboard clipboard, Extent targetExtent) { this.clipboard = clipboard; this.targetExtent = targetExtent; }

    public PasteBuilder to(BlockVector3 to) { this.to = to; return this; }
    public PasteBuilder ignoreAirBlocks(boolean v) { this.ignoreAirBlocks = v; return this; }
    public PasteBuilder ignoreStructureVoidBlocks(boolean v) { this.ignoreStructureVoidBlocks = v; return this; }
    public PasteBuilder copyEntities(boolean v) { this.copyEntities = v; return this; }
    public PasteBuilder copyBiomes(boolean v) { this.copyBiomes = v; return this; }

    public Operation build() {
        return new PasteOperation(clipboard, targetExtent, to, ignoreAirBlocks, ignoreStructureVoidBlocks, copyEntities, copyBiomes);
    }

    /** Carries the destination extent and flags through so the fake can simulate the paste actually writing
     *  blocks and tests can assert exactly which options were passed. */
    public record PasteOperation(Clipboard clipboard, Extent targetExtent, BlockVector3 to,
                                  boolean ignoreAirBlocks, boolean ignoreStructureVoidBlocks,
                                  boolean copyEntities, boolean copyBiomes) implements Operation {
        @Override public String toString() {
            return "Paste(" + clipboard + "->" + to + ",ignoreAir=" + ignoreAirBlocks
                    + ",ignoreVoid=" + ignoreStructureVoidBlocks + ",entities=" + copyEntities + ",biomes=" + copyBiomes + ")";
        }
    }
}

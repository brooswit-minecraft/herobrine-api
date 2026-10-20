package com.sk89q.worldedit.session;

import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.math.BlockVector3;

public final class PasteBuilder {
    private final Clipboard clipboard;
    private final Extent targetExtent;
    private BlockVector3 to;

    public PasteBuilder(Clipboard clipboard, Extent targetExtent) { this.clipboard = clipboard; this.targetExtent = targetExtent; }

    public PasteBuilder to(BlockVector3 to) { this.to = to; return this; }

    public Operation build() {
        return new Operation() {
            @Override public String toString() { return "Paste(" + clipboard + "->" + to + ")"; }
        };
    }
}

package com.sk89q.worldedit;
public class MaxChangedBlocksException extends WorldEditException {
    public final int blockLimit;
    public MaxChangedBlocksException(int blockLimit) { super("max changed blocks: " + blockLimit); this.blockLimit = blockLimit; }
}

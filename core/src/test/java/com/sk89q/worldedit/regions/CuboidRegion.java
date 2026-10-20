package com.sk89q.worldedit.regions;
import com.sk89q.worldedit.math.BlockVector3;

public final class CuboidRegion implements Region {
    private final BlockVector3 pos1, pos2;
    public CuboidRegion(BlockVector3 pos1, BlockVector3 pos2) { this.pos1 = pos1; this.pos2 = pos2; }

    @Override public BlockVector3 getMinimumPoint() {
        return BlockVector3.at(Math.min(pos1.x, pos2.x), Math.min(pos1.y, pos2.y), Math.min(pos1.z, pos2.z));
    }
    @Override public BlockVector3 getMaximumPoint() {
        return BlockVector3.at(Math.max(pos1.x, pos2.x), Math.max(pos1.y, pos2.y), Math.max(pos1.z, pos2.z));
    }
    @Override public String toString() { return "CuboidRegion(" + pos1 + "," + pos2 + ")"; }
}

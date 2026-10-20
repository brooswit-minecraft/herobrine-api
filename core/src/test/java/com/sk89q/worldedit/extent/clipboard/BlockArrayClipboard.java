package com.sk89q.worldedit.extent.clipboard;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;

public final class BlockArrayClipboard implements Clipboard {
    public final Region region;
    public BlockVector3 origin;
    public BlockArrayClipboard(Region region) { this.region = region; }
    @Override public Region getRegion() { return region; }
    public BlockVector3 getOrigin() { return origin; }
    public void setOrigin(BlockVector3 origin) { this.origin = origin; }
    @Override public String toString() { return "Clipboard(" + region + ",origin=" + origin + ")"; }
}

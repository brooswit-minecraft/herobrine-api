package baritone.api.schematic;
public class FillSchematic implements ISchematic {
    public final int x, y, z; public final Object block;
    public FillSchematic(int x, int y, int z, baritone.api.utils.BlockOptionalMeta block) { this.x = x; this.y = y; this.z = z; this.block = block; }
    @Override public String toString() { return "Fill(" + x + "x" + y + "x" + z + "," + block + ")"; }
}

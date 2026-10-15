package baritone.api.schematic;
public class WallsSchematic implements ISchematic {
    private final ISchematic inner;
    public WallsSchematic(ISchematic inner) { this.inner = inner; }
    @Override public String toString() { return "Walls(" + inner + ")"; }
}

package baritone.api.schematic;
public class SphereSchematic implements ISchematic {
    private final ISchematic inner; private final boolean filled;
    public SphereSchematic(ISchematic inner, boolean filled) { this.inner = inner; this.filled = filled; }
    @Override public String toString() { return (filled ? "Sphere(" : "HollowSphere(") + inner + ")"; }
}

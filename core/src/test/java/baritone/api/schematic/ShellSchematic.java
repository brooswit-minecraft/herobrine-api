package baritone.api.schematic;
public class ShellSchematic implements ISchematic {
    private final ISchematic inner;
    public ShellSchematic(ISchematic inner) { this.inner = inner; }
    @Override public String toString() { return "Shell(" + inner + ")"; }
}

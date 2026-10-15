package baritone.api.schematic;
public class ReplaceSchematic implements ISchematic {
    private final ISchematic inner; private final baritone.api.utils.BlockOptionalMetaLookup filter;
    public ReplaceSchematic(ISchematic inner, baritone.api.utils.BlockOptionalMetaLookup filter) { this.inner = inner; this.filter = filter; }
    @Override public String toString() { return "Replace(" + inner + " where " + filter + ")"; }
}

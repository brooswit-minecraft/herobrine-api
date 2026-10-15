package baritone.api.utils;
public class BlockOptionalMetaLookup {
    private final String[] ids;
    public BlockOptionalMetaLookup(String... ids) { this.ids = ids; }
    @Override public String toString() { return "Lookup" + java.util.Arrays.toString(ids); }
}

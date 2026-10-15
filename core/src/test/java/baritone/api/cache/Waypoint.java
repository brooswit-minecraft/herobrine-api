package baritone.api.cache;
public class Waypoint implements IWaypoint {
    private final String name; private final Tag tag; private final baritone.api.utils.BetterBlockPos pos;
    public Waypoint(String name, Tag tag, baritone.api.utils.BetterBlockPos pos) { this.name = name; this.tag = tag; this.pos = pos; }
    public String getName() { return name; } public Tag getTag() { return tag; } public Object getLocation() { return pos; }
}

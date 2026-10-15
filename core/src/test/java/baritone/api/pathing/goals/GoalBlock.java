package baritone.api.pathing.goals;
public class GoalBlock {
    private final int x, y, z;
    public GoalBlock(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
    @Override public String toString() { return "GoalBlock{" + x + "," + y + "," + z + "}"; }
}

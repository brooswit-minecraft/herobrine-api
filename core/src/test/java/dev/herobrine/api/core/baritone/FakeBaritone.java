package dev.herobrine.api.core.baritone;

import baritone.api.IBaritoneProvider;
import baritone.api.event.listener.IGameEventListener;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Stand-in for Baritone built from memory of its API shape. The implementation class is private, so
 * calls only work if the adapter resolves methods through these public interfaces.
 */
public final class FakeBaritone {
    public static final List<String> CALLS = new ArrayList<>();
    public static volatile IGameEventListener listener;
    public static volatile boolean lackExplore;
    public static volatile boolean primaryIsNull;

    public interface IBaritone {
        ICustomGoal getCustomGoalProcess(); IMine getMineProcess(); IFollow getFollowProcess(); Object getExploreProcess();
        IGetToBlock getGetToBlockProcess(); IPathing getPathingBehavior(); IControl getPathingControlManager();
        IBuilder getBuilderProcess(); ICmdManager getCommandManager(); IEventHandler getGameEventHandler();
    }
    public interface ICustomGoal { void setGoalAndPath(Object goal); }
    public interface IMine { void mineByName(int quantity, String... blocks); }
    public interface IFollow { void follow(Predicate<?> p); }
    public interface IExplore { void explore(int x, int z); }
    public interface IGetToBlock { void getToBlock(Object meta); }
    public interface IPathing { boolean isPathing(); Object getGoal(); boolean cancelEverything(); Optional<IPath> getPath(); }
    public interface IPath { List<Pos> positions(); }
    public static class Pos { final int x, y, z; Pos(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }
        public int getX() { return x; } public int getY() { return y; } public int getZ() { return z; } }
    public interface IControl { Optional<IProcess> mostRecentInControl(); }
    public interface IProcess { String displayName(); }
    public interface IBuilder { boolean build(String name, File f, Object origin); void clearArea(Object a, Object b); }
    public interface ICmdManager { boolean execute(String line); }
    public interface IEventHandler { void registerEventListener(IGameEventListener l); }

    public static IBaritoneProvider provider() { return () -> primaryIsNull ? null : new Impl(); }

    public static void reset() { CALLS.clear(); listener = null; lackExplore = false; primaryIsNull = false; }

    private static final class Impl implements IBaritone, ICustomGoal, IMine, IFollow, IExplore, IGetToBlock, IPathing,
            IPath, IControl, IProcess, IBuilder, ICmdManager, IEventHandler {
        public ICustomGoal getCustomGoalProcess() { return this; }
        public IMine getMineProcess() { return this; }
        public IFollow getFollowProcess() { return this; }
        public Object getExploreProcess() { return lackExplore ? new Object() : this; }
        public IGetToBlock getGetToBlockProcess() { return this; }
        public IPathing getPathingBehavior() { return this; }
        public IControl getPathingControlManager() { return this; }
        public IBuilder getBuilderProcess() { return this; }
        public ICmdManager getCommandManager() { return this; }
        public IEventHandler getGameEventHandler() { return this; }

        public void setGoalAndPath(Object goal) { CALLS.add("setGoalAndPath:" + goal); }
        public void mineByName(int quantity, String... blocks) { CALLS.add("mine:" + quantity + ":" + String.join("|", blocks)); }
        public void follow(Predicate<?> p) {
            @SuppressWarnings("unchecked") Predicate<Object> q = (Predicate<Object>) p;
            CALLS.add("follow:" + q.test("zombie") + "," + q.test("cow"));
        }
        public void explore(int x, int z) { CALLS.add("explore:" + x + "," + z); }
        public void getToBlock(Object meta) { CALLS.add("getToBlock:" + meta); }
        public boolean isPathing() { return true; }
        public Object getGoal() { return "GoalBlock{1,2,3}"; }
        public boolean cancelEverything() { CALLS.add("cancelEverything"); return true; }
        public Optional<IPath> getPath() { return Optional.of(this); }
        public List<Pos> positions() { return List.of(new Pos(1, 2, 3), new Pos(4, 5, 6), new Pos(7, 8, 9)); }
        public Optional<IProcess> mostRecentInControl() { return Optional.of(this); }
        public String displayName() { return "Custom Goal"; }
        public boolean build(String name, File f, Object origin) { CALLS.add("build:" + name + ":" + origin); return !name.startsWith("bad"); }
        public void clearArea(Object a, Object b) { CALLS.add("clearArea:" + a + "->" + b); }
        public boolean execute(String line) { CALLS.add("cmd:" + line); return true; }
        public void registerEventListener(IGameEventListener l) { listener = l; }
    }
}

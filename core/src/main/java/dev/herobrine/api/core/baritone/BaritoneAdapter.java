package dev.herobrine.api.core.baritone;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Typed facade over Baritone's published Java API (baritone.api.*). Every method throws
 * {@code ApiException}: {@code UNSUPPORTED} (501) when the installed build lacks the underlying API
 * call, {@code ACTION_UNAVAILABLE} (409) when Baritone is absent or has no active instance.
 * Baritone's command manager is deliberately not used.
 */
public interface BaritoneAdapter {
    /** Inclusive axis-aligned box between two corners, in any order. */
    record Box(int x1, int y1, int z1, int x2, int y2, int z2) {
        public int minX() { return Math.min(x1, x2); }
        public int minY() { return Math.min(y1, y2); }
        public int minZ() { return Math.min(z1, z2); }
        public int sizeX() { return Math.abs(x1 - x2) + 1; }
        public int sizeY() { return Math.abs(y1 - y2) + 1; }
        public int sizeZ() { return Math.abs(z1 - z2) + 1; }
    }

    enum Shape { SOLID, WALLS, SHELL, SPHERE, HOLLOW_SPHERE }

    boolean installed();

    // ---- processes (each starts a Baritone process and returns immediately) ----
    void gotoPos(int x, int y, int z);
    void gotoXZ(int x, int z);
    void gotoBlock(String blockId);
    /** Follow every entity the matcher accepts. The matcher receives Minecraft Entity objects. */
    void follow(Predicate<Object> entityMatcher);
    void explore(int x, int z);
    /** @param quantity how many to collect; 0 means until cancelled. */
    void mine(List<String> blockIds, int quantity);
    /** @param range farming radius; 0 uses Baritone's default. */
    void farm(int range);
    void cancel();

    // ---- building ----
    /** Builds a schematic file with its origin at x,y,z. */
    void buildSchematic(File file, int x, int y, int z);
    void clearArea(Box box);
    void fill(Shape shape, String blockId, Box box);
    /** Replaces {@code findId} with {@code replaceId} inside the box. */
    void replace(String findId, String replaceId, Box box);

    // ---- settings / waypoints ----
    List<Map<String, Object>> settings();
    /** Sets a boolean, integer, long, float, double or string setting; the value is parsed to the setting's type. */
    void setSetting(String name, String value);
    List<Map<String, Object>> waypoints();
    void saveWaypoint(String name, int x, int y, int z);

    // ---- state ----
    boolean isPathing();
    String goal();
    /** Display name of the process currently in control, or null. */
    String activeProcess();
    List<int[]> pathNodes(int cap);
    /** Known locations of a block in Baritone's world cache near the given center. */
    List<int[]> locationsOf(String blockId, int max, int centerX, int centerZ);

    default boolean busy() { return isPathing() || activeProcess() != null; }

    // ---- path events (CALC_FAILED, AT_GOAL, CANCELED, ...) ----
    /** The most recent path event name, or null if none has been seen. */
    String lastPathEvent();
    /** Called for every path event as it happens. */
    void addEventListener(Consumer<String> listener);
}

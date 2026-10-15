package dev.herobrine.api.core.baritone;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Typed facade over Baritone's published Java API (baritone.api.*). Every method throws
 * {@code ApiException} with code {@code UNSUPPORTED} when the installed build lacks the underlying
 * API call, so callers can fall back to Baritone's command manager for that one operation.
 */
public interface BaritoneAdapter {
    boolean installed();

    // ---- navigation / processes (all start a Baritone process and return immediately) ----
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
    void clearArea(int x1, int y1, int z1, int x2, int y2, int z2);

    // ---- state ----
    boolean isPathing();
    String goal();
    /** Display name of the process currently in control, or null. */
    String activeProcess();
    List<int[]> pathNodes(int cap);
    List<Map<String, Object>> settings();
    /** Known locations of a block in Baritone's world cache near the given center, nearest regions first. */
    List<int[]> locationsOf(String blockId, int max, int centerX, int centerZ);
    /** Baritone waypoints: name, tag, x, y, z. */
    List<Map<String, Object>> waypoints();

    default boolean busy() { return isPathing() || activeProcess() != null; }

    /** Last-resort access to Baritone's own command manager (the same parser as in-game '#' commands). */
    void command(String line);

    // ---- path events (CALC_FAILED, AT_GOAL, CANCELED, ...) ----
    /** The most recent path event name, or null if none has been seen. */
    String lastPathEvent();
    /** Called for every path event as it happens. */
    void addEventListener(Consumer<String> listener);
}

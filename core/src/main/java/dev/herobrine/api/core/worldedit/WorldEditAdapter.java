package dev.herobrine.api.core.worldedit;

import java.io.File;

/**
 * Typed facade over WorldEdit's published Java API (com.sk89q.worldedit.*), verified against WorldEdit
 * 7.3.8 ({@code worldedit-neoforge-mc1.21}, the last build published for Minecraft 1.21.x before the
 * artifact line split per-patch at 1.21.3 — see docs/COVERAGE.md for the full version note). Every method
 * throws {@code ApiException}: {@code UNSUPPORTED} (501) when the installed build lacks the underlying
 * API call or is an incompatible version, {@code ACTION_UNAVAILABLE} (409) when WorldEdit is absent.
 * WorldEdit's command dispatcher is deliberately not used; every call maps directly to a WorldEdit Java
 * API method. {@code actor} is an opaque WorldEdit {@code Actor} (also {@code Locatable}) instance; only
 * the platform module can construct one (wrapping a Minecraft player) - see MINECRAFT-476.
 */
public interface WorldEditAdapter {
    /** Inclusive axis-aligned box between two corners, in any order; maps to a WorldEdit {@code CuboidRegion}. */
    record Box(int x1, int y1, int z1, int x2, int y2, int z2) {
        public int minX() { return Math.min(x1, x2); }
        public int minY() { return Math.min(y1, y2); }
        public int minZ() { return Math.min(z1, z2); }
        public int maxX() { return Math.max(x1, x2); }
        public int maxY() { return Math.max(y1, y2); }
        public int maxZ() { return Math.max(z1, z2); }
    }

    boolean installed();
    /** WorldEdit's self-reported version string (e.g. "7.3.8+1379-7d32b45"), or null if absent. */
    String version();

    // ---- edits (actor-scoped: each opens an EditSession tied to the actor's LocalSession, applies the
    // change, records it in the session's undo history, then closes the session) ----

    /**
     * Fills the box with a pattern, in WorldEdit's own pattern syntax (e.g. "stone", "50%stone,50%dirt").
     * @return the number of blocks changed
     */
    int setBlocks(Object actor, Box box, String pattern);

    /**
     * Replaces blocks matching a mask (WorldEdit mask syntax, e.g. "stone,dirt") with a pattern inside the box.
     * @return the number of blocks changed
     */
    int replaceBlocks(Object actor, Box box, String mask, String pattern);

    // ---- history (WorldEdit-owned, per actor LocalSession; no separate queue of our own) ----

    /** @return whether an edit was undone */
    boolean undo(Object actor);
    /** @return whether an edit was redone */
    boolean redo(Object actor);

    // ---- clipboard / schematics (the actor's WorldEdit clipboard; one per LocalSession, like the game) ----

    /** Copies the box into the actor's clipboard, origin at the box's minimum corner. */
    void copy(Object actor, Box box);
    /** Pastes the actor's clipboard with its origin at x,y,z. */
    void paste(Object actor, int x, int y, int z);
    /** Writes the actor's clipboard to a schematic file; format is inferred from the file extension. */
    void saveSchematic(Object actor, File file);
    /** Reads a schematic file into the actor's clipboard; format is inferred from the file extension. */
    void loadSchematic(Object actor, File file);

    // ---- selection ----

    /**
     * Axis-aligned bounding box of the actor's current selection. Exact only for a cuboid selection;
     * other selection shapes (polygon, cylinder, ...) are reported by their bounding box.
     */
    Box selection(Object actor);

    // ---- regions (non-cuboid): a WorldEdit CylinderRegion, the most common non-cuboid selection/edit
    // shape - the same edits/selection reporting available for Box, additionally available for this shape.
    // Other non-cuboid shapes (polygon, ellipsoid, convex) remain a documented gap - see docs/worldedit-coverage.md.

    /**
     * A cylindrical region: center x,z with vertical extent minY..maxY (inclusive), and radii along X and Z
     * (equal for a circular cylinder). Maps exactly to a WorldEdit {@code CylinderRegion}.
     */
    record Cylinder(int centerX, int centerZ, double radiusX, double radiusZ, int minY, int maxY) {}

    /** Fills a cylindrical region with a pattern. @return the number of blocks changed */
    int setBlocks(Object actor, Cylinder cylinder, String pattern);
    /** Replaces blocks matching a mask with a pattern inside a cylindrical region. @return the number of blocks changed */
    int replaceBlocks(Object actor, Cylinder cylinder, String mask, String pattern);

    /**
     * The actor's current selection reported as a cylinder, only when the selector is exactly a
     * CylinderRegion.
     * @throws dev.herobrine.api.core.ApiException BAD_REQUEST if there's no selection, or the selector isn't a cylinder
     */
    Cylinder cylinderSelection(Object actor);

    // ---- transforms (compose onto the actor's clipboard transform; affect the next paste(), not past
    // pastes - this is WorldEdit's own //rotate and //flip semantics: non-destructive, stackable, applied
    // at paste time via ClipboardHolder.getTransform()) ----

    /**
     * Rotates the actor's clipboard, in degrees, a positive angle meaning clockwise (WorldEdit's own
     * {@code //rotate} convention). Not interpolated: each angle should be a multiple of 90. Composes with
     * (stacks onto) any existing clipboard transform; takes effect on the next {@link #paste}.
     */
    void rotateClipboard(Object actor, double degreesY, double degreesX, double degreesZ);

    /**
     * Scales the actor's clipboard by the given per-axis factors (WorldEdit's own {@code //flip} is this
     * with factors of 1 or -1). Composes with (stacks onto) any existing clipboard transform; takes effect
     * on the next {@link #paste}.
     */
    void scaleClipboard(Object actor, double sx, double sy, double sz);

    // ---- generation shapes (same actor/session/undo semantics as setBlocks: verified public EditSession
    // methods, not a parallel implementation) ----

    /**
     * Generates a sphere, or an ellipsoid when the radii differ, of a pattern centered at x,y,z.
     * @return the number of blocks changed
     */
    int makeSphere(Object actor, int x, int y, int z, double radiusX, double radiusY, double radiusZ, boolean filled, String pattern);

    /**
     * Generates a cylinder, or an elliptical one when radiusX != radiusZ, of a pattern with its base
     * centered at x,y,z. A negative height extends downward instead of upward.
     * @return the number of blocks changed
     */
    int makeCylinder(Object actor, int x, int y, int z, double radiusX, double radiusZ, int height, boolean filled, String pattern);

    /** Generates a square pyramid of a pattern with its base centered at x,y,z. @return the number of blocks changed */
    int makePyramid(Object actor, int x, int y, int z, int size, boolean filled, String pattern);

    /**
     * Leaves a shell of {@code thickness} blocks at the box's boundary untouched, and replaces every block
     * in the interior beyond it with a pattern (verified: {@code EditSession.hollowOutRegion} only
     * {@code setBlock}s positions with no neighbor in its computed boundary/"outside" set - the boundary
     * shell itself is what survives, not the interior).
     * @return the number of blocks changed
     */
    int hollowOutBox(Object actor, Box box, int thickness, String pattern);

    // ---- paste options ----

    /**
     * Pastes the actor's clipboard with its origin at x,y,z, with explicit paste options mirroring
     * WorldEdit's own {@code PasteBuilder} flags. {@link #paste(Object, int, int, int)} is this with
     * WorldEdit's own builder defaults: ignoreAirBlocks=false, ignoreStructureVoidBlocks=false,
     * copyEntities=true, copyBiomes=false.
     */
    void paste(Object actor, int x, int y, int z,
               boolean ignoreAirBlocks, boolean ignoreStructureVoidBlocks, boolean copyEntities, boolean copyBiomes);
}

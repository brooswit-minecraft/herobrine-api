package dev.herobrine.api.core.worldedit;

import java.io.File;
import java.util.List;

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

    /** Copies the cylinder into the actor's clipboard, origin at the cylinder's own bounding-box minimum corner. */
    void copy(Object actor, Cylinder cylinder);

    /** One point of a {@link Polygon}'s footprint, in the X/Z plane. */
    record Point2D(int x, int z) {}

    /**
     * A polygon footprint (at least 3 points, in the X/Z plane) extruded through a vertical range minY..maxY
     * (inclusive). Maps exactly to a WorldEdit {@code Polygonal2DRegion} - verified world-independent:
     * {@code Polygonal2DRegion()} (no-arg ctor), then {@code addPoint(BlockVector2)} per point and
     * {@code setMinimumY}/{@code setMaximumY}; {@code AbstractRegion.getWorldMinY/getWorldMaxY} gracefully
     * default to {@code Integer.MIN_VALUE}/{@code MAX_VALUE} when there is no {@code World} (there never is
     * one here - no actor/world construction, per MINECRAFT-476).
     */
    record Polygon(List<Point2D> points, int minY, int maxY) {}

    /** Fills a polygon footprint (extruded vertically) with a pattern. @return the number of blocks changed
     *  @throws dev.herobrine.api.core.ApiException BAD_REQUEST if fewer than 3 points are given */
    int setBlocks(Object actor, Polygon polygon, String pattern);
    /** Replaces blocks matching a mask with a pattern inside a polygon footprint. @return the number of blocks changed */
    int replaceBlocks(Object actor, Polygon polygon, String mask, String pattern);
    /** Copies the polygon into the actor's clipboard, origin at its own bounding-box minimum corner. */
    void copy(Object actor, Polygon polygon);

    /**
     * The actor's current selection reported as a polygon footprint, only when the selector is exactly a
     * Polygonal2DRegion.
     * @throws dev.herobrine.api.core.ApiException BAD_REQUEST if there's no selection, or the selector isn't a polygon
     */
    Polygon polygonSelection(Object actor);

    /**
     * An axis-aligned ellipsoid (a sphere when the radii are equal), centered at x,y,z. Maps exactly to a
     * WorldEdit {@code EllipsoidRegion} - verified world-independent 2-arg constructor
     * {@code EllipsoidRegion(BlockVector3 center, Vector3 radius)} (its single-{@code BlockVector3}-param
     * javadoc calls the arguments "pos1"/"pos2", which reads like two corners, but the constructor body
     * delegates as {@code center, radius} - verified by reading the body, not assumed from the parameter
     * names).
     */
    record Ellipsoid(int centerX, int centerY, int centerZ, double radiusX, double radiusY, double radiusZ) {}

    /** Fills an ellipsoid with a pattern. @return the number of blocks changed */
    int setBlocks(Object actor, Ellipsoid ellipsoid, String pattern);
    /** Replaces blocks matching a mask with a pattern inside an ellipsoid. @return the number of blocks changed */
    int replaceBlocks(Object actor, Ellipsoid ellipsoid, String mask, String pattern);
    /** Copies the ellipsoid into the actor's clipboard, origin at its own bounding-box minimum corner. */
    void copy(Object actor, Ellipsoid ellipsoid);

    /**
     * The actor's current selection reported as an ellipsoid, only when the selector is exactly an
     * EllipsoidRegion.
     * @throws dev.herobrine.api.core.ApiException BAD_REQUEST if there's no selection, or the selector isn't an ellipsoid
     */
    Ellipsoid ellipsoidSelection(Object actor);

    /** One vertex of a {@link ConvexHull}. */
    record Vertex(int x, int y, int z) {}

    /**
     * A convex polyhedron defined by its vertices (at least 4, not all coplanar). Maps exactly to a WorldEdit
     * {@code ConvexPolyhedralRegion} - verified world-independent (its constructor's {@code World} parameter
     * is {@code @Nullable}), built via repeated {@code addVertex(BlockVector3)}. Too few (or all-coplanar)
     * vertices leave the region {@code isDefined() == false} (verified: triangles only form once a 4th,
     * non-coplanar vertex is added) - a {@code BAD_REQUEST}, not silently a no-op or a crash.
     */
    record ConvexHull(List<Vertex> vertices) {}

    /** Fills a convex hull with a pattern. @return the number of blocks changed
     *  @throws dev.herobrine.api.core.ApiException BAD_REQUEST if the hull is degenerate (not {@code isDefined()}) */
    int setBlocks(Object actor, ConvexHull hull, String pattern);
    /** Replaces blocks matching a mask with a pattern inside a convex hull. @return the number of blocks changed */
    int replaceBlocks(Object actor, ConvexHull hull, String mask, String pattern);
    /** Copies the convex hull into the actor's clipboard, origin at its own bounding-box minimum corner. */
    void copy(Object actor, ConvexHull hull);

    /**
     * The actor's current selection reported as a convex hull, only when the selector is exactly a
     * ConvexPolyhedralRegion.
     * @throws dev.herobrine.api.core.ApiException BAD_REQUEST if there's no selection, or the selector isn't a convex hull
     */
    ConvexHull convexHullSelection(Object actor);

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
     * in the interior beyond it with a pattern. Computed geometrically (the interior box shrunk by
     * {@code thickness} on every axis, then {@link #setBlocks(Object, Box, String)}) rather than via
     * {@code EditSession.hollowOutRegion} - that method's own shell/interior split is determined by flood-
     * filling through already-passable world blocks (verified: {@code EditSession.recurseHollow} checks
     * {@code getBlock(current)} against the real world), so for an already-solid box (the common case) it
     * never finds a shell at all and overwrites the entire region. This method's behavior is geometry-only
     * and does not depend on existing terrain.
     * @return the number of blocks changed, or 0 if {@code thickness} leaves no interior
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

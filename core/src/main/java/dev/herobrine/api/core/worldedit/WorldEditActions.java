package dev.herobrine.api.core.worldedit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.herobrine.api.core.ApiException;
import dev.herobrine.api.core.Params;
import dev.herobrine.api.core.Validation;
import dev.herobrine.api.core.worldedit.WorldEditAdapter.Box;
import dev.herobrine.api.core.worldedit.WorldEditAdapter.ConvexHull;
import dev.herobrine.api.core.worldedit.WorldEditAdapter.Cylinder;
import dev.herobrine.api.core.worldedit.WorldEditAdapter.Ellipsoid;
import dev.herobrine.api.core.worldedit.WorldEditAdapter.Point2D;
import dev.herobrine.api.core.worldedit.WorldEditAdapter.Polygon;
import dev.herobrine.api.core.worldedit.WorldEditAdapter.Vertex;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dispatches a {@code worldedit.*} operation (see {@link WorldEditSchema#OPERATIONS}) to the matching
 * {@link WorldEditAdapter} method: checks the actor's WorldEdit permission, bounds and translates the
 * {@link Params}, and returns what the call returned. Pure and platform-independent - {@code actor} is the
 * WorldEdit actor the platform resolved for the request (the neoforge module wraps a {@code ServerPlayer};
 * tests hand in a {@code FakeActor}), and the caller is already on the thread that owns its world.
 *
 * <p>Every operation lists the WorldEdit permission nodes WorldEdit 7.3.8's own command for the same thing
 * checks (its {@code @CommandPermissions}); as there, holding any one of them is enough
 * ({@code PermissionCondition.satisfied}). WorldEdit does not check permissions below its command layer, so
 * without this an API caller would get past the limits a player has in game.
 *
 * <p>Every region/shape write - and every copy, and every paste of a clipboard - is capped by
 * {@code maxEditVolume} via a bounding box that is never smaller than the blocks the call can touch (see
 * {@link #cap}), the same "box volume" rule the Baritone bindings apply to their edits.
 */
public final class WorldEditActions {
    private WorldEditActions() {}

    /** Most points a polygon footprint, or vertices a convex hull, may list (WorldEdit tests every block against each). */
    public static final int MAX_SHAPE_POINTS = 256;
    /** Longest WorldEdit pattern or mask accepted; both are handed to WorldEdit's own parser. */
    public static final int MAX_PATTERN_LENGTH = 256;
    /** Largest per-axis clipboard scale factor magnitude ({@code //flip} itself only uses 1 and -1). */
    public static final double MAX_SCALE_FACTOR = 16;

    // WorldEdit's own permission nodes, verified against the @CommandPermissions of each command in the
    // 7.3.8 sources (worldedit-core, com/sk89q/worldedit/command/).
    static final String REGION_SET = "worldedit.region.set";                 // RegionCommands //set
    static final String REGION_REPLACE = "worldedit.region.replace";         // RegionCommands //replace
    static final String REGION_HOLLOW = "worldedit.region.hollow";           // RegionCommands //hollow
    static final String HISTORY_UNDO = "worldedit.history.undo";             // HistoryCommands undo
    static final String HISTORY_UNDO_SELF = "worldedit.history.undo.self";
    static final String HISTORY_REDO = "worldedit.history.redo";             // HistoryCommands redo
    static final String HISTORY_REDO_SELF = "worldedit.history.redo.self";
    static final String CLIPBOARD_COPY = "worldedit.clipboard.copy";         // ClipboardCommands //copy
    static final String CLIPBOARD_PASTE = "worldedit.clipboard.paste";       // ClipboardCommands //paste
    static final String CLIPBOARD_ROTATE = "worldedit.clipboard.rotate";     // ClipboardCommands //rotate
    static final String CLIPBOARD_FLIP = "worldedit.clipboard.flip";         // ClipboardCommands //flip (scale generalizes it)
    static final String CLIPBOARD_SAVE = "worldedit.clipboard.save";         // SchematicCommands save
    static final String SCHEMATIC_SAVE = "worldedit.schematic.save";
    static final String CLIPBOARD_LOAD = "worldedit.clipboard.load";         // SchematicCommands load
    static final String SCHEMATIC_LOAD = "worldedit.schematic.load";
    static final String SCHEMATIC_DELETE = "worldedit.schematic.delete";     // SchematicCommands save, to overwrite
    static final String SELECTION_SIZE = "worldedit.selection.size";         // SelectionCommands //size
    static final String GENERATION_SPHERE = "worldedit.generation.sphere";   // GenerationCommands //sphere
    static final String GENERATION_CYLINDER = "worldedit.generation.cylinder"; // GenerationCommands //cyl
    static final String GENERATION_PYRAMID = "worldedit.generation.pyramid"; // GenerationCommands //pyramid

    /** Whether an operation only reports state (served as a resource, open to the read-only token) or changes it. */
    public enum Kind { READ, WRITE }

    private record Call(WorldEditAdapter a, Object actor, Params p, long maxEditVolume, SchematicFiles schematics) {}

    @FunctionalInterface private interface Handler { Object run(Call c); }

    private record Op(Kind kind, List<String> permissions, Handler handler) {}

    private static Map.Entry<String, Op> read(String name, String permission, Handler h) {
        return Map.entry(name, new Op(Kind.READ, List.of(permission), h));
    }

    private static Map.Entry<String, Op> write(String name, List<String> permissions, Handler h) {
        return Map.entry(name, new Op(Kind.WRITE, permissions, h));
    }

    private static final Map<String, Op> OPS = Map.ofEntries(
            write("worldedit.set_blocks", List.of(REGION_SET),
                    c -> changed(c.a.setBlocks(c.actor, cap(box(c.p), c), pattern(c.p)))),
            write("worldedit.replace_blocks", List.of(REGION_REPLACE),
                    c -> changed(c.a.replaceBlocks(c.actor, cap(box(c.p), c), mask(c.p), pattern(c.p)))),
            write("worldedit.undo", List.of(HISTORY_UNDO, HISTORY_UNDO_SELF), c -> Map.of("undone", c.a.undo(c.actor))),
            write("worldedit.redo", List.of(HISTORY_REDO, HISTORY_REDO_SELF), c -> Map.of("redone", c.a.redo(c.actor))),
            write("worldedit.copy", List.of(CLIPBOARD_COPY), c -> {
                c.a.copy(c.actor, cap(box(c.p), c));
                return Map.of("copied", true);
            }),
            write("worldedit.paste", List.of(CLIPBOARD_PASTE), WorldEditActions::paste),
            write("worldedit.save_schematic", List.of(CLIPBOARD_SAVE, SCHEMATIC_SAVE), WorldEditActions::saveSchematic),
            write("worldedit.load_schematic", List.of(CLIPBOARD_LOAD, SCHEMATIC_LOAD), WorldEditActions::loadSchematic),
            read("worldedit.selection", SELECTION_SIZE, c -> c.a.selection(c.actor)),
            write("worldedit.rotate_clipboard", List.of(CLIPBOARD_ROTATE), c -> {
                c.a.rotateClipboard(c.actor, finite(c.p, "degreesY"), finite(c.p, "degreesX", 0), finite(c.p, "degreesZ", 0));
                return Map.of("rotated", true);
            }),
            write("worldedit.scale_clipboard", List.of(CLIPBOARD_FLIP), c -> {
                c.a.scaleClipboard(c.actor, scale(c.p, "sx"), scale(c.p, "sy"), scale(c.p, "sz"));
                return Map.of("scaled", true);
            }),
            write("worldedit.set_blocks_cylinder", List.of(REGION_SET),
                    c -> changed(c.a.setBlocks(c.actor, cap(cylinder(c.p), c), pattern(c.p)))),
            write("worldedit.replace_blocks_cylinder", List.of(REGION_REPLACE),
                    c -> changed(c.a.replaceBlocks(c.actor, cap(cylinder(c.p), c), mask(c.p), pattern(c.p)))),
            read("worldedit.cylinder_selection", SELECTION_SIZE, c -> c.a.cylinderSelection(c.actor)),
            write("worldedit.make_sphere", List.of(GENERATION_SPHERE), WorldEditActions::makeSphere),
            write("worldedit.make_cylinder", List.of(GENERATION_CYLINDER), WorldEditActions::makeCylinder),
            write("worldedit.make_pyramid", List.of(GENERATION_PYRAMID), WorldEditActions::makePyramid),
            write("worldedit.hollow_out_box", List.of(REGION_HOLLOW),
                    c -> changed(c.a.hollowOutBox(c.actor, cap(box(c.p), c), c.p.integer("thickness"), pattern(c.p)))),
            write("worldedit.copy_cylinder", List.of(CLIPBOARD_COPY), c -> {
                c.a.copy(c.actor, cap(cylinder(c.p), c));
                return Map.of("copied", true);
            }),
            write("worldedit.set_blocks_polygon", List.of(REGION_SET),
                    c -> changed(c.a.setBlocks(c.actor, cap(polygon(c.p), c), pattern(c.p)))),
            write("worldedit.replace_blocks_polygon", List.of(REGION_REPLACE),
                    c -> changed(c.a.replaceBlocks(c.actor, cap(polygon(c.p), c), mask(c.p), pattern(c.p)))),
            write("worldedit.copy_polygon", List.of(CLIPBOARD_COPY), c -> {
                c.a.copy(c.actor, cap(polygon(c.p), c));
                return Map.of("copied", true);
            }),
            read("worldedit.polygon_selection", SELECTION_SIZE, c -> c.a.polygonSelection(c.actor)),
            write("worldedit.set_blocks_ellipsoid", List.of(REGION_SET),
                    c -> changed(c.a.setBlocks(c.actor, cap(ellipsoid(c.p), c), pattern(c.p)))),
            write("worldedit.replace_blocks_ellipsoid", List.of(REGION_REPLACE),
                    c -> changed(c.a.replaceBlocks(c.actor, cap(ellipsoid(c.p), c), mask(c.p), pattern(c.p)))),
            write("worldedit.copy_ellipsoid", List.of(CLIPBOARD_COPY), c -> {
                c.a.copy(c.actor, cap(ellipsoid(c.p), c));
                return Map.of("copied", true);
            }),
            read("worldedit.ellipsoid_selection", SELECTION_SIZE, c -> c.a.ellipsoidSelection(c.actor)),
            write("worldedit.set_blocks_convex_hull", List.of(REGION_SET),
                    c -> changed(c.a.setBlocks(c.actor, cap(convexHull(c.p), c), pattern(c.p)))),
            write("worldedit.replace_blocks_convex_hull", List.of(REGION_REPLACE),
                    c -> changed(c.a.replaceBlocks(c.actor, cap(convexHull(c.p), c), mask(c.p), pattern(c.p)))),
            write("worldedit.copy_convex_hull", List.of(CLIPBOARD_COPY), c -> {
                c.a.copy(c.actor, cap(convexHull(c.p), c));
                return Map.of("copied", true);
            }),
            read("worldedit.convex_hull_selection", SELECTION_SIZE, c -> c.a.convexHullSelection(c.actor)));

    /** Every operation name this dispatcher handles; equal to the names in {@link WorldEditSchema#OPERATIONS}. */
    public static Set<String> names() { return OPS.keySet(); }

    public static Kind kind(String action) { return op(action).kind(); }

    /** The WorldEdit permission nodes, any one of which lets the actor run {@code action}. */
    public static List<String> permissions(String action) { return op(action).permissions(); }

    /**
     * Runs {@code action} as {@code actor}. Call on the thread that owns the actor's world.
     * @param maxEditVolume the configured edit cap, in blocks
     */
    public static Object dispatch(WorldEditAdapter a, String action, Object actor, Params p,
            long maxEditVolume, SchematicFiles schematics) {
        Op op = op(action);
        requireAny(a, actor, op.permissions());
        return op.handler().run(new Call(a, actor, p, maxEditVolume, schematics));
    }

    private static Op op(String action) {
        Op op = OPS.get(action);
        if (op == null) throw ApiException.notFound("Unknown worldedit action " + action);
        return op;
    }

    private static void requireAny(WorldEditAdapter a, Object actor, List<String> permissions) {
        for (String perm : permissions) if (a.hasPermission(actor, perm)) return;
        throw ApiException.forbidden("Needs WorldEdit permission " + String.join(" or ", permissions));
    }

    private static Map<String, Object> changed(int n) { return Map.of("changed", n); }

    // ---- clipboard and schematics ----

    private static Object paste(Call c) {
        int x = Validation.coordinate(c.p.integer("x")), y = Validation.coordinate(c.p.integer("y")),
                z = Validation.coordinate(c.p.integer("z"));
        long volume = c.a.clipboardVolume(c.actor);
        if (volume > c.maxEditVolume)
            throw ApiException.badRequest("Clipboard of " + volume + " blocks exceeds the limit of " + c.maxEditVolume);
        // Defaults are WorldEdit's own PasteBuilder defaults, which the schema declares too.
        c.a.paste(c.actor, x, y, z, c.p.bool("ignoreAirBlocks", false), c.p.bool("ignoreStructureVoidBlocks", false),
                c.p.bool("copyEntities", true), c.p.bool("copyBiomes", false));
        return Map.of("pasted", true);
    }

    /**
     * WorldEdit's own {@code //schem save} rule for an existing file (SchematicCommands.java:168-176): replacing
     * it needs {@code worldedit.schematic.delete} and then the explicit {@code -f} flag, here {@code overwrite}.
     */
    private static Object saveSchematic(Call c) {
        String name = c.p.string("file");
        Path file = c.schematics.resolve(name, true);
        if (Files.exists(file)) {
            requireAny(c.a, c.actor, List.of(SCHEMATIC_DELETE));
            if (!c.p.bool("overwrite", false))
                throw ApiException.badRequest("Schematic " + name + " already exists; pass overwrite=true to replace it");
        }
        c.a.saveSchematic(c.actor, file.toFile());
        return Map.of("saved", true, "file", name);
    }

    private static Object loadSchematic(Call c) {
        String name = c.p.string("file");
        c.a.loadSchematic(c.actor, c.schematics.resolve(name, false).toFile());
        return Map.of("loaded", true, "file", name);
    }

    // ---- generation ----

    private static Object makeSphere(Call c) {
        int x = c.p.integer("x"), y = c.p.integer("y"), z = c.p.integer("z");
        double rx = radius(c.p, "radiusX"), ry = radius(c.p, "radiusY"), rz = radius(c.p, "radiusZ");
        capBox((double) x - rx, (double) y - ry, (double) z - rz, (double) x + rx, (double) y + ry, (double) z + rz, c);
        return changed(c.a.makeSphere(c.actor, x, y, z, rx, ry, rz, c.p.bool("filled", false), pattern(c.p)));
    }

    /** {@code EditSession.makeCylinder} fills {@code y .. y+height-1} upward, or the {@code |height|} blocks below
     *  {@code y} for a negative height; {@code y .. y+height} covers both. */
    private static Object makeCylinder(Call c) {
        int x = c.p.integer("x"), y = c.p.integer("y"), z = c.p.integer("z");
        double rx = radius(c.p, "radiusX"), rz = radius(c.p, "radiusZ");
        int height = c.p.integer("height");
        double top = (double) y + height;
        capBox((double) x - rx, Math.min(y, top), (double) z - rz, (double) x + rx, Math.max(y, top), (double) z + rz, c);
        return changed(c.a.makeCylinder(c.actor, x, y, z, rx, rz, height, c.p.bool("filled", false), pattern(c.p)));
    }

    /** {@code EditSession.makePyramid} (EditSession.java:1975) fills {@code y .. y+size} and at most
     *  {@code size-1} out from the center; {@code ±size} covers it. */
    private static Object makePyramid(Call c) {
        int x = c.p.integer("x"), y = c.p.integer("y"), z = c.p.integer("z");
        int size = c.p.integer("size");
        if (size < 0) throw ApiException.badRequest("'size' must not be negative");
        capBox((double) x - size, y, (double) z - size, (double) x + size, (double) y + size, (double) z + size, c);
        return changed(c.a.makePyramid(c.actor, x, y, z, size, c.p.bool("filled", false), pattern(c.p)));
    }

    // ---- volume caps: bounding boxes never smaller than what the call can touch, so the cap never under-restricts ----

    private static Box cap(Box box, Call c) {
        Validation.boxVolume(box.x1(), box.y1(), box.z1(), box.x2(), box.y2(), box.z2(), c.maxEditVolume);
        return box;
    }

    /** A {@code CylinderRegion} reaches {@code floor(radius + 0.5)} from its center ({@code setRadius} adds 0.5),
     *  which {@code ceil(radius)} always covers; the same holds for ellipsoids and the generated shapes. */
    private static Cylinder cap(Cylinder cyl, Call c) {
        capBox((double) cyl.centerX() - cyl.radiusX(), cyl.minY(), (double) cyl.centerZ() - cyl.radiusZ(),
                (double) cyl.centerX() + cyl.radiusX(), cyl.maxY(), (double) cyl.centerZ() + cyl.radiusZ(), c);
        return cyl;
    }

    private static Ellipsoid cap(Ellipsoid e, Call c) {
        capBox((double) e.centerX() - e.radiusX(), (double) e.centerY() - e.radiusY(), (double) e.centerZ() - e.radiusZ(),
                (double) e.centerX() + e.radiusX(), (double) e.centerY() + e.radiusY(), (double) e.centerZ() + e.radiusZ(), c);
        return e;
    }

    private static Polygon cap(Polygon poly, Call c) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Point2D pt : poly.points()) {
            minX = Math.min(minX, pt.x()); maxX = Math.max(maxX, pt.x());
            minZ = Math.min(minZ, pt.z()); maxZ = Math.max(maxZ, pt.z());
        }
        if (!poly.points().isEmpty()) Validation.boxVolume(minX, poly.minY(), minZ, maxX, poly.maxY(), maxZ, c.maxEditVolume);
        return poly;
    }

    private static ConvexHull cap(ConvexHull hull, Call c) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE,
                minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Vertex v : hull.vertices()) {
            minX = Math.min(minX, v.x()); maxX = Math.max(maxX, v.x());
            minY = Math.min(minY, v.y()); maxY = Math.max(maxY, v.y());
            minZ = Math.min(minZ, v.z()); maxZ = Math.max(maxZ, v.z());
        }
        if (!hull.vertices().isEmpty()) Validation.boxVolume(minX, minY, minZ, maxX, maxY, maxZ, c.maxEditVolume);
        return hull;
    }

    /**
     * Caps a box given by fractional bounds, rounded outward ({@code floor} the minimum, {@code ceil} the
     * maximum) so a non-integer radius never shrinks it. Bounds beyond the {@code int} range saturate, which
     * {@link Validation#boxVolume} then rejects as out of range.
     */
    private static void capBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, Call c) {
        Validation.boxVolume((int) Math.floor(minX), (int) Math.floor(minY), (int) Math.floor(minZ),
                (int) Math.ceil(maxX), (int) Math.ceil(maxY), (int) Math.ceil(maxZ), c.maxEditVolume);
    }

    // ---- parameter extraction and bounds ----

    private static Box box(Params p) {
        return new Box(p.integer("x1"), p.integer("y1"), p.integer("z1"), p.integer("x2"), p.integer("y2"), p.integer("z2"));
    }

    private static Cylinder cylinder(Params p) {
        return new Cylinder(p.integer("centerX"), p.integer("centerZ"), radius(p, "radiusX"), radius(p, "radiusZ"),
                p.integer("minY"), p.integer("maxY"));
    }

    private static Ellipsoid ellipsoid(Params p) {
        return new Ellipsoid(p.integer("centerX"), p.integer("centerY"), p.integer("centerZ"),
                radius(p, "radiusX"), radius(p, "radiusY"), radius(p, "radiusZ"));
    }

    private static Polygon polygon(Params p) {
        List<Point2D> points = new ArrayList<>();
        for (JsonElement e : shapePoints(p, "points")) {
            JsonObject o = entry(e, "points");
            points.add(new Point2D(entryInt(o, "x"), entryInt(o, "z")));
        }
        return new Polygon(points, p.integer("minY"), p.integer("maxY"));
    }

    private static ConvexHull convexHull(Params p) {
        List<Vertex> vertices = new ArrayList<>();
        for (JsonElement e : shapePoints(p, "vertices")) {
            JsonObject o = entry(e, "vertices");
            vertices.add(new Vertex(entryInt(o, "x"), entryInt(o, "y"), entryInt(o, "z")));
        }
        return new ConvexHull(vertices);
    }

    private static JsonArray shapePoints(Params p, String name) {
        if (!p.has(name)) throw ApiException.badRequest("Missing parameter: " + name);
        JsonElement e = p.raw().get(name);
        if (!e.isJsonArray()) throw ApiException.badRequest("Parameter '" + name + "' must be an array");
        JsonArray a = e.getAsJsonArray();
        if (a.size() > MAX_SHAPE_POINTS)
            throw ApiException.badRequest("'" + name + "' has " + a.size() + " entries; at most " + MAX_SHAPE_POINTS + " are allowed");
        return a;
    }

    private static JsonObject entry(JsonElement e, String arrayName) {
        if (!e.isJsonObject()) throw ApiException.badRequest("Each entry of '" + arrayName + "' must be an object");
        return e.getAsJsonObject();
    }

    /** Same integer reading as a top-level {@code integer} parameter ({@link Params#integer}), and the same
     *  type check {@code ApiServer} applies to one: a JSON number, not a numeric string. */
    private static int entryInt(JsonObject o, String field) {
        JsonElement v = o.get(field);
        if (!(v instanceof JsonPrimitive prim) || !prim.isNumber())
            throw ApiException.badRequest("Field '" + field + "' must be an integer");
        return prim.getAsNumber().intValue();
    }

    /** A JSON number can still be non-finite (e.g. {@code 1e400} reads as infinity). */
    private static double finite(Params p, String name) {
        double v = p.number(name);
        if (!Double.isFinite(v)) throw ApiException.badRequest("'" + name + "' must be a finite number");
        return v;
    }

    private static double finite(Params p, String name, double def) {
        return p.has(name) ? finite(p, name) : def;
    }

    private static double radius(Params p, String name) {
        double r = finite(p, name);
        if (r < 0) throw ApiException.badRequest("'" + name + "' must not be negative");
        return r;
    }

    /** Zero would collapse the clipboard (a singular transform); WorldEdit's own {@code //flip} uses only ±1. */
    private static double scale(Params p, String name) {
        double s = finite(p, name);
        if (s == 0 || Math.abs(s) > MAX_SCALE_FACTOR)
            throw ApiException.badRequest("'" + name + "' must be non-zero and between -" + MAX_SCALE_FACTOR + " and " + MAX_SCALE_FACTOR);
        return s;
    }

    private static String pattern(Params p) { return worldEditInput(p, "pattern"); }
    private static String mask(Params p) { return worldEditInput(p, "mask"); }

    /** A pattern or mask in WorldEdit's own syntax: 1..{@link #MAX_PATTERN_LENGTH} characters, no control characters. */
    private static String worldEditInput(Params p, String name) {
        String s = p.string(name);
        if (s.isEmpty() || s.length() > MAX_PATTERN_LENGTH)
            throw ApiException.badRequest("'" + name + "' must be 1-" + MAX_PATTERN_LENGTH + " characters");
        for (int i = 0; i < s.length(); i++)
            if (s.charAt(i) < 0x20 || s.charAt(i) == 0x7f) throw ApiException.badRequest("'" + name + "' contains a control character");
        return s;
    }
}

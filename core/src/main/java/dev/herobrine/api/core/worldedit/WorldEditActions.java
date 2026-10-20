package dev.herobrine.api.core.worldedit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dispatches a namespaced {@code worldedit.*} action/resource name (see {@link
 * WorldEditSchema#OPERATIONS}, MINECRAFT-474) to the matching {@link WorldEditAdapter} method,
 * translating {@link Params} into the adapter's typed parameters/records. Pure and platform-independent
 * - {@code actor} is an already-constructed opaque WorldEdit actor the caller hands in (the neoforge
 * module resolves a real one from a {@code ServerPlayer}; tests hand in a {@code FakeActor}). One
 * {@code case} per {@link WorldEditSchema#OPERATIONS} entry - {@link #dispatchTest()} (see the test
 * class) asserts every entry has one, so nothing in the inventoried surface is silently dropped.
 *
 * <p>Schematic file resolution is deliberately excluded from this class and left to {@link
 * SchematicFiles}: only the platform module knows the real schematics directory and can confine a
 * request to it (see MINECRAFT-476's "keep schematic filesystem access confined" requirement) - this
 * class never touches a {@code File} it did not get back from that confinement check.
 *
 * <p>Every region/shape write operation is capped by {@code maxEditVolume} via its own bounding box
 * (see {@link #capped}) - the same "bound inputs using existing conventions" rule the Baritone bindings
 * already apply to box edits (see {@code BaritoneBindings#box}), generalized to every shape this
 * adapter supports, using a bounding box rather than the shape's exact (generally smaller) volume so
 * the cap never under-restricts.
 */
public final class WorldEditActions {
    private WorldEditActions() {}

    /** Resolves a schematic file name to a real, already-confined {@link File}; see the class Javadoc. */
    public interface SchematicFiles {
        File forSave(String name);
        File forLoad(String name);
    }

    public static Object dispatch(WorldEditAdapter a, String action, Object actor, Params p,
            long maxEditVolume, SchematicFiles schematics) {
        return switch (action) {
            case "worldedit.set_blocks" -> changed(a.setBlocks(actor, capped(box(p), maxEditVolume), pattern(p)));
            case "worldedit.replace_blocks" -> changed(a.replaceBlocks(actor, capped(box(p), maxEditVolume), mask(p), pattern(p)));
            case "worldedit.undo" -> Map.of("undone", a.undo(actor));
            case "worldedit.redo" -> Map.of("redone", a.redo(actor));
            case "worldedit.copy" -> { a.copy(actor, capped(box(p), maxEditVolume)); yield Map.of("copied", true); }
            case "worldedit.paste" -> { doPaste(a, actor, p); yield Map.of("pasted", true); }
            case "worldedit.save_schematic" -> { a.saveSchematic(actor, schematics.forSave(p.string("file"))); yield Map.of("saved", true); }
            case "worldedit.load_schematic" -> { a.loadSchematic(actor, schematics.forLoad(p.string("file"))); yield Map.of("loaded", true); }
            case "worldedit.selection" -> a.selection(actor);
            case "worldedit.rotate_clipboard" -> {
                a.rotateClipboard(actor, p.number("degreesY"), p.number("degreesX", 0), p.number("degreesZ", 0));
                yield Map.of("rotated", true);
            }
            case "worldedit.scale_clipboard" -> {
                a.scaleClipboard(actor, p.number("sx"), p.number("sy"), p.number("sz"));
                yield Map.of("scaled", true);
            }
            case "worldedit.set_blocks_cylinder" -> changed(a.setBlocks(actor, capped(cylinder(p), maxEditVolume), pattern(p)));
            case "worldedit.replace_blocks_cylinder" -> changed(a.replaceBlocks(actor, capped(cylinder(p), maxEditVolume), mask(p), pattern(p)));
            case "worldedit.cylinder_selection" -> a.cylinderSelection(actor);
            case "worldedit.make_sphere" -> changed(makeSphere(a, actor, p, maxEditVolume));
            case "worldedit.make_cylinder" -> changed(makeCylinder(a, actor, p, maxEditVolume));
            case "worldedit.make_pyramid" -> changed(makePyramid(a, actor, p, maxEditVolume));
            case "worldedit.hollow_out_box" -> changed(a.hollowOutBox(actor, capped(box(p), maxEditVolume), p.integer("thickness"), pattern(p)));
            case "worldedit.copy_cylinder" -> { a.copy(actor, capped(cylinder(p), maxEditVolume)); yield Map.of("copied", true); }
            case "worldedit.set_blocks_polygon" -> changed(a.setBlocks(actor, capped(polygon(p), maxEditVolume), pattern(p)));
            case "worldedit.replace_blocks_polygon" -> changed(a.replaceBlocks(actor, capped(polygon(p), maxEditVolume), mask(p), pattern(p)));
            case "worldedit.copy_polygon" -> { a.copy(actor, capped(polygon(p), maxEditVolume)); yield Map.of("copied", true); }
            case "worldedit.polygon_selection" -> a.polygonSelection(actor);
            case "worldedit.set_blocks_ellipsoid" -> changed(a.setBlocks(actor, capped(ellipsoid(p), maxEditVolume), pattern(p)));
            case "worldedit.replace_blocks_ellipsoid" -> changed(a.replaceBlocks(actor, capped(ellipsoid(p), maxEditVolume), mask(p), pattern(p)));
            case "worldedit.copy_ellipsoid" -> { a.copy(actor, capped(ellipsoid(p), maxEditVolume)); yield Map.of("copied", true); }
            case "worldedit.ellipsoid_selection" -> a.ellipsoidSelection(actor);
            case "worldedit.set_blocks_convex_hull" -> changed(a.setBlocks(actor, capped(convexHull(p), maxEditVolume), pattern(p)));
            case "worldedit.replace_blocks_convex_hull" -> changed(a.replaceBlocks(actor, capped(convexHull(p), maxEditVolume), mask(p), pattern(p)));
            case "worldedit.copy_convex_hull" -> { a.copy(actor, capped(convexHull(p), maxEditVolume)); yield Map.of("copied", true); }
            case "worldedit.convex_hull_selection" -> a.convexHullSelection(actor);
            default -> throw ApiException.notFound("Unknown worldedit action " + action);
        };
    }

    /** Every {@link WorldEditSchema#OPERATIONS} name this dispatcher recognizes - used by the test to
     *  assert none are silently dropped, without calling into a real/fake adapter for each one. */
    static boolean knows(String action) {
        return switch (action) {
            case "worldedit.set_blocks", "worldedit.replace_blocks", "worldedit.undo", "worldedit.redo",
                 "worldedit.copy", "worldedit.paste", "worldedit.save_schematic", "worldedit.load_schematic",
                 "worldedit.selection", "worldedit.rotate_clipboard", "worldedit.scale_clipboard",
                 "worldedit.set_blocks_cylinder", "worldedit.replace_blocks_cylinder", "worldedit.cylinder_selection",
                 "worldedit.make_sphere", "worldedit.make_cylinder", "worldedit.make_pyramid", "worldedit.hollow_out_box",
                 "worldedit.copy_cylinder", "worldedit.set_blocks_polygon", "worldedit.replace_blocks_polygon",
                 "worldedit.copy_polygon", "worldedit.polygon_selection", "worldedit.set_blocks_ellipsoid",
                 "worldedit.replace_blocks_ellipsoid", "worldedit.copy_ellipsoid", "worldedit.ellipsoid_selection",
                 "worldedit.set_blocks_convex_hull", "worldedit.replace_blocks_convex_hull",
                 "worldedit.copy_convex_hull", "worldedit.convex_hull_selection" -> true;
            default -> false;
        };
    }

    private static Map<String, Object> changed(int n) { return Map.of("changed", n); }

    private static void doPaste(WorldEditAdapter a, Object actor, Params p) {
        int x = p.integer("x"), y = p.integer("y"), z = p.integer("z");
        if (!p.has("ignoreAirBlocks") && !p.has("ignoreStructureVoidBlocks") && !p.has("copyEntities") && !p.has("copyBiomes")) {
            a.paste(actor, x, y, z);
        } else {
            a.paste(actor, x, y, z,
                    p.bool("ignoreAirBlocks", false), p.bool("ignoreStructureVoidBlocks", false),
                    p.bool("copyEntities", true), p.bool("copyBiomes", false));
        }
    }

    private static int makeSphere(WorldEditAdapter a, Object actor, Params p, long maxEditVolume) {
        int x = p.integer("x"), y = p.integer("y"), z = p.integer("z");
        double rx = p.number("radiusX"), ry = p.number("radiusY"), rz = p.number("radiusZ");
        capVolume(x, y, z, rx, ry, rz, maxEditVolume);
        return a.makeSphere(actor, x, y, z, rx, ry, rz, p.bool("filled", false), pattern(p));
    }

    private static int makeCylinder(WorldEditAdapter a, Object actor, Params p, long maxEditVolume) {
        int x = p.integer("x"), y = p.integer("y"), z = p.integer("z");
        double rx = p.number("radiusX"), rz = p.number("radiusZ");
        int height = p.integer("height");
        int y1 = height >= 0 ? y : y + height, y2 = height >= 0 ? y + height : y;
        capBoundingBox(x - rx, y1, z - rz, x + rx, y2, z + rz, maxEditVolume);
        return a.makeCylinder(actor, x, y, z, rx, rz, height, p.bool("filled", false), pattern(p));
    }

    private static int makePyramid(WorldEditAdapter a, Object actor, Params p, long maxEditVolume) {
        int x = p.integer("x"), y = p.integer("y"), z = p.integer("z");
        int size = p.integer("size");
        capBoundingBox(x - size, y, z - size, x + size, y + size, z + size, maxEditVolume);
        return a.makePyramid(actor, x, y, z, size, p.bool("filled", false), pattern(p));
    }

    private static void capVolume(int x, int y, int z, double rx, double ry, double rz, long maxEditVolume) {
        capBoundingBox(x - rx, y - ry, z - rz, x + rx, y + ry, z + rz, maxEditVolume);
    }

    /**
     * {@code (min..max)} on every axis, each pair rounded <b>outward</b> - {@code floor} the minimum,
     * {@code ceil} the maximum - so a fractional bound (e.g. a non-integer radius) is never rounded
     * inward into a smaller, under-restricting box.
     */
    private static void capBoundingBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, long maxEditVolume) {
        Validation.boxVolume((int) Math.floor(minX), (int) Math.floor(minY), (int) Math.floor(minZ),
                (int) Math.ceil(maxX), (int) Math.ceil(maxY), (int) Math.ceil(maxZ), maxEditVolume);
    }

    // ---- bounding boxes per shape: conservative (never smaller than the shape's real footprint), so
    // the cap never under-restricts - see the class Javadoc. ----

    private static Box capped(Box box, long maxEditVolume) {
        Validation.boxVolume(box.x1(), box.y1(), box.z1(), box.x2(), box.y2(), box.z2(), maxEditVolume);
        return box;
    }

    private static Cylinder capped(Cylinder c, long maxEditVolume) {
        capBoundingBox(c.centerX() - c.radiusX(), c.minY(), c.centerZ() - c.radiusZ(),
                c.centerX() + c.radiusX(), c.maxY(), c.centerZ() + c.radiusZ(), maxEditVolume);
        return c;
    }

    private static Polygon capped(Polygon poly, long maxEditVolume) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Point2D p : poly.points()) {
            minX = Math.min(minX, p.x()); maxX = Math.max(maxX, p.x());
            minZ = Math.min(minZ, p.z()); maxZ = Math.max(maxZ, p.z());
        }
        capBoundingBox(minX, poly.minY(), minZ, maxX, poly.maxY(), maxZ, maxEditVolume);
        return poly;
    }

    private static Ellipsoid capped(Ellipsoid e, long maxEditVolume) {
        capBoundingBox(e.centerX() - e.radiusX(), e.centerY() - e.radiusY(), e.centerZ() - e.radiusZ(),
                e.centerX() + e.radiusX(), e.centerY() + e.radiusY(), e.centerZ() + e.radiusZ(), maxEditVolume);
        return e;
    }

    private static ConvexHull capped(ConvexHull hull, long maxEditVolume) {
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE,
                minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (Vertex v : hull.vertices()) {
            minX = Math.min(minX, v.x()); maxX = Math.max(maxX, v.x());
            minY = Math.min(minY, v.y()); maxY = Math.max(maxY, v.y());
            minZ = Math.min(minZ, v.z()); maxZ = Math.max(maxZ, v.z());
        }
        capBoundingBox(minX, minY, minZ, maxX, maxY, maxZ, maxEditVolume);
        return hull;
    }

    // ---- param extraction ----

    private static Box box(Params p) {
        return new Box(p.integer("x1"), p.integer("y1"), p.integer("z1"), p.integer("x2"), p.integer("y2"), p.integer("z2"));
    }

    private static Cylinder cylinder(Params p) {
        return new Cylinder(p.integer("centerX"), p.integer("centerZ"), p.number("radiusX"), p.number("radiusZ"),
                p.integer("minY"), p.integer("maxY"));
    }

    private static Ellipsoid ellipsoid(Params p) {
        return new Ellipsoid(p.integer("centerX"), p.integer("centerY"), p.integer("centerZ"),
                p.number("radiusX"), p.number("radiusY"), p.number("radiusZ"));
    }

    private static Polygon polygon(Params p) {
        List<Point2D> points = new ArrayList<>();
        for (JsonElement e : array(p, "points")) {
            JsonObject o = asObject(e, "points");
            points.add(new Point2D(reqInt(o, "x"), reqInt(o, "z")));
        }
        return new Polygon(points, p.integer("minY"), p.integer("maxY"));
    }

    private static ConvexHull convexHull(Params p) {
        List<Vertex> vertices = new ArrayList<>();
        for (JsonElement e : array(p, "vertices")) {
            JsonObject o = asObject(e, "vertices");
            vertices.add(new Vertex(reqInt(o, "x"), reqInt(o, "y"), reqInt(o, "z")));
        }
        return new ConvexHull(vertices);
    }

    private static JsonArray array(Params p, String name) {
        if (!p.has(name)) throw ApiException.badRequest("Missing parameter: " + name);
        JsonElement e = p.raw().get(name);
        if (!e.isJsonArray()) throw ApiException.badRequest("Parameter '" + name + "' must be an array");
        return e.getAsJsonArray();
    }

    private static JsonObject asObject(JsonElement e, String arrayName) {
        if (!e.isJsonObject()) throw ApiException.badRequest("Each entry of '" + arrayName + "' must be an object");
        return e.getAsJsonObject();
    }

    private static int reqInt(JsonObject o, String field) {
        if (!o.has(field) || !o.get(field).isJsonPrimitive())
            throw ApiException.badRequest("Missing or invalid field '" + field + "'");
        try { return o.get(field).getAsInt(); }
        catch (RuntimeException ex) { throw ApiException.badRequest("Field '" + field + "' must be an integer"); }
    }

    private static String pattern(Params p) { return p.string("pattern"); }
    private static String mask(Params p) { return p.string("mask"); }
}

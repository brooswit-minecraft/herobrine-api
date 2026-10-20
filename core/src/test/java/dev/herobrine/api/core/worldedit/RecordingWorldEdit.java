package dev.herobrine.api.core.worldedit;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A {@link WorldEditAdapter} that records each call with its typed arguments, so dispatcher tests can assert
 * exactly what reached the adapter (and that nothing did when a request is refused) without reflection.
 */
final class RecordingWorldEdit implements WorldEditAdapter {
    final List<String> calls = new ArrayList<>();
    /** Permission nodes the actor holds; null = all of them. */
    Set<String> permissions;
    long clipboardVolume = 8;
    Path schematicsDirectory;

    private int record(String call, int changed) { calls.add(call); return changed; }
    private void record(String call) { calls.add(call); }

    @Override public boolean installed() { return true; }
    @Override public String version() { return "7.3.8"; }
    @Override public boolean hasPermission(Object actor, String permission) { return permissions == null || permissions.contains(permission); }
    @Override public Path schematicsDirectory() { return schematicsDirectory; }
    @Override public long clipboardVolume(Object actor) { return clipboardVolume; }

    @Override public int setBlocks(Object actor, Box box, String pattern) { return record("setBlocks(" + box + "," + pattern + ")", 7); }
    @Override public int replaceBlocks(Object actor, Box box, String mask, String pattern) { return record("replaceBlocks(" + box + "," + mask + "," + pattern + ")", 3); }
    @Override public boolean undo(Object actor) { record("undo"); return true; }
    @Override public boolean redo(Object actor) { record("redo"); return false; }
    @Override public void copy(Object actor, Box box) { record("copy(" + box + ")"); }
    @Override public void paste(Object actor, int x, int y, int z) { record("paste(" + x + "," + y + "," + z + ")"); }
    @Override public void saveSchematic(Object actor, File file) { record("saveSchematic(" + file + ")"); }
    @Override public void loadSchematic(Object actor, File file) { record("loadSchematic(" + file + ")"); }
    @Override public Box selection(Object actor) { record("selection"); return new Box(1, 2, 3, 4, 5, 6); }
    @Override public int setBlocks(Object actor, Cylinder c, String pattern) { return record("setBlocks(" + c + "," + pattern + ")", 7); }
    @Override public int replaceBlocks(Object actor, Cylinder c, String mask, String pattern) { return record("replaceBlocks(" + c + "," + mask + "," + pattern + ")", 3); }
    @Override public Cylinder cylinderSelection(Object actor) { record("cylinderSelection"); return new Cylinder(0, 0, 1, 1, 0, 1); }
    @Override public void copy(Object actor, Cylinder c) { record("copy(" + c + ")"); }
    @Override public int setBlocks(Object actor, Polygon p, String pattern) { return record("setBlocks(" + p + "," + pattern + ")", 7); }
    @Override public int replaceBlocks(Object actor, Polygon p, String mask, String pattern) { return record("replaceBlocks(" + p + "," + mask + "," + pattern + ")", 3); }
    @Override public void copy(Object actor, Polygon p) { record("copy(" + p + ")"); }
    @Override public Polygon polygonSelection(Object actor) { record("polygonSelection"); return new Polygon(List.of(), 0, 0); }
    @Override public int setBlocks(Object actor, Ellipsoid e, String pattern) { return record("setBlocks(" + e + "," + pattern + ")", 7); }
    @Override public int replaceBlocks(Object actor, Ellipsoid e, String mask, String pattern) { return record("replaceBlocks(" + e + "," + mask + "," + pattern + ")", 3); }
    @Override public void copy(Object actor, Ellipsoid e) { record("copy(" + e + ")"); }
    @Override public Ellipsoid ellipsoidSelection(Object actor) { record("ellipsoidSelection"); return new Ellipsoid(0, 0, 0, 1, 1, 1); }
    @Override public int setBlocks(Object actor, ConvexHull h, String pattern) { return record("setBlocks(" + h + "," + pattern + ")", 7); }
    @Override public int replaceBlocks(Object actor, ConvexHull h, String mask, String pattern) { return record("replaceBlocks(" + h + "," + mask + "," + pattern + ")", 3); }
    @Override public void copy(Object actor, ConvexHull h) { record("copy(" + h + ")"); }
    @Override public ConvexHull convexHullSelection(Object actor) { record("convexHullSelection"); return new ConvexHull(List.of()); }
    @Override public void rotateClipboard(Object actor, double y, double x, double z) { record("rotateClipboard(" + y + "," + x + "," + z + ")"); }
    @Override public void scaleClipboard(Object actor, double sx, double sy, double sz) { record("scaleClipboard(" + sx + "," + sy + "," + sz + ")"); }
    @Override public int makeSphere(Object actor, int x, int y, int z, double rx, double ry, double rz, boolean filled, String pattern) {
        return record("makeSphere(" + x + "," + y + "," + z + "," + rx + "," + ry + "," + rz + "," + filled + "," + pattern + ")", 11);
    }
    @Override public int makeCylinder(Object actor, int x, int y, int z, double rx, double rz, int height, boolean filled, String pattern) {
        return record("makeCylinder(" + x + "," + y + "," + z + "," + rx + "," + rz + "," + height + "," + filled + "," + pattern + ")", 13);
    }
    @Override public int makePyramid(Object actor, int x, int y, int z, int size, boolean filled, String pattern) {
        return record("makePyramid(" + x + "," + y + "," + z + "," + size + "," + filled + "," + pattern + ")", 17);
    }
    @Override public int hollowOutBox(Object actor, Box box, int thickness, String pattern) {
        return record("hollowOutBox(" + box + "," + thickness + "," + pattern + ")", 5);
    }
    @Override public void paste(Object actor, int x, int y, int z, boolean air, boolean structureVoid, boolean entities, boolean biomes) {
        record("paste(" + x + "," + y + "," + z + "," + air + "," + structureVoid + "," + entities + "," + biomes + ")");
    }
}

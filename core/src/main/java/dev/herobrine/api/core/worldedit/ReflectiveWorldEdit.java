package dev.herobrine.api.core.worldedit;

import dev.herobrine.api.core.ApiException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * {@link WorldEditAdapter} that talks to WorldEdit's API by reflection, so WorldEdit stays an optional
 * runtime dependency and no WorldEdit class is loaded before {@link #installed()} is checked. Every class
 * and method name below was verified against the real WorldEdit 7.3.8 sources (the last {@code
 * worldedit-neoforge-mc1.21} build, which covers Minecraft/NeoForge 1.21.1 - see docs/COVERAGE.md); none
 * are guessed. WorldEdit's command dispatcher is deliberately never used - every call below maps directly
 * to the same WorldEdit Java API methods its own commands use (verified against {@code
 * command/ClipboardCommands.java} and {@code extension/platform/PlatformCommandManager.java} in the
 * 7.3.8 sources), including the {@code LocalSession#remember} bookkeeping that WorldEdit's own command
 * dispatcher performs after every edit so undo/redo keep working.
 */
public final class ReflectiveWorldEdit implements WorldEditAdapter {
    private static final String WORLDEDIT = "com.sk89q.worldedit.WorldEdit";
    private static final String BLOCK_VECTOR3 = "com.sk89q.worldedit.math.BlockVector3";
    private static final String CUBOID_REGION = "com.sk89q.worldedit.regions.CuboidRegion";
    private static final String CYLINDER_REGION = "com.sk89q.worldedit.regions.CylinderRegion";
    private static final String POLYGONAL_2D_REGION = "com.sk89q.worldedit.regions.Polygonal2DRegion";
    private static final String ELLIPSOID_REGION = "com.sk89q.worldedit.regions.EllipsoidRegion";
    private static final String CONVEX_POLYHEDRAL_REGION = "com.sk89q.worldedit.regions.ConvexPolyhedralRegion";
    private static final String BLOCK_VECTOR2 = "com.sk89q.worldedit.math.BlockVector2";
    private static final String VECTOR2 = "com.sk89q.worldedit.math.Vector2";
    private static final String VECTOR3 = "com.sk89q.worldedit.math.Vector3";
    private static final String WORLD_CLASS = "com.sk89q.worldedit.world.World";
    private static final String PARSER_CONTEXT = "com.sk89q.worldedit.extension.input.ParserContext";
    private static final String CLIPBOARD_HOLDER = "com.sk89q.worldedit.session.ClipboardHolder";
    private static final String BLOCK_ARRAY_CLIPBOARD = "com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard";
    private static final String CLIPBOARD_FORMATS = "com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats";
    private static final String FORWARD_EXTENT_COPY = "com.sk89q.worldedit.function.operation.ForwardExtentCopy";
    private static final String OPERATIONS = "com.sk89q.worldedit.function.operation.Operations";
    private static final String AFFINE_TRANSFORM = "com.sk89q.worldedit.math.transform.AffineTransform";
    private static final String PLAYER = "com.sk89q.worldedit.entity.Player";
    private static final String LOCATABLE = "com.sk89q.worldedit.extension.platform.Locatable";

    // Checked exceptions worth translating into a specific ApiException rather than a generic 500.
    private static final String INCOMPLETE_REGION_EXCEPTION = "com.sk89q.worldedit.IncompleteRegionException";
    private static final String EMPTY_CLIPBOARD_EXCEPTION = "com.sk89q.worldedit.EmptyClipboardException";
    private static final String MAX_CHANGED_BLOCKS_EXCEPTION = "com.sk89q.worldedit.MaxChangedBlocksException";
    private static final String MAX_RADIUS_EXCEPTION = "com.sk89q.worldedit.MaxRadiusException";
    private static final String INPUT_PARSE_EXCEPTION = "com.sk89q.worldedit.extension.input.InputParseException";

    /**
     * What WorldEdit's NeoForge world throws once the {@code ServerLevel} behind it has been unloaded: a plain
     * {@code RuntimeException} with this exact message (verified: {@code NeoForgeWorld.getWorld()},
     * {@code NeoForgeWorld.java:165-172} in worldedit-neoforge-mc1.21 7.3.8), reported as 409 rather than 500.
     */
    private static final String WORLD_UNLOADED_MESSAGE = "The reference to the world was lost";

    /** The exact WorldEdit release this adapter was verified against (see class Javadoc) - not a branch:
     *  every signature here was checked against 7.3.8 specifically, not against 7.3.9+ or any other 7.3.x. */
    private static final String SUPPORTED_VERSION = "7.3.8";

    /**
     * What {@code WorldEdit.getVersion()} returns when {@code WorldEditManifest} cannot read its own jar's
     * manifest (verified: {@code WorldEditManifest.java:61}) - it only reads a {@code jar:} class URL
     * ({@code WorldEditManifest.java:70}), and a mod loaded through NeoForge's module class loader is served
     * from a {@code union:} URL instead.
     */
    private static final String UNKNOWN_VERSION = "(unknown)";

    private final ClassLoader loader;
    private final Supplier<String> declaredVersion;

    public ReflectiveWorldEdit(ClassLoader loader) {
        this(loader, () -> null);
    }

    /**
     * @param declaredVersion the version the installed WorldEdit declares to the mod loader (e.g. its
     *        {@code mods.toml} version "7.3.8+1379-7d32b45"), used only when {@code WorldEdit.getVersion()}
     *        cannot tell; may return null
     */
    public ReflectiveWorldEdit(ClassLoader loader, Supplier<String> declaredVersion) {
        this.loader = loader;
        this.declaredVersion = declaredVersion;
    }

    // ---- availability ----

    @Override public boolean installed() {
        try { Class.forName(WORLDEDIT, false, loader); return true; }
        catch (ClassNotFoundException | LinkageError e) { return false; }
    }

    @Override public String version() {
        if (!installed()) return null;
        Object v = invokeStatic(load(WORLDEDIT), "getVersion");
        String version = v == null ? null : v.toString();
        return version == null || version.equals(UNKNOWN_VERSION) ? declaredVersion.get() : version;
    }

    /** @return the WorldEdit instance, after checking the installed build is the verified-compatible version. */
    private Object worldEdit() {
        if (!installed()) throw ApiException.unavailable("WorldEdit is not installed");
        String v = version();
        if (!isSupportedVersion(v)) {
            throw new ApiException(501, "UNSUPPORTED",
                    "Installed WorldEdit " + v + " is not the verified " + SUPPORTED_VERSION
                            + " (worldedit-neoforge-mc1.21, NeoForge/Minecraft 1.21.1) - every other 7.3.x "
                            + "release, verified or not, is also rejected");
        }
        return invokeStatic(load(WORLDEDIT), "getInstance");
    }

    /**
     * Exact match on {@link #SUPPORTED_VERSION}, allowing WorldEdit's own build-metadata suffix (e.g.
     * {@code "7.3.8+1379-7d32b45"}, verified: {@code WorldEdit.getVersion()} returns the Gradle-generated
     * version string with a {@code +build-hash} suffix). {@code startsWith("7.3.")} - the first draft of
     * this check - would also accept 7.3.9, 7.3.80, or any other 7.3.x release that was never verified.
     */
    private static boolean isSupportedVersion(String v) {
        return v != null && (v.equals(SUPPORTED_VERSION) || v.startsWith(SUPPORTED_VERSION + "+"));
    }

    private Object session(Object we, Object actor) {
        return invoke(invoke(we, "getSessionManager"), "get", actor);
    }

    // ---- actor context ----

    @Override public boolean hasPermission(Object actor, String permission) {
        worldEdit();
        return (Boolean) invoke(actor, "hasPermission", permission);
    }

    /** {@code WorldEdit.getWorkingDirectoryPath(getConfiguration().saveDir)} - verified the exact expression
     *  {@code SchematicCommands.save}/{@code load} use (SchematicCommands.java:117,164); {@code saveDir} is a
     *  public field of {@code LocalConfiguration}, not a getter. */
    @Override public Path schematicsDirectory() {
        Object we = worldEdit();
        Object config = invoke(we, "getConfiguration");
        Object saveDir;
        try {
            saveDir = config.getClass().getField("saveDir").get(config);
        } catch (ReflectiveOperationException e) {
            throw new ApiException(501, "UNSUPPORTED", "Installed WorldEdit has no LocalConfiguration.saveDir");
        }
        if (!(saveDir instanceof String dir)) throw new ApiException(501, "UNSUPPORTED", "WorldEdit's saveDir is not set");
        return ((Path) invoke(we, "getWorkingDirectoryPath", dir)).toAbsolutePath().normalize();
    }

    @Override public long clipboardVolume(Object actor) {
        Object we = worldEdit();
        Object holder = invokeTranslating(session(we, actor), "getClipboard", EMPTY_CLIPBOARD_EXCEPTION, "No clipboard is set");
        Object region = invoke(invoke(holder, "getClipboard"), "getRegion");
        return ((Number) invoke(region, "getVolume")).longValue();
    }

    /**
     * The world the actor's edits and selection apply to, picked exactly as WorldEdit's own commands inject
     * it (verified: {@code PlatformCommandManager.java:263-282}): the session's world override if it has one,
     * else the actor's own world as a {@code Locatable}.
     */
    private Object editingWorld(Object session, Object actor) {
        Object world = worldOf(session, actor);
        if (world == null) throw ApiException.badRequest("The actor is not in a world WorldEdit can edit");
        return world;
    }

    /** {@link #editingWorld}, or null when the actor has none. */
    private Object worldOf(Object session, Object actor) {
        if ((Boolean) invoke(session, "hasWorldOverride")) return invoke(session, "getWorldOverride");
        if (load(LOCATABLE).isInstance(actor)) {
            Object extent = invoke(actor, "getExtent");
            if (load(WORLD_CLASS).isInstance(extent)) return extent;
        }
        return null;
    }

    /**
     * {@code LocalSession.createEditSession(Actor)}, never {@code WorldEdit.newEditSession(Actor)}: the
     * session-owned call honors the actor's configured block change limit, mask, world override and block
     * bag the way WorldEdit's own commands do (verified in {@code LocalSession.java:1104-1127}); the bare
     * builder behind {@code newEditSession} does not.
     */
    private Object newEditSession(Object session, Object actor) {
        return invoke(session, "createEditSession", actor);
    }

    // ---- edits ----

    /**
     * Real WorldEdit applies changes one block at a time through an extent chain where
     * {@code ChangeSetExtent} (records into undo history) is wrapped *inside*
     * {@code BlockChangeLimiter} (verified: {@code BlockChangeLimiter.java} checks {@code count >= limit}
     * and only then delegates to the wrapped, history-recording extent) - so a change that exceeds the
     * limit still leaves every block applied up to that point already recorded. WorldEdit's own command
     * dispatcher therefore calls {@code session.remember(editSession)} in a {@code finally}
     * ({@code PlatformCommandManager.java:510-513}), unconditionally, not only on success. {@code remember}
     * itself is a no-op for an edit session with no recorded changes, so calling it unconditionally here is
     * always safe - the opposite (remembering only on success, the first draft of this adapter) silently
     * discards a partially-applied, otherwise-unrecoverable edit.
     */
    private void rememberAndClose(Object session, Object editSession) {
        try { invoke(session, "remember", editSession); }
        finally { invoke(editSession, "close"); }
    }

    @Override public int setBlocks(Object actor, Box box, String pattern) {
        return setBlocksOnRegion(actor, region(box), pattern);
    }

    @Override public int replaceBlocks(Object actor, Box box, String mask, String pattern) {
        return replaceBlocksOnRegion(actor, region(box), mask, pattern);
    }

    /** Shared by every region shape's {@code setBlocks} overload (verified: {@code EditSession.setBlocks}
     *  takes the {@code Region} interface, so any real region implementation works identically here). */
    private int setBlocksOnRegion(Object actor, Object region, String pattern) {
        Object we = worldEdit();
        Object session = session(we, actor);
        Object editSession = newEditSession(session, actor);
        try {
            Object patternObj = parsePattern(we, parserContext(session, actor, editSession), pattern);
            return (Integer) limited(() -> invoke(editSession, "setBlocks", region, patternObj));
        } finally {
            rememberAndClose(session, editSession);
        }
    }

    /** Shared by every region shape's {@code replaceBlocks} overload. */
    private int replaceBlocksOnRegion(Object actor, Object region, String mask, String pattern) {
        Object we = worldEdit();
        Object session = session(we, actor);
        Object editSession = newEditSession(session, actor);
        try {
            // A fresh context per argument, as FactoryConverter creates one per converted argument.
            Object maskObj = parseMask(we, parserContext(session, actor, editSession), mask);
            Object patternObj = parsePattern(we, parserContext(session, actor, editSession), pattern);
            return (Integer) limited(() -> invoke(editSession, "replaceBlocks", region, maskObj, patternObj));
        } finally {
            rememberAndClose(session, editSession);
        }
    }

    // ---- history ----

    /**
     * Mirrors WorldEdit's own {@code //undo} (verified: {@code HistoryCommands.java:79-85}): the session's
     * block bag for a player actor ({@code LocalSession.getBlockBag(Player)}, null unless the session uses
     * the player's inventory), then {@code WorldEdit.flushBlockBag} on whatever was undone.
     */
    @Override public boolean undo(Object actor) {
        return replayHistory(actor, "undo");
    }

    /** Mirrors WorldEdit's own {@code //redo} the same way ({@code HistoryCommands.java:118-124}). */
    @Override public boolean redo(Object actor) {
        return replayHistory(actor, "redo");
    }

    private boolean replayHistory(Object actor, String direction) {
        Object we = worldEdit();
        Object session = session(we, actor);
        Object blockBag = load(PLAYER).isInstance(actor) ? invoke(session, "getBlockBag", actor) : null;
        Object replayed = invoke(session, direction, blockBag, actor);
        if (replayed == null) return false;
        invoke(we, "flushBlockBag", actor, replayed);
        return true;
    }

    // ---- clipboard / schematics ----

    /** Origin is the box's minimum corner: a fixed, typed input, not WorldEdit's placement-position heuristic. */
    @Override public void copy(Object actor, Box box) {
        copyRegion(actor, region(box));
    }

    @Override public void copy(Object actor, Cylinder cylinder) {
        copyRegion(actor, cylinderRegion(cylinder));
    }

    /** Shared by every region shape's {@code copy}. Origin is always the region's own bounding-box minimum
     *  corner - a fixed, typed input, not WorldEdit's placement-position heuristic (see {@code copy(Box)}). */
    private void copyRegion(Object actor, Object region) {
        Object we = worldEdit();
        Object session = session(we, actor);
        Object clipboard = create(BLOCK_ARRAY_CLIPBOARD, region);
        Object minPoint = invoke(region, "getMinimumPoint");
        invoke(clipboard, "setOrigin", minPoint);
        Object editSession = newEditSession(session, actor);
        try {
            Object copyOp = create(FORWARD_EXTENT_COPY, editSession, region, clipboard, minPoint);
            invokeStatic(load(OPERATIONS), "completeLegacy", copyOp);
            invoke(session, "setClipboard", create(CLIPBOARD_HOLDER, clipboard));
        } finally {
            // copy only reads the world through editSession (the clipboard is the write destination), so
            // this is normally a no-op remember - kept for parity with every other call site and with
            // WorldEdit's own dispatcher, which remembers whichever EditSession a command used, unconditionally.
            rememberAndClose(session, editSession);
        }
    }

    /** WorldEdit's own {@code PasteBuilder} field defaults (verified: {@code session/PasteBuilder.java:47-51}). */
    @Override public void paste(Object actor, int x, int y, int z) {
        paste(actor, x, y, z, false, false, true, false);
    }

    @Override public void paste(Object actor, int x, int y, int z,
            boolean ignoreAirBlocks, boolean ignoreStructureVoidBlocks, boolean copyEntities, boolean copyBiomes) {
        Object we = worldEdit();
        Object session = session(we, actor);
        Object holder = invokeTranslating(session, "getClipboard", EMPTY_CLIPBOARD_EXCEPTION, "No clipboard is set");
        Object editSession = newEditSession(session, actor);
        try {
            Object to = blockVector3(x, y, z);
            Object pasteBuilder = invoke(holder, "createPaste", editSession);
            pasteBuilder = invoke(pasteBuilder, "to", to);
            pasteBuilder = invoke(pasteBuilder, "ignoreAirBlocks", ignoreAirBlocks);
            pasteBuilder = invoke(pasteBuilder, "ignoreStructureVoidBlocks", ignoreStructureVoidBlocks);
            pasteBuilder = invoke(pasteBuilder, "copyEntities", copyEntities);
            pasteBuilder = invoke(pasteBuilder, "copyBiomes", copyBiomes);
            Object operation = invoke(pasteBuilder, "build");
            limited(() -> invokeStatic(load(OPERATIONS), "completeLegacy", operation));
        } finally {
            rememberAndClose(session, editSession);
        }
    }

    @Override public void saveSchematic(Object actor, File file) {
        Object we = worldEdit();
        Object format = resolveFormat(file);
        Object session = session(we, actor);
        Object holder = invokeTranslating(session, "getClipboard", EMPTY_CLIPBOARD_EXCEPTION, "No clipboard is set");
        // Bake in any pending rotate/scale exactly as //schem save does (verified:
        // SchematicCommands.SchematicOutputTask.writeToOutputStream, SchematicCommands.java:372-375).
        Object clipboard = invoke(invoke(holder, "getClipboard"), "transform", invoke(holder, "getTransform"));
        try (FileOutputStream out = new FileOutputStream(file)) {
            Object writer = invoke(format, "getWriter", out);
            try { invoke(writer, "write", clipboard); } finally { invoke(writer, "close"); }
        } catch (IOException e) {
            throw new ApiException(500, "ACTION_FAILED", "Cannot write schematic " + file.getName() + ": " + e);
        }
    }

    @Override public void loadSchematic(Object actor, File file) {
        Object we = worldEdit();
        Object format = resolveFormat(file);
        Object clipboard;
        try (FileInputStream in = new FileInputStream(file)) {
            Object reader = invoke(format, "getReader", in);
            try { clipboard = invoke(reader, "read"); } finally { invoke(reader, "close"); }
        } catch (IOException e) {
            throw new ApiException(500, "ACTION_FAILED", "Cannot read schematic " + file.getName() + ": " + e);
        }
        invoke(session(we, actor), "setClipboard", create(CLIPBOARD_HOLDER, clipboard));
    }

    /**
     * Resolves a schematic file's format. Tries content detection ({@code ClipboardFormats.findByFile},
     * which opens and sniffs the file - verified in {@code ClipboardFormats.java:93} /
     * {@code ClipboardFormat.java:68}) only when the file already exists; a brand-new save target cannot
     * have content to sniff, so falls back to {@code ClipboardFormats.findByAlias} on the file's extension
     * (verified: {@code BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC} registers {@code "schem"} as both its
     * primary extension and an alias - {@code BuiltInClipboardFormat.java:135}). Using {@code findByFile}
     * alone for saves was wrong: it always returned {@code null} for a file that did not yet exist.
     */
    private Object resolveFormat(File file) {
        Object format = file.isFile() ? invokeStatic(load(CLIPBOARD_FORMATS), "findByFile", file) : null;
        if (format == null) {
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
                format = invokeStatic(load(CLIPBOARD_FORMATS), "findByAlias", ext);
            }
        }
        if (format == null) throw ApiException.badRequest("Unrecognized schematic format for file " + file.getName());
        return format;
    }

    // ---- selection ----

    @Override public Box selection(Object actor) {
        Object we = worldEdit();
        Object region = selectedRegion(we, actor);
        Object min = invoke(region, "getMinimumPoint");
        Object max = invoke(region, "getMaximumPoint");
        return new Box((Integer) invoke(min, "getX"), (Integer) invoke(min, "getY"), (Integer) invoke(min, "getZ"),
                (Integer) invoke(max, "getX"), (Integer) invoke(max, "getY"), (Integer) invoke(max, "getZ"));
    }

    @Override public Cylinder cylinderSelection(Object actor) {
        Object we = worldEdit();
        Object region = selectedRegion(we, actor);
        if (!load(CYLINDER_REGION).isInstance(region)) {
            throw ApiException.badRequest("Current selection is not a cylinder");
        }
        Object center = invoke(region, "getCenter");
        Object radius = invoke(region, "getRadius");
        int centerX = (int) Math.round((Double) invoke(center, "getX"));
        int centerZ = (int) Math.round((Double) invoke(center, "getZ"));
        double radiusX = (Double) invoke(radius, "getX");
        double radiusZ = (Double) invoke(radius, "getZ");
        int minY = (Integer) invoke(region, "getMinimumY");
        int maxY = (Integer) invoke(region, "getMaximumY");
        return new Cylinder(centerX, centerZ, radiusX, radiusZ, minY, maxY);
    }

    /**
     * The actor's selection in the world it is editing ({@link #editingWorld}), as WorldEdit's own commands
     * inject {@code @Selection Region} ({@code PlatformCommandManager.java:238-251}). A selection made in
     * another world is not this world's selection - {@code LocalSession.getSelection(World)} throws
     * {@code IncompleteRegionException} for it - so it is reported missing rather than as if it were current.
     */
    private Object selectedRegion(Object we, Object actor) {
        Object session = session(we, actor);
        return invokeTranslating(session, "getSelection", INCOMPLETE_REGION_EXCEPTION,
                "No complete selection in the world being edited (a selection made in another world does not count)",
                editingWorld(session, actor));
    }

    // ---- cylinder edits (same mechanics as the Box overloads, different region construction) ----

    @Override public int setBlocks(Object actor, Cylinder cylinder, String pattern) {
        return setBlocksOnRegion(actor, cylinderRegion(cylinder), pattern);
    }

    @Override public int replaceBlocks(Object actor, Cylinder cylinder, String mask, String pattern) {
        return replaceBlocksOnRegion(actor, cylinderRegion(cylinder), mask, pattern);
    }

    /** Builds a {@code CylinderRegion} (verified 4-arg ctor: center, radius, minY, maxY - center's Y is
     *  discarded internally, so 0 is passed). */
    private Object cylinderRegion(Cylinder cylinder) {
        Object center = blockVector3(cylinder.centerX(), 0, cylinder.centerZ());
        Object radius = invokeStatic(load(VECTOR2), "at", cylinder.radiusX(), cylinder.radiusZ());
        return create(CYLINDER_REGION, center, radius, cylinder.minY(), cylinder.maxY());
    }

    // ---- polygon edits/selection ----

    @Override public int setBlocks(Object actor, Polygon polygon, String pattern) {
        return setBlocksOnRegion(actor, polygonRegion(polygon), pattern);
    }

    @Override public int replaceBlocks(Object actor, Polygon polygon, String mask, String pattern) {
        return replaceBlocksOnRegion(actor, polygonRegion(polygon), mask, pattern);
    }

    @Override public void copy(Object actor, Polygon polygon) {
        copyRegion(actor, polygonRegion(polygon));
    }

    @Override public Polygon polygonSelection(Object actor) {
        Object we = worldEdit();
        Object region = selectedRegion(we, actor);
        if (!load(POLYGONAL_2D_REGION).isInstance(region)) {
            throw ApiException.badRequest("Current selection is not a polygon");
        }
        List<Object> points = (List<Object>) invoke(region, "getPoints");
        List<Point2D> result = new ArrayList<>(points.size());
        for (Object p : points) {
            result.add(new Point2D((Integer) invoke(p, "getX"), (Integer) invoke(p, "getZ")));
        }
        int minY = (Integer) invoke(region, "getMinimumY");
        int maxY = (Integer) invoke(region, "getMaximumY");
        return new Polygon(result, minY, maxY);
    }

    /**
     * Builds a {@code Polygonal2DRegion} via its verified 4-arg constructor
     * {@code Polygonal2DRegion(World world, List<BlockVector2> points, int minY, int maxY)} - world may be
     * {@code null} (same as every other no-{@code World} region here; its own no-arg/{@code World}-only
     * constructors delegate to this one with {@code world = null}). Both Y bounds are passed in one shot,
     * {@code points} already populated, deliberately <b>not</b> built via the no-arg constructor followed by
     * separate {@code setMinimumY}/{@code setMaximumY} calls: verified {@code recalculate()} (called by
     * every mutator, including both those setters) re-sorts {@code minY}/{@code maxY} from whatever they
     * currently are, every time either one changes - {@code Polygonal2DRegion.java}'s own
     * {@code recalculate()} body does {@code minY = Math.min(oldMinY, oldMaxY); maxY = Math.max(oldMinY,
     * oldMaxY)}. Starting from the default {@code 0..0} and calling {@code setMinimumY(64)} then
     * {@code setMaximumY(70)} one at a time therefore silently produces {@code 0..70}, not the requested
     * {@code 64..70}: the first call's re-sort flips the still-{@code 0} max past the newly-set {@code 64}
     * min before the second call ever runs. Passing both bounds to the 4-arg constructor avoids this
     * entirely, since {@code recalculate()} only runs once there, after both fields already hold their
     * final, correct values.
     * <p>At least 3 points are required - verified: fewer and the real
     * {@code Polygonal2DRegion.contains(BlockVector3)} always returns false (a silent, always-empty
     * region), which this adapter rejects explicitly instead.
     */
    private Object polygonRegion(Polygon polygon) {
        if (polygon.points().size() < 3) throw ApiException.badRequest("A polygon needs at least 3 points");
        List<Object> points = new ArrayList<>();
        for (Point2D p : polygon.points()) {
            points.add(invokeStatic(load(BLOCK_VECTOR2), "at", p.x(), p.z()));
        }
        return create(POLYGONAL_2D_REGION, null, points, polygon.minY(), polygon.maxY());
    }

    // ---- ellipsoid edits/selection ----

    @Override public int setBlocks(Object actor, Ellipsoid ellipsoid, String pattern) {
        return setBlocksOnRegion(actor, ellipsoidRegion(ellipsoid), pattern);
    }

    @Override public int replaceBlocks(Object actor, Ellipsoid ellipsoid, String mask, String pattern) {
        return replaceBlocksOnRegion(actor, ellipsoidRegion(ellipsoid), mask, pattern);
    }

    @Override public void copy(Object actor, Ellipsoid ellipsoid) {
        copyRegion(actor, ellipsoidRegion(ellipsoid));
    }

    @Override public Ellipsoid ellipsoidSelection(Object actor) {
        Object we = worldEdit();
        Object region = selectedRegion(we, actor);
        if (!load(ELLIPSOID_REGION).isInstance(region)) {
            throw ApiException.badRequest("Current selection is not an ellipsoid");
        }
        Object center = invoke(region, "getCenter");
        Object radius = invoke(region, "getRadius");
        int centerX = (int) Math.round((Double) invoke(center, "getX"));
        int centerY = (int) Math.round((Double) invoke(center, "getY"));
        int centerZ = (int) Math.round((Double) invoke(center, "getZ"));
        double radiusX = (Double) invoke(radius, "getX");
        double radiusY = (Double) invoke(radius, "getY");
        double radiusZ = (Double) invoke(radius, "getZ");
        return new Ellipsoid(centerX, centerY, centerZ, radiusX, radiusY, radiusZ);
    }

    /** Builds an {@code EllipsoidRegion} via its verified world-independent 2-arg constructor
     *  {@code EllipsoidRegion(BlockVector3 center, Vector3 radius)} - the ctor body assigns its first
     *  argument to the center field and passes the second to {@code setRadius}, confirmed by reading the
     *  body (not assumed from the javadoc's generic "pos1"/"pos2" parameter names). */
    private Object ellipsoidRegion(Ellipsoid ellipsoid) {
        Object center = blockVector3(ellipsoid.centerX(), ellipsoid.centerY(), ellipsoid.centerZ());
        Object radius = invokeStatic(load(VECTOR3), "at", ellipsoid.radiusX(), ellipsoid.radiusY(), ellipsoid.radiusZ());
        return create(ELLIPSOID_REGION, center, radius);
    }

    // ---- convex hull edits/selection ----

    @Override public int setBlocks(Object actor, ConvexHull hull, String pattern) {
        return setBlocksOnRegion(actor, convexHullRegion(hull), pattern);
    }

    @Override public int replaceBlocks(Object actor, ConvexHull hull, String mask, String pattern) {
        return replaceBlocksOnRegion(actor, convexHullRegion(hull), mask, pattern);
    }

    @Override public void copy(Object actor, ConvexHull hull) {
        copyRegion(actor, convexHullRegion(hull));
    }

    @Override public ConvexHull convexHullSelection(Object actor) {
        Object we = worldEdit();
        Object region = selectedRegion(we, actor);
        if (!load(CONVEX_POLYHEDRAL_REGION).isInstance(region)) {
            throw ApiException.badRequest("Current selection is not a convex hull");
        }
        Collection<Object> vertices = (Collection<Object>) invoke(region, "getVertices");
        List<Vertex> result = new ArrayList<>(vertices.size());
        for (Object v : vertices) {
            result.add(new Vertex((Integer) invoke(v, "getX"), (Integer) invoke(v, "getY"), (Integer) invoke(v, "getZ")));
        }
        return new ConvexHull(result);
    }

    /**
     * Builds a {@code ConvexPolyhedralRegion} with a null {@code World} (its constructor parameter is
     * verified {@code @Nullable}) via repeated {@code addVertex(BlockVector3)}.
     * <p>Does <b>not</b> validate with {@code isDefined()} - verified that check is too weak:
     * {@code isDefined()} is just {@code !triangles.isEmpty()}, and {@code addVertex} already adds two
     * (coincident, back-to-back, zero-volume) triangles once exactly 3 vertices exist
     * ({@code ConvexPolyhedralRegion.java}, the {@code case 3} branch) - before any 4th vertex, coplanar or
     * not. A 4th vertex that's coplanar with the first 3 is routed to an internal backlog instead of
     * expanding the hull ({@code containsRaw(vertexD)} check, same file), leaving {@code triangles} at
     * exactly those same 2 - so {@code isDefined()} reads {@code true} for a flat, degenerate input and
     * would let a zero-volume region through. A genuinely non-coplanar 4th vertex instead removes the one
     * triangle facing it and adds 3 new ones in its place (net 2 - 1 + 3 = 4, verified in the same method's
     * {@code default:} branch), so a real (non-degenerate) hull always has strictly more than 2 triangular
     * faces. Checking {@code getTriangles().size() > 2} is therefore the verified-correct non-degeneracy
     * test, not {@code isDefined()}.
     * <p>Separately: the single constructor's only parameter type is {@code World}, but
     * {@code ConvexPolyhedralRegion} also has a copy-constructor taking another {@code ConvexPolyhedralRegion}
     * - both take one non-primitive argument, so passing a literal {@code null} through the generic
     * {@link #create} helper would be ambiguous between them; this looks up the {@code World}-parameter
     * constructor explicitly instead.
     */
    private Object convexHullRegion(ConvexHull hull) {
        Object region;
        try {
            region = load(CONVEX_POLYHEDRAL_REGION).getConstructor(load(WORLD_CLASS)).newInstance((Object) null);
        } catch (ReflectiveOperationException e) {
            throw new ApiException(500, "ACTION_FAILED", "Cannot construct ConvexPolyhedralRegion: " + e);
        }
        for (Vertex v : hull.vertices()) {
            invoke(region, "addVertex", blockVector3(v.x(), v.y(), v.z()));
        }
        Object triangles = invoke(region, "getTriangles");
        if (!(triangles instanceof java.util.Collection<?> c) || c.size() <= 2) {
            throw ApiException.badRequest("Convex hull is degenerate (needs at least 4 non-coplanar vertices)");
        }
        return region;
    }

    // ---- generation shapes ----

    /** Checks each radius against WorldEdit's configured maxRadius first, as //sphere does
     *  (GenerationCommands.java:230-232). */
    @Override public int makeSphere(Object actor, int x, int y, int z, double radiusX, double radiusY, double radiusZ, boolean filled, String pattern) {
        Object we = worldEdit();
        checkMaxRadius(we, radiusX, radiusY, radiusZ);
        Object pos = blockVector3(x, y, z);
        Object session = session(we, actor);
        Object editSession = newEditSession(session, actor);
        try {
            Object patternObj = parsePattern(we, parserContext(session, actor, editSession), pattern);
            return (Integer) limited(() -> invoke(editSession, "makeSphere", pos, patternObj, radiusX, radiusY, radiusZ, filled));
        } finally {
            rememberAndClose(session, editSession);
        }
    }

    /** Checks both radii and the height against maxRadius first, as //cyl does (GenerationCommands.java:125-127). */
    @Override public int makeCylinder(Object actor, int x, int y, int z, double radiusX, double radiusZ, int height, boolean filled, String pattern) {
        Object we = worldEdit();
        checkMaxRadius(we, radiusX, radiusZ, height);
        Object pos = blockVector3(x, y, z);
        Object session = session(we, actor);
        Object editSession = newEditSession(session, actor);
        try {
            Object patternObj = parsePattern(we, parserContext(session, actor, editSession), pattern);
            return (Integer) limited(() -> invoke(editSession, "makeCylinder", pos, patternObj, radiusX, radiusZ, height, filled));
        } finally {
            rememberAndClose(session, editSession);
        }
    }

    /** Checks the size against maxRadius first, as //pyramid does (GenerationCommands.java:344). */
    @Override public int makePyramid(Object actor, int x, int y, int z, int size, boolean filled, String pattern) {
        Object we = worldEdit();
        checkMaxRadius(we, size);
        Object pos = blockVector3(x, y, z);
        Object session = session(we, actor);
        Object editSession = newEditSession(session, actor);
        try {
            Object patternObj = parsePattern(we, parserContext(session, actor, editSession), pattern);
            return (Integer) limited(() -> invoke(editSession, "makePyramid", pos, patternObj, size, filled));
        } finally {
            rememberAndClose(session, editSession);
        }
    }

    /**
     * Deliberately does NOT call {@code EditSession.hollowOutRegion} - verified its actual body
     * ({@code EditSession.java:2743-2765}, {@code recurseHollow}): it flood-fills from the region's faces
     * through existing *passable* blocks only ({@code getBlock(current).getBlockType().getMaterial()
     * .isMovementBlocker()}), reading real world state. For a region that is already solid (the common case
     * for a box nobody has carved into yet), that flood fill never starts, the "outside"/shell set stays
     * empty, and the method fills the *entire* region, including what should be the shell - the opposite of
     * "preserve a shell." Its shell-preserving behavior depends on pre-existing terrain, not geometry, so it
     * cannot back a deterministic typed contract. Instead, this computes the interior box directly (shrunk
     * by {@code thickness} on every axis) and reuses the already-verified, already-tested
     * {@link #setBlocks(Object, Box, String)} on it - deterministic regardless of existing terrain, built
     * only from already-verified primitives.
     */
    @Override public int hollowOutBox(Object actor, Box box, int thickness, String pattern) {
        if (thickness < 0) throw ApiException.badRequest("thickness must not be negative");
        Box inner = shrink(box, thickness);
        if (inner == null) return 0; // thickness leaves no interior: nothing qualifies, nothing changes
        return setBlocks(actor, inner, pattern);
    }

    /**
     * @return the box shrunk by {@code thickness} on every axis, or null if that leaves no interior.
     * Requires {@code thickness >= 0} (checked by the only caller). Computed in {@code long} so an extreme
     * {@code thickness} (e.g. near {@code Integer.MAX_VALUE}) cannot silently wrap a plain {@code int}
     * computation around into a different, wrong-but-plausible-looking box instead of correctly reporting
     * "no interior": with {@code thickness >= 0}, {@code box.minX() + thickness} and
     * {@code box.maxX() - thickness} never overflow as {@code long}, so the {@code minX > maxX} comparison
     * is always exact; and whenever it's false, both bounds are sandwiched inside the original box's own
     * int-ranged bounds ({@code box.minX() <= minX <= maxX <= box.maxX()}), so narrowing the surviving
     * result back to {@code int} is always safe.
     */
    private Box shrink(Box box, int thickness) {
        long minX = (long) box.minX() + thickness, maxX = (long) box.maxX() - thickness;
        long minY = (long) box.minY() + thickness, maxY = (long) box.maxY() - thickness;
        long minZ = (long) box.minZ() + thickness, maxZ = (long) box.maxZ() - thickness;
        if (minX > maxX || minY > maxY || minZ > maxZ) return null;
        return new Box((int) minX, (int) minY, (int) minZ, (int) maxX, (int) maxY, (int) maxZ);
    }

    // ---- transforms ----

    /**
     * Mirrors WorldEdit's own {@code /rotate} command exactly (verified: {@code
     * command/ClipboardCommands.java:233-238}): build a fresh {@code AffineTransform}, negate each angle
     * before passing it to {@code rotateY}/{@code rotateX}/{@code rotateZ} (so a positive input angle reads
     * as clockwise, matching the command's own documented convention), then compose it onto the clipboard's
     * existing transform with {@code existingTransform.combine(newTransform)} and write that back.
     */
    @Override public void rotateClipboard(Object actor, double degreesY, double degreesX, double degreesZ) {
        Object we = worldEdit();
        Object session = session(we, actor);
        Object holder = invokeTranslating(session, "getClipboard", EMPTY_CLIPBOARD_EXCEPTION, "No clipboard is set");
        Object transform = create(AFFINE_TRANSFORM);
        transform = invoke(transform, "rotateY", -degreesY);
        transform = invoke(transform, "rotateX", -degreesX);
        transform = invoke(transform, "rotateZ", -degreesZ);
        composeTransform(holder, transform);
    }

    /** Mirrors WorldEdit's own {@code /flip} command's mechanism (verified: {@code
     * command/ClipboardCommands.java:253-254}), generalized from a +/-1 flip to arbitrary per-axis factors. */
    @Override public void scaleClipboard(Object actor, double sx, double sy, double sz) {
        Object we = worldEdit();
        Object session = session(we, actor);
        Object holder = invokeTranslating(session, "getClipboard", EMPTY_CLIPBOARD_EXCEPTION, "No clipboard is set");
        Object transform = create(AFFINE_TRANSFORM);
        transform = invoke(transform, "scale", sx, sy, sz);
        composeTransform(holder, transform);
    }

    private void composeTransform(Object holder, Object transform) {
        Object existing = invoke(holder, "getTransform");
        Object combined = invoke(existing, "combine", transform);
        invoke(holder, "setTransform", combined);
    }

    // ---- shared construction helpers ----

    private Object region(Box box) {
        Object pos1 = blockVector3(box.minX(), box.minY(), box.minZ());
        Object pos2 = blockVector3(box.maxX(), box.maxY(), box.maxZ());
        return create(CUBOID_REGION, pos1, pos2);
    }

    private Object blockVector3(int x, int y, int z) {
        return invokeStatic(load(BLOCK_VECTOR3), "at", x, y, z);
    }

    private Object parsePattern(Object we, Object parserContext, String pattern) {
        return parse(invoke(we, "getPatternFactory"), "pattern", pattern, parserContext);
    }

    private Object parseMask(Object we, Object parserContext, String mask) {
        return parse(invoke(we, "getMaskFactory"), "mask", mask, parserContext);
    }

    /** {@code AbstractFactory.parseFromInput}; a parser's {@code InputParseException} is a 400 carrying its message. */
    private Object parse(Object factory, String what, String input, Object parserContext) {
        try {
            return invoke(factory, "parseFromInput", input, parserContext);
        } catch (ApiException e) {
            if (!causedBy(e, INPUT_PARSE_EXCEPTION)) throw e;
            throw ApiException.badRequest("Invalid " + what + " '" + input + "': " + e.getCause().getMessage());
        }
    }

    /**
     * The {@code ParserContext} for one operation's pattern or mask, built as WorldEdit's own pattern/mask argument
     * converter builds one ({@code FactoryConverter.createContext}, FactoryConverter.java:99-123):
     * <ul>
     * <li>the actor, with restricted input ({@code setRestricted(true)}), so WorldEdit's disallowed-blocks rule
     *     still holds for an actor without {@code worldedit.anyblock} (DefaultBlockParser.java:423-429);</li>
     * <li>the actor's own {@code LocalSession} - the one this operation edits through - which {@code #clipboard}
     *     and {@code #copy} ({@code ClipboardPatternParser}) and {@code #region}, {@code #selection} and
     *     {@code #sel} ({@code RegionMaskParser}) read, so one actor's clipboard or selection is never another's;</li>
     * <li>the world, {@link #editingWorld}: the session's world override first, the world this operation edits
     *     and the selection reporters read. {@code FactoryConverter} prefers a player's own world to the override,
     *     so there {@code #region} can resolve a different world's selection than the one the command edits
     *     (its injected {@code World} is override-first); using one world for both keeps them the same;</li>
     * <li>the extent masks test blocks against: this operation's own {@code EditSession}. That is what
     *     {@code FactoryConverter}'s {@code RequestExtent} resolves to through WorldEdit's thread-local command
     *     request, which this API never opens, so it is passed directly - after {@code setWorld}, which resets
     *     the extent to the bare world (ParserContext.setWorld).</li>
     * </ul>
     */
    Object parserContext(Object session, Object actor, Object editSession) {
        Object ctx = create(PARSER_CONTEXT);
        invoke(ctx, "setActor", actor);
        Object world = worldOf(session, actor);
        if (world != null) invoke(ctx, "setWorld", world);
        invoke(ctx, "setExtent", editSession);
        invoke(ctx, "setSession", session);
        invoke(ctx, "setRestricted", true);
        return ctx;
    }

    /** {@code WorldEdit.checkMaxRadius(double)} for each value: WorldEdit's own configured generation limit. */
    private void checkMaxRadius(Object we, double... radii) {
        for (double r : radii) {
            invokeTranslating(we, "checkMaxRadius", MAX_RADIUS_EXCEPTION,
                    "Radius " + r + " exceeds WorldEdit's configured maximum (maxRadius)", r);
        }
    }

    /**
     * Runs an edit-producing call. Real WorldEdit applies changes one at a time and refuses only the write
     * past the session's block change limit ({@code BlockChangeLimiter}), so a {@code MaxChangedBlocksException}
     * means a partial edit: everything up to the limit is applied, and the caller's {@code finally} remembers
     * it, so the error says it stays undoable rather than implying nothing happened.
     */
    private Object limited(Supplier<Object> call) {
        try {
            return call.get();
        } catch (ApiException e) {
            if (!causedBy(e, MAX_CHANGED_BLOCKS_EXCEPTION)) throw e;
            throw ApiException.badRequest("Max blocks changed in an operation reached (" + invoke(e.getCause(), "getBlockLimit")
                    + "); the changes made before the limit stay applied and can be undone with worldedit.undo");
        }
    }

    /** Like {@link #invoke}, but translates a specific checked exception into a 400 with a friendlier message. */
    private Object invokeTranslating(Object target, String name, String checkedExceptionClass, String friendlyMessage, Object... args) {
        try {
            return invoke(target, name, args);
        } catch (ApiException e) {
            if (causedBy(e, checkedExceptionClass)) throw ApiException.badRequest(friendlyMessage);
            throw e;
        }
    }

    private boolean causedBy(ApiException e, String exceptionClassName) {
        Throwable cause = e.getCause();
        if (cause == null) return false;
        try { return Class.forName(exceptionClassName, false, loader).isInstance(cause); }
        catch (ClassNotFoundException | LinkageError ex) { return false; }
    }

    // ---- reflection helpers (same technique as ReflectiveBaritone) ----

    private Class<?> load(String name) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException | LinkageError e) { throw ApiException.unavailable("WorldEdit class not found: " + name); }
    }

    private Object create(String className, Object... args) {
        Class<?> c = load(className);
        for (Constructor<?> k : c.getConstructors()) {
            if (!paramsMatch(k.getParameterTypes(), args)) continue;
            try { return k.newInstance(args); }
            catch (InvocationTargetException e) { throw new ApiException(500, "ACTION_FAILED", className + " rejected arguments: " + e.getCause()); }
            catch (ReflectiveOperationException e) { throw new ApiException(500, "ACTION_FAILED", "Cannot construct " + className + ": " + e); }
        }
        throw new ApiException(501, "UNSUPPORTED", "Installed WorldEdit has no matching constructor for " + className);
    }

    private static Object invoke(Object target, String name, Object... args) {
        return run(findMethod(target.getClass(), name, args), target, name, args);
    }

    private static Object invokeStatic(Class<?> c, String name, Object... args) {
        return run(findMethod(c, name, args), null, name, args);
    }

    private static Object run(Method m, Object target, String name, Object[] args) {
        try { return m.invoke(target, args); }
        catch (InvocationTargetException e) {
            ApiException ex = worldUnloaded(e.getCause())
                    ? ApiException.unavailable("The world WorldEdit is editing has been unloaded")
                    : new ApiException(500, "ACTION_FAILED", "WorldEdit " + name + " failed: " + e.getCause());
            ex.initCause(e.getCause());
            throw ex;
        }
        catch (IllegalAccessException e) { throw new ApiException(501, "UNSUPPORTED", "WorldEdit " + name + " is not accessible: " + e.getMessage()); }
    }

    private static boolean worldUnloaded(Throwable t) {
        for (; t != null; t = t.getCause())
            if (t.getClass() == RuntimeException.class && String.valueOf(t.getMessage()).startsWith(WORLD_UNLOADED_MESSAGE)) return true;
        return false;
    }

    /** Prefers methods declared on public interfaces (always accessible), then the class itself. */
    private static Method findMethod(Class<?> c, String name, Object[] args) {
        for (Class<?> i : allInterfaces(c))
            for (Method m : i.getMethods())
                if (m.getName().equals(name) && paramsMatch(m.getParameterTypes(), args)) return m;
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && paramsMatch(m.getParameterTypes(), args)) {
                m.trySetAccessible();
                return m;
            }
        }
        throw new ApiException(501, "UNSUPPORTED",
                "Installed WorldEdit does not provide " + c.getSimpleName() + "." + name + "/" + args.length);
    }

    private static Set<Class<?>> allInterfaces(Class<?> c) {
        Set<Class<?>> out = new LinkedHashSet<>();
        if (c.isInterface()) out.add(c);
        for (Class<?> k = c; k != null; k = k.getSuperclass()) collect(k, out);
        out.removeIf(i -> !java.lang.reflect.Modifier.isPublic(i.getModifiers()));
        return out;
    }

    private static void collect(Class<?> c, Set<Class<?>> out) {
        for (Class<?> i : c.getInterfaces()) { if (out.add(i)) collect(i, out); }
    }

    private static boolean paramsMatch(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return false;
        for (int i = 0; i < types.length; i++) {
            Class<?> t = wrap(types[i]);
            if (args[i] == null ? types[i].isPrimitive() : !t.isInstance(args[i])) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == boolean.class) return Boolean.class;
        if (t == long.class) return Long.class;
        if (t == double.class) return Double.class;
        if (t == float.class) return Float.class;
        if (t == short.class) return Short.class;
        if (t == byte.class) return Byte.class;
        return Character.class;
    }
}

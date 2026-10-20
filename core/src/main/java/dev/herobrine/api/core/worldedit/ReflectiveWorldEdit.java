package dev.herobrine.api.core.worldedit;

import dev.herobrine.api.core.ApiException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;

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
    private static final String PARSER_CONTEXT = "com.sk89q.worldedit.extension.input.ParserContext";
    private static final String CLIPBOARD_HOLDER = "com.sk89q.worldedit.session.ClipboardHolder";
    private static final String BLOCK_ARRAY_CLIPBOARD = "com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard";
    private static final String CLIPBOARD_FORMATS = "com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats";
    private static final String FORWARD_EXTENT_COPY = "com.sk89q.worldedit.function.operation.ForwardExtentCopy";
    private static final String OPERATIONS = "com.sk89q.worldedit.function.operation.Operations";
    private static final String AFFINE_TRANSFORM = "com.sk89q.worldedit.math.transform.AffineTransform";

    // Checked exceptions worth translating into a specific ApiException rather than a generic 500.
    private static final String INCOMPLETE_REGION_EXCEPTION = "com.sk89q.worldedit.IncompleteRegionException";
    private static final String EMPTY_CLIPBOARD_EXCEPTION = "com.sk89q.worldedit.EmptyClipboardException";
    private static final String MAX_CHANGED_BLOCKS_EXCEPTION = "com.sk89q.worldedit.MaxChangedBlocksException";
    private static final String INPUT_PARSE_EXCEPTION = "com.sk89q.worldedit.extension.input.InputParseException";

    /** The WorldEdit version branch this adapter was verified against (see class Javadoc). */
    private static final String SUPPORTED_VERSION_PREFIX = "7.3.";

    private final ClassLoader loader;

    public ReflectiveWorldEdit(ClassLoader loader) {
        this.loader = loader;
    }

    // ---- availability ----

    @Override public boolean installed() {
        try { Class.forName(WORLDEDIT, false, loader); return true; }
        catch (ClassNotFoundException | LinkageError e) { return false; }
    }

    @Override public String version() {
        if (!installed()) return null;
        Object v = invokeStatic(load(WORLDEDIT), "getVersion");
        return v == null ? null : v.toString();
    }

    /** @return the WorldEdit instance, after checking the installed build is a verified-compatible version. */
    private Object worldEdit() {
        if (!installed()) throw ApiException.unavailable("WorldEdit is not installed");
        String v = version();
        if (v == null || !v.startsWith(SUPPORTED_VERSION_PREFIX)) {
            throw new ApiException(501, "UNSUPPORTED",
                    "Installed WorldEdit " + v + " is not in the verified " + SUPPORTED_VERSION_PREFIX
                            + "x branch (worldedit-neoforge-mc1.21, NeoForge/Minecraft 1.21.1)");
        }
        return invokeStatic(load(WORLDEDIT), "getInstance");
    }

    private Object session(Object we, Object actor) {
        return invoke(invoke(we, "getSessionManager"), "get", actor);
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
        Object we = worldEdit();
        Object region = region(box);
        Object patternObj = parsePattern(we, actor, pattern);
        Object session = session(we, actor);
        Object editSession = newEditSession(session, actor);
        try {
            return (Integer) invokeTranslating(editSession, "setBlocks", MAX_CHANGED_BLOCKS_EXCEPTION,
                    "Edit exceeds the configured block change limit", region, patternObj);
        } finally {
            rememberAndClose(session, editSession);
        }
    }

    @Override public int replaceBlocks(Object actor, Box box, String mask, String pattern) {
        Object we = worldEdit();
        Object region = region(box);
        Object maskObj = parseMask(we, actor, mask);
        Object patternObj = parsePattern(we, actor, pattern);
        Object session = session(we, actor);
        Object editSession = newEditSession(session, actor);
        try {
            return (Integer) invokeTranslating(editSession, "replaceBlocks", MAX_CHANGED_BLOCKS_EXCEPTION,
                    "Edit exceeds the configured block change limit", region, maskObj, patternObj);
        } finally {
            rememberAndClose(session, editSession);
        }
    }

    // ---- history ----

    @Override public boolean undo(Object actor) {
        Object we = worldEdit();
        return invoke(session(we, actor), "undo", null, actor) != null;
    }

    @Override public boolean redo(Object actor) {
        Object we = worldEdit();
        return invoke(session(we, actor), "redo", null, actor) != null;
    }

    // ---- clipboard / schematics ----

    /** Origin is the box's minimum corner: a fixed, typed input, not WorldEdit's placement-position heuristic. */
    @Override public void copy(Object actor, Box box) {
        Object we = worldEdit();
        Object region = region(box);
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

    @Override public void paste(Object actor, int x, int y, int z) {
        Object we = worldEdit();
        Object session = session(we, actor);
        Object holder = invokeTranslating(session, "getClipboard", EMPTY_CLIPBOARD_EXCEPTION, "No clipboard is set");
        Object editSession = newEditSession(session, actor);
        try {
            Object to = blockVector3(x, y, z);
            Object pasteBuilder = invoke(holder, "createPaste", editSession);
            pasteBuilder = invoke(pasteBuilder, "to", to);
            Object operation = invoke(pasteBuilder, "build");
            invokeStatic(load(OPERATIONS), "completeLegacy", operation);
        } finally {
            rememberAndClose(session, editSession);
        }
    }

    @Override public void saveSchematic(Object actor, File file) {
        Object we = worldEdit();
        Object format = resolveFormat(file);
        Object session = session(we, actor);
        Object holder = invokeTranslating(session, "getClipboard", EMPTY_CLIPBOARD_EXCEPTION, "No clipboard is set");
        Object clipboard = invoke(holder, "getClipboard");
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
        Object session = session(we, actor);
        Object world = invoke(session, "getSelectionWorld");
        if (world == null) throw ApiException.badRequest("No selection world is set");
        Object region = invokeTranslating(session, "getSelection", INCOMPLETE_REGION_EXCEPTION,
                "No selection is defined (or it is in a different world)", world);
        Object min = invoke(region, "getMinimumPoint");
        Object max = invoke(region, "getMaximumPoint");
        return new Box((Integer) invoke(min, "getX"), (Integer) invoke(min, "getY"), (Integer) invoke(min, "getZ"),
                (Integer) invoke(max, "getX"), (Integer) invoke(max, "getY"), (Integer) invoke(max, "getZ"));
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

    private Object parsePattern(Object we, Object actor, String pattern) {
        Object parserContext = parserContext(actor);
        Object factory = invoke(we, "getPatternFactory");
        return invokeTranslating(factory, "parseFromInput", INPUT_PARSE_EXCEPTION,
                "Invalid pattern: " + pattern, pattern, parserContext);
    }

    private Object parseMask(Object we, Object actor, String mask) {
        Object parserContext = parserContext(actor);
        Object factory = invoke(we, "getMaskFactory");
        return invokeTranslating(factory, "parseFromInput", INPUT_PARSE_EXCEPTION,
                "Invalid mask: " + mask, mask, parserContext);
    }

    private Object parserContext(Object actor) {
        Object ctx = create(PARSER_CONTEXT);
        invoke(ctx, "setActor", actor);
        return ctx;
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
            ApiException ex = new ApiException(500, "ACTION_FAILED", "WorldEdit " + name + " failed: " + e.getCause());
            ex.initCause(e.getCause());
            throw ex;
        }
        catch (IllegalAccessException e) { throw new ApiException(501, "UNSUPPORTED", "WorldEdit " + name + " is not accessible: " + e.getMessage()); }
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

package dev.herobrine.api.neoforge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;

/**
 * Exact method provenance for the WorldEdit binding: every WorldEdit class, method, constructor and field that
 * {@code ReflectiveWorldEdit} and {@link WorldEditBindings} reach by reflection is resolved here, by exact
 * signature, against the real WorldEdit 7.3.8 jars ({@code worldedit-neoforge-mc1.21} and its
 * {@code worldedit-core}, test runtime classpath only). A renamed method, a changed parameter type or a
 * missing class fails this build instead of surfacing as a 501 in a live game. A few constructions that need
 * no running platform are also run for real, to pin the region behaviors the adapter relies on.
 */
class WorldEditProvenanceTest {
    private static final ClassLoader LOADER = WorldEditProvenanceTest.class.getClassLoader();
    private static final String WE = "com.sk89q.worldedit.";

    private static Class<?> type(String name) {
        try { return Class.forName(name.startsWith("com.") ? name : WE + name, false, LOADER); }
        catch (ClassNotFoundException e) { throw new AssertionError("WorldEdit 7.3.8 has no class " + name, e); }
    }

    private static Class<?>[] types(Object... params) {
        Class<?>[] out = new Class<?>[params.length];
        for (int i = 0; i < params.length; i++) out[i] = params[i] instanceof Class<?> c ? c : type((String) params[i]);
        return out;
    }

    /** The public method {@code owner.name(params)}, which must return {@code returns}. */
    private static Method method(String owner, String name, Object returns, Object... params) {
        Method m;
        try { m = type(owner).getMethod(name, types(params)); }
        catch (NoSuchMethodException e) { throw new AssertionError("WorldEdit 7.3.8 has no " + owner + "." + name + List.of(params), e); }
        Class<?> expected = types(returns)[0];
        assertTrue(expected.isAssignableFrom(m.getReturnType()), owner + "." + name + " returns " + m.getReturnType());
        return m;
    }

    private static void staticMethod(String owner, String name, Object returns, Object... params) {
        assertTrue(Modifier.isStatic(method(owner, name, returns, params).getModifiers()), owner + "." + name + " is not static");
    }

    private static void constructor(String owner, Object... params) {
        try { type(owner).getConstructor(types(params)); }
        catch (NoSuchMethodException e) { throw new AssertionError("WorldEdit 7.3.8 has no constructor " + owner + List.of(params), e); }
    }

    private static void isA(String sub, String sup) {
        assertTrue(type(sup).isAssignableFrom(type(sub)), sub + " is not a " + sup);
    }

    @Test void actorConstructionIsWorldEditsOwnNeoForgeAdapter() {
        Method adapt = method("com.sk89q.worldedit.neoforge.NeoForgeAdapter", "adaptPlayer", "entity.Player", ServerPlayer.class);
        assertTrue(Modifier.isStatic(adapt.getModifiers()));
        // The adapted player is an Actor (sessions, permissions) and Locatable (its world).
        isA(adapt.getReturnType().getName(), "extension.platform.Actor");
        isA(adapt.getReturnType().getName(), "extension.platform.Locatable");
        isA("extension.platform.Actor", "session.SessionOwner");
        isA("extension.platform.Actor", "util.auth.Subject");
        method("util.auth.Subject", "hasPermission", boolean.class, String.class);
        method("extension.platform.Locatable", "getExtent", "extent.Extent");
        isA("world.World", "extent.Extent");
        isA("entity.Player", "extension.platform.Actor");
    }

    @Test void entryPointSessionsAndConfiguration() throws Exception {
        staticMethod("WorldEdit", "getVersion", String.class);
        staticMethod("WorldEdit", "getInstance", "WorldEdit");
        method("WorldEdit", "getSessionManager", "session.SessionManager");
        method("WorldEdit", "getPatternFactory", "extension.factory.PatternFactory");
        method("WorldEdit", "getMaskFactory", "extension.factory.MaskFactory");
        method("WorldEdit", "getConfiguration", "LocalConfiguration");
        method("WorldEdit", "getWorkingDirectoryPath", Path.class, String.class);
        method("WorldEdit", "checkMaxRadius", void.class, double.class);
        method("WorldEdit", "flushBlockBag", void.class, "extension.platform.Actor", "EditSession");
        Field saveDir = type("LocalConfiguration").getField("saveDir");
        assertEquals(String.class, saveDir.getType());
        method("session.SessionManager", "get", "LocalSession", "session.SessionOwner");

        method("LocalSession", "createEditSession", "EditSession", "extension.platform.Actor");
        method("LocalSession", "remember", void.class, "EditSession");
        method("LocalSession", "undo", "EditSession", "extent.inventory.BlockBag", "extension.platform.Actor");
        method("LocalSession", "redo", "EditSession", "extent.inventory.BlockBag", "extension.platform.Actor");
        method("LocalSession", "getBlockBag", "extent.inventory.BlockBag", "entity.Player");
        method("LocalSession", "getClipboard", "session.ClipboardHolder");
        method("LocalSession", "setClipboard", void.class, "session.ClipboardHolder");
        method("LocalSession", "getSelection", "regions.Region", "world.World");
        method("LocalSession", "hasWorldOverride", boolean.class);
        method("LocalSession", "getWorldOverride", "world.World");
    }

    @Test void editsGenerationAndParsing() {
        method("EditSession", "setBlocks", int.class, "regions.Region", "function.pattern.Pattern");
        method("EditSession", "replaceBlocks", int.class, "regions.Region", "function.mask.Mask", "function.pattern.Pattern");
        method("EditSession", "makeSphere", int.class, "math.BlockVector3", "function.pattern.Pattern",
                double.class, double.class, double.class, boolean.class);
        method("EditSession", "makeCylinder", int.class, "math.BlockVector3", "function.pattern.Pattern",
                double.class, double.class, int.class, boolean.class);
        method("EditSession", "makePyramid", int.class, "math.BlockVector3", "function.pattern.Pattern", int.class, boolean.class);
        method("EditSession", "close", void.class);
        method("extension.factory.PatternFactory", "parseFromInput", Object.class, String.class, "extension.input.ParserContext");
        method("extension.factory.MaskFactory", "parseFromInput", Object.class, String.class, "extension.input.ParserContext");
        constructor("extension.input.ParserContext");
        method("extension.input.ParserContext", "setActor", void.class, "extension.platform.Actor");
        method("MaxChangedBlocksException", "getBlockLimit", int.class);
        // The checked exceptions the adapter translates into 400s, identified by these exact names.
        for (String e : List.of("IncompleteRegionException", "EmptyClipboardException", "MaxChangedBlocksException",
                "MaxRadiusException", "extension.input.InputParseException"))
            isA(e, "WorldEditException");
    }

    @Test void vectorsRegionsAndTheirAccessors() {
        staticMethod("math.BlockVector3", "at", "math.BlockVector3", int.class, int.class, int.class);
        staticMethod("math.BlockVector2", "at", "math.BlockVector2", int.class, int.class);
        staticMethod("math.Vector2", "at", "math.Vector2", double.class, double.class);
        staticMethod("math.Vector3", "at", "math.Vector3", double.class, double.class, double.class);
        for (String axis : List.of("getX", "getY", "getZ")) {
            method("math.BlockVector3", axis, int.class);
            method("math.Vector3", axis, double.class);
        }
        for (String axis : List.of("getX", "getZ")) {
            method("math.BlockVector2", axis, int.class);
            method("math.Vector2", axis, double.class);
        }
        constructor("regions.CuboidRegion", "math.BlockVector3", "math.BlockVector3");
        constructor("regions.CylinderRegion", "math.BlockVector3", "math.Vector2", int.class, int.class);
        constructor("regions.Polygonal2DRegion", "world.World", List.class, int.class, int.class);
        constructor("regions.EllipsoidRegion", "math.BlockVector3", "math.Vector3");
        constructor("regions.ConvexPolyhedralRegion", "world.World");
        method("regions.CylinderRegion", "getCenter", "math.Vector3");
        method("regions.CylinderRegion", "getRadius", "math.Vector2");
        method("regions.EllipsoidRegion", "getCenter", "math.Vector3");
        method("regions.EllipsoidRegion", "getRadius", "math.Vector3");
        for (String r : List.of("regions.CylinderRegion", "regions.Polygonal2DRegion")) {
            method(r, "getMinimumY", int.class);
            method(r, "getMaximumY", int.class);
        }
        method("regions.Polygonal2DRegion", "getPoints", List.class);
        method("regions.ConvexPolyhedralRegion", "addVertex", boolean.class, "math.BlockVector3");
        method("regions.ConvexPolyhedralRegion", "getTriangles", Collection.class);
        method("regions.ConvexPolyhedralRegion", "getVertices", Collection.class);
        method("regions.Region", "getMinimumPoint", "math.BlockVector3");
        method("regions.Region", "getMaximumPoint", "math.BlockVector3");
        method("regions.Region", "getVolume", long.class);
    }

    @Test void clipboardTransformsPasteAndSchematics() {
        constructor("extent.clipboard.BlockArrayClipboard", "regions.Region");
        method("extent.clipboard.BlockArrayClipboard", "setOrigin", void.class, "math.BlockVector3");
        method("extent.clipboard.Clipboard", "getRegion", "regions.Region");
        method("extent.clipboard.Clipboard", "transform", "extent.clipboard.Clipboard", "math.transform.Transform");
        constructor("function.operation.ForwardExtentCopy", "extent.Extent", "regions.Region", "extent.Extent", "math.BlockVector3");
        staticMethod("function.operation.Operations", "completeLegacy", void.class, "function.operation.Operation");
        constructor("session.ClipboardHolder", "extent.clipboard.Clipboard");
        method("session.ClipboardHolder", "getClipboard", "extent.clipboard.Clipboard");
        method("session.ClipboardHolder", "getTransform", "math.transform.Transform");
        method("session.ClipboardHolder", "setTransform", void.class, "math.transform.Transform");
        method("session.ClipboardHolder", "createPaste", "session.PasteBuilder", "extent.Extent");
        method("session.PasteBuilder", "to", "session.PasteBuilder", "math.BlockVector3");
        for (String flag : List.of("ignoreAirBlocks", "ignoreStructureVoidBlocks", "copyEntities", "copyBiomes"))
            method("session.PasteBuilder", flag, "session.PasteBuilder", boolean.class);
        method("session.PasteBuilder", "build", "function.operation.Operation");
        constructor("math.transform.AffineTransform");
        for (String rotate : List.of("rotateX", "rotateY", "rotateZ"))
            method("math.transform.AffineTransform", rotate, "math.transform.AffineTransform", double.class);
        method("math.transform.AffineTransform", "scale", "math.transform.AffineTransform", double.class, double.class, double.class);
        method("math.transform.Transform", "combine", "math.transform.Transform", "math.transform.Transform");
        staticMethod("extent.clipboard.io.ClipboardFormats", "findByFile", "extent.clipboard.io.ClipboardFormat", File.class);
        staticMethod("extent.clipboard.io.ClipboardFormats", "findByAlias", "extent.clipboard.io.ClipboardFormat", String.class);
        method("extent.clipboard.io.ClipboardFormat", "getReader", "extent.clipboard.io.ClipboardReader", InputStream.class);
        method("extent.clipboard.io.ClipboardFormat", "getWriter", "extent.clipboard.io.ClipboardWriter", OutputStream.class);
        method("extent.clipboard.io.ClipboardReader", "read", "extent.clipboard.Clipboard");
        method("extent.clipboard.io.ClipboardReader", "close", void.class);
        method("extent.clipboard.io.ClipboardWriter", "write", void.class, "extent.clipboard.Clipboard");
        method("extent.clipboard.io.ClipboardWriter", "close", void.class);
    }

    // ---- real behavior that needs no running platform ----

    @Test void theBindingsOwnLookupReachesTheRealAdaptPlayer() {
        // No ServerPlayer exists without a running server, so pass none: reaching WorldEdit's own null check
        // proves WorldEditBindings.actor found and invoked the real method by its exact signature.
        dev.herobrine.api.core.ApiException e = assertThrows(dev.herobrine.api.core.ApiException.class, () -> WorldEditBindings.actor(null));
        assertEquals("ACTION_FAILED", e.code);
        assertTrue(e.getMessage().contains("NullPointerException"), e.getMessage());
    }

    private static Object at(int x, int y, int z) throws Exception {
        return type("math.BlockVector3").getMethod("at", int.class, int.class, int.class).invoke(null, x, y, z);
    }

    @Test void polygonBoundsAreTakenAtomicallyByTheFourArgConstructor() throws Exception {
        Method at2 = type("math.BlockVector2").getMethod("at", int.class, int.class);
        List<Object> points = List.of(at2.invoke(null, 0, 0), at2.invoke(null, 4, 0), at2.invoke(null, 4, 4));
        Object poly = type("regions.Polygonal2DRegion").getConstructor(type("world.World"), List.class, int.class, int.class)
                .newInstance(null, points, 64, 70);
        assertEquals(64, type("regions.Polygonal2DRegion").getMethod("getMinimumY").invoke(poly));
        assertEquals(70, type("regions.Polygonal2DRegion").getMethod("getMaximumY").invoke(poly));
    }

    @Test void aCoplanarConvexHullHasExactlyTwoTrianglesAndARealOneMore() throws Exception {
        Class<?> hullType = type("regions.ConvexPolyhedralRegion");
        Method add = hullType.getMethod("addVertex", type("math.BlockVector3"));
        Method triangles = hullType.getMethod("getTriangles");
        Object hull = hullType.getConstructor(type("world.World")).newInstance((Object) null);
        add.invoke(hull, at(0, 0, 0));
        add.invoke(hull, at(4, 0, 0));
        add.invoke(hull, at(0, 4, 0));
        assertEquals(2, ((Collection<?>) triangles.invoke(hull)).size());
        add.invoke(hull, at(4, 4, 0)); // coplanar: still flat
        assertEquals(2, ((Collection<?>) triangles.invoke(hull)).size());
        add.invoke(hull, at(0, 0, 4));
        assertTrue(((Collection<?>) triangles.invoke(hull)).size() > 2);
    }

    @Test void aCuboidsVolumeIsWhatAPasteOfItWrites() throws Exception {
        Object box = type("regions.CuboidRegion").getConstructor(type("math.BlockVector3"), type("math.BlockVector3"))
                .newInstance(at(0, 0, 0), at(1, 2, 3));
        assertEquals(24L, type("regions.Region").getMethod("getVolume").invoke(box));
    }
}

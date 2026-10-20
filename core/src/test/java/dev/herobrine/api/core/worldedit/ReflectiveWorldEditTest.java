package dev.herobrine.api.core.worldedit;

import static org.junit.jupiter.api.Assertions.*;

import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import dev.herobrine.api.core.ApiException;
import dev.herobrine.api.core.worldedit.WorldEditAdapter.Box;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class ReflectiveWorldEditTest {
    ReflectiveWorldEdit a;
    FakeActor actor;

    @BeforeEach void setUp() {
        FakeWorldEdit.reset();
        a = new ReflectiveWorldEdit(getClass().getClassLoader());
        actor = new FakeActor("steve");
    }

    private LocalSession session() {
        return WorldEdit.getInstance().getSessionManager().get(actor);
    }

    @Test void installedAndVersionAreReported() {
        assertTrue(a.installed());
        assertEquals("7.3.8+1379-7d32b45", a.version());
    }

    @Test void genuineAbsenceIsUnavailableNotACrash() {
        ClassLoader isolated = new URLClassLoader(new URL[0], null);
        ReflectiveWorldEdit absent = new ReflectiveWorldEdit(isolated);
        assertFalse(absent.installed());
        assertNull(absent.version());
        ApiException e = assertThrows(ApiException.class, () -> absent.setBlocks(actor, new Box(0, 0, 0, 1, 1, 1), "stone"));
        assertEquals("ACTION_UNAVAILABLE", e.code);
        assertEquals(409, e.status);
    }

    @Test void incompatibleVersionIsUnsupported() {
        FakeWorldEdit.INSTANCE.version = "7.2.0";
        ApiException e = assertThrows(ApiException.class, () -> a.setBlocks(actor, new Box(0, 0, 0, 1, 1, 1), "stone"));
        assertEquals("UNSUPPORTED", e.code);
        assertEquals(501, e.status);
        assertTrue(e.getMessage().contains("7.2.0"));
    }

    @Test void anUnverifiedSiblingReleaseIsAlsoUnsupportedNotJustADifferentBranch() {
        // A prior draft accepted any 7.3.x via startsWith("7.3."), which would wrongly let an unverified
        // 7.3.9 (or 7.3.80, etc.) run reflective calls instead of failing fast. Only 7.3.8 itself - and its
        // own build-metadata suffix - is verified.
        FakeWorldEdit.INSTANCE.version = "7.3.9";
        ApiException e = assertThrows(ApiException.class, () -> a.setBlocks(actor, new Box(0, 0, 0, 1, 1, 1), "stone"));
        assertEquals("UNSUPPORTED", e.code);

        FakeWorldEdit.INSTANCE.version = "7.3.80";
        ApiException e2 = assertThrows(ApiException.class, () -> a.setBlocks(actor, new Box(0, 0, 0, 1, 1, 1), "stone"));
        assertEquals("UNSUPPORTED", e2.code);

        FakeWorldEdit.INSTANCE.version = "7.3.8"; // exact, no build metadata - still accepted
        assertEquals(7, a.setBlocks(actor, new Box(0, 0, 0, 1, 1, 1), "stone"));
    }

    @Test void setBlocksBuildsRegionAndPatternAndRemembersForUndo() {
        Box box = new Box(5, 10, 5, 3, 8, 7); // min (3,8,5), max (5,10,7)
        int changed = a.setBlocks(actor, box, "stone");
        assertEquals(7, changed);
        assertEquals(
                java.util.List.of("parsePattern:stone", "createEditSession:" + actor + ":maxBlocks=-1",
                        "setBlocks:CuboidRegion(BV3(3,8,5),BV3(5,10,7)):stone", "close"),
                FakeWorldEdit.INSTANCE.calls);
        assertEquals(1, session().history.size());
    }

    @Test void exceedingTheSessionBlockChangeLimitIsABadRequestButRemembersThePartialEdit() {
        // Proves the adapter honors LocalSession.createEditSession's configured limit (not a bare,
        // unlimited WorldEdit.newEditSession builder): FakeWorldEdit.setBlocks "attempts" 7 blocks.
        // Real WorldEdit applies (and records) writes one at a time - verified against BlockChangeLimiter,
        // which checks the count and only then delegates to the recording extent - so the first 5 of those
        // 7 writes are already applied and recorded by the time the 6th is rejected. WorldEdit's own command
        // dispatcher remembers that EditSession unconditionally (PlatformCommandManager.java:510-513), so
        // this partial edit must stay undo-able rather than being silently discarded.
        session().blockChangeLimit = 5;
        Box box = new Box(0, 0, 0, 1, 1, 1);
        ApiException e = assertThrows(ApiException.class, () -> a.setBlocks(actor, box, "stone"));
        assertEquals("BAD_REQUEST", e.code);
        assertEquals(1, session().history.size());
        assertEquals(5, session().history.get(0).size);
    }

    @Test void replaceBlocksBuildsMaskAndPattern() {
        Box box = new Box(0, 0, 0, 1, 1, 1);
        int changed = a.replaceBlocks(actor, box, "dirt", "stone");
        assertEquals(3, changed);
        assertTrue(FakeWorldEdit.INSTANCE.calls.contains("parseMask:dirt"));
        assertTrue(FakeWorldEdit.INSTANCE.calls.contains("parsePattern:stone"));
        assertEquals(1, session().history.size());
    }

    @Test void badPatternAndMaskAreBadRequests() {
        Box box = new Box(0, 0, 0, 1, 1, 1);
        ApiException e1 = assertThrows(ApiException.class, () -> a.setBlocks(actor, box, "bad-pattern"));
        assertEquals("BAD_REQUEST", e1.code);
        ApiException e2 = assertThrows(ApiException.class, () -> a.replaceBlocks(actor, box, "bad-mask", "stone"));
        assertEquals("BAD_REQUEST", e2.code);
    }

    @Test void editSessionIsAlwaysClosedEvenWhenTheEditIsRejected() {
        session().blockChangeLimit = 5;
        Box box = new Box(0, 0, 0, 1, 1, 1);
        assertThrows(ApiException.class, () -> a.setBlocks(actor, box, "stone"));
        assertTrue(session().history.get(0).closed);
    }

    @Test void undoRedoHappyPath() {
        Box box = new Box(0, 0, 0, 1, 1, 1);
        a.setBlocks(actor, box, "stone");
        assertTrue(a.undo(actor));
        assertFalse(a.undo(actor)); // nothing left to undo
        assertTrue(a.redo(actor));
        assertFalse(a.redo(actor)); // nothing left to redo
    }

    @Test void copyFillsTheClipboardWithOriginAtTheBoxMinimum() {
        // No placement position needed: origin is the box's own minimum corner, a fixed typed input
        // (WorldEditAdapter#copy's contract), not WorldEdit's own placement-position heuristic.
        Box box = new Box(5, 10, 5, 3, 8, 7); // min (3,8,5)
        a.copy(actor, box);
        assertNotNull(session().clipboard);
        assertTrue(FakeWorldEdit.INSTANCE.calls.stream()
                .anyMatch(c -> c.startsWith("ForwardExtentCopy:") && c.contains("BV3(3,8,5)")));
    }

    @Test void pasteRequiresAClipboardAndRemembersTheEdit() {
        ApiException e = assertThrows(ApiException.class, () -> a.paste(actor, 1, 2, 3));
        assertEquals("BAD_REQUEST", e.code);

        a.copy(actor, new Box(0, 0, 0, 1, 1, 1));
        int before = session().history.size();
        a.paste(actor, 10, 20, 30);
        assertEquals(before + 1, session().history.size());
        assertTrue(FakeWorldEdit.INSTANCE.calls.stream().anyMatch(c -> c.startsWith("completeLegacy:Paste(")));
    }

    @Test void rotateClipboardRequiresAClipboardAndNegatesAnglesForClockwiseConvention() {
        ApiException e = assertThrows(ApiException.class, () -> a.rotateClipboard(actor, 90, 0, 0));
        assertEquals("BAD_REQUEST", e.code);

        a.copy(actor, new Box(0, 0, 0, 1, 1, 1));
        a.rotateClipboard(actor, 90, 45, 0);
        var transform = session().clipboard.getTransform();
        assertEquals("AffineTransform[rotateY(-90.0), rotateX(-45.0), rotateZ(-0.0)]", transform.toString());
    }

    @Test void scaleClipboardComposesOntoAnyExistingTransform() {
        a.copy(actor, new Box(0, 0, 0, 1, 1, 1));
        a.rotateClipboard(actor, 90, 0, 0);
        a.scaleClipboard(actor, -1, 1, 1);
        var transform = session().clipboard.getTransform();
        assertEquals("AffineTransform[rotateY(-90.0), rotateX(-0.0), rotateZ(-0.0), scale(-1.0,1.0,1.0)]", transform.toString());
    }

    @Test void selectionReportsTheBoundingBoxOfACuboidSelection() {
        ApiException e = assertThrows(ApiException.class, () -> a.selection(actor));
        assertEquals("BAD_REQUEST", e.code);

        session().selectionWorld = actor.world;
        session().selectionRegion = new com.sk89q.worldedit.regions.CuboidRegion(BlockVector3.at(1, 2, 3), BlockVector3.at(4, 5, 6));
        Box box = a.selection(actor);
        assertEquals(new Box(1, 2, 3, 4, 5, 6), box);
    }

    @Test void cylinderSelectionReportsCenterRadiusAndYExtent() {
        ApiException e = assertThrows(ApiException.class, () -> a.cylinderSelection(actor));
        assertEquals("BAD_REQUEST", e.code);

        session().selectionWorld = actor.world;
        session().selectionRegion = new com.sk89q.worldedit.regions.CuboidRegion(BlockVector3.at(1, 2, 3), BlockVector3.at(4, 5, 6));
        ApiException notCylinder = assertThrows(ApiException.class, () -> a.cylinderSelection(actor));
        assertEquals("BAD_REQUEST", notCylinder.code);

        session().selectionRegion = new com.sk89q.worldedit.regions.CylinderRegion(
                BlockVector3.at(10, 0, 20), com.sk89q.worldedit.math.Vector2.at(5, 7), 60, 70);
        var cyl = a.cylinderSelection(actor);
        assertEquals(new WorldEditAdapter.Cylinder(10, 20, 5, 7, 60, 70), cyl);
    }

    @Test void cylinderEditsBuildACylinderRegion() {
        var cyl = new WorldEditAdapter.Cylinder(10, 20, 5, 7, 60, 70);
        int changed = a.setBlocks(actor, cyl, "stone");
        assertEquals(7, changed);
        assertTrue(FakeWorldEdit.INSTANCE.calls.stream().anyMatch(c ->
                c.startsWith("setBlocks:CylinderRegion(center=BV3(10,0,20),radius=V2(5.0,7.0),y=60..70):stone")));

        int replaced = a.replaceBlocks(actor, cyl, "dirt", "stone");
        assertEquals(3, replaced);
    }

    @Test void cylinderCanBeCopied() {
        a.copy(actor, new WorldEditAdapter.Cylinder(10, 20, 5, 7, 60, 70));
        assertNotNull(session().clipboard);
        assertTrue(FakeWorldEdit.INSTANCE.calls.stream().anyMatch(c -> c.startsWith("ForwardExtentCopy:")));
    }

    @Test void polygonSelectionReportsPointsAndYExtent() {
        ApiException e = assertThrows(ApiException.class, () -> a.polygonSelection(actor));
        assertEquals("BAD_REQUEST", e.code);

        session().selectionWorld = actor.world;
        session().selectionRegion = new com.sk89q.worldedit.regions.CuboidRegion(BlockVector3.at(1, 2, 3), BlockVector3.at(4, 5, 6));
        ApiException notPolygon = assertThrows(ApiException.class, () -> a.polygonSelection(actor));
        assertEquals("BAD_REQUEST", notPolygon.code);

        var poly = new com.sk89q.worldedit.regions.Polygonal2DRegion(actor.world, java.util.List.of(
                com.sk89q.worldedit.math.BlockVector2.at(0, 0),
                com.sk89q.worldedit.math.BlockVector2.at(4, 0),
                com.sk89q.worldedit.math.BlockVector2.at(4, 4)), 10, 20);
        session().selectionRegion = poly;
        var result = a.polygonSelection(actor);
        assertEquals(new WorldEditAdapter.Polygon(java.util.List.of(
                new WorldEditAdapter.Point2D(0, 0),
                new WorldEditAdapter.Point2D(4, 0),
                new WorldEditAdapter.Point2D(4, 4)), 10, 20), result);
    }

    @Test void polygonEditsRequireAtLeastThreePoints() {
        var tooFew = new WorldEditAdapter.Polygon(java.util.List.of(
                new WorldEditAdapter.Point2D(0, 0), new WorldEditAdapter.Point2D(1, 1)), 0, 1);
        ApiException e = assertThrows(ApiException.class, () -> a.setBlocks(actor, tooFew, "stone"));
        assertEquals("BAD_REQUEST", e.code);
    }

    @Test void polygonEditsBuildAPolygonalRegion() {
        var triangle = new WorldEditAdapter.Polygon(java.util.List.of(
                new WorldEditAdapter.Point2D(0, 0),
                new WorldEditAdapter.Point2D(4, 0),
                new WorldEditAdapter.Point2D(4, 4)), 10, 20);
        int changed = a.setBlocks(actor, triangle, "stone");
        assertEquals(7, changed);
        assertTrue(FakeWorldEdit.INSTANCE.calls.stream().anyMatch(c -> c.startsWith("setBlocks:Polygonal2DRegion(")));

        int replaced = a.replaceBlocks(actor, triangle, "dirt", "stone");
        assertEquals(3, replaced);

        a.copy(actor, triangle);
        assertNotNull(session().clipboard);
    }

    @Test void polygonEditsPreserveBothYBoundsExactlyNotClobberedToZero() {
        // Verified real Polygonal2DRegion.recalculate() (called by setMinimumY/setMaximumY) re-sorts
        // min/maxY from whatever they currently hold - building via the no-arg ctor plus two separate
        // setter calls would silently turn a requested 64..70 into 0..70 (the first setter's re-sort flips
        // the still-0 max past the newly-set 64 min before the second setter ever runs). The adapter
        // instead uses the 4-arg constructor, so both bounds are already final before any sort happens.
        var triangle = new WorldEditAdapter.Polygon(java.util.List.of(
                new WorldEditAdapter.Point2D(0, 0),
                new WorldEditAdapter.Point2D(4, 0),
                new WorldEditAdapter.Point2D(4, 4)), 64, 70);
        a.setBlocks(actor, triangle, "stone");
        assertTrue(FakeWorldEdit.INSTANCE.calls.stream()
                .anyMatch(c -> c.startsWith("setBlocks:Polygonal2DRegion(") && c.contains("y=64..70")));
    }

    @Test void polygonEditsNormalizeAReversedYRangeCorrectly() {
        var reversed = new WorldEditAdapter.Polygon(java.util.List.of(
                new WorldEditAdapter.Point2D(0, 0),
                new WorldEditAdapter.Point2D(4, 0),
                new WorldEditAdapter.Point2D(4, 4)), 20, 10); // minY > maxY, given out of order on purpose
        a.setBlocks(actor, reversed, "stone");
        assertTrue(FakeWorldEdit.INSTANCE.calls.stream()
                .anyMatch(c -> c.startsWith("setBlocks:Polygonal2DRegion(") && c.contains("y=10..20")));
    }

    @Test void ellipsoidSelectionReportsCenterAndRadii() {
        ApiException e = assertThrows(ApiException.class, () -> a.ellipsoidSelection(actor));
        assertEquals("BAD_REQUEST", e.code);

        session().selectionWorld = actor.world;
        session().selectionRegion = new com.sk89q.worldedit.regions.CuboidRegion(BlockVector3.at(1, 2, 3), BlockVector3.at(4, 5, 6));
        ApiException notEllipsoid = assertThrows(ApiException.class, () -> a.ellipsoidSelection(actor));
        assertEquals("BAD_REQUEST", notEllipsoid.code);

        session().selectionRegion = new com.sk89q.worldedit.regions.EllipsoidRegion(
                BlockVector3.at(10, 20, 30), com.sk89q.worldedit.math.Vector3.at(3, 4, 5));
        var result = a.ellipsoidSelection(actor);
        assertEquals(new WorldEditAdapter.Ellipsoid(10, 20, 30, 3, 4, 5), result);
    }

    @Test void ellipsoidEditsBuildAnEllipsoidRegion() {
        var ellipsoid = new WorldEditAdapter.Ellipsoid(10, 20, 30, 3, 4, 5);
        int changed = a.setBlocks(actor, ellipsoid, "stone");
        assertEquals(7, changed);
        assertTrue(FakeWorldEdit.INSTANCE.calls.stream().anyMatch(c ->
                c.startsWith("setBlocks:EllipsoidRegion(center=BV3(10,20,30),radius=V3(3.0,4.0,5.0)):stone")));

        int replaced = a.replaceBlocks(actor, ellipsoid, "dirt", "stone");
        assertEquals(3, replaced);

        a.copy(actor, ellipsoid);
        assertNotNull(session().clipboard);
    }

    @Test void convexHullSelectionReportsVertices() {
        ApiException e = assertThrows(ApiException.class, () -> a.convexHullSelection(actor));
        assertEquals("BAD_REQUEST", e.code);

        session().selectionWorld = actor.world;
        session().selectionRegion = new com.sk89q.worldedit.regions.CuboidRegion(BlockVector3.at(1, 2, 3), BlockVector3.at(4, 5, 6));
        ApiException notHull = assertThrows(ApiException.class, () -> a.convexHullSelection(actor));
        assertEquals("BAD_REQUEST", notHull.code);

        var hull = new com.sk89q.worldedit.regions.ConvexPolyhedralRegion(null);
        hull.addVertex(BlockVector3.at(0, 0, 0));
        hull.addVertex(BlockVector3.at(4, 0, 0));
        hull.addVertex(BlockVector3.at(0, 4, 0));
        hull.addVertex(BlockVector3.at(0, 0, 4));
        session().selectionRegion = hull;
        var result = a.convexHullSelection(actor);
        assertEquals(4, result.vertices().size());
        assertTrue(result.vertices().contains(new WorldEditAdapter.Vertex(4, 0, 0)));
    }

    @Test void convexHullEditsRejectADegenerateHull() {
        var tooFew = new WorldEditAdapter.ConvexHull(java.util.List.of(
                new WorldEditAdapter.Vertex(0, 0, 0), new WorldEditAdapter.Vertex(1, 0, 0)));
        ApiException e = assertThrows(ApiException.class, () -> a.setBlocks(actor, tooFew, "stone"));
        assertEquals("BAD_REQUEST", e.code);
    }

    @Test void convexHullEditsRejectExactlyThreeCoplanarVerticesNotJustTooFew() {
        // Verified against the real ConvexPolyhedralRegion.addVertex: exactly 3 vertices already produce 2
        // (coincident, back-to-back, zero-volume) triangles, so the real isDefined() ("!triangles.isEmpty()")
        // would incorrectly read true here - this is exactly the degenerate case isDefined() alone misses.
        var threeCoplanar = new WorldEditAdapter.ConvexHull(java.util.List.of(
                new WorldEditAdapter.Vertex(0, 0, 0),
                new WorldEditAdapter.Vertex(4, 0, 0),
                new WorldEditAdapter.Vertex(0, 4, 0)));
        ApiException e = assertThrows(ApiException.class, () -> a.setBlocks(actor, threeCoplanar, "stone"));
        assertEquals("BAD_REQUEST", e.code);
    }

    @Test void convexHullEditsBuildAConvexPolyhedralRegion() {
        var hull = new WorldEditAdapter.ConvexHull(java.util.List.of(
                new WorldEditAdapter.Vertex(0, 0, 0),
                new WorldEditAdapter.Vertex(4, 0, 0),
                new WorldEditAdapter.Vertex(0, 4, 0),
                new WorldEditAdapter.Vertex(0, 0, 4)));
        int changed = a.setBlocks(actor, hull, "stone");
        assertEquals(7, changed);
        assertTrue(FakeWorldEdit.INSTANCE.calls.stream().anyMatch(c -> c.startsWith("setBlocks:ConvexPolyhedralRegion(")));

        int replaced = a.replaceBlocks(actor, hull, "dirt", "stone");
        assertEquals(3, replaced);

        a.copy(actor, hull);
        assertNotNull(session().clipboard);
    }

    @Test void generationShapesUseVerifiedEditSessionMethods() {
        int sphere = a.makeSphere(actor, 1, 2, 3, 4, 5, 6, true, "stone");
        assertEquals(11, sphere);
        assertTrue(FakeWorldEdit.INSTANCE.calls.contains("makeSphere:BV3(1,2,3):stone:4.0,5.0,6.0:true"));

        int cylinder = a.makeCylinder(actor, 1, 2, 3, 4, 5, 6, true, "stone");
        assertEquals(13, cylinder);
        assertTrue(FakeWorldEdit.INSTANCE.calls.contains("makeCylinder:BV3(1,2,3):stone:4.0,5.0:6:true"));

        int pyramid = a.makePyramid(actor, 1, 2, 3, 5, true, "stone");
        assertEquals(17, pyramid);
        assertTrue(FakeWorldEdit.INSTANCE.calls.contains("makePyramid:BV3(1,2,3):stone:5:true"));

        // 3x3x3 box, thickness 1: the shrunk interior is the single center block - delegates to setBlocks,
        // so the fake's usual fixed "7 changed" applies - see the dedicated hollowOutBox tests below for the
        // actual shell/interior geometry this builds instead of relying on EditSession.hollowOutRegion.
        int hollow = a.hollowOutBox(actor, new Box(0, 0, 0, 2, 2, 2), 1, "stone");
        assertEquals(7, hollow);
        assertTrue(FakeWorldEdit.INSTANCE.calls.contains("setBlocks:CuboidRegion(BV3(1,1,1),BV3(1,1,1)):stone"));
        assertEquals(4, session().history.size()); // each generation call is its own remembered edit
    }

    @Test void hollowOutBoxDelegatesToSetBlocksOnTheGeometricallyShrunkInterior() {
        // Deliberately NOT built on EditSession.hollowOutRegion: verified its actual body
        // (EditSession.recurseHollow, EditSession.java:2743-2765) flood-fills from the region's faces
        // through existing *passable* world blocks only - for an already-solid box (the common case for one
        // nobody has carved into yet) that flood fill never starts, its "outside"/shell set stays empty, and
        // the real method overwrites the *entire* region, not just the interior. That makes its shell
        // behavior depend on incidental terrain, not geometry, so it can't back a deterministic typed
        // contract. This computes the interior box directly (shrunk by `thickness` on every axis) and reuses
        // the already-verified setBlocks instead - deterministic regardless of terrain.
        a.hollowOutBox(actor, new Box(0, 0, 0, 4, 4, 4), 1, "stone"); // 5x5x5 box, thickness 1 -> 3x3x3 interior
        assertTrue(FakeWorldEdit.INSTANCE.calls.contains("setBlocks:CuboidRegion(BV3(1,1,1),BV3(3,3,3)):stone"));
    }

    @Test void hollowOutBoxWithNoRemainingInteriorChangesNothing() {
        // 3x3x3 box, thickness 2: shrinking by 2 on every axis inverts the box (no interior exists at all).
        int changed = a.hollowOutBox(actor, new Box(0, 0, 0, 2, 2, 2), 2, "stone");
        assertEquals(0, changed);
        assertTrue(session().history.isEmpty()); // no edit session was even created
    }

    @Test void hollowOutBoxWithExtremeThicknessStillChangesNothingRatherThanOverflow() {
        // A naive `int` shrink (box.minX() + thickness) silently wraps around near Integer.MAX_VALUE,
        // turning "no interior" into a different, wrong-but-plausible-looking box instead of correctly
        // reporting no interior at all. shrink() computes in `long` specifically to rule this out.
        int changed = a.hollowOutBox(actor, new Box(0, 0, 0, 2, 2, 2), Integer.MAX_VALUE, "stone");
        assertEquals(0, changed);
        assertTrue(session().history.isEmpty());

        ApiException e = assertThrows(ApiException.class,
                () -> a.hollowOutBox(actor, new Box(0, 0, 0, 2, 2, 2), -1, "stone"));
        assertEquals("BAD_REQUEST", e.code);
    }

    @Test void pasteDefaultsMatchWorldEditsOwnBuilderDefaults() {
        a.copy(actor, new Box(0, 0, 0, 1, 1, 1));
        a.paste(actor, 1, 2, 3);
        assertTrue(FakeWorldEdit.INSTANCE.calls.stream().anyMatch(
                c -> c.contains("ignoreAir=false,ignoreVoid=false,entities=true,biomes=false")));
    }

    @Test void pasteOptionsAreExplicitlyControllable() {
        a.copy(actor, new Box(0, 0, 0, 1, 1, 1));
        a.paste(actor, 1, 2, 3, true, true, false, true);
        assertTrue(FakeWorldEdit.INSTANCE.calls.stream().anyMatch(
                c -> c.contains("ignoreAir=true,ignoreVoid=true,entities=false,biomes=true")));
    }

    @Test void schematicRoundTrip(@TempDir java.nio.file.Path tmp) throws Exception {
        File schem = tmp.resolve("house.schem").toFile();
        File badExt = tmp.resolve("house.bin").toFile();
        Files.createFile(badExt.toPath());

        ApiException badFormat = assertThrows(ApiException.class, () -> a.saveSchematic(actor, badExt));
        assertEquals("BAD_REQUEST", badFormat.code);

        ApiException noClipboard = assertThrows(ApiException.class, () -> a.saveSchematic(actor, schem));
        assertEquals("BAD_REQUEST", noClipboard.code);

        a.copy(actor, new Box(0, 0, 0, 1, 1, 1));
        a.saveSchematic(actor, schem);
        assertTrue(FakeWorldEdit.INSTANCE.calls.stream().anyMatch(c -> c.startsWith("schematic.write:")));

        a.loadSchematic(actor, schem);
        assertTrue(FakeWorldEdit.INSTANCE.calls.contains("schematic.read"));
        assertNotNull(session().clipboard);
    }

    @Test void schematicIoFailureIsAnActionFailedNotACrash(@TempDir java.nio.file.Path tmp) {
        File schem = tmp.resolve("house.schem").toFile();
        a.copy(actor, new Box(0, 0, 0, 1, 1, 1));
        FakeWorldEdit.INSTANCE.schematicIoFails = true;
        ApiException e = assertThrows(ApiException.class, () -> a.saveSchematic(actor, schem));
        assertEquals("ACTION_FAILED", e.code);
    }

    // ---- MINECRAFT-476: actor context and faithful WorldEdit behavior ----

    /** An actor that is not a player and not Locatable, like a console. */
    private static com.sk89q.worldedit.extension.platform.Actor console() {
        return new com.sk89q.worldedit.extension.platform.Actor() {
            final java.util.UUID id = java.util.UUID.randomUUID();
            @Override public com.sk89q.worldedit.session.SessionKey getSessionKey() { return () -> id; }
            @Override public boolean hasPermission(String permission) { return true; }
            @Override public String toString() { return "Console"; }
        };
    }

    @Test void anUnreadableManifestVersionFallsBackToTheVersionWorldEditDeclaresToTheLoader() {
        // WorldEditManifest only reads a jar: class URL; under NeoForge's module class loader it reports "(unknown)".
        FakeWorldEdit.INSTANCE.version = "(unknown)";
        ReflectiveWorldEdit declared = new ReflectiveWorldEdit(getClass().getClassLoader(), () -> "7.3.8+1379-7d32b45");
        assertEquals("7.3.8+1379-7d32b45", declared.version());
        assertEquals(7, declared.setBlocks(actor, new Box(0, 0, 0, 1, 1, 1), "stone"));
        assertEquals("UNSUPPORTED", assertThrows(ApiException.class,
                () -> a.setBlocks(actor, new Box(0, 0, 0, 1, 1, 1), "stone")).code); // nothing declared: still refused

        FakeWorldEdit.INSTANCE.version = "7.3.9"; // a version WorldEdit does report is never overridden
        assertEquals("UNSUPPORTED", assertThrows(ApiException.class,
                () -> declared.setBlocks(actor, new Box(0, 0, 0, 1, 1, 1), "stone")).code);
    }

    @Test void hasPermissionAsksTheActorItself() {
        FakeActor guest = new FakeActor("guest", new FakeWorld("overworld"), java.util.Set.of("worldedit.region.set"));
        assertTrue(a.hasPermission(guest, "worldedit.region.set"));
        assertFalse(a.hasPermission(guest, "worldedit.region.replace"));
    }

    @Test void theSelectionIsTheOneInTheWorldBeingEdited() {
        session().selectionRegion = new com.sk89q.worldedit.regions.CuboidRegion(BlockVector3.at(1, 2, 3), BlockVector3.at(4, 5, 6));
        session().selectionWorld = new FakeWorld("the_nether"); // made in another world than the actor's
        ApiException elsewhere = assertThrows(ApiException.class, () -> a.selection(actor));
        assertEquals("BAD_REQUEST", elsewhere.code);

        session().worldOverride = new FakeWorld("the_nether"); // like //world the_nether: the override wins
        assertEquals(new Box(1, 2, 3, 4, 5, 6), a.selection(actor));

        session().worldOverride = null;
        actor.world = new FakeWorld("the_nether"); // or the player went there
        assertEquals(new Box(1, 2, 3, 4, 5, 6), a.selection(actor));
    }

    @Test void anActorWithNoWorldHasNoSelectionToReport() {
        ApiException e = assertThrows(ApiException.class, () -> a.selection(console()));
        assertEquals("BAD_REQUEST", e.code);
        assertTrue(e.getMessage().contains("not in a world"));
    }

    @Test void undoAndRedoUseTheSessionsBlockBagAndFlushItLikeWorldEdit() {
        session().inventoryBlockBag = new com.sk89q.worldedit.extent.inventory.BlockBag();
        a.setBlocks(actor, new Box(0, 0, 0, 1, 1, 1), "stone");
        FakeWorldEdit.INSTANCE.calls.clear();
        assertTrue(a.undo(actor));
        assertEquals(java.util.List.of("undo:BlockBag", "flushBlockBag:Actor(steve):BlockBag"), FakeWorldEdit.INSTANCE.calls);
        FakeWorldEdit.INSTANCE.calls.clear();
        assertFalse(a.undo(actor)); // nothing undone: nothing to flush
        assertEquals(java.util.List.of("undo:BlockBag"), FakeWorldEdit.INSTANCE.calls);
        FakeWorldEdit.INSTANCE.calls.clear();
        assertTrue(a.redo(actor));
        assertEquals(java.util.List.of("redo:BlockBag", "flushBlockBag:Actor(steve):BlockBag"), FakeWorldEdit.INSTANCE.calls);
    }

    @Test void aNonPlayerActorUndoesWithoutABlockBag() {
        var console = console();
        a.setBlocks(console, new Box(0, 0, 0, 1, 1, 1), "stone");
        FakeWorldEdit.INSTANCE.calls.clear();
        assertTrue(a.undo(console));
        assertEquals("undo:null", FakeWorldEdit.INSTANCE.calls.get(0));
    }

    @Test void savingBakesThePendingTransformLikeSchemSave(@TempDir java.nio.file.Path tmp) {
        a.copy(actor, new Box(0, 0, 0, 1, 1, 1));
        a.rotateClipboard(actor, 90, 0, 0);
        a.saveSchematic(actor, tmp.resolve("rotated.schem").toFile());
        assertTrue(FakeWorldEdit.INSTANCE.calls.stream().anyMatch(
                c -> c.startsWith("schematic.write:Clipboard(") && c.contains("+AffineTransform[rotateY(-90.0)")),
                FakeWorldEdit.INSTANCE.calls.toString());
    }

    @Test void generationChecksWorldEditsConfiguredMaxRadiusFirst() {
        FakeWorldEdit.INSTANCE.maxRadius = 5;
        assertEquals("BAD_REQUEST", assertThrows(ApiException.class,
                () -> a.makeSphere(actor, 0, 64, 0, 6, 1, 1, true, "stone")).code);
        assertEquals("BAD_REQUEST", assertThrows(ApiException.class,
                () -> a.makeCylinder(actor, 0, 64, 0, 1, 1, 6, true, "stone")).code); // //cyl checks the height too
        assertEquals("BAD_REQUEST", assertThrows(ApiException.class,
                () -> a.makePyramid(actor, 0, 64, 0, 6, true, "stone")).code);
        assertTrue(FakeWorldEdit.INSTANCE.calls.stream().noneMatch(c -> c.startsWith("createEditSession")));
        assertEquals(11, a.makeSphere(actor, 0, 64, 0, 5, 5, 5, true, "stone"));
    }

    @Test void theBlockChangeLimitErrorNamesTheLimitAndThatThePartialEditCanBeUndone() {
        session().blockChangeLimit = 5;
        ApiException e = assertThrows(ApiException.class, () -> a.setBlocks(actor, new Box(0, 0, 0, 1, 1, 1), "stone"));
        assertTrue(e.getMessage().contains("(5)"), e.getMessage());
        assertTrue(e.getMessage().contains("worldedit.undo"), e.getMessage());
    }

    @Test void aPasteOverTheBlockChangeLimitIsABadRequestAndStaysUndoable() {
        a.copy(actor, new Box(0, 0, 0, 1, 1, 1));
        session().blockChangeLimit = 2; // the fake paste writes 4 blocks
        ApiException e = assertThrows(ApiException.class, () -> a.paste(actor, 0, 0, 0));
        assertEquals("BAD_REQUEST", e.code);
        assertEquals(1, session().history.size());
        assertEquals(2, session().history.get(0).size);
    }

    @Test void schematicsDirectoryIsWorldEditsConfiguredSaveDir(@TempDir java.nio.file.Path tmp) {
        FakeWorldEdit.INSTANCE.workingDirectory = tmp.resolve("config").resolve("worldedit");
        assertEquals(tmp.resolve("config").resolve("worldedit").resolve("schematics"), a.schematicsDirectory());
        FakeWorldEdit.INSTANCE.configuration.saveDir = tmp.resolve("elsewhere").toString(); // an absolute saveDir is used as is
        assertEquals(tmp.resolve("elsewhere"), a.schematicsDirectory());
    }

    @Test void clipboardVolumeIsTheClipboardRegionsVolume() {
        assertEquals("BAD_REQUEST", assertThrows(ApiException.class, () -> a.clipboardVolume(actor)).code);
        a.copy(actor, new Box(0, 0, 0, 1, 2, 3));
        assertEquals(24, a.clipboardVolume(actor));
    }

    @Test void anUnloadedWorldIsUnavailableNotAServerError() {
        FakeWorldEdit.INSTANCE.worldUnloaded = true;
        ApiException e = assertThrows(ApiException.class, () -> a.setBlocks(actor, new Box(0, 0, 0, 1, 1, 1), "stone"));
        assertEquals(409, e.status);
        assertEquals("ACTION_UNAVAILABLE", e.code);
    }
}

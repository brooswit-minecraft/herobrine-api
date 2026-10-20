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

        World world = new World() {};
        session().selectionWorld = world;
        session().selectionRegion = new com.sk89q.worldedit.regions.CuboidRegion(BlockVector3.at(1, 2, 3), BlockVector3.at(4, 5, 6));
        Box box = a.selection(actor);
        assertEquals(new Box(1, 2, 3, 4, 5, 6), box);
    }

    @Test void cylinderSelectionReportsCenterRadiusAndYExtent() {
        ApiException e = assertThrows(ApiException.class, () -> a.cylinderSelection(actor));
        assertEquals("BAD_REQUEST", e.code);

        World world = new World() {};
        session().selectionWorld = world;
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

        // 3x3x3 box, thickness 1: only the exact center is more than `thickness` away from every face,
        // so exactly 1 block (the interior) changes - see hollowOutBoxPreservesTheShellAndChangesTheInterior
        // for the test that actually distinguishes shell-preserved from interior-preserved.
        int hollow = a.hollowOutBox(actor, new Box(0, 0, 0, 2, 2, 2), 1, "stone");
        assertEquals(1, hollow);
        assertEquals(4, session().history.size()); // each generation call is its own remembered edit
    }

    @Test void hollowOutBoxPreservesTheShellAndChangesTheInterior() {
        // Verified against the real EditSession.hollowOutRegion body (EditSession.java:2496-2558): `thickness`
        // boundary layers are left untouched (the shell), and setBlock only runs for the interior beyond it -
        // the opposite of "replace the shell, leave the interior", which a prior draft of this adapter's own
        // javadoc incorrectly claimed.
        int changed = a.hollowOutBox(actor, new Box(0, 0, 0, 4, 4, 4), 1, "stone"); // 5x5x5 box, thickness 1
        assertEquals(27, changed); // the 3x3x3 interior
        assertFalse(FakeWorldEdit.INSTANCE.lastHollowInterior.contains("0,0,0")); // a shell corner: untouched
        assertFalse(FakeWorldEdit.INSTANCE.lastHollowInterior.contains("2,2,0")); // a shell face center: untouched
        assertTrue(FakeWorldEdit.INSTANCE.lastHollowInterior.contains("2,2,2")); // the exact center: touched
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
}

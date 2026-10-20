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

    @Test void exceedingTheSessionBlockChangeLimitIsABadRequestAndNeverRemembered() {
        // Proves the adapter honors LocalSession.createEditSession's configured limit (not a bare,
        // unlimited WorldEdit.newEditSession builder): FakeWorldEdit.setBlocks always "changes" 7 blocks.
        session().blockChangeLimit = 5;
        Box box = new Box(0, 0, 0, 1, 1, 1);
        ApiException e = assertThrows(ApiException.class, () -> a.setBlocks(actor, box, "stone"));
        assertEquals("BAD_REQUEST", e.code);
        assertEquals(0, session().history.size());
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

    @Test void exceedingTheBlockChangeLimitIsABadRequestNotACrash() {
        Box box = new Box(0, 0, 0, 1, 1, 1);
        ApiException e = assertThrows(ApiException.class, () -> a.setBlocks(actor, box, "overflow"));
        assertEquals("BAD_REQUEST", e.code);
        assertEquals(0, session().history.size()); // the failed edit session was never remembered
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

    @Test void selectionReportsTheBoundingBoxOfACuboidSelection() {
        ApiException e = assertThrows(ApiException.class, () -> a.selection(actor));
        assertEquals("BAD_REQUEST", e.code);

        World world = new World() {};
        session().selectionWorld = world;
        session().selectionRegion = new com.sk89q.worldedit.regions.CuboidRegion(BlockVector3.at(1, 2, 3), BlockVector3.at(4, 5, 6));
        Box box = a.selection(actor);
        assertEquals(new Box(1, 2, 3, 4, 5, 6), box);
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

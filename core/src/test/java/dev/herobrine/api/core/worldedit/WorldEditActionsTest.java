package dev.herobrine.api.core.worldedit;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.herobrine.api.core.ApiException;
import dev.herobrine.api.core.Params;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class WorldEditActionsTest {
    static final long CAP = 1_000;
    RecordingWorldEdit a;
    SchematicFiles schematics;
    @TempDir Path tmp;
    final Object actor = new Object();

    @BeforeEach void setUp() {
        a = new RecordingWorldEdit();
        a.schematicsDirectory = tmp.resolve("schematics");
        schematics = new SchematicFiles(() -> a.schematicsDirectory);
    }

    private Object run(String action, String paramsJson) { return run(action, paramsJson, CAP); }

    private Object run(String action, String paramsJson, long cap) {
        JsonObject json = JsonParser.parseString(paramsJson).getAsJsonObject();
        return WorldEditActions.dispatch(a, action, actor, new Params(json), cap, schematics);
    }

    private ApiException refused(String action, String paramsJson, String code) {
        ApiException e = assertThrows(ApiException.class, () -> run(action, paramsJson));
        assertEquals(code, e.code, e.getMessage());
        return e;
    }

    // ---- inventory ----

    @Test void dispatcherCoversExactlyTheSchemaInventory() {
        Set<String> schema = new HashSet<>();
        WorldEditSchema.OPERATIONS.forEach(op -> schema.add(op.name()));
        assertEquals(schema, WorldEditActions.names());
        Set<String> reads = new HashSet<>();
        for (String n : WorldEditActions.names()) if (WorldEditActions.kind(n) == WorldEditActions.Kind.READ) reads.add(n);
        assertEquals(Set.of("worldedit.selection", "worldedit.cylinder_selection", "worldedit.polygon_selection",
                "worldedit.ellipsoid_selection", "worldedit.convex_hull_selection"), reads);
    }

    @Test void unknownOperationIsNotFound() {
        refused("worldedit.bogus", "{}", "NOT_FOUND");
    }

    // ---- WorldEdit permissions ----

    @Test void everyOperationNeedsOneOfItsWorldEditPermissionsBeforeAnythingRuns() {
        a.permissions = Set.of();
        for (String name : WorldEditActions.names()) {
            ApiException e = refused(name, "{}", "PERMISSION_DENIED");
            for (String perm : WorldEditActions.permissions(name)) assertTrue(e.getMessage().contains(perm), name);
        }
        assertEquals(List.of(), a.calls);
    }

    @Test void anyOneListedNodeIsEnoughLikeWorldEditsOwnCommands() {
        a.permissions = Set.of("worldedit.history.undo.self");
        assertEquals(Map.of("undone", true), run("worldedit.undo", "{}"));
        refused("worldedit.redo", "{}", "PERMISSION_DENIED");
        assertEquals(List.of("worldedit.history.redo", "worldedit.history.redo.self"), WorldEditActions.permissions("worldedit.redo"));
    }

    @Test void permissionNodesAreWorldEditsOwnCommandNodes() {
        assertEquals(List.of("worldedit.region.set"), WorldEditActions.permissions("worldedit.set_blocks_polygon"));
        assertEquals(List.of("worldedit.region.replace"), WorldEditActions.permissions("worldedit.replace_blocks"));
        assertEquals(List.of("worldedit.clipboard.copy"), WorldEditActions.permissions("worldedit.copy_ellipsoid"));
        assertEquals(List.of("worldedit.clipboard.save", "worldedit.schematic.save"), WorldEditActions.permissions("worldedit.save_schematic"));
        assertEquals(List.of("worldedit.clipboard.load", "worldedit.schematic.load"), WorldEditActions.permissions("worldedit.load_schematic"));
        assertEquals(List.of("worldedit.generation.cylinder"), WorldEditActions.permissions("worldedit.make_cylinder"));
        assertEquals(List.of("worldedit.selection.size"), WorldEditActions.permissions("worldedit.convex_hull_selection"));
    }

    // ---- translation ----

    @Test void editsReachTheAdapterWithTypedArgumentsAndReturnWhatItReturned() {
        assertEquals(Map.of("changed", 7), run("worldedit.set_blocks",
                "{\"x1\":5,\"y1\":10,\"z1\":5,\"x2\":3,\"y2\":8,\"z2\":7,\"pattern\":\"stone\"}"));
        assertEquals(Map.of("changed", 3), run("worldedit.replace_blocks_ellipsoid",
                "{\"centerX\":0,\"centerY\":64,\"centerZ\":0,\"radiusX\":2,\"radiusY\":3,\"radiusZ\":4,\"mask\":\"dirt\",\"pattern\":\"stone\"}"));
        assertEquals(Map.of("changed", 7), run("worldedit.set_blocks_polygon",
                "{\"points\":[{\"x\":0,\"z\":0},{\"x\":4,\"z\":0},{\"x\":4,\"z\":4}],\"minY\":60,\"maxY\":62,\"pattern\":\"stone\"}"));
        assertEquals(Map.of("copied", true), run("worldedit.copy_convex_hull",
                "{\"vertices\":[{\"x\":0,\"y\":0,\"z\":0},{\"x\":4,\"y\":0,\"z\":0},{\"x\":0,\"y\":4,\"z\":0},{\"x\":0,\"y\":0,\"z\":4}]}"));
        assertEquals(Map.of("changed", 5), run("worldedit.hollow_out_box",
                "{\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":4,\"y2\":4,\"z2\":4,\"thickness\":1,\"pattern\":\"air\"}"));
        assertEquals(List.of(
                "setBlocks(Box[x1=5, y1=10, z1=5, x2=3, y2=8, z2=7],stone)",
                "replaceBlocks(Ellipsoid[centerX=0, centerY=64, centerZ=0, radiusX=2.0, radiusY=3.0, radiusZ=4.0],dirt,stone)",
                "setBlocks(Polygon[points=[Point2D[x=0, z=0], Point2D[x=4, z=0], Point2D[x=4, z=4]], minY=60, maxY=62],stone)",
                "copy(ConvexHull[vertices=[Vertex[x=0, y=0, z=0], Vertex[x=4, y=0, z=0], Vertex[x=0, y=4, z=0], Vertex[x=0, y=0, z=4]]])",
                "hollowOutBox(Box[x1=0, y1=0, z1=0, x2=4, y2=4, z2=4],1,air)"), a.calls);
    }

    @Test void selectionReadsReturnTheAdaptersShape() {
        assertEquals(new WorldEditAdapter.Box(1, 2, 3, 4, 5, 6), run("worldedit.selection", "{}"));
        assertEquals(new WorldEditAdapter.Cylinder(0, 0, 1, 1, 0, 1), run("worldedit.cylinder_selection", "{}"));
    }

    @Test void pasteDefaultsAreWorldEditsOwnPasteBuilderDefaults() {
        assertEquals(Map.of("pasted", true), run("worldedit.paste", "{\"x\":1,\"y\":2,\"z\":3}"));
        run("worldedit.paste", "{\"x\":1,\"y\":2,\"z\":3,\"ignoreAirBlocks\":true,\"copyEntities\":false}");
        assertEquals(List.of("paste(1,2,3,false,false,true,false)", "paste(1,2,3,true,false,false,false)"), a.calls);
    }

    @Test void rotateDefaultsTheOtherAxesToZero() {
        run("worldedit.rotate_clipboard", "{\"degreesY\":90}");
        run("worldedit.scale_clipboard", "{\"sx\":-1,\"sy\":1,\"sz\":2}");
        assertEquals(List.of("rotateClipboard(90.0,0.0,0.0)", "scaleClipboard(-1.0,1.0,2.0)"), a.calls);
    }

    @Test void generationPassesItsShapeThrough() {
        assertEquals(Map.of("changed", 11), run("worldedit.make_sphere",
                "{\"x\":0,\"y\":64,\"z\":0,\"radiusX\":2,\"radiusY\":2,\"radiusZ\":2,\"filled\":true,\"pattern\":\"glass\"}"));
        assertEquals(Map.of("changed", 13), run("worldedit.make_cylinder",
                "{\"x\":0,\"y\":64,\"z\":0,\"radiusX\":1,\"radiusZ\":1,\"height\":-3,\"filled\":false,\"pattern\":\"glass\"}"));
        assertEquals(Map.of("changed", 17), run("worldedit.make_pyramid",
                "{\"x\":0,\"y\":64,\"z\":0,\"size\":3,\"filled\":true,\"pattern\":\"sandstone\"}"));
        assertEquals(List.of("makeSphere(0,64,0,2.0,2.0,2.0,true,glass)", "makeCylinder(0,64,0,1.0,1.0,-3,false,glass)",
                "makePyramid(0,64,0,3,true,sandstone)"), a.calls);
    }

    // ---- volume caps: refused before WorldEdit is called ----

    @Test void boxEditsAreCappedBeforeWorldEditIsCalled() {
        String box = "{\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":9,\"y2\":9,\"z2\":9,\"pattern\":\"stone\"}"; // 1000 blocks
        assertEquals(Map.of("changed", 7), run("worldedit.set_blocks", box, 1_000));
        ApiException e = assertThrows(ApiException.class, () -> run("worldedit.set_blocks", box, 999));
        assertEquals("BAD_REQUEST", e.code);
        assertTrue(e.getMessage().contains("1000"));
        assertThrows(ApiException.class, () -> run("worldedit.copy", box.replace(",\"pattern\":\"stone\"", ""), 999));
        assertEquals(1, a.calls.size());
    }

    @Test void aWorldSizedBoxIsRejectedNotOverflowedIntoAPass() {
        // 60,000,001^3 blocks overflows a long; an unchecked product wraps to a value that can pass the cap.
        refused("worldedit.set_blocks", "{\"x1\":-30000000,\"y1\":-30000000,\"z1\":-30000000,"
                + "\"x2\":30000000,\"y2\":30000000,\"z2\":30000000,\"pattern\":\"stone\"}", "BAD_REQUEST");
        refused("worldedit.set_blocks", "{\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":30000001,\"y2\":0,\"z2\":0,\"pattern\":\"stone\"}", "BAD_REQUEST");
        assertEquals(List.of(), a.calls);
    }

    @Test void fractionalRadiiRoundOutwardSoTheCapNeverUnderRestricts() {
        // radius 2.5 reaches floor(2.5 + 0.5) = 3 blocks from the center: x and z span 7, y 0..0 spans 1 -> 49.
        String cyl = "{\"centerX\":0,\"centerZ\":0,\"radiusX\":2.5,\"radiusZ\":2.5,\"minY\":0,\"maxY\":0,\"pattern\":\"stone\"}";
        assertThrows(ApiException.class, () -> run("worldedit.set_blocks_cylinder", cyl, 48));
        assertEquals(Map.of("changed", 7), run("worldedit.set_blocks_cylinder", cyl, 49));
    }

    @Test void generatedShapesAreCappedByTheirFootprint() {
        String sphere = "{\"x\":0,\"y\":64,\"z\":0,\"radiusX\":2,\"radiusY\":2,\"radiusZ\":2,\"filled\":true,\"pattern\":\"stone\"}";
        assertThrows(ApiException.class, () -> run("worldedit.make_sphere", sphere, 124));   // 5^3 = 125
        run("worldedit.make_sphere", sphere, 125);
        String cylinder = "{\"x\":0,\"y\":64,\"z\":0,\"radiusX\":1,\"radiusZ\":1,\"height\":-3,\"filled\":true,\"pattern\":\"stone\"}";
        assertThrows(ApiException.class, () -> run("worldedit.make_cylinder", cylinder, 35));  // 3 x 4 x 3 = 36
        run("worldedit.make_cylinder", cylinder, 36);
        String pyramid = "{\"x\":0,\"y\":64,\"z\":0,\"size\":3,\"filled\":true,\"pattern\":\"stone\"}";
        assertThrows(ApiException.class, () -> run("worldedit.make_pyramid", pyramid, 195)); // 7 x 4 x 7 = 196
        run("worldedit.make_pyramid", pyramid, 196);
        assertEquals(3, a.calls.size());
    }

    @Test void pasteIsCappedByTheClipboardItWouldWrite() {
        a.clipboardVolume = 1_001;
        ApiException e = refused("worldedit.paste", "{\"x\":0,\"y\":64,\"z\":0}", "BAD_REQUEST");
        assertTrue(e.getMessage().contains("1001"));
        assertEquals(List.of(), a.calls);
        refused("worldedit.paste", "{\"x\":30000001,\"y\":64,\"z\":0}", "BAD_REQUEST");
    }

    // ---- input bounds ----

    @Test void nonFiniteNegativeAndDegenerateNumbersAreRejected() {
        refused("worldedit.make_sphere", "{\"x\":0,\"y\":0,\"z\":0,\"radiusX\":1e400,\"radiusY\":1,\"radiusZ\":1,\"filled\":true,\"pattern\":\"stone\"}", "BAD_REQUEST");
        refused("worldedit.make_sphere", "{\"x\":0,\"y\":0,\"z\":0,\"radiusX\":-1,\"radiusY\":1,\"radiusZ\":1,\"filled\":true,\"pattern\":\"stone\"}", "BAD_REQUEST");
        refused("worldedit.make_pyramid", "{\"x\":0,\"y\":0,\"z\":0,\"size\":-1,\"filled\":true,\"pattern\":\"stone\"}", "BAD_REQUEST");
        refused("worldedit.rotate_clipboard", "{\"degreesY\":1e400}", "BAD_REQUEST");
        refused("worldedit.scale_clipboard", "{\"sx\":0,\"sy\":1,\"sz\":1}", "BAD_REQUEST");
        refused("worldedit.scale_clipboard", "{\"sx\":1,\"sy\":" + (WorldEditActions.MAX_SCALE_FACTOR + 1) + ",\"sz\":1}", "BAD_REQUEST");
        assertEquals(List.of(), a.calls);
    }

    @Test void shapePointListsAreBoundedAndStrictlyTyped() {
        StringBuilder many = new StringBuilder("[");
        for (int i = 0; i <= WorldEditActions.MAX_SHAPE_POINTS; i++) many.append(i == 0 ? "" : ",").append("{\"x\":").append(i).append(",\"z\":0}");
        refused("worldedit.copy_polygon", "{\"points\":" + many + "],\"minY\":0,\"maxY\":0}", "BAD_REQUEST");
        refused("worldedit.copy_polygon", "{\"points\":[{\"x\":\"1\",\"z\":0}],\"minY\":0,\"maxY\":0}", "BAD_REQUEST");
        refused("worldedit.copy_polygon", "{\"points\":[1,2,3],\"minY\":0,\"maxY\":0}", "BAD_REQUEST");
        refused("worldedit.copy_convex_hull", "{\"vertices\":{}}", "BAD_REQUEST");
        assertEquals(List.of(), a.calls);
    }

    @Test void patternsAndMasksAreBoundedAndControlCharacterFree() {
        String box = "\"x1\":0,\"y1\":0,\"z1\":0,\"x2\":1,\"y2\":1,\"z2\":1";
        refused("worldedit.set_blocks", "{" + box + ",\"pattern\":\"\"}", "BAD_REQUEST");
        refused("worldedit.set_blocks", "{" + box + ",\"pattern\":\"" + "a".repeat(WorldEditActions.MAX_PATTERN_LENGTH + 1) + "\"}", "BAD_REQUEST");
        refused("worldedit.replace_blocks", "{" + box + ",\"mask\":\"dirt\\n\",\"pattern\":\"stone\"}", "BAD_REQUEST");
        assertEquals(List.of(), a.calls);
        run("worldedit.set_blocks", "{" + box + ",\"pattern\":\"" + "a".repeat(WorldEditActions.MAX_PATTERN_LENGTH) + "\"}");
    }

    // ---- schematics: confined to WorldEdit's schematics folder ----

    @Test void saveGoesIntoTheSchematicsFolderAndNowhereElse() throws Exception {
        assertEquals(Map.of("saved", true, "file", "house.schem"), run("worldedit.save_schematic", "{\"file\":\"house.schem\"}"));
        assertTrue(Files.isDirectory(a.schematicsDirectory));
        assertEquals(List.of("saveSchematic(" + a.schematicsDirectory.toRealPath().resolve("house.schem") + ")"), a.calls);
        for (String bad : List.of("../house.schem", "sub/house.schem", "/tmp/house.schem", "..\\\\house.schem", "house.txt", "house"))
            refused("worldedit.save_schematic", "{\"file\":\"" + bad + "\"}", "BAD_REQUEST");
        assertEquals(1, a.calls.size());
    }

    @Test void overwritingFollowsWorldEditsOwnRules() throws Exception {
        Files.createDirectories(a.schematicsDirectory);
        Files.writeString(a.schematicsDirectory.resolve("house.schem"), "existing");
        refused("worldedit.save_schematic", "{\"file\":\"house.schem\"}", "BAD_REQUEST");
        a.permissions = Set.of("worldedit.schematic.save");
        refused("worldedit.save_schematic", "{\"file\":\"house.schem\",\"overwrite\":true}", "PERMISSION_DENIED");
        a.permissions = Set.of("worldedit.schematic.save", "worldedit.schematic.delete");
        run("worldedit.save_schematic", "{\"file\":\"house.schem\",\"overwrite\":true}");
        assertEquals(1, a.calls.size());
    }

    @Test void loadNeedsAnExistingPlainFile() throws Exception {
        refused("worldedit.load_schematic", "{\"file\":\"missing.schem\"}", "NOT_FOUND");
        Files.createDirectories(a.schematicsDirectory.resolve("dir.schem"));
        refused("worldedit.load_schematic", "{\"file\":\"dir.schem\"}", "BAD_REQUEST");
        Files.writeString(a.schematicsDirectory.resolve("house.schem"), "data");
        assertEquals(Map.of("loaded", true, "file", "house.schem"), run("worldedit.load_schematic", "{\"file\":\"house.schem\"}"));
        assertEquals(List.of("loadSchematic(" + a.schematicsDirectory.resolve("house.schem").toRealPath() + ")"), a.calls);
    }

    @Test void aLinkInsideTheFolderCannotReachAFileOutsideIt() throws Exception {
        Files.createDirectories(a.schematicsDirectory);
        Path outside = Files.writeString(tmp.resolve("secret.schem"), "outside");
        try {
            Files.createSymbolicLink(a.schematicsDirectory.resolve("link.schem"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assumptions.abort("symbolic links unavailable: " + e);
        }
        refused("worldedit.load_schematic", "{\"file\":\"link.schem\"}", "BAD_REQUEST");
        refused("worldedit.save_schematic", "{\"file\":\"link.schem\",\"overwrite\":true}", "BAD_REQUEST");
        assertEquals("outside", Files.readString(outside));
        assertEquals(List.of(), a.calls);
    }
}

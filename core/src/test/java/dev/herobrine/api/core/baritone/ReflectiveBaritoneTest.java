package dev.herobrine.api.core.baritone;

import static org.junit.jupiter.api.Assertions.*;

import dev.herobrine.api.core.ApiException;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.*;

class ReflectiveBaritoneTest {
    enum PathEventFake { CALC_STARTED, CALC_FAILED, AT_GOAL }

    ReflectiveBaritone b;

    @BeforeEach void setUp() {
        FakeBaritone.reset();
        b = new ReflectiveBaritone(getClass().getClassLoader(), c -> "BlockPos(" + c[0] + "," + c[1] + "," + c[2] + ")");
    }

    @Test void typedProcessCallsReachTheApi() {
        assertTrue(b.installed());
        b.gotoPos(1, 2, 3);
        b.gotoXZ(4, 5);
        b.gotoBlock("minecraft:crafting_table");
        b.mine(List.of("minecraft:stone", "minecraft:coal_ore"), 0);
        b.mine(List.of("minecraft:iron_ore"), 16);
        b.farm(8);
        b.farm(0);
        b.explore(10, -10);
        b.follow(o -> "zombie".equals(o));
        b.cancel();
        assertEquals(List.of(
                "setGoalAndPath:GoalBlock{1,2,3}",
                "setGoalAndPath:GoalXZ{4,5}",
                "getToBlock:BOM{minecraft:crafting_table}",
                "mine:0:minecraft:stone|minecraft:coal_ore",
                "mine:16:minecraft:iron_ore",
                "farm:8:null",
                "farm:0:null",
                "explore:10,-10",
                "follow:true,false",
                "cancelEverything"), FakeBaritone.CALLS);
    }

    @Test void buildingUsesBlockPosFactory() {
        b.buildSchematic(new File("house.schem"), 1, 2, 3);
        b.clearArea(new BaritoneAdapter.Box(0, 0, 0, 2, 2, 2));
        assertEquals("buildFile:house.schem:BlockPos(1,2,3)", FakeBaritone.CALLS.get(0));
        assertEquals("clearArea:BlockPos(0,0,0)->BlockPos(2,2,2)", FakeBaritone.CALLS.get(1));
        ApiException e = assertThrows(ApiException.class, () -> b.buildSchematic(new File("bad.schem"), 0, 0, 0));
        assertEquals("BAD_REQUEST", e.code);
    }

    @Test void stateAndSettings() {
        assertTrue(b.isPathing());
        assertEquals("GoalBlock{1,2,3}", b.goal());
        assertEquals("Custom Goal", b.activeProcess());
        assertTrue(b.busy());
        var nodes = b.pathNodes(2);
        assertEquals(2, nodes.size());
        assertArrayEquals(new int[]{4, 5, 6}, nodes.get(1));
        var s = b.settings().get(0);
        assertEquals("allowBreak", s.get("name"));
        assertEquals("true", s.get("value"));
        assertEquals("false", s.get("default"));
    }

    @Test void worldCacheAndWaypoints() {
        var found = b.locationsOf("minecraft:diamond_ore", 2, 5, 6);
        assertEquals("find:minecraft:diamond_ore:2:5,6:4", FakeBaritone.CALLS.get(0));
        assertEquals(2, found.size());
        assertArrayEquals(new int[]{20, 65, 20}, found.get(1));
        var wp = b.waypoints().get(0);
        assertEquals("home", wp.get("name"));
        assertEquals("HOME", wp.get("tag"));
        assertEquals(100, wp.get("x"));
        assertEquals(-5, wp.get("z"));
    }

    @Test void fillAndReplaceBuildInMemorySchematicsAtTheBoxMinimum() {
        var box = new BaritoneAdapter.Box(5, 10, 5, 3, 8, 7); // corners in any order: min = (3,8,5), size 3x3x3
        b.fill(BaritoneAdapter.Shape.SOLID, "minecraft:stone", box);
        b.fill(BaritoneAdapter.Shape.WALLS, "minecraft:stone", box);
        b.fill(BaritoneAdapter.Shape.HOLLOW_SPHERE, "minecraft:glass", box);
        b.replace("minecraft:dirt", "minecraft:grass_block", box);
        assertEquals(List.of(
                "buildSchematic:fill:Fill(3x3x3,BOM{minecraft:stone}):BlockPos(3,8,5)",
                "buildSchematic:fill:Walls(Fill(3x3x3,BOM{minecraft:stone})):BlockPos(3,8,5)",
                "buildSchematic:fill:HollowSphere(Fill(3x3x3,BOM{minecraft:glass})):BlockPos(3,8,5)",
                "buildSchematic:replace:Replace(Fill(3x3x3,BOM{minecraft:grass_block}) where Lookup[minecraft:dirt]):BlockPos(3,8,5)"),
                FakeBaritone.CALLS);
    }

    @Test void settingsAreWrittenWithTheRightType() {
        b.setSetting("allowBreak", "false");
        b.setSetting("maxFallHeight", "7");
        b.setSetting("chatDebug", "hello world");
        var all = b.settings();
        assertEquals("false", all.get(0).get("value"));
        assertEquals("7", all.get(1).get("value"));
        assertEquals("Integer", all.get(1).get("type"));
        assertEquals("hello world", all.get(2).get("value"));
        assertEquals("BAD_REQUEST", assertThrows(ApiException.class, () -> b.setSetting("maxFallHeight", "abc")).code);
        assertEquals("BAD_REQUEST", assertThrows(ApiException.class, () -> b.setSetting("allowBreak", "yes")).code);
        assertEquals("NOT_FOUND", assertThrows(ApiException.class, () -> b.setSetting("nope", "1")).code);
        assertEquals("UNSUPPORTED", assertThrows(ApiException.class, () -> b.setSetting("nullValue", "1")).code);
        assertEquals("UNSUPPORTED", assertThrows(ApiException.class, () -> b.setSetting("weird", "1")).code);
    }

    @Test void waypointsCanBeSaved() {
        b.saveWaypoint("base", 1, 2, 3);
        var w = FakeBaritone.SAVED.get(0);
        assertEquals("base", w.getName());
        assertEquals("USER", w.getTag().name());
        assertEquals("BBP(1,2,3)", w.getLocation().toString());
    }

    @Test void missingApiReportsUnsupportedSoCallersCanFallBack() {
        FakeBaritone.lackExplore = true;
        ApiException e = assertThrows(ApiException.class, () -> b.explore(1, 2));
        assertEquals("UNSUPPORTED", e.code);
        assertEquals(501, e.status);
    }

    @Test void noActiveInstanceIsUnavailableNotACrash() {
        FakeBaritone.primaryIsNull = true;
        ApiException e = assertThrows(ApiException.class, () -> b.gotoPos(1, 2, 3));
        assertEquals("ACTION_UNAVAILABLE", e.code);
    }

    @Test void pathEventsAreCapturedThroughTheListenerProxy() {
        List<String> pushed = new ArrayList<>();
        b.addEventListener(pushed::add);
        assertNull(b.lastPathEvent());
        assertNull(FakeBaritone.listener);
        b.gotoPos(1, 2, 3); // first Baritone access registers the listener
        assertNotNull(FakeBaritone.listener);

        var l = FakeBaritone.listener;
        l.onTick("ignored");
        assertFalse(l.onSomethingBoolean());
        assertEquals(0, l.onSomethingInt());
        l.onPathEvent(PathEventFake.CALC_STARTED);
        l.onPathEvent(PathEventFake.AT_GOAL);

        assertEquals("AT_GOAL", b.lastPathEvent());
        assertEquals(List.of("CALC_STARTED", "AT_GOAL"), pushed);
        assertNotNull(l.toString());
        assertEquals(l, l);
    }
}

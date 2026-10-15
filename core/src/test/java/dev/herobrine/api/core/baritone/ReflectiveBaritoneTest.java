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
        b.clearArea(0, 0, 0, 2, 2, 2);
        assertEquals("build:house.schem:BlockPos(1,2,3)", FakeBaritone.CALLS.get(0));
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
        b.command("goto 1 2");
        assertEquals("cmd:goto 1 2", FakeBaritone.CALLS.get(0));
        assertThrows(ApiException.class, () -> b.command("a\nb"));
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
        long before = b.eventSeq();
        List<String> pushed = new ArrayList<>();
        b.addEventListener(pushed::add);
        assertNull(FakeBaritone.listener);
        b.gotoPos(1, 2, 3); // first Baritone access registers the listener
        assertNotNull(FakeBaritone.listener);

        var l = FakeBaritone.listener;
        l.onTick("ignored");
        assertFalse(l.onSomethingBoolean());
        assertEquals(0, l.onSomethingInt());
        l.onPathEvent(PathEventFake.CALC_STARTED);
        l.onPathEvent(PathEventFake.AT_GOAL);

        assertEquals(List.of("CALC_STARTED", "AT_GOAL"), b.eventsAfter(before));
        assertEquals(List.of("CALC_STARTED", "AT_GOAL"), pushed);
        assertEquals(List.of("AT_GOAL"), b.eventsAfter(b.eventSeq() - 1));
        assertNotNull(l.toString());
        assertEquals(l, l);
    }
}

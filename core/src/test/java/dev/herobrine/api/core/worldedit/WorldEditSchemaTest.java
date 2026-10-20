package dev.herobrine.api.core.worldedit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorldEditSchemaTest {
    @Test void everyOperationIsNamespacedAndUniquelyNamed() {
        Set<String> seen = new HashSet<>();
        for (var op : WorldEditSchema.OPERATIONS) {
            assertTrue(op.name().startsWith("worldedit."), () -> op.name() + " is not namespaced");
            assertTrue(seen.add(op.name()), () -> "duplicate operation name " + op.name());
            assertFalse(op.description().isBlank(), () -> op.name() + " has no description");
            for (var p : op.params()) {
                assertFalse(p.name().isBlank());
                assertFalse(p.type().isBlank());
            }
        }
        assertEquals(9, WorldEditSchema.OPERATIONS.size());
    }

    @Test void boxOperationsDeclareAllSixCorners() {
        for (String name : java.util.List.of("worldedit.set_blocks", "worldedit.replace_blocks", "worldedit.copy")) {
            var op = WorldEditSchema.OPERATIONS.stream().filter(o -> o.name().equals(name)).findFirst().orElseThrow();
            Set<String> paramNames = new HashSet<>();
            op.params().forEach(p -> paramNames.add(p.name()));
            assertTrue(paramNames.containsAll(java.util.List.of("x1", "y1", "z1", "x2", "y2", "z2")),
                    () -> name + " is missing a box corner param");
        }
    }
}

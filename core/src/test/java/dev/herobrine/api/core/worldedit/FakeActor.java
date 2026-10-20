package dev.herobrine.api.core.worldedit;

import com.sk89q.worldedit.entity.Player;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.session.SessionKey;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * A WorldEdit player actor. Like the real {@code NeoForgePlayer}, its session key is the player's UUID - here
 * derived from the name - so two actor objects for the same name share one {@code LocalSession}, and each
 * different name gets its own. It stands in a world, and holds every WorldEdit permission unless given a set.
 */
public final class FakeActor implements Player {
    public final String name;
    public FakeWorld world;
    private final Set<String> permissions;

    public FakeActor(String name) { this(name, new FakeWorld("overworld"), null); }
    public FakeActor(String name, FakeWorld world, Set<String> permissions) {
        this.name = name;
        this.world = world;
        this.permissions = permissions;
    }

    @Override public SessionKey getSessionKey() {
        UUID id = UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
        return () -> id;
    }

    @Override public boolean hasPermission(String permission) { return permissions == null || permissions.contains(permission); }
    @Override public Extent getExtent() { return world; }
    @Override public String toString() { return "Actor(" + name + ")"; }
}

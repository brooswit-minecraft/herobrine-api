package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.ApiException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/** Shared accessors and argument checks for the bindings. All of these must be called on the client thread. */
final class Game {
    private Game() {}

    static LocalPlayer player() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) throw ApiException.unavailable("Player is not connected to a world");
        return p;
    }

    static ClientLevel level() {
        ClientLevel l = Minecraft.getInstance().level;
        if (l == null) throw ApiException.unavailable("Not in a world");
        return l;
    }

    static int inRange(int v, int min, int max, String name) {
        if (v < min || v > max) throw ApiException.badRequest("'" + name + "' must be between " + min + " and " + max);
        return v;
    }
}

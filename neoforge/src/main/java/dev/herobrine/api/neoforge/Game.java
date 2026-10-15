package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.ApiException;
import dev.herobrine.api.core.Params;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

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

    static MultiPlayerGameMode gameMode() {
        MultiPlayerGameMode gm = Minecraft.getInstance().gameMode;
        if (gm == null) throw ApiException.unavailable("Not connected to a game");
        return gm;
    }

    /** The open menu, refused if the caller passed a containerId that is no longer the open one. */
    static AbstractContainerMenu menu(Params p) {
        AbstractContainerMenu menu = player().containerMenu;
        if (p.has("containerId") && p.integer("containerId") != menu.containerId)
            throw ApiException.unavailable("The open container changed (it is now " + menu.containerId + "); read container.open again");
        return menu;
    }
}

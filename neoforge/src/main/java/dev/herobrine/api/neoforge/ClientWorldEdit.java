package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.ApiContext;
import dev.herobrine.api.core.ApiException;
import dev.herobrine.api.core.GameThreadHop;
import dev.herobrine.api.core.ParamDef;
import dev.herobrine.api.core.Params;
import dev.herobrine.api.core.worldedit.ActorScope;
import dev.herobrine.api.core.worldedit.WorldEditBinding;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The client's WorldEdit endpoints (see {@link WorldEditBindings}). WorldEdit runs on the server, so the
 * client reaches it only through its own integrated server - singleplayer, or a LAN world it hosts - acting
 * as its own player there; connected to someone else's server there is no WorldEdit the client may call, and
 * every {@code worldedit.*} call says so (409). Requests arrive on the client thread and hop to the integrated
 * server's thread, which owns the world.
 */
final class ClientWorldEdit {
    private ClientWorldEdit() {}

    static void register(ApiContext ctx) {
        WorldEditBinding.register(ctx, WorldEditBindings.ADAPTER, new LocalPlayerScope(), ClientConfig.MAX_EDIT_VOLUME::get);
    }

    /** The client's own player, as the integrated server's {@code ServerPlayer} with the same UUID. */
    private static final class LocalPlayerScope implements ActorScope {
        @Override public List<ParamDef> params() { return List.of(); }

        @Override public <T> T call(Params p, Function<Object, T> work) {
            UUID id = Game.player().getUUID();
            IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server == null)
                throw ApiException.unavailable("WorldEdit runs on the server; the API reaches it only in singleplayer or a LAN world you host");
            return GameThreadHop.call(new DedicatedServerGameThread(server), server.isSameThread(),
                    WorldEditBindings.SERVER_START_TIMEOUT_MS, WorldEditBindings.SERVER_FINISH_TIMEOUT_MS, () -> {
                        ServerPlayer player = server.getPlayerList().getPlayer(id);
                        if (player == null) throw ApiException.unavailable("Your player has not joined the integrated server yet");
                        return work.apply(WorldEditBindings.actor(player));
                    });
        }
    }
}

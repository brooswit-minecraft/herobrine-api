package dev.herobrine.api.neoforge;

import dev.herobrine.api.core.ApiContext;
import dev.herobrine.api.core.ApiException;
import dev.herobrine.api.core.GameThreadHop;
import dev.herobrine.api.core.ParamDef;
import dev.herobrine.api.core.Params;
import dev.herobrine.api.core.worldedit.ActorScope;
import dev.herobrine.api.core.worldedit.ReflectiveWorldEdit;
import dev.herobrine.api.core.worldedit.WorldEditBinding;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/**
 * WorldEdit endpoints (MINECRAFT-476): every {@code worldedit.*} operation, through WorldEdit's typed Java API
 * ({@link ReflectiveWorldEdit}; its command dispatcher is never used), acting as a real WorldEdit actor that
 * WorldEdit itself builds for a {@code ServerPlayer} ({@link #actor}). WorldEdit lives on the server, so every
 * call runs on the server thread that owns the actor's world. Registered on a dedicated server by
 * {@link ServerLifecycle} (acting as a named online player) and on the client by {@link ClientWorldEdit}
 * (acting as the client's own player on its integrated server). References no {@code net.minecraft.client.*}
 * class.
 */
final class WorldEditBindings {
    private WorldEditBindings() {}

    static final ReflectiveWorldEdit ADAPTER =
            new ReflectiveWorldEdit(WorldEditBindings.class.getClassLoader(), WorldEditBindings::declaredVersion);

    private static final String WORLDEDIT_MOD_ID = "worldedit";

    /** How long a request waits for the server thread to pick its WorldEdit call up before withdrawing it -
     *  well inside {@code ApiServer}'s own game-thread timeout, so a refusal is reported in time. */
    static final long SERVER_START_TIMEOUT_MS = 5_000;
    /** How long a WorldEdit call the server thread has started may run before its outcome is reported unknown. */
    static final long SERVER_FINISH_TIMEOUT_MS = 60_000;

    private static final String NEOFORGE_ADAPTER = "com.sk89q.worldedit.neoforge.NeoForgeAdapter";
    /** A Minecraft player name, as {@code BaritoneBindings} matches one. */
    private static final String PLAYER_NAME = "[A-Za-z0-9_]{1,16}";

    /** Dedicated server: the operator key acts as the online player named by the {@code player} parameter. */
    static void registerServer(ApiContext ctx, MinecraftServer server) {
        WorldEditBinding.register(ctx, ADAPTER, new OnlinePlayerScope(server), ServerConfig.MAX_EDIT_VOLUME::get);
    }

    /**
     * The version WorldEdit declares to NeoForge (its {@code mods.toml}: "7.3.8+1379-7d32b45" for 7.3.8), which
     * the adapter falls back to when {@code WorldEdit.getVersion()} cannot read its own jar manifest.
     */
    private static String declaredVersion() {
        return ModList.get().getModContainerById(WORLDEDIT_MOD_ID)
                .map(c -> c.getModInfo().getVersion().toString()).orElse(null);
    }

    /**
     * WorldEdit's own actor for a server player: {@code NeoForgeAdapter.adaptPlayer(ServerPlayer)} (verified in
     * worldedit-neoforge-mc1.21 7.3.8: {@code public static NeoForgePlayer adaptPlayer(ServerPlayer)}, the same
     * call WorldEdit's own {@code NeoForgeWorldEdit.getSession(ServerPlayer)} makes). The result is a WorldEdit
     * {@code Player}: its session is keyed by the player's UUID, its world is the player's level, and its
     * permissions are WorldEdit's NeoForge permission provider's. Looked up by exact signature, by reflection,
     * so WorldEdit stays an optional dependency. Call on the server thread.
     */
    static Object actor(ServerPlayer player) {
        try {
            return Class.forName(NEOFORGE_ADAPTER, false, WorldEditBindings.class.getClassLoader())
                    .getMethod("adaptPlayer", ServerPlayer.class)
                    .invoke(null, player);
        } catch (ClassNotFoundException | LinkageError e) {
            throw ApiException.unavailable("WorldEdit's NeoForge platform is not installed");
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new ApiException(501, "UNSUPPORTED", "Installed WorldEdit does not provide NeoForgeAdapter.adaptPlayer(ServerPlayer)");
        } catch (InvocationTargetException e) {
            throw new ApiException(500, "ACTION_FAILED", "WorldEdit adaptPlayer failed: " + e.getCause());
        }
    }

    /**
     * Acts as an online player chosen by name. The dedicated server's one key is the operator's full-control
     * key; with it a request works inside that player's own WorldEdit session (selection, clipboard, history),
     * in that player's world and within that player's WorldEdit permissions - what an operator gets by running
     * a WorldEdit command through {@code /execute as <player>}.
     */
    private record OnlinePlayerScope(MinecraftServer server) implements ActorScope {
        @Override public List<ParamDef> params() {
            return List.of(ParamDef.required("player", "string",
                    "Online player to act as: their WorldEdit session (selection, clipboard, history), world and permissions"));
        }

        @Override public <T> T call(Params p, Function<Object, T> work) {
            String name = p.string("player");
            if (!name.matches(PLAYER_NAME)) throw ApiException.badRequest("Invalid player name: " + name);
            return GameThreadHop.call(new DedicatedServerGameThread(server), server.isSameThread(),
                    SERVER_START_TIMEOUT_MS, SERVER_FINISH_TIMEOUT_MS, () -> {
                        ServerPlayer player = server.getPlayerList().getPlayerByName(name);
                        if (player == null) throw ApiException.unavailable("No online player named " + name);
                        return work.apply(actor(player));
                    });
        }
    }
}

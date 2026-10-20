# Dedicated-server usage

The mod now loads on a NeoForge 1.21.1 **dedicated server** as well as the client
(`@Mod(MOD_ID)`, no `dist =` restriction). The dedicated server gets its own, independent
lifecycle, config and key -- it does not share a port, a token, or any client-only resource with
the client build.

## How it differs from the client

| | Client | Dedicated server |
|---|---|---|
| Entry point | `ClientLifecycle` (`FMLClientSetupEvent`) | `ServerLifecycle` (`ServerStartedEvent` / `ServerStoppingEvent`) |
| Game thread | `MinecraftGameThread` (`Minecraft.getInstance().execute`) | `DedicatedServerGameThread` (`MinecraftServer.execute`) |
| Config | `config/herobrine_api-client.toml` (`ModConfig.Type.CLIENT`) | `serverconfig/herobrine_api-server.toml`, per world (`ModConfig.Type.SERVER`) |
| Key(s) | `config/herobrine_api.token` (full) + `config/herobrine_api.read-token` (read-only) | one key, `config/herobrine_api-server.token` (full access only -- there is no read-only split server-side) |
| Default port | `25580` | `25581` |
| `/herobrine_api on\|off\|status\|regenerate` | yes, client command | not available -- toggle via `serverconfig/herobrine_api-server.toml` (`enabled`) and restart, or edit the key file directly; this is a deliberate scope limit (no server command console UI was added) |
| `capabilities.client` | `true` | `false` -- reported honestly, not silently omitted |
| Resources/actions | the full client set (world, entities, inventory, crafting, stations, chat, input, Baritone) | `server.info`, `server.players` only -- the client-only bindings are never registered server-side, because they are meaningless there, not because they "failed" |
| Multiplayer control-block | yes, `allowControlOnMultiplayer` | not applicable -- the server has no "someone else's server" concept |

Only a genuine **dedicated server** gets `ServerLifecycle`; a client's own embedded/integrated
server (singleplayer) keeps using the client's API exactly as before and does not also start a
second, server-side API instance.

## Enabling it

1. Install the mod jar in the dedicated server's `mods/` folder like any other NeoForge mod.
2. Start the server once. It creates:
   - `serverconfig/herobrine_api-server.toml` (the `enabled`/`port`/limits config, written into the
     world's save directory the first time that world starts)
   - `config/herobrine_api-server.token` (the operator key -- owner-only file permissions where the
     filesystem supports them)
3. `enabled = true` by default, listening on `127.0.0.1:25581`. Set `enabled = false` and restart to
   turn it off.
4. Read the key: `cat config/herobrine_api-server.token`. If that file is ever missing, deleted,
   empty, or whitespace-only, the *next* request (or server start) regenerates and persists a fresh
   one automatically -- no server restart required for that recovery.

```
H="Authorization: Bearer $(cat config/herobrine_api-server.token)"
curl -H "$H" 127.0.0.1:25581/api/v1/resources/server.info
curl -H "$H" 127.0.0.1:25581/api/v1/resources/server.players
```

## What is intentionally not here yet

This change is about the lifecycle, classloading and key-management split, not about growing the
server-side resource/action inventory. `server.info` and `server.players` exist to prove the
server-side game thread and HTTP server actually run; building out a server-side equivalent of the
client's world/entity/inventory/crafting resources is future work and tracked separately.

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
| Key(s) | `config/herobrine_api.token` (full) + `config/herobrine_api.read-token` (read-only), each its own file | one key, the `apiKey` field **inside** `serverconfig/herobrine_api-server.toml` itself (full access only -- there is no read-only split server-side, and no separate key file) |
| Default port | `25580` | `25581` |
| `/herobrine_api on\|off\|status\|regenerate` | yes, client command | not available -- toggle via `serverconfig/herobrine_api-server.toml` (`enabled`) and restart, or edit `apiKey` in that same file directly; this is a deliberate scope limit (no server command console UI was added) |
| `capabilities.client` | `true` | `false` -- reported honestly, not silently omitted |
| Resources/actions | the full client set (world, entities, inventory, crafting, stations, chat, input, Baritone, WorldEdit as your own player) | `server.info`, `server.players`, and WorldEdit acting as a named online player (below) -- the client-only bindings are never registered server-side, because they are meaningless there, not because they "failed" |
| Multiplayer control-block | yes, `allowControlOnMultiplayer` | not applicable -- the server has no "someone else's server" concept |

Only a genuine **dedicated server** gets `ServerLifecycle`; a client's own embedded/integrated
server (singleplayer) keeps using the client's API exactly as before and does not also start a
second, server-side API instance.

## Enabling it

1. Install the mod jar in the dedicated server's `mods/` folder like any other NeoForge mod.
2. Start the server once. It creates `serverconfig/herobrine_api-server.toml` in the world's save
   directory, with `enabled`/`port`/the size limits, **and** the operator key itself as the
   `apiKey` field in that same file -- generated fresh the first time the API starts (it is written
   back into the file as soon as it's generated; you don't need to restart to see it).
3. `enabled = true` by default, listening on `127.0.0.1:25581`. Set `enabled = false` and restart to
   turn it off.
4. Read the key: `grep apiKey serverconfig/herobrine_api-server.toml`. If that field is ever
   found missing, deleted, empty, or whitespace-only, the *next* request (or server start)
   regenerates it and writes it back into this same file automatically -- no server restart
   required for that recovery. (NeoForge does not live-watch this file for *external* edits while
   the server is running, so hand-editing `apiKey` yourself only takes effect on the next start.)

```
H="Authorization: Bearer $(grep apiKey serverconfig/herobrine_api-server.toml | cut -d'"' -f2)"
curl -H "$H" 127.0.0.1:25581/api/v1/resources/server.info
curl -H "$H" 127.0.0.1:25581/api/v1/resources/server.players
```

## WorldEdit

With WorldEdit 7.3.8 installed on the server, every `worldedit.*` operation is available (see
`docs/worldedit-coverage.md`). The server has no player of its own, so each call names an online player with
the required `player` parameter (`?player=` on the selection resources): it then works inside that player's own
WorldEdit session (selection, clipboard, history), in that player's world, and only within that player's
WorldEdit permissions - what an operator gets from `/execute as <player>` with a WorldEdit command. Edits run on
the server thread and are capped by `maxEditVolume` in this config file.

```
curl -H "$H" -d '{"action":"worldedit.set_blocks","parameters":{"player":"Steve","x1":0,"y1":64,"z1":0,"x2":4,"y2":64,"z2":4,"pattern":"stone"}}' 127.0.0.1:25581/api/v1/actions/execute
curl -H "$H" '127.0.0.1:25581/api/v1/resources/worldedit.selection?player=Steve'
```

## What is intentionally not here yet

This change is about the lifecycle, classloading and key-management split, not about growing the
server-side resource/action inventory. `server.info` and `server.players` exist to prove the
server-side game thread and HTTP server actually run; building out a server-side equivalent of the
client's world/entity/inventory/crafting resources is future work and tracked separately. (WorldEdit,
above, is the exception: it is a server-side mod, so the server is where it belongs.)

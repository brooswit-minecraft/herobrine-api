# Coverage matrix

Required artifact (spec §17). Status: **Tested** = automated tests in `core`; **Draft** = compiles in CI against
NeoForge 21.1.172 (Minecraft/NeoForge signatures are verified by the compiler) but has never been run in a real client;
Baritone calls are reflective, so the compiler does not check them. Update with every change.

Design rule: 1-to-1 with the game. No operations, queues, locks, batches or delays; actions return immediately.
The only state the API keeps is the two token hashes, SSE subscriber queues, and the set of keys it is holding.

## Platform (core, Minecraft-independent)

| Subsystem | Class/API | Resource | Actions | Events | Availability | Implementation | Limitations |
|---|---|---|---|---|---|---|---|
| HTTP server | `ApiServer` (JDK `HttpServer`) | `/api/v1` index | `POST /actions/execute` | — | client | Tested | No TLS (use a proxy); no WebSocket |
| Auth | `Tokens` | — | — | — | client | Tested | Two persistent tokens: full and read-only; `regenerate` rotates both; a missing/deleted/blank value is regenerated and persisted on the next access, no restart, via an OS file lock + atomic rename so concurrent accessors converge on one value |
| Control gating | `ApiContext.controlBlock` | — | refuses all actions while set (multiplayer without opt-in) | — | client | Tested | Platform decides when |
| Discovery | `OpenApi`, registries | `/resources /actions /events /capabilities /mods /schema /openapi.json` | — | — | client | Tested | OpenAPI response schemas are generic |
| Capabilities | `ApiContext.capabilitiesDocument` | `/capabilities` | — | — | client | Tested | Flags registered by the platform module |
| Missing data | `Unavailable`, `Reason` | embedded in values | — | — | client | Tested | Few resources use it yet |
| Limits | `Limits` | — | — | — | client | Tested (response size) | Concurrency, body size, event streams, page size |
| Late-run safety | `ApiServer.onGameThread` | — | — | — | client | Tested | A task that started in time but finishes late still ran |
| Pagination | `Page` | list resources | — | — | client | Tested | — |
| Input validation | `Validation` | — | chat text, command allowlist, ids, schematic names, box volume | — | client | Tested | — |
| Provider SPI | `ResourceProvider`, `ApiRegistrar` | namespaced `modid.*` | `modid.*` | — | client | Tested | — |
| Event stream | `EventBus` | `/events`, `/events/stream` (SSE, `?types=`) | — | declared types | client | Tested | No WebSocket; few producers (see Baritone) |
| Baritone adapter | `BaritoneAdapter`, `ReflectiveBaritone` | — | typed process / builder / settings / waypoint calls | path events | client | Tested against fakes of the API | Names/signatures from memory, unverified against a real build; no command-manager fallback |
| WorldEdit adapter | `WorldEditAdapter`, `ReflectiveWorldEdit` | — | typed edit / history / clipboard / schematic / selection / transform / generation calls (cuboid, cylinder, polygon, ellipsoid and convex-hull regions) | — | client | Tested against fakes of the API; every reflected name resolved against the real 7.3.8 jars in CI (`WorldEditProvenanceTest`) | Verified against real WorldEdit 7.3.8 sources (not from memory) - see docs/worldedit-coverage.md for the full version-qualified matrix and gaps |
| WorldEdit binding | `WorldEditBinding`, `WorldEditActions`, `ActorScope`, `SchematicFiles`, `GameThreadHop` | the selection reporters (`worldedit.selection`, `worldedit.*_selection`) | every other `worldedit.*` operation | — | client + server | Tested (through `ApiServer` with real tokens, the real adapter against fakes, and a client-shaped thread hop) | Each operation checks the WorldEdit permission node its own command checks; writes need the full token; edits, copies and pastes capped by `maxEditVolume`; schematics confined to WorldEdit's schematics folder (MINECRAFT-476) |

## Minecraft client (neoforge module — all Draft)

| Subsystem | Class/API | Resource | Actions | Events | Availability | Implementation | Limitations |
|---|---|---|---|---|---|---|---|
| Client runtime | `Minecraft`, `Window`, `SharedConstants` | `client.info` | — | — | LOCAL | Draft | Subset of §4.1 |
| Local player | `LocalPlayer` | `player.local` | `player.look` | — | SERVER_SYNCED | Draft | Subset of §4.7; no inventory/effects/abilities; look snaps instantly |
| World info | `Level` | `world.info` | — | — | SERVER_SYNCED | Draft | No gamerules/border/weather detail |
| World | `ClientLevel`, `BlockState`, `LevelChunk` | `world.block` (state, properties, light, fluid, biome), `world.blocks` (box scan: palette + index array, -1 = not loaded), `world.chunks` (loaded chunk coords), `world.block_entities` (type + synced SNBT) | — | — | SERVER_SYNCED | Draft | Scans capped by `maxScanVolume`; unloaded chunks reported as not loaded; client holds no data for chunks the server never sent |
| Entities | `ClientLevel#entitiesForRendering`, `LivingEntity` | `entities` (nearby, by radius/type), `entity` (detail: bounding box, tags, team, effects, equipment), `players` (tab list + tracked position) | — | — | SERVER_SYNCED | Draft | Only what the server syncs to the client; other players' inventories/stats are never available; no AI/brain data (server-only) |
| Inventory | `Inventory`, `AbstractContainerMenu`, `MultiPlayerGameMode#handleInventoryMouseClick`, `ItemStack.CODEC` | `player.inventory` (all non-empty slots + carried), `container.open` (open menu slots with GUI positions) | `inventory.click` (raw: PICKUP/QUICK_MOVE/SWAP/CLONE/THROW/QUICK_CRAFT/PICKUP_ALL), `move`, `swap`, `drop`, `select_slot`, `close`; clicks take an optional `containerId` guard | — | SERVER_SYNCED | Draft | Stacks include full SNBT (data components). Container contents only exist client-side while the menu is open; click results arrive on later ticks (re-read `container.open`); no creative-mode item spawning |
| Crafting | `RecipeManager`, `RecipeBookMenu`, `MultiPlayerGameMode#handlePlaceRecipe` | `recipes` (filter by output/type/unlocked; ingredients, shape, result), `container.open` gains `crafting` (result slot, grid size) | `crafting.place` (recipe-book click, `all` = shift), `crafting.take` | — | SERVER_SYNCED | Draft | Place and take are separate calls (server fills the grid on a later tick); no recipe-book unlock management; recipe availability per inventory not computed |
| Chat | `ClientChatReceivedEvent` | `chat.history` (last 500 received, `after` cursor) | — | `chat.message` | SERVER_SYNCED | Draft | Minecraft keeps chat lines private, so this buffers what it receives after the mod loads (the only chat state kept) |
| Input | `KeyMapping` | `input.keybindings` | `input.key` (setDown), `input.click` (KeyMapping.click) | — | LOCAL | Draft | No mouse/cursor/raw key events; keys held by the API are released on stop |
| Chat (send) | `ClientPacketListener`, `Validation` | — | `chat.send` (plain text ≤256), `chat.command` (no leading `/`) | — | CLIENT_REQUEST | Draft | no cooldown |
| Registries | `BuiltInRegistries`, `RegistryAccess` | `registry.list`, `registry.entries` (paged; cached for built-ins) | — | — | LOCAL | Draft | Ids only; no per-entry properties |
| Mods | `ModList`, `IModInfo` | `/mods`, `mods.detail` | — | — | LOCAL | Draft | No config; commands/keybindings not attributed to mods |
| Commands | `ClientPacketListener#getCommands` | `commands.known` | — | — | SERVER_SYNCED | Draft | Root names only |
| Runtime toggle | `ApiController`, `ClientConfig`, `/herobrine_api` | — | `on` / `off` / `status` / `regenerate` | — | LOCAL | Draft | Multiplayer detection uses `hasSingleplayerServer()`; no on-screen indicator |
| WorldEdit | `ClientWorldEdit`; actor `NeoForgeAdapter.adaptPlayer` (WorldEdit's own) of the integrated server's `ServerPlayer` | `worldedit.selection`, `worldedit.*_selection` | all other `worldedit.*` operations, as your own player | — | LOCAL | Draft | Singleplayer or a LAN world you host only (409 on someone else's server); requests hop from the client thread to the integrated server's thread; see docs/worldedit-coverage.md |
| Baritone | typed API only, via `BaritoneAdapter` (command manager never used) | `baritone.status`, `baritone.path`, `baritone.settings`, `baritone.waypoints`, `baritone.find`, `baritone.schematics` (names only) | see table below | `baritone.path_event` | LOCAL | Draft | Every API name/signature is from memory and unverified; a missing call returns 501; path cost not exposed; no cylinders |

### Baritone: typed API call behind each action (all from memory, unverified)
Actions return immediately; `baritone.cancel` stops a running task. A call the installed build lacks fails with 501.

| Action | Baritone API call |
|---|---|
| `goto` | `ICustomGoalProcess.setGoalAndPath(GoalBlock / GoalXZ)` |
| `goto_block` | `IGetToBlockProcess.getToBlock(BlockOptionalMeta)` |
| `thisway` | goal computed from your facing, then `setGoalAndPath(GoalXZ)` |
| `follow_player`, `follow_entity` | `IFollowProcess.follow(Predicate<Entity>)` |
| `explore`, `mine` (quantity), `farm` | `IExploreProcess.explore`, `IMineProcess.mineByName`, `IFarmProcess.farm` |
| `cancel` | `IPathingBehavior.cancelEverything` |
| `build` | `IBuilderProcess.build(name, File, origin)` |
| `clear_area` | `IBuilderProcess.clearArea(pos1, pos2)` |
| `fill` (solid/walls/shell/sphere/hollow_sphere), `replace` | `IBuilderProcess.build(name, ISchematic, origin)` with `FillSchematic` / `WallsSchematic` / `ShellSchematic` / `SphereSchematic` / `ReplaceSchematic`; boxes capped by `maxEditVolume` |
| `set_setting` | writes `Settings.Setting.value` (boolean, integer, long, float, double, string) |
| `waypoint_save` | `IWaypointCollection.addWaypoint(new Waypoint(name, USER, BetterBlockPos))` |
| `waypoint_goto` | looks the waypoint up, then `setGoalAndPath(GoalBlock)` |
| resource `baritone.find` | `getWorldProvider().getCurrentWorld().getCachedWorld().getLocationsOf(...)` |
| resource `baritone.waypoints` | `getWaypoints().getAllWaypoints()` |

Dropped because Baritone's API offers no call I could use without its command manager: `pause`, `resume`, `repath`,
`tunnel`, `axis`, `surface`, `blacklist`, selection copy/paste/clear, and a generic command action. Also not
exposed: `click` (needs a mouse), file commands (`explorefilter`, `saveall`, `reloadall`, `repack`), `come`,
`invert`, `gc`, `render`, `schematic`.

## Dedicated server (neoforge module — Draft, same unverified-at-runtime caveat as the client table)

| Subsystem | Class/API | Resource | Actions | Events | Availability | Implementation | Limitations |
|---|---|---|---|---|---|---|---|
| Lifecycle | `ServerLifecycle`, `ServerStartedEvent`/`ServerStoppingEvent` | — | — | — | server | Draft | Dedicated-server dist only; a client's own integrated/singleplayer server does not also start this |
| Game thread | `DedicatedServerGameThread` (`MinecraftServer#execute`) | — | — | — | server | Draft | Mirrors `MinecraftGameThread`'s `CompletableFuture` pattern |
| Config | `ServerConfig` (`ModConfig.Type.SERVER`) | — | — | — | server | Draft | Per-world `serverconfig/herobrine_api-server.toml` |
| Auth | `Tokens.loadOrCreateSingle(SecretAccessor)`, `ServerApiKeyAccessor` | — | — | — | server | Tested (core: generic accessor + locking; neoforge: real end-to-end `runServer` run) | One FULL-only key stored as the `apiKey` field inside `serverconfig/herobrine_api-server.toml` itself (not a separate file); no read/write split server-side |
| Honest capability reporting | `ApiContext.client = false` | `/capabilities` | — | — | server | Tested (core) | Client-only resources/actions are not registered at all server-side, rather than registered-and-broken |
| Minimal server resources | `ServerBindings` | `server.info`, `server.players` | — | — | server | Draft | Deliberately minimal; proves the lifecycle, not a server game-data inventory (out of scope here) |
| WorldEdit | `WorldEditBindings`; actor `NeoForgeAdapter.adaptPlayer` of a named online `ServerPlayer` | `worldedit.selection`, `worldedit.*_selection` (`?player=`) | all other `worldedit.*` operations (required `player`) | — | server | Draft | Acts inside that player's WorldEdit session, world and permissions; runs on the server thread; capped by the server's `maxEditVolume` (MINECRAFT-476) |

## Not started (spec reference)
- §4.8: living-entity AI (server-only); item/recipe definitions beyond what a stack carries
- §4.10, 4.12, 4.13, 4.15: GUI widgets, camera/raycast/screenshots, networking diagnostics, sound/particles, scoreboard
- §5: interaction, inventory ops, GUI actions, client settings, multiplayer connect/disconnect
- WebSocket and game-event producers (SSE transport exists)
- Generic object inspection, NBT/data-component serialization, spatial queries
- Server companion mod (`serverCompanion` capability reports `false`)
- The systematic Minecraft/NeoForge class audit (needs decompiled sources)

## Known open questions
- Everything in `neoforge/` is unproven at runtime: only compilation is verified.
- Whether `jdk.httpserver` is available on NeoForge's module path (reviewer concern; test in a real client).
- Whether Baritone's classes are visible from this mod's classloader; the same for WorldEdit's (MINECRAFT-477).

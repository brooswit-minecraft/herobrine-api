# Coverage matrix

Required artifact (spec §17). Status: **Tested** = automated tests in `core`; **Draft** = written but never
compiled or run (the build environment could not reach maven.neoforged.net; the `neoforge/` sources were only
syntax-checked with `javac`). Update with every change.

Design rule: 1-to-1 with the game. No operations, queues, locks, batches or delays; actions return immediately.
The only state the API keeps is the two token hashes, SSE subscriber queues, and the set of keys it is holding.

## Platform (core, Minecraft-independent)

| Subsystem | Class/API | Resource | Actions | Events | Availability | Implementation | Limitations |
|---|---|---|---|---|---|---|---|
| HTTP server | `ApiServer` (JDK `HttpServer`) | `/api/v1` index | `POST /actions/execute` | — | client | Tested | No TLS (use a proxy); no WebSocket |
| Auth | `Tokens` | — | — | — | client | Tested | Two persistent tokens: full and read-only; `regenerate` rotates both |
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

## Minecraft client (neoforge module — all Draft)

| Subsystem | Class/API | Resource | Actions | Events | Availability | Implementation | Limitations |
|---|---|---|---|---|---|---|---|
| Client runtime | `Minecraft`, `Window`, `SharedConstants` | `client.info` | — | — | LOCAL | Draft | Subset of §4.1 |
| Local player | `LocalPlayer` | `player.local` | `player.look` | — | SERVER_SYNCED | Draft | Subset of §4.7; no inventory/effects/abilities; look snaps instantly |
| World | `Level` | `world.info` | — | — | SERVER_SYNCED | Draft | No gamerules/border/biome/weather detail |
| Input | `KeyMapping` | `input.keybindings` | `input.key` (setDown), `input.click` (KeyMapping.click) | — | LOCAL | Draft | No mouse/cursor/raw key events; keys held by the API are released on stop |
| Chat | `ClientPacketListener`, `Validation` | — | `chat.send` (plain text ≤256), `chat.command` (no leading `/`) | — | CLIENT_REQUEST | Draft | No chat history/events; no cooldown |
| Registries | `BuiltInRegistries`, `RegistryAccess` | `registry.list`, `registry.entries` (paged; cached for built-ins) | — | — | LOCAL | Draft | Ids only; no per-entry properties |
| Mods | `ModList`, `IModInfo` | `/mods`, `mods.detail` | — | — | LOCAL | Draft | No config; commands/keybindings not attributed to mods |
| Commands | `ClientPacketListener#getCommands` | `commands.known` | — | — | SERVER_SYNCED | Draft | Root names only |
| Runtime toggle | `ApiController`, `ClientConfig`, `/herobrine_api` | — | `on` / `off` / `status` / `regenerate` | — | LOCAL | Draft | Multiplayer detection uses `hasSingleplayerServer()`; no on-screen indicator |
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

## Not started (spec reference)
- §4.2–4.6, 4.8–4.9: blocks, chunks, block entities, entities, other players, living-entity AI, items/inventories/data components
- §4.10, 4.12, 4.13, 4.15: GUI/widgets, camera/raycast/screenshots, networking diagnostics, sound/particles
- §5: interaction, inventory ops, GUI actions, client settings, multiplayer connect/disconnect
- WebSocket and game-event producers (SSE transport exists)
- Generic object inspection, NBT/data-component serialization, spatial queries
- Server companion mod (`serverCompanion` capability reports `false`)
- The systematic Minecraft/NeoForge class audit (needs decompiled sources)

## Known open questions
- NeoForge 1.21.1 / Mojang-mapped signatures in `neoforge/` are from memory and must be verified by compiling; the pinned NeoForge version is a guess.
- Whether `jdk.httpserver` is available on NeoForge's module path (reviewer concern; test in a real client).
- Whether Baritone's classes are visible from this mod's classloader.

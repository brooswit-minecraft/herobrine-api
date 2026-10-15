# Coverage matrix

Required artifact (spec §17). Status values: **Tested** = covered by automated tests in `core`;
**Draft** = written but never compiled or run (the `neoforge` module could not reach maven.neoforged.net);
**Not started**. Update this file with every change.

## Platform / architecture (core, Minecraft-independent)

| Subsystem | Class/API | Resource | Actions | Events | Availability | Implementation | Limitations |
|---|---|---|---|---|---|---|---|
| HTTP server | `ApiServer` (JDK `HttpServer`) | `/api/v1` index | — | — | client | Tested | No TLS (use a proxy); no WebSocket |
| Auth & permissions (§12) | `TokenStore` | `/admin/tokens` | create / revoke | — | client | Tested | Tokens are in-memory, regenerated on start; no per-IP controls |
| Audit (§12.4) | `AuditLog` | `/admin/audit` (every batch step is logged individually) | — | — | client | Tested | Ring buffer of 1000 |
| Limits (§13.2) | `Limits` | — | — | — | client | Tested (concurrency, rate, response size, batch size, body size) | No max radius/block/entity limits yet (no spatial queries exist) |
| Pagination (§13.3) | `Page` | used by list endpoints | — | — | client | Tested | — |
| Discovery (§3.5) | `OpenApi`, registries | `/resources /actions /events /capabilities /mods /schema /openapi.json` | — | — | client | Tested | OpenAPI response schemas are generic; no JSON Schema for resource values |
| Capabilities (§11.3) | `ApiContext.capabilitiesDocument` | `/capabilities` | — | — | client | Tested | Flags are registered by the platform module |
| Missing-data markers (§14.4) | `Unavailable`, `Reason` | embedded in values | — | — | client | Tested | Used by few resources so far |
| Result envelope (§10.4) | `Stage`, `Staged` | — | all | — | client | Tested | Handlers must opt in to SERVER_ACKNOWLEDGED / CONFIRMED |
| Operations (§10.2) | `Operations` | `/operations`, `/operations/{id}`, `POST …/cancel` | cancel | — | client | Tested | History capped at 500 |
| Queuing (§10.3) | `ActionExecutor` | `/actions/batch` | delayed, sequential, concurrent | — | client | Tested | No cron-style scheduling |
| Resource locks (§10.3) | `Operations.checkLocks` | — | — | — | client | Tested | Locks are advisory names chosen per action |
| Provider SPI (§7.2) | `ResourceProvider`, `ApiRegistrar` | namespaced `modid.*` | `modid.*` | — | client | Tested | Spec §7.2 was truncated at "Example:" — API shape is my interpretation |
| Control gating | `ApiContext.controlBlock`, `ActionExecutor.checkControl` | — | refuses `control.*` actions (also re-checked when delayed/sequenced steps run) | — | client | Tested (core) | Platform decides when to block |
| Events | `EventBus` | `/events`, `/events/stream` (SSE, `?types=`) | — | declared types only | client | Tested | No WebSocket; nothing publishes game events yet |

## Minecraft client (neoforge module — all Draft)

| Subsystem | Class/API | Resource | Actions | Events | Availability | Implementation | Limitations |
|---|---|---|---|---|---|---|---|
| Client runtime | `Minecraft`, `Window`, `SharedConstants` | `client.info` | — | — | LOCAL | Draft | Subset of §4.1 only |
| Local player | `LocalPlayer` | `player.local` | `player.look` | — | SERVER_SYNCED | Draft | Subset of §4.7; no inventory/effects/abilities |
| World | `Level` | `world.info` | — | — | SERVER_SYNCED | Draft | No gamerules/border/biome/weather detail |
| Input | `KeyMapping` | `input.keybindings` | `input.hold` | — | LOCAL | Draft | No mouse, cursor or raw key events |
| Chat | `ClientPacketListener`, `Validation` | — | `chat.send` (plain text, ≤256 chars, no control chars/§/leading `/`), `chat.command` (needs `control.server`) | — | CLIENT_REQUEST | Draft (validation Tested in core) | No chat history/events; no per-action cooldown yet |
| Registries (§7.1, §4.16) | `BuiltInRegistries`, `RegistryAccess` | `registry.list`, `registry.entries` (paged, cached for built-ins) | — | — | LOCAL | Draft | Ids only; no per-entry property serialization |
| Mods (§7.1) | `ModList`, `IModInfo` | `/mods`, `mods.detail` (deps, registered counts per registry) | — | — | LOCAL | Draft | No config exposure; commands/keybindings/capabilities/events not attributed to mods |
| Commands | `ClientPacketListener#getCommands` | `commands.known` | — | — | SERVER_SYNCED | Draft | Root names only; no client commands, syntax or arg types |
| Runtime toggle | `ApiController`, `ClientConfig`, `/herobrine_api` | — | `on` / `off` / `status` | — | LOCAL | Draft | Multiplayer control block uses `hasSingleplayerServer()`; no on-screen indicator yet |
| Baritone (§6) | `BaritoneAdapter` / `ReflectiveBaritone` (core, tested against fakes) over `baritone.api.*`; command manager only as fallback | `baritone.status` (incl. recent path events), `baritone.path` (both need `read.world`), `baritone.settings`, `baritone.schematics` (names only) | See the Baritone table below | event `baritone.path_event` (CALC_FAILED, AT_GOAL, CANCELED, …) | LOCAL | Draft | API names/signatures are from memory and unverified against a real build; path cost not exposed; setting changes go through `set`; `sel` fill/replace syntax unverified; no cylinders |

### Baritone: which path each action takes
Typed API first; if the installed build lacks the call the adapter reports `UNSUPPORTED` and the binding falls back to the command in the last column. Every Baritone action takes the `player.movement` lock; bulk edits need `control.build` and are capped by `maxEditVolume`.

| Action | Typed API call (from memory) | Fallback / command-only |
|---|---|---|
| `goto`, `goto_block` | `ICustomGoalProcess.setGoalAndPath(GoalBlock/GoalXZ)`, `IGetToBlockProcess.getToBlock` | `goto …` |
| `follow_player`, `follow_entity` | `IFollowProcess.follow(Predicate)` | `follow player/entity …` |
| `explore`, `mine` | `IExploreProcess.explore`, `IMineProcess.mineByName` | `explore …`, `mine …` |
| `cancel` | `IPathingBehavior.cancelEverything` | `cancel` |
| `build` | `IBuilderProcess.build(name, file, origin)` | `build name x y z` |
| `clear_area` | `IBuilderProcess.clearArea` | `sel pos1/pos2/cleararea` |
| `fill`, `replace`, `clear_selection` | — (schematic composition classes too version-dependent) | `sel …` only |
| `pause`, `resume`, `repath`, `set_setting` | — | command only |
| `command` | — | allowlisted escape hatch |

Operations started by process actions end with Baritone's real outcome from path events: `AT_GOAL` (completed), `CALC_FAILED` (operation `failed` with a reason), `CANCELED`, or `IDLE` for goal-less processes (mine/explore/follow).

## Not started (spec reference)
- §4.2–4.6, 4.8–4.9: blocks, chunks, block entities, entities, other players, living-entity AI, items/inventories/data components
- §4.10, 4.12, 4.13, 4.15: GUI/widgets, camera/raycast/screenshots, networking diagnostics, sound/particles
- §5: interaction, inventory ops, GUI actions, client settings, multiplayer connect/disconnect
- §3.2 / Phase 4: WebSocket and game-event publishing (SSE transport exists, no producers)
- Phase 5: generic object inspection, NBT/data-component serialization, spatial queries
- Phase 7: server companion mod (`serverCompanion` capability reports `false`)
- Phase 8 audit of Minecraft/NeoForge classes — requires the decompiled sources, unavailable here
- §10.2/§11 details not yet modeled: per-operation timeouts configured server-side, JSON Schema for resource values

## Known open questions
- Exact NeoForge 1.21.1 / Mojang-mapped signatures in `neoforge/` are from memory and must be verified by compiling.
- Whether Baritone's classes are visible to this mod's classloader depends on the Baritone build; the optional dependency in `neoforge.mods.toml` is meant to help.

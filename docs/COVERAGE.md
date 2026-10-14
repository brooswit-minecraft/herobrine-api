# Coverage inventory

Status of the design handoff (sections refer to the original spec). **The `neoforge` module has not been
compiled** — the build environment could not reach maven.neoforged.net. Treat it as a first draft.

## Done and tested (`core`, no Minecraft dependency)
- Loopback-only HTTP server with bearer-token auth (§3.1, §3.4)
- Game-thread marshalling interface; long-running operations with status + cancel (§3.4)
- Discovery: `/api/v1`, `/resources`, `/actions`, `/events`, `/capabilities`, `/mods`, `/schema`, `/openapi.json` (§3.5)
- Generic action dispatch with parameter validation: `POST /actions/execute` (§3.3)
- Server-Sent Events: `GET /events/stream` (§3.2)
- `Availability` tagging on every resource/action (§2.3)

## Seeded in `neoforge` (unverified)
- Resources: `client.info`, `player.local`, `world.info`, `registry.list`
- Actions: `chat.send`, `player.look`, `input.hold`
- `/mods` and `/capabilities` (detects Baritone)

## Not started
- WebSocket (JDK has none; needs a library or hand-rolled framing) — SSE only for now
- Everything else in §4 (blocks, chunks, entities, inventory, GUI, input state, camera, networking, chat history, sound, registries beyond names, persistent data) and §5 (inventory ops, GUI, interaction, settings, multiplayer)
- Baritone integration (§6) — spec was truncated at §6.3
- Screenshots / binary responses
- Server companion mod

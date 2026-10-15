# herobrine-api

Minecraft Universal API mod: a thin HTTP API embedded in the Minecraft client (NeoForge 1.21.1 first).
The design goal is **as close to 1-to-1 with the game as possible**: reads return what Minecraft says right
now, actions do the thing and return what it returned. The API keeps no operation, queue, lock or session state.

- `core/` — platform-independent server, registries, Baritone adapter. `./gradlew :core:test`
- `neoforge/` — Minecraft bindings (opt-in: `./gradlew -PwithNeoForge :neoforge:build`; compiles in CI against NeoForge 21.1.172 but **has never been run in a real client**)
- `docs/COVERAGE.md` — what is implemented vs. the spec

## CI
`.github/workflows/ci.yml` runs the `core` tests and the NeoForge build on every PR and push to `main`. Publishing a GitHub release
(`release.yml`) rebuilds and attaches `herobrine-api-*.jar` to it.

## Usage
The API is **on by default** and listens on `127.0.0.1:25580` (`port` in `config/herobrine_api-client.toml`).

**Two tokens**, created once and kept in `config/` (owner-only permissions, they survive restarts):
- `herobrine_api.token` — full control (reads + actions)
- `herobrine_api.read-token` — read only (every resource and the event stream; actions are refused)

In-game: `/herobrine_api on|off|status|regenerate`. `off` stops the server and releases any keys it was holding
(the tokens stay); `regenerate` replaces both tokens immediately.
While connected to a multiplayer server, **actions are refused** unless `allowControlOnMultiplayer = true`
in the config (reads still work).

```
H="Authorization: Bearer $(cat config/herobrine_api.token)"
curl -H "$H" localhost:25580/api/v1/resources/player.local
curl -H "$H" -d '{"action":"input.key","parameters":{"key":"key.forward","down":true}}' localhost:25580/api/v1/actions/execute
curl -H "$H" -d '{"action":"baritone.goto","parameters":{"x":100,"z":-40}}' localhost:25580/api/v1/actions/execute
curl -H "$H" localhost:25580/api/v1/resources/baritone.status
curl -H "$H" -N localhost:25580/api/v1/events/stream?types=baritone.path_event
```

- Reads: `GET /api/v1/resources/{id}` → `{success, resource, availability, source, observedTick, value}`.
- Actions: `POST /api/v1/actions/execute` `{action, parameters}` → `{success, action, result}`, or
  `{success:false, error:{code, message}}`. Actions return immediately. Anything that takes time (Baritone pathing,
  held keys) is started by one call, observed through resources/events, and stopped by another call
  (`baritone.cancel`, `input.key` with `down:false`).
- Game state: blocks and block entities (`world.block`, `world.blocks`, `world.block_entities`), loaded chunks, nearby entities and players, your inventory and the open container, and chat history; `GET /api/v1/resources` lists everything with descriptions and parameters.
- Discovery: `/resources`, `/actions`, `/events`, `/capabilities`, `/mods`, `/schema`, `/openapi.json`.
- Data the client can't legitimately have is returned as `{"available": false, "reason": "SERVER_ONLY", ...}`, never invented.

Baritone actions use its typed Java API only (reflection, optional dependency); its command manager is never used.
If the installed build lacks a call, the action fails with `501 UNSUPPORTED` rather than guessing. Path events
(`AT_GOAL`, `CALC_FAILED`, `CANCELED`, …) are published on the `baritone.path_event` event stream, and
`baritone.status` shows the latest. Bulk edits (`fill`, `clear_area`, `replace`) are capped by `maxEditVolume`;
`build` reads schematics only by bare file name from `<game dir>/schematics`.

## Extending from another mod
Subscribe to `RegisterApiEvent` on `NeoForge.EVENT_BUS` and call `event.install(provider)` with a
`ResourceProvider`; everything it registers is namespaced `yourmodid.*`.

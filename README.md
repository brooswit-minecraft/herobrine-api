# herobrine-api

Minecraft Universal API mod: an HTTP API embedded in the Minecraft client (NeoForge 1.21.1 first).

- `core/` — platform-independent server, registries, dispatch, OpenAPI. `gradle :core:test`
- `neoforge/` — Minecraft bindings (opt-in: `gradle -PwithNeoForge :neoforge:build`; currently uncompiled)
- `docs/COVERAGE.md` — what is implemented vs. the spec

## Usage
The API is **on by default** and listens on `127.0.0.1:25580` (`port` in `config/herobrine_api-client.toml`).
Toggle it in-game with `/herobrine_api on|off|status`; `off` stops the server, cancels running operations and
deletes the token file. While connected to a multiplayer server, **control actions are refused** unless
`allowControlOnMultiplayer = true` in the config (read-only endpoints still work). A fresh admin token is
written to `config/herobrine_api.token` (owner-only permissions) each time the server starts. Create scoped tokens via `POST /api/v1/admin/tokens`
(`{"name":"agent","permissions":["read.*","control.input"]}`) and revoke them with `DELETE /api/v1/admin/tokens/{id}`.

```
T=$(cat config/herobrine_api.token); H="Authorization: Bearer $T"
curl -H "$H" localhost:25580/api/v1/resources/player.local
curl -H "$H" -d '{"action":"input.hold","parameters":{"key":"key.jump","ticks":5}}' localhost:25580/api/v1/actions/execute
curl -H "$H" -d '{"action":"baritone.goto","parameters":{"x":100,"z":-40},"timeoutMs":120000}' localhost:25580/api/v1/actions/execute
curl -H "$H" localhost:25580/api/v1/operations
curl -H "$H" -d '{"mode":"sequential","actions":[{"action":"player.look","parameters":{"yaw":90}},{"action":"chat.send","parameters":{"message":"hi"},"delayTicks":20}]}' localhost:25580/api/v1/actions/batch
```

Every action returns `{"success":…, "action":…, "stage":…, "result":…}` or `{"success":false,"error":{"code","message"}}`.
Long-running actions return an `operationId`; poll `/operations/{id}` or `POST /operations/{id}/cancel`.
Bulk block editing (`baritone.fill`, `clear_area`, `replace`, `build`) needs its own `control.build` permission, caps the box size (`maxEditVolume` in the config) and only reads schematics by bare file name from `<game dir>/schematics`.
Actions that share a lock (e.g. `player.movement`) cannot be driven by two tokens at once.

## Extending from another mod
Subscribe to `RegisterApiEvent` on `NeoForge.EVENT_BUS` and call `event.install(provider)` with a
`ResourceProvider`; everything it registers is namespaced `yourmodid.*`.

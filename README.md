# herobrine-api

Minecraft Universal API mod: an HTTP API embedded in the Minecraft client (NeoForge 1.21.1 first).

- `core/` — platform-independent server, registries, dispatch, OpenAPI. `gradle :core:test`
- `neoforge/` — Minecraft bindings (opt-in: `gradle -PwithNeoForge :neoforge:build`; currently uncompiled)
- `docs/COVERAGE.md` — what is implemented vs. the spec

## Usage
The mod listens on `127.0.0.1:25580` (override `-Dherobrine.api.port=`). The bearer token is written to
`config/herobrine_api.token`.

```
curl -H "Authorization: Bearer $(cat config/herobrine_api.token)" localhost:25580/api/v1/resources/player.local
curl -H "Authorization: Bearer …" -d '{"action":"input.hold","parameters":{"key":"key.jump","ticks":5}}' localhost:25580/api/v1/actions/execute
```

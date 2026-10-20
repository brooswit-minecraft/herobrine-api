# WorldEdit adapter: version and method coverage

Companion to the "WorldEdit adapter" row in `docs/COVERAGE.md` (spec §17). MINECRAFT-474 defined the typed
contract (`WorldEditAdapter`) and a reflective implementation (`ReflectiveWorldEdit`) with verified signatures,
absence detection and version-compatibility detection. MINECRAFT-476 binds it: every operation is an HTTP
action or resource, acting as a real WorldEdit actor built from a Minecraft player, on the server thread - see
[HTTP binding and actor context](#minecraft-476-http-binding-and-actor-context) below. Running it in a real
game with WorldEdit installed is MINECRAFT-477.

## Verified version

- **WorldEdit 7.3.8** (`com.sk89q.worldedit:worldedit-core:7.3.8`, platform artifact
  `com.sk89q.worldedit:worldedit-neoforge-mc1.21:7.3.8`), downloaded from `repo.enginehub.org` and inspected
  directly (sources jar), not assumed from documentation.
- Its `META-INF/neoforge.mods.toml` declares `minecraft` versionRange `[1.21,1.22)` and `neoforge`
  versionRange `[21.0.16-beta,)` - both satisfied by this repo's NeoForge build (`21.1.172`, i.e. Minecraft
  1.21.1; see `neoforge/build.gradle`).
- **Version ceiling, a real finding, not an assumption**: `worldedit-neoforge-mc1.21` tops out at `7.3.8`.
  Every later WorldEdit release (`7.3.9`+) only publishes `worldedit-neoforge-mc1.21.3` and newer
  per-patch artifacts, which do **not** declare 1.21.1 in their version range. So 7.3.8 is not an arbitrary
  pin - it is the newest WorldEdit build that actually supports this repo's exact Minecraft/NeoForge version.
  A future bump of this repo's NeoForge version past 1.21.1 should re-run this check rather than assume the
  next WorldEdit release still applies.
- `ReflectiveWorldEdit` enforces this at runtime: the version must equal `"7.3.8"` exactly, or match it with a
  build-metadata suffix (e.g. `"7.3.8+1379-7d32b45"`), or every adapter call fails fast with
  `501 UNSUPPORTED` naming the installed version. The version is `WorldEdit.getVersion()`, which reads the
  `WorldEdit-Version` attribute of the manifest of the jar holding `WorldEditManifest` - `7.3.8` in the
  published `worldedit-neoforge-mc1.21-7.3.8.jar` (checked). That lookup only understands a `jar:` class URL
  (`WorldEditManifest.java:70`) and otherwise reports `"(unknown)"` (`:61`); NeoForge's module class loader
  (securejarhandler 3.0.8 in this build) serves classes from `union:` URLs, so under NeoForge the adapter
  falls back to the version WorldEdit declares to the loader (its `mods.toml`: `7.3.8+1379-7d32b45`, read
  through NeoForge's `ModList`; MINECRAFT-476). A version WorldEdit does report is never overridden. An earlier draft matched any `"7.3."`-prefixed version,
  which would have silently accepted an unverified `7.3.9` (or `7.3.80`, etc.) instead of rejecting it - only
  the one release actually verified here is accepted.

## Coverage matrix (`WorldEditAdapter` / `ReflectiveWorldEdit`)

All reflective, like the Baritone adapter; the compiler does not check these names, so each one below was
read directly out of the WorldEdit 7.3.8 sources (file:line noted in commit review) rather than recalled
from general WorldEdit knowledge. Status for everything here is **Tested against fakes** (unit tests under
`core/src/test/java`, fakes recompiled against the verified real signatures) - no real client run.

| Operation family | Contract method | WorldEdit API call | Notes |
|---|---|---|---|
| Edits | `setBlocks(actor, box, pattern)` | `EditSession.setBlocks(Region, Pattern)` | Region is a `CuboidRegion` built from the box; `Pattern` parsed via `WorldEdit.getPatternFactory().parseFromInput` (WorldEdit's own pattern syntax, e.g. `stone`, `50%stone,50%dirt`) |
| Edits | `replaceBlocks(actor, box, mask, pattern)` | `EditSession.replaceBlocks(Region, Mask, Pattern)` | `Mask` parsed via `WorldEdit.getMaskFactory().parseFromInput` (WorldEdit's own mask syntax, e.g. `stone,dirt`) |
| Edits | `setBlocks`/`replaceBlocks` overloaded for `Cylinder`, `Polygon`, `Ellipsoid`, `ConvexHull` | Same `EditSession.setBlocks`/`replaceBlocks`, with the corresponding region type instead of `CuboidRegion` | Proves the edit methods are region-shape-agnostic - one shared `setBlocksOnRegion`/`replaceBlocksOnRegion` helper, no per-shape reimplementation |
| Regions | `WorldEditAdapter.Box` → `CuboidRegion(BlockVector3, BlockVector3)` | — | — |
| Regions | `WorldEditAdapter.Cylinder` → `CylinderRegion(BlockVector3, Vector2, int minY, int maxY)` (verified 4-arg ctor; `Vector2.at(radiusX, radiusZ)`, center's Y discarded internally) | — | — |
| Regions | `WorldEditAdapter.Polygon` (points + minY/maxY) → `Polygonal2DRegion(World world, List<BlockVector2> points, int minY, int maxY)`, `world = null` | — | Verified fully world-independent (`world` may be `null` - its own no-arg/`World`-only constructors delegate to this one that way). **Deliberately not built via the no-arg constructor plus separate `setMinimumY`/`setMaximumY` calls**: verified `recalculate()` (called by every mutator, including both those setters) re-sorts `minY`/`maxY` from whatever they *currently* hold - `recalculate()`'s own body does `minY = Math.min(oldMinY, oldMaxY); maxY = Math.max(oldMinY, oldMaxY)`. Starting from the default `0..0` and calling `setMinimumY(64)` then `setMaximumY(70)` one at a time silently produces `0..70`, not the requested `64..70` - the first call's re-sort flips the still-`0` max past the newly-set `64` min before the second call ever runs. An earlier draft of this adapter did exactly that and shipped the bug; fixed by passing both bounds to the 4-arg constructor in one call, so `recalculate()` only runs once, after both fields already hold their final values. At least 3 points required: fewer and the real `Polygonal2DRegion.contains(BlockVector3)` always returns false (a silently-always-empty region) - rejected explicitly as `400` instead |
| Regions | `WorldEditAdapter.Ellipsoid` (center + 3 radii) → `EllipsoidRegion(BlockVector3 center, Vector3 radius)` (verified world-independent 2-arg ctor) | — | The ctor's own javadoc names its parameters "pos1"/"pos2", which reads like two corners; verified by reading the constructor body that they are actually `center, radius` (it delegates to the 3-arg `world, center, radius` ctor) - not assumed from the parameter names |
| Regions | `WorldEditAdapter.ConvexHull` (≥4 vertices) → `ConvexPolyhedralRegion(null)` (its `World` parameter is verified `@Nullable`) + repeated `addVertex(BlockVector3)`, checked against `getTriangles().size() > 2` | — | **Not** `isDefined()`: verified it is just `!triangles.isEmpty()`, which is already `true` at exactly 3 (degenerate, coincident) vertices - before any 4th, non-coplanar vertex exists, and a coplanar 4th+ vertex is routed to an internal backlog rather than expanding the hull, so `isDefined()` would wrongly accept a flat/degenerate input. A real, non-degenerate hull's 4th non-coplanar vertex instead replaces 1 triangle with 3 (net 2→4 faces, verified in `ConvexPolyhedralRegion.addVertex`'s `case 3`/`default` branches), so `getTriangles().size() > 2` is the correct discriminator - a degenerate/too-few-vertex hull is rejected as `400` rather than silently accepted or left to NPE inside `EditSession` (`getMinimumPoint()`/`getMaximumPoint()` are null until a hull actually forms) |
| Clipboard | `copy` overloaded for `Box`, `Cylinder`, `Polygon`, `Ellipsoid`, `ConvexHull` | One shared `copyRegion` helper: `BlockArrayClipboard(region)` + `clipboard.setOrigin(region.getMinimumPoint())` + `ForwardExtentCopy(source, region, destination, region.getMinimumPoint())` + `Operations.completeLegacy` + `session.setClipboard(new ClipboardHolder(...))` | Origin is always the region's own bounding-box minimum corner - a fixed, typed input, consistent across every shape - not `session.getPlacementPosition(actor)` (WorldEdit's own `/copy` command default, which depends on hidden per-actor placement state); matches this repo's "1-to-1, no hidden defaults" design rule, and keeps `copy` fully self-contained (no placement setup needed first) |
| Transforms | `rotateClipboard(actor, degreesY, degreesX, degreesZ)` | `AffineTransform` (fresh instance) → `rotateY(-degreesY)` → `rotateX(-degreesX)` → `rotateZ(-degreesZ)` → `existingTransform.combine(newTransform)` → `ClipboardHolder.setTransform` | Mirrors WorldEdit's own `/rotate` command exactly (verified: `command/ClipboardCommands.java:233-238`, including the angle negation that makes a positive input angle read as clockwise); non-destructive and stackable, takes effect on the next `paste`, not past ones |
| Transforms | `scaleClipboard(actor, sx, sy, sz)` | `AffineTransform.scale(sx, sy, sz)` → `existingTransform.combine(newTransform)` → `ClipboardHolder.setTransform` | Generalizes WorldEdit's own `/flip` command mechanism (verified: `command/ClipboardCommands.java:253-254`, which calls the same `scale` with +/-1 factors) to arbitrary per-axis factors |
| Masks / patterns | pattern & mask string parameters on edit methods | `PatternFactory`/`MaskFactory extends AbstractFactory<E>.parseFromInput(String, ParserContext)` | Reuses WorldEdit's own parser/grammar instead of a parallel one; malformed input surfaces as `400 BAD_REQUEST`, not a crash |
| Clipboard | `paste(actor, x, y, z)` / `paste(actor, x, y, z, ignoreAirBlocks, ignoreStructureVoidBlocks, copyEntities, copyBiomes)` | `ClipboardHolder.createPaste(Extent).to(BlockVector3).ignoreAirBlocks(..).ignoreStructureVoidBlocks(..).copyEntities(..).copyBiomes(..).build()` + `Operations.completeLegacy` | Destination is an explicit typed x,y,z (not WorldEdit's placement-position heuristic either), same "no hidden defaults" rule as `copy`. The 3-arg overload uses WorldEdit's own `PasteBuilder` field defaults verified at `session/PasteBuilder.java:47-51`: `ignoreAirBlocks=false`, `ignoreStructureVoidBlocks=false`, `copyEntities=true`, `copyBiomes=false` |
| Schematics | `saveSchematic(actor, file)` / `loadSchematic(actor, file)` | `ClipboardFormats.findByFile(File)` (content detection, existing files only) falling back to `ClipboardFormats.findByAlias(extension)` (verified: `BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC` registers `"schem"` as both its primary extension and an alias, `BuiltInClipboardFormat.java:135`) + `ClipboardFormat.getWriter`/`getReader`; a save writes `Clipboard.transform(holder.getTransform())`, the clipboard with its pending rotate/scale baked in, as `//schem save` does (`SchematicCommands.java:372-375`; until MINECRAFT-476 the untransformed clipboard was saved) | `findByFile` alone (the first draft of this adapter) is wrong for saves: it opens and sniffs the file's *existing* content, which a new output file never has, so it always returned `null` and rejected every save. Format is now inferred from the file extension for a file that doesn't exist yet, with content-sniffing preferred when it does; an unrecognized extension is `400`, not a silent fallback |
| History | `undo(actor)` / `redo(actor)` | `LocalSession.undo(BlockBag, Actor)` / `redo(...)`, then `WorldEdit.flushBlockBag(Actor, EditSession)` | Reuses WorldEdit's own per-actor history (`LocalSession`), not a separate undo queue. Since MINECRAFT-476 it passes the session's block bag for a player actor (`LocalSession.getBlockBag(Player)`, null unless the session uses the inventory) and flushes it after a step that did something - exactly `HistoryCommands.undo`/`redo` (`HistoryCommands.java:80-84`, `:119-123`); the first draft always passed `null` |
| Selection | `selection(actor)` | `LocalSession.getSelection(World)` → `Region.getMinimumPoint()`/`getMaximumPoint()` | Reports the **bounding box** of the active selection for any selector shape. The `World` is the one the actor is editing - the session's world override (`hasWorldOverride`/`getWorldOverride`), else the actor's own (`Locatable.getExtent()`) - which is how WorldEdit's own commands inject `@Selection Region` (`PlatformCommandManager.java:238-282`). Until MINECRAFT-476 it used `getSelectionWorld()`, the selection's *own* world, and so reported a selection made in another dimension as if it were current; it is now `400` like `//size`. The four exact-shape selection reporters below share this |
| Selection | `cylinderSelection(actor)` | `CylinderRegion.getCenter()`/`getRadius()`/`getMinimumY()`/`getMaximumY()`, when the real selector is exactly a `CylinderRegion` | Reports the **exact** shape (not just bounding box); `400` if the selector is a different (or no) shape |
| Selection | `polygonSelection(actor)` | `Polygonal2DRegion.getPoints()` (`List<BlockVector2>`) + `getMinimumY()`/`getMaximumY()`, when the real selector is exactly a `Polygonal2DRegion` | Exact point list + Y-range; `400` if the selector is a different (or no) shape |
| Selection | `ellipsoidSelection(actor)` | `EllipsoidRegion.getCenter()`/`getRadius()`, when the real selector is exactly an `EllipsoidRegion` | `400` if the selector is a different (or no) shape |
| Selection | `convexHullSelection(actor)` | `ConvexPolyhedralRegion.getVertices()` (`Collection<BlockVector3>`), when the real selector is exactly a `ConvexPolyhedralRegion` | `400` if the selector is a different (or no) shape |
| Generation | `makeSphere(actor, x, y, z, radiusX, radiusY, radiusZ, filled, pattern)` | `EditSession.makeSphere(BlockVector3, Pattern, double radiusX, double radiusY, double radiusZ, boolean filled)` - the verified **six-argument** overload (`EditSession.java:1889`; equal radii = a uniform sphere, matching the real 4-arg overload's own delegation `makeSphere(pos, block, radius, radius, radius, filled)` at `EditSession.java:1875`) | Always uses the 6-arg overload rather than selecting between it and the 4-arg uniform-sphere one - mathematically identical when radiusX==radiusY==radiusZ, and avoids an extra overload-selection branch |
| Generation | `makeCylinder(actor, x, y, z, radiusX, radiusZ, height, filled, pattern)` | `EditSession.makeCylinder(BlockVector3, Pattern, double radiusX, double radiusZ, int height, boolean filled)` (`EditSession.java:1721`; equal radii = a circular cylinder, same delegation pattern as makeSphere, `EditSession.java:1705`) | Same one-overload-covers-both-cases choice as makeSphere |
| Generation | `makePyramid(actor, x, y, z, size, filled, pattern)` | `EditSession.makePyramid(BlockVector3, Pattern, int size, boolean filled)` (`EditSession.java:1975`) | Only one real overload exists |
| Generation | `hollowOutBox(actor, box, thickness, pattern)` | **Not** `EditSession.hollowOutRegion` - geometrically shrinks the box by `thickness` on every axis, then reuses `setBlocks(actor, innerBox, pattern)` | **`EditSession.hollowOutRegion` was tried first and rejected after reading its actual body** (`EditSession.recurseHollow`, `EditSession.java:2743-2765`): the flood fill that determines its "outside"/shell set only passes through blocks where `getBlock(current).getBlockType().getMaterial().isMovementBlocker()` is false - i.e. it reads real, already-placed world state, not region geometry. For an already-solid box (the ordinary case for one nobody has carved into yet) that flood fill never starts, the shell set stays empty, and the real method overwrites the *entire* region, including what should be the shell - not a deterministic "preserve N layers" operation at all. Two earlier drafts of this doc got this wrong in two different ways: first claiming the interior was left untouched (backwards even for the real method's intended use), then claiming the real method does reliably preserve a geometric shell (true only when the surrounding terrain happens to already be open/passable). The adapter now computes the interior box directly and calls the already-verified `setBlocks`, which is deterministic regardless of terrain. The shrink arithmetic itself is computed in `long` (`thickness < 0` is rejected first): a plain `int` computation silently overflows for a `thickness` near `Integer.MAX_VALUE`, turning "no interior exists" into a different, wrong-but-plausible-looking box at the wrong location instead of correctly reporting no interior at all |
| Edit-session construction | every edit-producing method (`setBlocks`, `replaceBlocks`, `copy`, `paste`) | `LocalSession.createEditSession(Actor)` (`LocalSession.java:1104-1127`), **never** `WorldEdit.newEditSession(Actor)` | The session-owned call is what applies the actor's configured block change limit, mask, world override and block bag; the bare `WorldEdit.newEditSession`/`EditSessionBuilder` path (the first draft of this adapter) silently ignores all of them (defaults to unlimited) |
| Actor/session bookkeeping | every edit-producing method | `LocalSession.remember(EditSession)` in a `finally`, unconditionally - **not** only on success | Verified against `BlockChangeLimiter.setBlock` (checks `count >= limit` and only then delegates to the history-recording extent) and `PlatformCommandManager.java:510-513` (WorldEdit's own command dispatcher calls `remember` in a `finally`): a change that exceeds the block-change limit still leaves every block up to the limit already applied and recorded, so it must stay undo-able. The first draft of this adapter remembered only on success, silently discarding a partially-applied, otherwise-unrecoverable edit on every limit (or other mid-edit) failure. `remember` itself is a no-op for a never-modified edit session, so calling it unconditionally is always safe. A test proves an over-limit edit is rejected (`400`) but its partial change is still in history and `undo`-able, with a fake that models per-write limiting (applying and recording writes one at a time up to the limit, mirroring `BlockChangeLimiter`) rather than an atomic preflight check that could never have told the two behaviors apart |

## Namespaced discovery/schema definitions

`core/src/main/java/dev/herobrine/api/core/worldedit/WorldEditSchema.java` declares the typed, namespaced
(`worldedit.*`) operation inventory - name, description and a `ParamDef` list per `WorldEditAdapter` method,
built from this repo's existing schema types (`dev.herobrine.api.core.ParamDef`). It stays declarative data;
MINECRAFT-476 registers exactly these definitions as the HTTP actions/resources (`WorldEditBinding`), with
`WorldEditActions` holding each operation's handler, read/write kind and permission nodes. Its only 476 change
is `save_schematic`'s optional `overwrite` (default `false`).

## Gaps - explicit, not silently dropped, and split by why they're gaps

A prior draft of this doc lumped "real API exists but isn't mapped yet" together with "no clean API
exists at all" under one vague "deferred" bucket - called out in review as needing to distinguish
discretionary deferrals (still public, still in scope, just not mapped to a contract method *yet*) from
genuinely unsupported ones (no non-interactive, non-command API exists for this ticket's "typed method, not
raw command" requirement to map to). Transforms (rotate/scale), generation shapes, paste flags, and all four
non-cuboid region shapes this adapter's own `Region` subtype inventory covers (cylinder, polygon, ellipsoid,
convex hull) were all in the first bucket and are now implemented (see the matrix above); everything below
is sorted into one bucket or the other, not left vague.

There is no remaining "discretionary deferral" bucket item for regions/edits/selection: every public,
constructible `Region` subtype this adapter identified (`CuboidRegion`, `CylinderRegion`,
`Polygonal2DRegion`, `EllipsoidRegion`, `ConvexPolyhedralRegion`) now has `setBlocks`/`replaceBlocks`/`copy`,
and every one of those plus the bounding-box-only `selection()` has an exact-shape selection reporter
(`cylinderSelection`/`polygonSelection`/`ellipsoidSelection`/`convexHullSelection`). Add any *other* WorldEdit
`Region` implementation (there are more obscure/internal ones, e.g. clipboard-backed or null regions) with
the same verified-signature discipline, when a concrete use case needs it - none were found to be both
public and meaningfully constructible outside of an existing selection during this pass.

**Genuinely unsupported for this ticket's "typed method, not raw command" contract:**
- **Snapshots/rollback**, **scripting (CraftScripts)**, **tool binding** (super pickaxe, brushes, held-item
  triggered brush strokes): all built around an interactive play loop (equip a tool, click a block, repeat) or
  an admin snapshot/rollback workflow; no one-shot, non-command, non-interactive public API equivalent was
  found that fits a single typed call the way `setBlocks` or `rotateClipboard` do.
- **Command-based fallback**: deliberately never used, per the ticket (WorldEdit's command dispatcher is
  bypassed entirely, matching the Baritone adapter's own rule).

Actor and world construction, listed here as MINECRAFT-476 dependencies before, are done - see the next
section. The former open question is settled: the actor is WorldEdit's own `NeoForgePlayer`, which extends
`AbstractPlayerActor` and so is a `Player` (`Entity`, hence `Locatable`) - checked in the 7.3.8 jar and pinned
by `WorldEditProvenanceTest`.

## MINECRAFT-476: HTTP binding and actor context

Every `WorldEditSchema` operation is registered by `WorldEditBinding` (core) through `WorldEditActions`
(core: permission check, input bounds, translation) onto the adapter above, with the platform resolving who the
request acts as (`ActorScope`) - `WorldEditBindings` and `ClientWorldEdit` in the neoforge module. Nothing here
adds a queue, a session or any other state: selection, clipboard and history are the actor's own WorldEdit
`LocalSession`, and each call is one WorldEdit call. WorldEdit's command dispatcher, command strings and
console are never used.

### Actor and world

| | Client | Dedicated server |
|---|---|---|
| Acts as | the client's own player: the integrated server's `ServerPlayer` with the same UUID | the online player named by the required `player` parameter (an operator acting inside that player's session, as `/execute as <player>` with a WorldEdit command would) |
| WorldEdit actor | `NeoForgeAdapter.adaptPlayer(ServerPlayer)` - WorldEdit's own wrapper, the call its `NeoForgeWorldEdit.getSession(ServerPlayer)` makes; a `NeoForgePlayer`, built per request, never cached | same |
| Session | that player's `LocalSession` (`SessionManager.get`, keyed by the player's UUID), so players never share a selection, clipboard or history | same |
| World | the session's world override, else the player's level - how WorldEdit's own commands pick it | same |
| Thread | the request runs on the client thread and hops to the integrated server's thread (`GameThreadHop`): not started within `SERVER_START_TIMEOUT_MS` (5 s) it is withdrawn and refused (503, nothing was done); once started it is waited for, up to `SERVER_FINISH_TIMEOUT_MS` (60 s) | the request already runs on the server thread, so the call runs inline |
| Unavailable (409) | not in a world; connected to someone else's server - WorldEdit there belongs to that server and is reachable only through its commands, which this API does not send | the named player is not online |
| Tokens | actions need the full token (the read-only token gets 403); the selection resources are readable with either | the operator key |
| Control gating | actions are refused on a remote server unless `allowControlOnMultiplayer` (and are unavailable there anyway) | none, as for every server action |
| Edit cap | `maxEditVolume` in `herobrine_api-client.toml` | `maxEditVolume` in `herobrine_api-server.toml` |

### Operations

All are named `worldedit.<operation>`. The permission column lists the nodes WorldEdit 7.3.8's own command for
the same operation checks (its `@CommandPermissions`); as in WorldEdit, holding any one is enough
(`PermissionCondition.satisfied`). WorldEdit checks permissions only in its command layer, so the binding checks
them itself (`Subject.hasPermission`) before anything runs - an API caller gets no further than the player could
with WorldEdit commands.

| Operations | Served as | Adapter call | WorldEdit permission (command) | Capped by `maxEditVolume` |
|---|---|---|---|---|
| `set_blocks`, `set_blocks_cylinder`, `set_blocks_polygon`, `set_blocks_ellipsoid`, `set_blocks_convex_hull` | action | `setBlocks(actor, shape, pattern)` | `worldedit.region.set` (`//set`) | the shape's bounding box |
| `replace_blocks`, `replace_blocks_cylinder`, `replace_blocks_polygon`, `replace_blocks_ellipsoid`, `replace_blocks_convex_hull` | action | `replaceBlocks(actor, shape, mask, pattern)` | `worldedit.region.replace` (`//replace`) | the shape's bounding box |
| `copy`, `copy_cylinder`, `copy_polygon`, `copy_ellipsoid`, `copy_convex_hull` | action | `copy(actor, shape)` | `worldedit.clipboard.copy` (`//copy`) | the shape's bounding box |
| `paste` | action | `paste(actor, x, y, z, ignoreAirBlocks, ignoreStructureVoidBlocks, copyEntities, copyBiomes)`, flags defaulting to WorldEdit's `PasteBuilder` defaults | `worldedit.clipboard.paste` (`//paste`) | the clipboard's region volume (`clipboardVolume`: `Region.getVolume()`), however the clipboard was filled |
| `undo`, `redo` | action | `undo(actor)`, `redo(actor)` - one step per call | `worldedit.history.undo` or `worldedit.history.undo.self` (`undo`); `worldedit.history.redo` or `worldedit.history.redo.self` (`redo`) | - (replays an edit that was capped) |
| `rotate_clipboard` | action | `rotateClipboard(actor, degreesY, degreesX, degreesZ)` | `worldedit.clipboard.rotate` (`//rotate`) | - |
| `scale_clipboard` | action | `scaleClipboard(actor, sx, sy, sz)` | `worldedit.clipboard.flip` (`//flip`, which it generalizes) | - |
| `save_schematic` | action | `saveSchematic(actor, file)` | `worldedit.clipboard.save` or `worldedit.schematic.save` (`//schem save`); replacing an existing file also needs `worldedit.schematic.delete` and `overwrite: true` (its `-f`), checked in that order as `SchematicCommands.save` does (`SchematicCommands.java:168-176`) | - |
| `load_schematic` | action | `loadSchematic(actor, file)` | `worldedit.clipboard.load` or `worldedit.schematic.load` (`//schem load`) | - (the paste is) |
| `make_sphere`, `make_cylinder`, `make_pyramid` | action | `makeSphere`, `makeCylinder`, `makePyramid` (each first checks WorldEdit's own `maxRadius`) | `worldedit.generation.sphere` (`//sphere`), `worldedit.generation.cylinder` (`//cyl`), `worldedit.generation.pyramid` (`//pyramid`) | the generated footprint |
| `hollow_out_box` | action | `hollowOutBox(actor, box, thickness, pattern)` | `worldedit.region.hollow` (`//hollow`) | the outer box |
| `selection`, `cylinder_selection`, `polygon_selection`, `ellipsoid_selection`, `convex_hull_selection` | resource | `selection(actor)`, ... | `worldedit.selection.size` (`//size`) | - |

Actions return what the call returned: `{changed}` for edits and generation, `{copied}`, `{pasted}`,
`{undone}`/`{redone}` (false when there was nothing to undo or redo), `{rotated}`, `{scaled}`,
`{saved, file}`, `{loaded, file}`. The resources return the selection in the adapter's typed shape.

**Bounding boxes never under-restrict.** A cylinder or ellipsoid region reaches `floor(r + 0.5)` blocks from
its center (`setRadius` adds 0.5), and the generated sphere and cylinder do the same; the cap uses `ceil(r)`,
which always covers it. `makeCylinder` fills `y .. y+height-1` (or the `|height|` blocks below `y`) and the cap
uses `y .. y+height`; `makePyramid` reaches `size-1` out and `size` up (`EditSession.java:1975`) and the cap
uses `±size`. A box volume is computed overflow-checked: a world-sized box's block count does not fit a `long`,
and the unchecked product used to wrap around to a value that could pass (fixed in `Validation.boxVolume`, which
the Baritone edits share).

### Input bounds

Named constants, in `WorldEditActions` unless noted:

| Input | Bound |
|---|---|
| block coordinates | within ±30,000,000 (`Validation.coordinate`, `Validation.boxVolume`) |
| `points` of a polygon, `vertices` of a convex hull | at most `MAX_SHAPE_POINTS` (256) entries - WorldEdit tests every block of the region against each - each an object of JSON numbers |
| `pattern`, `mask` | 1 to `MAX_PATTERN_LENGTH` (256) characters, no control characters; parsed by WorldEdit's own factories |
| radii | finite and not negative |
| `size` (pyramid) | not negative |
| rotation angles | finite |
| scale factors | finite, not zero (a singular transform) and at most `MAX_SCALE_FACTOR` (16) in magnitude |
| `file` | a bare file name with a known extension (`Validation.schematicName`, as for `baritone.build`) |

### Schematics

Files are read and written only in WorldEdit's own schematics folder: its configured `saveDir` resolved by
`WorldEdit.getWorkingDirectoryPath` (`config/worldedit/schematics` by default), the folder `//schem save` and
`//schem load` use, so files saved here are the ones players see in game and vice versa. `SchematicFiles`
resolves the bare name against the folder's real path and refuses anything there that is not a plain regular
file - a symbolic link, which could point outside, or a directory. A save creates the folder if needed; a load of
a missing file is 404.

### Errors

| Situation | Response |
|---|---|
| read-only token on an action | 403 `PERMISSION_DENIED` (`ApiServer`) |
| the actor lacks the operation's WorldEdit permission | 403 `PERMISSION_DENIED` naming the nodes |
| WorldEdit not installed | actions 409 `ACTION_UNAVAILABLE`; resources `{available: false, reason: MOD_NOT_INSTALLED}`; `/capabilities` reports `integrations.worldedit: false`; everything stays listed |
| a WorldEdit version other than 7.3.8 | 501 `UNSUPPORTED` |
| no complete selection in the world being edited, including one made in another world | 400 |
| empty clipboard (paste, rotate, scale, save) | 400 |
| the actor is not in a world | 400 |
| the world WorldEdit is editing was unloaded (`NeoForgeWorld.getWorld()`'s `RuntimeException`) | 409 |
| over `maxEditVolume` | 400, before any WorldEdit call |
| over WorldEdit's configured `maxRadius` (`MaxRadiusException`) | 400, before the edit |
| over the session's block change limit (`MaxChangedBlocksException`) | 400 naming the limit: the changes made up to it stay applied and are remembered, so `worldedit.undo` reverts them (also for `paste`, which used to be a 500 here) |
| a pattern or mask WorldEdit cannot parse | 400 |
| a bad schematic name, a link or a non-regular file | 400; a missing file on load 404; an existing file on save 403 without `worldedit.schematic.delete`, else 400 without `overwrite` |
| request not started by the integrated server in time (client) | 503 `GAME_THREAD_BUSY`, nothing was done |

### Provenance

`WorldEditProvenanceTest` (neoforge tests) resolves every WorldEdit class, method, constructor and field that
`ReflectiveWorldEdit` and `WorldEditBindings` reach by reflection - by exact signature and return type -
against the real `com.sk89q.worldedit:worldedit-neoforge-mc1.21:7.3.8` and its `worldedit-core`, fetched from
`maven.enginehub.org` onto the **test runtime classpath only** (the mod neither compiles against nor bundles
WorldEdit). It also runs, on the real classes, what needs no running platform: the production
`WorldEditBindings.actor` lookup reaching the real `NeoForgeAdapter.adaptPlayer`, `Polygonal2DRegion`'s atomic
Y bounds, `ConvexPolyhedralRegion`'s triangle count for a flat versus a real hull, and `Region.getVolume()`.

### Remaining gaps

- **Never run in a real game with WorldEdit installed** - that is MINECRAFT-477. In particular unproven at
  runtime: that WorldEdit's classes are visible from this mod's class loader (the same open question as
  Baritone), the `union:`-URL version fallback above, WorldEdit's NeoForge permission provider, and real edits.
- Not exposed, as before: snapshots, CraftScripts, tools and brushes (see the gaps above); `undo`/`redo` take
  one step per call (`LocalSession.undo`/`redo` are one step; WorldEdit's `times` argument is its command's loop).
- No console actor on the dedicated server: a console has no world, and giving it one means setting a hidden
  session world override, so the server acts as a named online player instead.
- From the client, only the client's own integrated server is reachable; another server's WorldEdit is not.

# WorldEdit adapter: version and method coverage

Companion to the "WorldEdit adapter" row in `docs/COVERAGE.md` (spec §17). Scope: MINECRAFT-474 defines the
typed contract (`WorldEditAdapter`) and a reflective implementation (`ReflectiveWorldEdit`) with verified
signatures, absence detection and version-compatibility detection. It deliberately does **not** wire this
into the neoforge module, the HTTP action/resource registry, or actor/world construction from a real
Minecraft player - that is MINECRAFT-476 ("Implement direct WorldEdit API bindings and actor context").

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
- `ReflectiveWorldEdit` enforces this at runtime: `WorldEdit.getVersion()` must equal `"7.3.8"` exactly, or
  match it with WorldEdit's own build-metadata suffix (e.g. `"7.3.8+1379-7d32b45"`, verified:
  `WorldEdit.getVersion()` reads this from `WorldEditManifest`), or every adapter call fails fast with
  `501 UNSUPPORTED` naming the installed version. An earlier draft matched any `"7.3."`-prefixed version,
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
| Schematics | `saveSchematic(actor, file)` / `loadSchematic(actor, file)` | `ClipboardFormats.findByFile(File)` (content detection, existing files only) falling back to `ClipboardFormats.findByAlias(extension)` (verified: `BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC` registers `"schem"` as both its primary extension and an alias, `BuiltInClipboardFormat.java:135`) + `ClipboardFormat.getWriter`/`getReader` | `findByFile` alone (the first draft of this adapter) is wrong for saves: it opens and sniffs the file's *existing* content, which a new output file never has, so it always returned `null` and rejected every save. Format is now inferred from the file extension for a file that doesn't exist yet, with content-sniffing preferred when it does; an unrecognized extension is `400`, not a silent fallback |
| History | `undo(actor)` / `redo(actor)` | `LocalSession.undo(BlockBag, Actor)` / `redo(...)` | Reuses WorldEdit's own per-actor history (`LocalSession`), not a separate undo queue |
| Selection | `selection(actor)` | `LocalSession.getSelection(World)` → `Region.getMinimumPoint()`/`getMaximumPoint()` | Reports the **bounding box** of the active selection for any selector shape |
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
built from this repo's existing schema types (`dev.herobrine.api.core.ParamDef`) so MINECRAFT-476 can attach
real `ActionDef`/`ResourceDef` handlers to an already-typed parameter schema instead of re-deriving one. It is
declarative data only: no `Function<Params, Object> handler`, no `ApiContext`/HTTP registration - building a
real `ActionDef` needs a concrete platform `Actor`, which only the neoforge module can construct (MINECRAFT-476).

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
- **Actor construction**: `Actor` is only an opaque `Object` parameter here - the adapter never constructs
  one. `LocalSession.createEditSession(Actor)` (which this adapter does call) additionally checks whether the
  actor implements `Locatable` to resolve a world override; whether MINECRAFT-476's eventual actor wrapper
  satisfies `Locatable` is still the open question below. Wrapping a Minecraft `ServerPlayer`/`LocalPlayer`
  into a real WorldEdit `Actor` requires the neoforge module's own platform registration; that is
  MINECRAFT-476, not this ticket.
- **World construction**: likewise, `com.sk89q.worldedit.world.World` instances (needed internally by
  `LocalSession.getSelection(World)`) are only ever read back from the actor's own session state here, never
  constructed by this adapter - another MINECRAFT-476 dependency.
- **Command-based fallback**: deliberately never used, per the ticket (WorldEdit's command dispatcher is
  bypassed entirely, matching the Baritone adapter's own rule).

## Known open question
- Whether `com.sk89q.worldedit.extension.platform.Actor` instances built by the neoforge module in
  MINECRAFT-476 will also satisfy `Locatable`. This is now a soft dependency, not a hard one: this adapter
  calls `LocalSession.createEditSession(Actor)` (not the `newEditSession(A extends Actor & Locatable)`
  shorthand the first draft used), which only does a runtime `actor instanceof Locatable` check to resolve a
  world override (`LocalSession.java:1106`) - a non-`Locatable` actor still works, it just can't supply a
  world override this way. The real WorldEdit neoforge platform almost certainly supplies `Locatable`, but it
  was not verified here since no neoforge-side actor wrapper exists yet.

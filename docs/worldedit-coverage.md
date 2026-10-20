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
- `ReflectiveWorldEdit` enforces this at runtime: `WorldEdit.getVersion()` must start with `"7.3."`, or every
  adapter call fails fast with `501 UNSUPPORTED` naming the installed version, instead of silently
  attempting calls against an unverified build.

## Coverage matrix (`WorldEditAdapter` / `ReflectiveWorldEdit`)

All reflective, like the Baritone adapter; the compiler does not check these names, so each one below was
read directly out of the WorldEdit 7.3.8 sources (file:line noted in commit review) rather than recalled
from general WorldEdit knowledge. Status for everything here is **Tested against fakes** (unit tests under
`core/src/test/java`, fakes recompiled against the verified real signatures) - no real client run.

| Operation family | Contract method | WorldEdit API call | Notes |
|---|---|---|---|
| Edits | `setBlocks(actor, box, pattern)` | `EditSession.setBlocks(Region, Pattern)` | Region is a `CuboidRegion` built from the box; `Pattern` parsed via `WorldEdit.getPatternFactory().parseFromInput` (WorldEdit's own pattern syntax, e.g. `stone`, `50%stone,50%dirt`) |
| Edits | `replaceBlocks(actor, box, mask, pattern)` | `EditSession.replaceBlocks(Region, Mask, Pattern)` | `Mask` parsed via `WorldEdit.getMaskFactory().parseFromInput` (WorldEdit's own mask syntax, e.g. `stone,dirt`) |
| Edits | `setBlocks(actor, cylinder, pattern)` / `replaceBlocks(actor, cylinder, mask, pattern)` | Same `EditSession.setBlocks`/`replaceBlocks`, with a `CylinderRegion` instead of `CuboidRegion` | Proves the edit methods are region-shape-agnostic - no separate implementation per shape |
| Regions | `WorldEditAdapter.Box` → `CuboidRegion(BlockVector3, BlockVector3)`; `WorldEditAdapter.Cylinder` → `CylinderRegion(BlockVector3, Vector2, int minY, int maxY)` (verified 4-arg ctor; `Vector2.at(radiusX, radiusZ)`, center's Y discarded internally) | — | Cuboid and cylinder are the two shapes this adapter constructs; other non-cuboid shapes (polygon, ellipsoid, convex) are a remaining, narrower gap - see below |
| Transforms | `rotateClipboard(actor, degreesY, degreesX, degreesZ)` | `AffineTransform` (fresh instance) → `rotateY(-degreesY)` → `rotateX(-degreesX)` → `rotateZ(-degreesZ)` → `existingTransform.combine(newTransform)` → `ClipboardHolder.setTransform` | Mirrors WorldEdit's own `/rotate` command exactly (verified: `command/ClipboardCommands.java:233-238`, including the angle negation that makes a positive input angle read as clockwise); non-destructive and stackable, takes effect on the next `paste`, not past ones |
| Transforms | `scaleClipboard(actor, sx, sy, sz)` | `AffineTransform.scale(sx, sy, sz)` → `existingTransform.combine(newTransform)` → `ClipboardHolder.setTransform` | Generalizes WorldEdit's own `/flip` command mechanism (verified: `command/ClipboardCommands.java:253-254`, which calls the same `scale` with +/-1 factors) to arbitrary per-axis factors |
| Masks / patterns | pattern & mask string parameters on edit methods | `PatternFactory`/`MaskFactory extends AbstractFactory<E>.parseFromInput(String, ParserContext)` | Reuses WorldEdit's own parser/grammar instead of a parallel one; malformed input surfaces as `400 BAD_REQUEST`, not a crash |
| Clipboard | `copy(actor, box)` | `BlockArrayClipboard(Region)` + `clipboard.setOrigin(region.getMinimumPoint())` + `ForwardExtentCopy(source, region, destination, region.getMinimumPoint())` + `Operations.completeLegacy` + `session.setClipboard(new ClipboardHolder(...))` | Origin is the box's own minimum corner - a fixed, typed input - not `session.getPlacementPosition(actor)` (WorldEdit's own `/copy` command default, which depends on hidden per-actor placement state); matches this repo's "1-to-1, no hidden defaults" design rule, and keeps `copy` fully self-contained (no placement setup needed first) |
| Clipboard | `paste(actor, x, y, z)` / `paste(actor, x, y, z, ignoreAirBlocks, ignoreStructureVoidBlocks, copyEntities, copyBiomes)` | `ClipboardHolder.createPaste(Extent).to(BlockVector3).ignoreAirBlocks(..).ignoreStructureVoidBlocks(..).copyEntities(..).copyBiomes(..).build()` + `Operations.completeLegacy` | Destination is an explicit typed x,y,z (not WorldEdit's placement-position heuristic either), same "no hidden defaults" rule as `copy`. The 3-arg overload uses WorldEdit's own `PasteBuilder` field defaults verified at `session/PasteBuilder.java:47-51`: `ignoreAirBlocks=false`, `ignoreStructureVoidBlocks=false`, `copyEntities=true`, `copyBiomes=false` |
| Schematics | `saveSchematic(actor, file)` / `loadSchematic(actor, file)` | `ClipboardFormats.findByFile(File)` (content detection, existing files only) falling back to `ClipboardFormats.findByAlias(extension)` (verified: `BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC` registers `"schem"` as both its primary extension and an alias, `BuiltInClipboardFormat.java:135`) + `ClipboardFormat.getWriter`/`getReader` | `findByFile` alone (the first draft of this adapter) is wrong for saves: it opens and sniffs the file's *existing* content, which a new output file never has, so it always returned `null` and rejected every save. Format is now inferred from the file extension for a file that doesn't exist yet, with content-sniffing preferred when it does; an unrecognized extension is `400`, not a silent fallback |
| History | `undo(actor)` / `redo(actor)` | `LocalSession.undo(BlockBag, Actor)` / `redo(...)` | Reuses WorldEdit's own per-actor history (`LocalSession`), not a separate undo queue |
| Selection | `selection(actor)` | `LocalSession.getSelection(World)` → `Region.getMinimumPoint()`/`getMaximumPoint()` | Reports the **bounding box** of the active selection for any selector shape |
| Selection | `cylinderSelection(actor)` | `LocalSession.getSelection(World)`, then `CylinderRegion.getCenter()`/`getRadius()`/`getMinimumY()`/`getMaximumY()` when the result is exactly a `CylinderRegion` | Reports the **exact** shape (not just bounding box) when the selector is a cylinder - `400` if it's a different (or no) selector. Other non-cuboid shapes (polygon, ellipsoid, convex) remain bounding-box-only, a narrower remaining gap |
| Generation | `makeSphere(actor, x, y, z, radiusX, radiusY, radiusZ, filled, pattern)` | `EditSession.makeSphere(BlockVector3, Pattern, double radiusX, double radiusY, double radiusZ, boolean filled)` - the verified **six-argument** overload (`EditSession.java:1889`; equal radii = a uniform sphere, matching the real 4-arg overload's own delegation `makeSphere(pos, block, radius, radius, radius, filled)` at `EditSession.java:1875`) | Always uses the 6-arg overload rather than selecting between it and the 4-arg uniform-sphere one - mathematically identical when radiusX==radiusY==radiusZ, and avoids an extra overload-selection branch |
| Generation | `makeCylinder(actor, x, y, z, radiusX, radiusZ, height, filled, pattern)` | `EditSession.makeCylinder(BlockVector3, Pattern, double radiusX, double radiusZ, int height, boolean filled)` (`EditSession.java:1721`; equal radii = a circular cylinder, same delegation pattern as makeSphere, `EditSession.java:1705`) | Same one-overload-covers-both-cases choice as makeSphere |
| Generation | `makePyramid(actor, x, y, z, size, filled, pattern)` | `EditSession.makePyramid(BlockVector3, Pattern, int size, boolean filled)` (`EditSession.java:1975`) | Only one real overload exists |
| Generation | `hollowOutBox(actor, box, thickness, pattern)` | `EditSession.hollowOutRegion(Region, int thickness, Pattern)` (`EditSession.java:2493`) | Region is a `CuboidRegion` built from the box, same as `setBlocks`. **Semantics, verified against the real method body** (`EditSession.java:2496-2558`): `thickness` is how many boundary layers are left **untouched** (the shell); the pattern is written to every block that is **not** part of that shell - i.e. the interior changes, the shell survives. An earlier draft of this doc and its javadoc had this backwards (said the interior was left untouched) |
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
raw command" requirement to map to). Transforms (rotate/scale), generation shapes, paste flags and cylinder
regions were all in the first bucket and are now implemented (see the matrix above); everything below is
sorted into one bucket or the other, not left vague.

**Discretionary deferrals - real, public, verified methods exist; not mapped to a contract method yet:**
- **Exact non-cuboid selection shape beyond cylinder** (polygon, ellipsoid, convex selectors): only the
  bounding box is exposed for these (cylinder is now exact, see `cylinderSelection` above). The real
  `Polygonal2DRegion`/`EllipsoidRegion`/`ConvexPolyhedralRegion` classes exist; exposing each exactly would
  need its own typed contract shape (a list of 2D points for polygon, a center+3 radii for ellipsoid, a
  point cloud for convex) - a larger, multi-shape addition deferred past this round rather than a
  "no API exists" gap.
- **Edits/selection on those same remaining shapes** (polygon/ellipsoid/convex regions as `setBlocks`/
  `replaceBlocks`/`copy` input, analogous to the `Cylinder` overloads above): same reasoning, deferred together
  with the selection-shape gap above since they'd share the same typed per-shape contract additions.

Add any of these with the same verified-signature discipline as everything else in this adapter, when a
concrete use case needs them - this list is the "remaining integration dependencies" the ticket's
acceptance criteria asks to name explicitly.

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

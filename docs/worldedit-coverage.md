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
| Regions | `WorldEditAdapter.Box` → `CuboidRegion(BlockVector3, BlockVector3)` | — | Only cuboid regions are constructed by this adapter; arbitrary/irregular selections are only *read* (see Selection) |
| Transforms | — | — | Not covered: WorldEdit's `Transform`/`AffineTransform` machinery is only reachable through brush/paste pipelines beyond this ticket's scope; deferred to MINECRAFT-476 if a concrete call is needed |
| Masks / patterns | pattern & mask string parameters on edit methods | `PatternFactory`/`MaskFactory extends AbstractFactory<E>.parseFromInput(String, ParserContext)` | Reuses WorldEdit's own parser/grammar instead of a parallel one; malformed input surfaces as `400 BAD_REQUEST`, not a crash |
| Clipboard | `copy(actor, box)` | `BlockArrayClipboard(Region)` + `ForwardExtentCopy(source, region, destination, region.getMinimumPoint())` + `Operations.completeLegacy` + `session.setClipboard(new ClipboardHolder(...))` | Mirrors WorldEdit's own `/copy` command (`command/ClipboardCommands.java`) exactly, including `clipboard.setOrigin(session.getPlacementPosition(actor))` |
| Clipboard | `paste(actor, x, y, z)` | `ClipboardHolder.createPaste(Extent).to(BlockVector3).build()` + `Operations.completeLegacy` | Destination is an explicit typed x,y,z (not WorldEdit's placement-position heuristic), matching this repo's "1-to-1, no hidden defaults" design rule |
| Schematics | `saveSchematic(actor, file)` / `loadSchematic(actor, file)` | `ClipboardFormats.findByFile(File)` + `ClipboardFormat.getWriter`/`getReader` | Format is inferred from the file extension exactly like WorldEdit's own CLI; an unrecognized extension is `400`, not a silent fallback |
| History | `undo(actor)` / `redo(actor)` | `LocalSession.undo(BlockBag, Actor)` / `redo(...)` | Reuses WorldEdit's own per-actor history (`LocalSession`), not a separate undo queue |
| Selection | `selection(actor)` | `LocalSession.getSelection(World)` → `Region.getMinimumPoint()`/`getMaximumPoint()` | Reports the **bounding box** of the active selection; exact only for a cuboid selector - a polygon/cylinder/convex selection is reported by its AABB, not its true shape (see gaps below) |
| Actor/session bookkeeping | every edit-producing method | `LocalSession.remember(EditSession)` after the edit | Replicates the bookkeeping WorldEdit's own command dispatcher performs centrally (`extension/platform/PlatformCommandManager.java:514`), since this adapter bypasses that dispatcher entirely |

## Gaps - explicit, not silently dropped

- **Exact non-cuboid selection shape** (polygon, cylinder, convex, ellipsoid selectors): only the bounding
  box is exposed. Exposing the true shape would require typed region subclasses per selector kind; not
  attempted here.
- **Transforms** (rotate/flip/scale via `AffineTransform`), **brushes/tools**, **generation** (`//generate`,
  `//sphere`, `//hollow*` outside plain fill), **structure void / biome-specific copy flags**, **entity
  copy/paste flags**: real WorldEdit methods exist for these (seen while reading the sources) but are not
  mapped to a contract method yet; add them with the same verified-signature discipline when a concrete use
  case needs them.
- **Snapshots/rollback**, **scripting (CraftScripts)**, **tool binding** (super pickaxe, brushes): internal
  to interactive play; no API-level, non-command equivalent was found that fits this ticket's "typed method,
  not raw command" requirement.
- **Actor construction**: `Actor` (and the `Locatable` bound `newEditSession` needs) is only an opaque
  `Object` parameter here - the adapter never constructs one. Wrapping a Minecraft `ServerPlayer`/`LocalPlayer`
  into a real WorldEdit `Actor` requires the neoforge module's own platform registration; that is
  MINECRAFT-476, not this ticket.
- **World construction**: likewise, `com.sk89q.worldedit.world.World` instances (needed internally by
  `LocalSession.getSelection(World)`) are only ever read back from the actor's own session state here, never
  constructed by this adapter - another MINECRAFT-476 dependency.
- **Command-based fallback**: deliberately never used, per the ticket (WorldEdit's command dispatcher is
  bypassed entirely, matching the Baritone adapter's own rule).

## Known open question
- Whether `com.sk89q.worldedit.extension.platform.Actor` instances built by the neoforge module in
  MINECRAFT-476 will also satisfy `Locatable` (required by the `newEditSession(A extends Actor & Locatable)`
  shorthand this adapter calls) - the real WorldEdit neoforge platform almost certainly supplies this, but it
  was not verified here since no neoforge-side actor wrapper exists yet.

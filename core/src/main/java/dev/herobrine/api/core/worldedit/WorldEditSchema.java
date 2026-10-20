package dev.herobrine.api.core.worldedit;

import dev.herobrine.api.core.ParamDef;
import java.util.List;

/**
 * Namespaced ({@code worldedit.*}) discovery/schema inventory for every {@link WorldEditAdapter} operation:
 * name, description and a typed {@link ParamDef} list per method, built from this repo's existing schema
 * types. Declarative only - no {@code Function<Params, Object>} handler and no {@code ApiContext}/HTTP
 * registration (that needs a real platform {@code Actor}, which is MINECRAFT-476, not this ticket). The
 * point is that MINECRAFT-476 can attach a real {@code ActionDef}/{@code ResourceDef} handler to an
 * already-typed parameter schema instead of re-deriving one. See docs/worldedit-coverage.md.
 */
public final class WorldEditSchema {
    private WorldEditSchema() {}

    /** One operation's namespaced name, description and typed parameter list. */
    public record Operation(String name, String description, List<ParamDef> params) {}

    private static final List<ParamDef> BOX_PARAMS = List.of(
            ParamDef.required("x1", "integer", "Box corner coordinate x1"),
            ParamDef.required("y1", "integer", "Box corner coordinate y1"),
            ParamDef.required("z1", "integer", "Box corner coordinate z1"),
            ParamDef.required("x2", "integer", "Box corner coordinate x2"),
            ParamDef.required("y2", "integer", "Box corner coordinate y2"),
            ParamDef.required("z2", "integer", "Box corner coordinate z2"));

    private static final List<ParamDef> CYLINDER_PARAMS = List.of(
            ParamDef.required("centerX", "integer", "Cylinder center X"),
            ParamDef.required("centerZ", "integer", "Cylinder center Z"),
            ParamDef.required("radiusX", "number", "Radius along the X axis"),
            ParamDef.required("radiusZ", "number", "Radius along the Z axis (equal to radiusX for a circular cylinder)"),
            ParamDef.required("minY", "integer", "Minimum Y, inclusive"),
            ParamDef.required("maxY", "integer", "Maximum Y, inclusive"));

    private static final List<ParamDef> PASTE_OPTION_PARAMS = List.of(
            ParamDef.optional("ignoreAirBlocks", "boolean", "Skip air blocks from the source", false),
            ParamDef.optional("ignoreStructureVoidBlocks", "boolean", "Skip structure-void blocks from the source", false),
            ParamDef.optional("copyEntities", "boolean", "Copy entities from the source", true),
            ParamDef.optional("copyBiomes", "boolean", "Copy biomes from the source", false));

    public static final List<Operation> OPERATIONS = List.of(
            new Operation("worldedit.set_blocks",
                    "Fill a box with a pattern (WorldEditAdapter#setBlocks)",
                    concat(BOX_PARAMS, ParamDef.required("pattern", "string",
                            "WorldEdit pattern syntax, e.g. stone or 50%stone,50%dirt"))),
            new Operation("worldedit.replace_blocks",
                    "Replace blocks matching a mask with a pattern inside a box (WorldEditAdapter#replaceBlocks)",
                    concat(BOX_PARAMS,
                            ParamDef.required("mask", "string", "WorldEdit mask syntax, e.g. stone,dirt"),
                            ParamDef.required("pattern", "string", "WorldEdit pattern syntax"))),
            new Operation("worldedit.undo", "Undo the actor's last remembered edit (WorldEditAdapter#undo)", List.of()),
            new Operation("worldedit.redo", "Redo the actor's last undone edit (WorldEditAdapter#redo)", List.of()),
            new Operation("worldedit.copy",
                    "Copy a box into the actor's clipboard, origin at its minimum corner (WorldEditAdapter#copy)",
                    BOX_PARAMS),
            new Operation("worldedit.paste",
                    "Paste the actor's clipboard with its origin at x,y,z, with optional WorldEdit paste flags "
                            + "(defaults match WorldEdit's own PasteBuilder) (WorldEditAdapter#paste)",
                    concat(List.of(
                            ParamDef.required("x", "integer", "Paste origin X"),
                            ParamDef.required("y", "integer", "Paste origin Y"),
                            ParamDef.required("z", "integer", "Paste origin Z")), PASTE_OPTION_PARAMS.toArray(ParamDef[]::new))),
            new Operation("worldedit.save_schematic",
                    "Write the actor's clipboard to a schematic file; format inferred from the file extension (WorldEditAdapter#saveSchematic)",
                    List.of(ParamDef.required("file", "string", "Schematic file name"))),
            new Operation("worldedit.load_schematic",
                    "Read a schematic file into the actor's clipboard (WorldEditAdapter#loadSchematic)",
                    List.of(ParamDef.required("file", "string", "Schematic file name"))),
            new Operation("worldedit.selection",
                    "Axis-aligned bounding box of the actor's current selection (WorldEditAdapter#selection)",
                    List.of()),
            new Operation("worldedit.rotate_clipboard",
                    "Rotate the actor's clipboard in degrees, clockwise; takes effect on the next paste (WorldEditAdapter#rotateClipboard)",
                    List.of(
                            ParamDef.required("degreesY", "number", "Rotation about the y-axis, in degrees"),
                            ParamDef.optional("degreesX", "number", "Rotation about the x-axis, in degrees", 0),
                            ParamDef.optional("degreesZ", "number", "Rotation about the z-axis, in degrees", 0))),
            new Operation("worldedit.scale_clipboard",
                    "Scale the actor's clipboard by per-axis factors; takes effect on the next paste (WorldEditAdapter#scaleClipboard)",
                    List.of(
                            ParamDef.required("sx", "number", "X-axis scale factor"),
                            ParamDef.required("sy", "number", "Y-axis scale factor"),
                            ParamDef.required("sz", "number", "Z-axis scale factor"))),
            new Operation("worldedit.set_blocks_cylinder",
                    "Fill a cylindrical region with a pattern (WorldEditAdapter#setBlocks(Object, Cylinder, String))",
                    concat(CYLINDER_PARAMS, ParamDef.required("pattern", "string", "WorldEdit pattern syntax"))),
            new Operation("worldedit.replace_blocks_cylinder",
                    "Replace blocks matching a mask with a pattern inside a cylindrical region (WorldEditAdapter#replaceBlocks(Object, Cylinder, String, String))",
                    concat(CYLINDER_PARAMS,
                            ParamDef.required("mask", "string", "WorldEdit mask syntax"),
                            ParamDef.required("pattern", "string", "WorldEdit pattern syntax"))),
            new Operation("worldedit.cylinder_selection",
                    "The actor's current selection as a cylinder, only when the selector is exactly a CylinderRegion (WorldEditAdapter#cylinderSelection)",
                    List.of()),
            new Operation("worldedit.make_sphere",
                    "Generate a sphere (or ellipsoid, when the radii differ) of a pattern centered at x,y,z (WorldEditAdapter#makeSphere)",
                    List.of(
                            ParamDef.required("x", "integer", "Center X"),
                            ParamDef.required("y", "integer", "Center Y"),
                            ParamDef.required("z", "integer", "Center Z"),
                            ParamDef.required("radiusX", "number", "Radius along the X axis"),
                            ParamDef.required("radiusY", "number", "Radius along the Y axis"),
                            ParamDef.required("radiusZ", "number", "Radius along the Z axis"),
                            ParamDef.required("filled", "boolean", "false generates only a shell"),
                            ParamDef.required("pattern", "string", "WorldEdit pattern syntax"))),
            new Operation("worldedit.make_cylinder",
                    "Generate a cylinder (or elliptical cylinder, when radiusX != radiusZ) of a pattern with its base centered at x,y,z (WorldEditAdapter#makeCylinder)",
                    List.of(
                            ParamDef.required("x", "integer", "Base center X"),
                            ParamDef.required("y", "integer", "Base center Y"),
                            ParamDef.required("z", "integer", "Base center Z"),
                            ParamDef.required("radiusX", "number", "Radius along the X axis"),
                            ParamDef.required("radiusZ", "number", "Radius along the Z axis"),
                            ParamDef.required("height", "integer", "Up/down extent; negative extends downward"),
                            ParamDef.required("filled", "boolean", "false generates only a shell"),
                            ParamDef.required("pattern", "string", "WorldEdit pattern syntax"))),
            new Operation("worldedit.make_pyramid",
                    "Generate a square pyramid of a pattern with its base centered at x,y,z (WorldEditAdapter#makePyramid)",
                    List.of(
                            ParamDef.required("x", "integer", "Base center X"),
                            ParamDef.required("y", "integer", "Base center Y"),
                            ParamDef.required("z", "integer", "Base center Z"),
                            ParamDef.required("size", "integer", "Pyramid size"),
                            ParamDef.required("filled", "boolean", "false generates only a shell"),
                            ParamDef.required("pattern", "string", "WorldEdit pattern syntax"))),
            new Operation("worldedit.hollow_out_box",
                    "Replace blocks within `thickness` of the box's boundary with a pattern, leaving the interior untouched (WorldEditAdapter#hollowOutBox)",
                    concat(BOX_PARAMS,
                            ParamDef.required("thickness", "integer", "Shell thickness (manhattan distance)"),
                            ParamDef.required("pattern", "string", "WorldEdit pattern syntax"))));

    private static List<ParamDef> concat(List<ParamDef> base, ParamDef... extra) {
        var out = new java.util.ArrayList<>(base);
        out.addAll(List.of(extra));
        return List.copyOf(out);
    }
}

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
                    "Paste the actor's clipboard with its origin at x,y,z (WorldEditAdapter#paste)",
                    List.of(
                            ParamDef.required("x", "integer", "Paste origin X"),
                            ParamDef.required("y", "integer", "Paste origin Y"),
                            ParamDef.required("z", "integer", "Paste origin Z"))),
            new Operation("worldedit.save_schematic",
                    "Write the actor's clipboard to a schematic file; format inferred from the file extension (WorldEditAdapter#saveSchematic)",
                    List.of(ParamDef.required("file", "string", "Schematic file name"))),
            new Operation("worldedit.load_schematic",
                    "Read a schematic file into the actor's clipboard (WorldEditAdapter#loadSchematic)",
                    List.of(ParamDef.required("file", "string", "Schematic file name"))),
            new Operation("worldedit.selection",
                    "Axis-aligned bounding box of the actor's current selection (WorldEditAdapter#selection)",
                    List.of()));

    private static List<ParamDef> concat(List<ParamDef> base, ParamDef... extra) {
        var out = new java.util.ArrayList<>(base);
        out.addAll(List.of(extra));
        return List.copyOf(out);
    }
}

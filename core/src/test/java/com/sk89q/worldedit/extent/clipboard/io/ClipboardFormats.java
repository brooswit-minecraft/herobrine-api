package com.sk89q.worldedit.extent.clipboard.io;
import dev.herobrine.api.core.worldedit.FakeWorldEdit;
import java.io.File;

public final class ClipboardFormats {
    /** Test double: recognizes "*.schem"; any other extension reports an unrecognized format (returns null),
     *  matching the real {@code findByFile} contract of returning null when no format claims the file. */
    public static ClipboardFormat findByFile(File file) {
        if (!file.getName().endsWith(".schem")) return null;
        return FakeWorldEdit.INSTANCE.schematicFormat();
    }
}

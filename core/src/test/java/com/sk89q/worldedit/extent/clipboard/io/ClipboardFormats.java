package com.sk89q.worldedit.extent.clipboard.io;
import dev.herobrine.api.core.worldedit.FakeWorldEdit;
import java.io.File;

public final class ClipboardFormats {
    /**
     * Test double. {@code findByFile} only matches a {@code .schem} file that actually has content (mirrors
     * the real {@link ClipboardFormat#isFormat(File)} default, which opens and reads the file - a missing or
     * empty file never matches, which is exactly the save-time regression this fake now exercises).
     * {@code findByAlias} matches "schem" regardless of whether the file exists, mirroring the real
     * alias-map lookup ({@code BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC} registers "schem" as both its
     * primary extension and an alias).
     */
    public static ClipboardFormat findByFile(File file) {
        if (!file.isFile() || file.length() == 0 || !file.getName().endsWith(".schem")) return null;
        return FakeWorldEdit.INSTANCE.schematicFormat();
    }

    public static ClipboardFormat findByAlias(String alias) {
        if (!"schem".equalsIgnoreCase(alias)) return null;
        return FakeWorldEdit.INSTANCE.schematicFormat();
    }
}

package com.sk89q.worldedit.session;

import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.clipboard.Clipboard;

public final class ClipboardHolder {
    private final Clipboard clipboard;
    public ClipboardHolder(Clipboard clipboard) { this.clipboard = clipboard; }
    public Clipboard getClipboard() { return clipboard; }
    public PasteBuilder createPaste(Extent targetExtent) { return new PasteBuilder(clipboard, targetExtent); }
}

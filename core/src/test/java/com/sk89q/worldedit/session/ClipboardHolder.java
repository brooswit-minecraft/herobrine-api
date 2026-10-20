package com.sk89q.worldedit.session;

import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.math.transform.Identity;
import com.sk89q.worldedit.math.transform.Transform;

public final class ClipboardHolder {
    private final Clipboard clipboard;
    private Transform transform = new Identity();
    public ClipboardHolder(Clipboard clipboard) { this.clipboard = clipboard; }
    public Clipboard getClipboard() { return clipboard; }
    public Transform getTransform() { return transform; }
    public void setTransform(Transform transform) { this.transform = transform; }
    public PasteBuilder createPaste(Extent targetExtent) { return new PasteBuilder(clipboard, targetExtent); }
}

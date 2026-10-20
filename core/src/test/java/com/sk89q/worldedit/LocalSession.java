package com.sk89q.worldedit;

import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.World;
import dev.herobrine.api.core.worldedit.FakeWorldEdit;
import java.util.ArrayList;
import java.util.List;

/** Test double mirroring the slice of real LocalSession the adapter touches: history, clipboard, selection, placement. */
public final class LocalSession {
    public final List<EditSession> history = new ArrayList<>();
    public int historyPointer;
    public ClipboardHolder clipboard;
    public World selectionWorld;
    public Region selectionRegion;
    public BlockVector3 placementPosition;
    /** -1 = unlimited, mirrors {@code LocalSession.getBlockChangeLimit()}. */
    public int blockChangeLimit = -1;

    /**
     * Mirrors the real {@code LocalSession.createEditSession(Actor)} (LocalSession.java:1104): the session
     * is what applies the configured block change limit (and, in the real implementation, mask/world
     * override/block bag), not a bare {@code WorldEdit.newEditSession(Actor)} builder.
     */
    public EditSession createEditSession(Actor actor) {
        FakeWorldEdit.INSTANCE.calls.add("createEditSession:" + actor + ":maxBlocks=" + blockChangeLimit);
        return new EditSession(actor, blockChangeLimit);
    }

    public void remember(EditSession editSession) {
        while (historyPointer < history.size()) history.remove(historyPointer);
        history.add(editSession);
        historyPointer = history.size();
    }

    public EditSession undo(Object newBlockBag, Actor actor) {
        --historyPointer;
        if (historyPointer >= 0) return history.get(historyPointer);
        historyPointer = 0;
        return null;
    }

    public EditSession redo(Object newBlockBag, Actor actor) {
        if (historyPointer < history.size()) return history.get(historyPointer++);
        return null;
    }

    public ClipboardHolder getClipboard() throws EmptyClipboardException {
        if (clipboard == null) throw new EmptyClipboardException();
        return clipboard;
    }

    public void setClipboard(ClipboardHolder clipboard) { this.clipboard = clipboard; }

    public World getSelectionWorld() { return selectionWorld; }

    public Region getSelection(World world) throws IncompleteRegionException {
        if (world == null || selectionWorld == null || !selectionWorld.equals(world) || selectionRegion == null) {
            throw new IncompleteRegionException();
        }
        return selectionRegion;
    }

    public BlockVector3 getPlacementPosition(Actor actor) throws IncompleteRegionException {
        if (placementPosition == null) throw new IncompleteRegionException();
        return placementPosition;
    }
}

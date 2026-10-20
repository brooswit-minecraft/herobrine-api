package com.sk89q.worldedit;

import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.World;
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

package com.sk89q.worldedit.extent.clipboard;

import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.math.transform.Identity;
import com.sk89q.worldedit.math.transform.Transform;
import com.sk89q.worldedit.regions.Region;

/** Test double: the region accessor and the transform-baking default method the adapter calls. */
public interface Clipboard extends Extent {
    Region getRegion();

    /** Like the real default (ClipboardTransformBaker), an identity transform returns the clipboard itself. */
    default Clipboard transform(Transform transform) {
        if (transform instanceof Identity) return this;
        Clipboard original = this;
        return new Clipboard() {
            @Override public Region getRegion() { return original.getRegion(); }
            @Override public String toString() { return original + "+" + transform; }
        };
    }
}

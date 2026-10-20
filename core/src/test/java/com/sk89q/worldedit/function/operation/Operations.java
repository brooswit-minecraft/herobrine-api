package com.sk89q.worldedit.function.operation;
import com.sk89q.worldedit.MaxChangedBlocksException;
import dev.herobrine.api.core.worldedit.FakeWorldEdit;

public final class Operations {
    public static void completeLegacy(Operation op) throws MaxChangedBlocksException {
        FakeWorldEdit.INSTANCE.completeLegacy(op);
    }
}

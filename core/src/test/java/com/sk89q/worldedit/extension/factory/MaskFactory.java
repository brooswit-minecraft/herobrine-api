package com.sk89q.worldedit.extension.factory;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.function.mask.Mask;
import dev.herobrine.api.core.worldedit.FakeWorldEdit;

public final class MaskFactory {
    public Mask parseFromInput(String input, ParserContext context) throws InputParseException {
        return FakeWorldEdit.INSTANCE.parseMask(input, context);
    }
}

package com.sk89q.worldedit.extension.factory;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extension.input.ParserContext;
import com.sk89q.worldedit.function.pattern.Pattern;
import dev.herobrine.api.core.worldedit.FakeWorldEdit;

public final class PatternFactory {
    public Pattern parseFromInput(String input, ParserContext context) throws InputParseException {
        return FakeWorldEdit.INSTANCE.parsePattern(input, context);
    }
}

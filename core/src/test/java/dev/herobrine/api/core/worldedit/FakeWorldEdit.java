package dev.herobrine.api.core.worldedit;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.MaxChangedBlocksException;
import com.sk89q.worldedit.extension.input.InputParseException;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.mask.Mask;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.pattern.Pattern;
import com.sk89q.worldedit.regions.Region;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in for WorldEdit's runtime state, wired up from the real-shaped test double classes under
 * {@code com.sk89q.worldedit.*} the same way {@code FakeBaritone} backs {@code baritone.api.*}.
 */
public final class FakeWorldEdit {
    public static volatile FakeWorldEdit INSTANCE = new FakeWorldEdit();

    public volatile String version = "7.3.8+1379-7d32b45";
    public final List<String> calls = new ArrayList<>();
    public volatile boolean schematicIoFails;

    public static void reset() { INSTANCE = new FakeWorldEdit(); }

    public Pattern parsePattern(String input) throws InputParseException {
        if ("bad-pattern".equals(input)) throw new InputParseException("Unknown pattern: " + input);
        calls.add("parsePattern:" + input);
        return new NamedPattern(input);
    }

    public Mask parseMask(String input) throws InputParseException {
        if ("bad-mask".equals(input)) throw new InputParseException("Unknown mask: " + input);
        calls.add("parseMask:" + input);
        return new NamedMask(input);
    }

    public int setBlocks(EditSession session, Region region, Pattern pattern) throws MaxChangedBlocksException {
        if ("overflow".equals(pattern.toString())) throw new MaxChangedBlocksException(100);
        calls.add("setBlocks:" + region + ":" + pattern);
        return 7;
    }

    public int replaceBlocks(EditSession session, Region region, Mask mask, Pattern pattern) throws MaxChangedBlocksException {
        if ("overflow".equals(pattern.toString())) throw new MaxChangedBlocksException(100);
        calls.add("replaceBlocks:" + region + ":" + mask + ":" + pattern);
        return 3;
    }

    public void completeLegacy(Operation op) throws MaxChangedBlocksException {
        if (op.toString().contains("overflow")) throw new MaxChangedBlocksException(100);
        calls.add("completeLegacy:" + op);
    }

    public ClipboardFormat schematicFormat() {
        return new ClipboardFormat() {
            @Override public ClipboardReader getReader(InputStream inputStream) {
                return new ClipboardReader() {
                    @Override public Clipboard read() throws IOException {
                        if (schematicIoFails) throw new IOException("simulated read failure");
                        calls.add("schematic.read");
                        return new NamedClipboard("loaded");
                    }
                    @Override public void close() { calls.add("reader.close"); }
                };
            }
            @Override public ClipboardWriter getWriter(OutputStream outputStream) {
                return new ClipboardWriter() {
                    @Override public void write(Clipboard clipboard) throws IOException {
                        if (schematicIoFails) throw new IOException("simulated write failure");
                        outputStream.write("FAKE-SCHEM".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        calls.add("schematic.write:" + clipboard);
                    }
                    @Override public void close() { calls.add("writer.close"); }
                };
            }
        };
    }

    private record NamedPattern(String name) implements Pattern {
        @Override public String toString() { return name; }
    }
    private record NamedMask(String name) implements Mask {
        @Override public String toString() { return name; }
    }
    private record NamedClipboard(String name) implements Clipboard {
        @Override public String toString() { return name; }
    }
}

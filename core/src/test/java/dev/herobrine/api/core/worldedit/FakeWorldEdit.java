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

    /** "Attempts" to change a fixed number of blocks, applied one at a time against the session's own
     *  configured limit (see {@link EditSession#applyWithLimit}) - not an atomic preflight check, so an
     *  edit that exceeds the limit still leaves the blocks up to the limit applied and recorded. */
    public int setBlocks(EditSession session, Region region, Pattern pattern) throws MaxChangedBlocksException {
        calls.add("setBlocks:" + region + ":" + pattern);
        return session.applyWithLimit(7);
    }

    public int replaceBlocks(EditSession session, Region region, Mask mask, Pattern pattern) throws MaxChangedBlocksException {
        calls.add("replaceBlocks:" + region + ":" + mask + ":" + pattern);
        return session.applyWithLimit(3);
    }

    /**
     * Simulates the operation actually running: a paste writes a fixed number of blocks into its
     * destination extent (the editSession passed to {@code ClipboardHolder.createPaste}), via the same
     * per-write-limited {@link EditSession#applyWithLimit} every other write goes through - so a paste's
     * history bookkeeping is exercised the same way setBlocks/replaceBlocks is. A plain copy's
     * {@code ForwardExtentCopy} isn't a {@code PasteOperation}, so it doesn't touch any size - copy only
     * reads through its editSession, it never writes through it.
     */
    public void completeLegacy(Operation op) throws MaxChangedBlocksException {
        calls.add("completeLegacy:" + op);
        if (op instanceof com.sk89q.worldedit.session.PasteBuilder.PasteOperation paste
                && paste.targetExtent() instanceof EditSession editSession) {
            editSession.applyWithLimit(4);
        }
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

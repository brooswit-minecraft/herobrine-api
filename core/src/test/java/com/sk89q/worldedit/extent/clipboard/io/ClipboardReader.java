package com.sk89q.worldedit.extent.clipboard.io;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import java.io.Closeable;
import java.io.IOException;

public interface ClipboardReader extends Closeable {
    Clipboard read() throws IOException;
}

package com.sk89q.worldedit.extent.clipboard.io;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import java.io.Closeable;
import java.io.IOException;

public interface ClipboardWriter extends Closeable {
    void write(Clipboard clipboard) throws IOException;
}

package com.sk89q.worldedit.extent.clipboard.io;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface ClipboardFormat {
    ClipboardReader getReader(InputStream inputStream) throws IOException;
    ClipboardWriter getWriter(OutputStream outputStream) throws IOException;
}

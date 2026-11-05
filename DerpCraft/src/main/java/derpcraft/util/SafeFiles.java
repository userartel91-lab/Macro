package derpcraft.util;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/**
 * Crash-safe saving: the text goes to "name.tmp" first and then replaces the file in one step, so a crash or a full
 * disk in the middle of a save leaves the old file intact instead of a half-written one (a broken blockids.txt
 * scrambles every block of the world).
 */
public final class SafeFiles {
    private SafeFiles() {}

    public static void writeString(Path file, CharSequence text) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Properties.store into a file the same way (same format as Properties.store to a Writer). */
    public static void store(Properties p, Path file, String comment) throws IOException {
        StringWriter w = new StringWriter();
        p.store(w, comment);
        writeString(file, w.toString());
    }
}

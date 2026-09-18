package red.jackf.chesttracker.impl.cmsync;

import red.jackf.chesttracker.impl.util.Constants;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;

/**
 * Append-only team-sync debug log: {@code <game>/chesttracker/cmsync.log}.
 *
 * <p>Every sync cycle, transport failure (WITH the exception message — chat only
 * shows the bare result), handshake, wipe and generation change lands here, so a
 * flaky connection can be diagnosed from the file instead of screenshots.
 * Capped at ~512KB (oldest lines trimmed on write); never throws.
 */
public final class CMSyncLog {
    private static final long MAX_BYTES = 512 * 1024;
    private static final int KEEP_LINES = 300;

    private CMSyncLog() {
    }

    public static synchronized void log(String tag, String msg) {
        try {
            Path dir = Constants.STORAGE_DIR;
            Files.createDirectories(dir);
            Path p = dir.resolve("cmsync.log");
            if (Files.isRegularFile(p) && Files.size(p) > MAX_BYTES) {
                List<String> all = Files.readAllLines(p, StandardCharsets.UTF_8);
                List<String> tail = all.subList(Math.max(0, all.size() - KEEP_LINES), all.size());
                Files.write(p, tail, StandardCharsets.UTF_8);
            }
            String line = "[" + Instant.now() + "] [" + tag + "] " + msg + System.lineSeparator();
            Files.writeString(p, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    /** Single-line, capped helper for exception/transport messages (chat + log). */
    public static String trunc(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').replace('\r', ' ').strip();
        if (s.isEmpty()) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}

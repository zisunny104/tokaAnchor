package dev.toka.anchor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Changes one value of config.yml in place, so every comment in the file stays where it is. */
public final class ConfigEdit {
    private ConfigEdit() {}

    /** Replaces the line {@code key: ...} inside the top-level block {@code section:}; false when the block or the key is not there. */
    public static boolean set(Path file, String section, String key, String value) throws IOException {
        List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
        int start = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).matches(java.util.regex.Pattern.quote(section) + ":\\s*(#.*)?")) { start = i; break; }
        if (start < 0) return false;
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) return false;
            if (line.matches("  " + java.util.regex.Pattern.quote(key) + ":.*")) {
                lines.set(i, "  " + key + ": " + value);
                Files.write(file, lines, StandardCharsets.UTF_8);
                return true;
            }
        }
        return false;
    }
}

package dev.toka.anchor;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConfigEditTest {
    @Test public void onlyTheNamedKeyOfTheNamedBlockChanges() throws Exception {
        Path file = Files.createTempFile("config", ".yml");
        Files.writeString(file, "# keep\nafk:\n  mode: x\naccess:\n  # note\n  mode: open\n  allow: []\nclean:\n  mode: y\n");
        assertTrue(ConfigEdit.set(file, "access", "mode", "linked"));
        assertEquals("# keep\nafk:\n  mode: x\naccess:\n  # note\n  mode: linked\n  allow: []\nclean:\n  mode: y\n".trim(), Files.readString(file).replace("\r\n", "\n").trim());
        assertFalse(ConfigEdit.set(file, "access", "missing", "1"));
        assertFalse(ConfigEdit.set(file, "nothing", "mode", "1"));
    }
}

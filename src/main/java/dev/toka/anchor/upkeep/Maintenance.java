package dev.toka.anchor.upkeep;

import cn.nukkit.Server;
import java.util.Locale;
import java.util.Set;

/**
 * Maintenance mode: while it is on only operators and the listed names may log in. Other plugins can ask whether it is on
 * through {@code Main.maintenance()}, so a feature that must not run during upkeep can stand aside.
 */
public final class Maintenance {
    private final Server server;
    private final Set<String> allowed;
    private volatile boolean on;
    private volatile String message = "";

    public Maintenance(Server server, Set<String> allowedNames, boolean on, String message) {
        this.server = server;
        this.allowed = Set.copyOf(allowedNames.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList());
        this.on = on;
        this.message = message == null ? "" : message;
    }

    public boolean on() { return on; }

    public String message() { return message; }

    public void set(boolean on, String message) {
        this.on = on;
        if (message != null && !message.isBlank()) this.message = message;
    }

    /** Whether this name may be here while maintenance is on. */
    public boolean admits(String name) { return server.isOp(name) || allowed.contains(name.toLowerCase(Locale.ROOT)); }
}

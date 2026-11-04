package dev.toka.anchor.upkeep;

import cn.nukkit.scheduler.TaskHandler;
import dev.toka.anchor.Main;

/**
 * A clean-up that tells everyone first and can be cancelled while it counts: the top of the screen every ten seconds, then every
 * second for the last five. Main thread only.
 */
public final class CleanCountdown {
    private final Main plugin;
    private final int minimum;
    private final boolean gc;
    private int left = -1;
    private TaskHandler task;

    public CleanCountdown(Main plugin, int minimumSeconds, boolean gc) {
        this.plugin = plugin;
        this.gc = gc;
        this.minimum = Math.max(1, minimumSeconds);
    }

    /** The id of the pinned line at the top, so every change replaces the one before. */
    private static final String PIN = "tokaAnchor.clean";

    public boolean running() { return left >= 0; }

    public int minimum() { return minimum; }

    /** Starts the countdown; false when one is already running. The time is never shorter than the configured minimum. */
    public boolean start(int seconds) {
        if (running()) return false;
        left = Math.max(minimum, seconds);
        plugin.getLogger().info(plugin.text(null, "console.clean.started", plugin.duration(null, left)));
        announce();
        task = plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, this::tick, 20);
        return true;
    }

    /** The timed clean-up: counts first and does nothing when there is nothing to remove. */
    public boolean startScheduled() {
        if (running() || plugin.cleaner().count().total() == 0) return false;
        return start(minimum);
    }

    /** Stops a countdown that is running; false when there is none. */
    public boolean cancel() {
        if (!running()) return false;
        stop();
        plugin.getLogger().info(plugin.text(null, "console.clean.cancelled"));
        plugin.unpinTop(PIN);
        plugin.pinTop(true, PIN, 4, "clean.cancelled");
        return true;
    }

    /** For onDisable: forget the countdown without saying anything */
    public void shutdown() { stop(); plugin.unpinTop(PIN); }

    private void stop() {
        left = -1;
        if (task != null) task.cancel();
        task = null;
    }

    private void tick() {
        if (left < 0) return;
        left--;
        if (left <= 0) {
            stop();
            var result = plugin.cleaner().clean();
            if (gc) System.gc();
            plugin.getLogger().info(plugin.text(null, "console.clean.done", result.items() + result.projectiles(), result.creatures(), result.orbs()));
            plugin.pinTop(false, PIN, 8, "clean.doneTop", result.items() + result.projectiles(), result.creatures(), result.orbs());
            return;
        }
        announce();
    }

    /** One line at the top for everyone, with what would go if it ran now. */
    private void announce() {
        plugin.pinTop(false, PIN, 0, "clean.top", plugin.duration(null, left));
    }
}

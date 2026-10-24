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
    private int left = -1;
    private TaskHandler task;

    public CleanCountdown(Main plugin, int minimumSeconds) {
        this.plugin = plugin;
        this.minimum = Math.max(1, minimumSeconds);
    }

    public boolean running() { return left >= 0; }

    public int minimum() { return minimum; }

    /** Starts the countdown; false when one is already running. The time is never shorter than the configured minimum. */
    public boolean start(int seconds) {
        if (running()) return false;
        left = Math.max(minimum, seconds);
        plugin.broadcast("clean.started", plugin.duration(null, left));
        announce();
        task = plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, this::tick, 20);
        return true;
    }

    /** Stops a countdown that is running; false when there is none. */
    public boolean cancel() {
        if (!running()) return false;
        stop();
        plugin.broadcast("clean.cancelled");
        return true;
    }

    /** For onDisable: forget the countdown without saying anything */
    public void shutdown() { stop(); }

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
            plugin.broadcast("clean.done", result.items() + result.projectiles(), result.creatures(), result.orbs());
            plugin.top("clean.doneTop", result.total());
            return;
        }
        if (left <= 5 || left % 10 == 0) announce();
    }

    private void announce() { plugin.top("clean.top", plugin.duration(null, left)); }
}

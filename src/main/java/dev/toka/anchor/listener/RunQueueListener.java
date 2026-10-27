package dev.toka.anchor.listener;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import dev.toka.anchor.Main;
import dev.toka.anchor.run.PendingRuns;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the commands queued for a player a moment after they log in. They leave the queue (and the stored document) before they
 * run, so a crash never runs one twice; what has not run when the player leaves, or the plugin stops, goes back to the front.
 */
public final class RunQueueListener implements Listener {
    private final Main plugin;
    private final PendingRuns pending;
    /** Taken from the queue, waiting for the delay to end. */
    private final Map<UUID, PendingRuns.Queue> waiting = new ConcurrentHashMap<>();

    public RunQueueListener(Main plugin, PendingRuns pending) {
        this.plugin = plugin;
        this.pending = pending;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        List<PendingRuns.Entry> entries = pending.take(player.getUniqueId(), System.currentTimeMillis());
        if (entries.isEmpty()) return;
        UUID id = player.getUniqueId();
        waiting.put(id, new PendingRuns.Queue(player.getName(), entries));
        int delay = Math.max(1, plugin.getConfig().getInt("run.queue.join-delay-ticks", 10));
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, () -> run(player, id), delay);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PendingRuns.Queue queue = waiting.remove(event.getPlayer().getUniqueId());
        if (queue != null) pending.putBack(event.getPlayer().getUniqueId(), queue.name(), queue.entries());
    }

    private void run(Player player, UUID id) {
        PendingRuns.Queue queue = waiting.remove(id);
        if (queue == null) return;
        List<PendingRuns.Entry> entries = queue.entries();
        for (int i = 0; i < entries.size(); i++) {
            if (!player.isOnline()) { pending.putBack(id, player.getName(), entries.subList(i, entries.size())); return; }
            PendingRuns.Entry entry = entries.get(i);
            plugin.log("ranAsQueued", entry.by(), player.getName(), entry.command());
            Player admin = plugin.getServer().getPlayerExact(entry.by());
            if (admin != null) admin.sendMessage(plugin.text(admin, "run.queuedDone", player.getName(), entry.command()));
            try { plugin.getServer().dispatchCommand(player, entry.command()); }
            catch (RuntimeException failure) { plugin.log("queuedFailed", player.getName(), entry.command(), failure.getClass().getSimpleName()); }
        }
    }

    /** Puts back everything that is still waiting out its delay; the plugin is stopping. */
    public void shutdown() {
        for (UUID id : List.copyOf(waiting.keySet())) {
            PendingRuns.Queue queue = waiting.remove(id);
            if (queue != null) pending.putBack(id, queue.name(), queue.entries());
        }
    }
}

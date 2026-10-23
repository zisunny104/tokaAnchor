package dev.toka.anchor.upkeep;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerMoveEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Decides who is away: a player who has not moved for the set time. It only knows the state; how it is shown (a light on the name
 * tag, a mark in a list) is up to whoever asks.
 */
public final class Afk implements Listener {
    private final Map<UUID, Long> lastMove = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final long afterMillis;

    public Afk(LongSupplier clock, int afterSeconds) {
        this.clock = clock;
        this.afterMillis = Math.max(1, afterSeconds) * 1000L;
    }

    public boolean isAfk(UUID player) {
        Long last = lastMove.get(player);
        return last != null && clock.getAsLong() - last >= afterMillis;
    }

    public void touch(UUID player) { lastMove.put(player, clock.getAsLong()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onMove(PlayerMoveEvent event) {
        var from = event.getFrom();
        var to = event.getTo();
        if (to != null && (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ() || from.getYaw() != to.getYaw() || from.getPitch() != to.getPitch()))
            touch(event.getPlayer().getUniqueId());
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { touch(event.getPlayer().getUniqueId()); }

    @EventHandler public void onQuit(PlayerQuitEvent event) { lastMove.remove(event.getPlayer().getUniqueId()); }

    public void touchAll(Iterable<Player> players) { for (Player player : players) touch(player.getUniqueId()); }
}

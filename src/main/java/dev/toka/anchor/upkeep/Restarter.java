package dev.toka.anchor.upkeep;

import cn.nukkit.Player;
import cn.nukkit.network.protocol.TransferPacket;
import cn.nukkit.scheduler.Task;
import dev.toka.anchor.Main;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Restarts the server at the configured daily times, or after a delay an administrator asks for, with a countdown told to
 * everyone. If an address is configured the players are sent back to it as the server stops, so their game reconnects by itself;
 * without one the server simply stops. One second is the finest the countdown needs, so it checks once a second.
 */
public final class Restarter {
    private final Main plugin;
    private final List<LocalTime> times;
    private final ZoneId zone;
    private final Set<Integer> steps;
    private final String transferAddress;
    private final int transferPort;
    private Instant target;
    private long announced = -1;

    public Restarter(Main plugin, List<LocalTime> times, ZoneId zone, Set<Integer> steps, String transferAddress, int transferPort) {
        this.plugin = plugin;
        this.times = times;
        this.zone = zone;
        this.steps = Set.copyOf(steps);
        this.transferAddress = transferAddress;
        this.transferPort = transferPort;
        this.target = RestartPlan.next(times, zone, Instant.now()).orElse(null);
    }

    public void start() {
        plugin.getServer().getScheduler().scheduleRepeatingTask(plugin, new Task() {
            @Override public void onRun(int tick) { tick(); }
        }, 20);
    }

    /** The next restart, or empty when none is planned. */
    public synchronized Optional<Instant> target() { return Optional.ofNullable(target); }

    public synchronized void restartIn(long seconds) {
        target = Instant.now().plusSeconds(Math.max(1, seconds));
        announced = -1;
    }

    /** Drops the planned restart and goes back to the daily times. */
    public synchronized void cancel() {
        target = RestartPlan.next(times, zone, Instant.now()).orElse(null);
        announced = -1;
    }

    private synchronized void tick() {
        if (target == null) return;
        long left = RestartPlan.secondsLeft(target, Instant.now());
        if (left > 0) {
            if (steps.contains((int) Math.min(left, Integer.MAX_VALUE)) && left != announced) {
                announced = left;
                plugin.broadcast("restart.countdown", left);
            }
            return;
        }
        target = null;
        plugin.getLogger().info("依排程重啟伺服器。");
        plugin.broadcast("restart.now");
        // Stop on the next tick so the last message is sent first.
        plugin.getServer().getScheduler().scheduleDelayedTask(plugin, this::stop, 20);
    }

    private void stop() {
        if (!transferAddress.isBlank()) {
            for (Player player : plugin.getServer().getOnlinePlayers().values()) {
                TransferPacket packet = new TransferPacket();
                packet.address = transferAddress;
                packet.port = transferPort;
                player.dataPacket(packet);
            }
        }
        plugin.getServer().shutdown();
    }
}

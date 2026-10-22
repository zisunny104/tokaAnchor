package dev.toka.anchor.listener;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerPreLoginEvent;
import dev.toka.anchor.Main;
import dev.toka.anchor.ban.Ban;
import dev.toka.anchor.ban.BanService;
import dev.toka.anchor.upkeep.Maintenance;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Optional;

/** Refuses the login of a banned player, with a message that says why, since when and until when. */
public final class GateListener implements Listener {
    private final Main plugin;
    private final BanService bans;
    private final Maintenance maintenance;

    public GateListener(Main plugin, BanService bans, Maintenance maintenance) {
        this.plugin = plugin;
        this.bans = bans;
        this.maintenance = maintenance;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreLogin(PlayerPreLoginEvent event) {
        Player player = event.getPlayer();
        if (maintenance.on() && !maintenance.admits(player.getName())) {
            event.setKickMessage(plugin.text(null, "maintenance.kick", maintenance.message()));
            event.setCancelled();
            return;
        }
        String device = null;
        try { device = player.getLoginChainData().getDeviceId(); } catch (RuntimeException unknown) { /* no device id: address only */ }
        Optional<Ban> ban = bans.checkLogin(player.getName(), player.getAddress(), device, System.currentTimeMillis());
        if (ban.isEmpty()) return;
        event.setKickMessage(message(ban.get()));
        event.setCancelled();
    }

    private String message(Ban ban) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        String until = ban.expires() == null ? plugin.text(null, "ban.forever") : plugin.text(null, "ban.until", format.format(new Date(ban.expires())));
        String appeal = plugin.getConfig().getString("ban.appeal", "");
        return plugin.text(null, "ban.kick", ban.name(), ban.reason(), format.format(new Date(ban.created())), ban.source(), until, appeal.isBlank() ? "" : "\n§f" + appeal);
    }
}

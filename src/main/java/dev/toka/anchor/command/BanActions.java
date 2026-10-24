package dev.toka.anchor.command;

import cn.nukkit.command.CommandSender;
import dev.toka.anchor.Main;
import dev.toka.anchor.ban.BanService;
import java.io.IOException;
import java.util.List;

/** Banning and lifting as one piece of logic, shared by the commands and the admin window. */
public final class BanActions {
    private final Main plugin;
    private final BanService bans;

    public BanActions(Main plugin, BanService bans) {
        this.plugin = plugin;
        this.bans = bans;
    }

    private void send(CommandSender to, String key, Object... args) { to.sendMessage(plugin.text(to, key, args)); }

    /** @param length milliseconds, or null for permanent */
    public void ban(CommandSender sender, String name, String reason, Long length, boolean withLinked) throws IOException {
        BanService.Outcome outcome = bans.ban(name, reason, sender.getName(), length, System.currentTimeMillis(), withLinked);
        plugin.log("banned", sender.getName(), outcome.banned(), reason);
        send(sender, "ban.done", name, reason);
        if (outcome.banned().size() > 1) send(sender, "ban.linked", String.join("、", outcome.banned().subList(1, outcome.banned().size())));
        if (!outcome.possible().isEmpty()) send(sender, "ban.maybe", String.join("、", outcome.possible()));
        for (String banned : outcome.banned()) {
            var online = plugin.getServer().getPlayerExact(banned);
            if (online != null) online.kick(plugin.text(online, "ban.kicked", reason), false);
        }
    }

    /** @return false when the account had no ban */
    public boolean lift(CommandSender sender, String name) throws IOException {
        List<String> lifted = bans.book().removeWithLinked(name, System.currentTimeMillis());
        if (lifted.isEmpty()) { send(sender, "ban.none", name); return false; }
        plugin.log("lifted", sender.getName(), lifted);
        send(sender, "ban.lifted", name);
        if (lifted.size() > 1) send(sender, "ban.liftedLinked", String.join("、", lifted.subList(1, lifted.size())));
        return true;
    }
}

package dev.toka.anchor.command;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.command.data.CommandParameter;
import dev.toka.anchor.Main;
import dev.toka.anchor.upkeep.Readable;
import dev.toka.anchor.upkeep.RestartPlan;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /anchor: help for everything in tokaAnchor, its status, and the upkeep commands (restart, clean, maintenance). */
public final class AnchorCommand extends Command {
    private final Main plugin;
    private final HelpPages help;
    private final AdminMenu menu;
    private final java.util.Map<BanCommand.Kind, BanCommand> banCommands;

    public AnchorCommand(Main plugin, dev.toka.anchor.ban.BanService bans, BanActions actions, java.util.Map<BanCommand.Kind, BanCommand> banCommands) {
        super("anchor", plugin.text(null, "desc.anchor"), "/anchor help");
        this.plugin = plugin;
        setPermission("tokaanchor.use;tokaanchor.ban;tokaanchor.admin");
        getCommandParameters().clear();
        addCommandParameters("help", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"help"}), CommandParameter.newType("page", true, CommandParamType.INT)});
        addCommandParameters("reload", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"reload"})});
        addCommandParameters("access", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"access"}), CommandParameter.newEnum("mode", new String[]{"open", "linked"})});
        addCommandParameters("ban", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"ban"}), CommandParameter.newType("player", CommandParamType.TARGET),
                CommandParameter.newEnum("length", dev.toka.anchor.ban.Span.PRESETS), CommandParameter.newType("reason", CommandParamType.MESSAGE)});
        addCommandParameters("banTimed", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"ban"}), CommandParameter.newType("player", CommandParamType.TARGET),
                CommandParameter.newType("length", CommandParamType.STRING), CommandParameter.newType("reason", CommandParamType.MESSAGE)});
        addCommandParameters("banForever", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"ban"}), CommandParameter.newType("player", CommandParamType.TARGET),
                CommandParameter.newType("reason", CommandParamType.MESSAGE)});
        addCommandParameters("unban", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"unban", "link"}), CommandParameter.newType("player", CommandParamType.TARGET)});
        addCommandParameters("list", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"list"}), CommandParameter.newType("page", true, CommandParamType.INT)});
        addCommandParameters("status", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"status"})});
        addCommandParameters("restartIn", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"restart"}), CommandParameter.newType("seconds", CommandParamType.INT)});
        addCommandParameters("restartOther", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"restart"}), CommandParameter.newEnum("what", new String[]{"cancel", "status"})});
        addCommandParameters("clean", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"clean"}), CommandParameter.newType("seconds", true, cn.nukkit.command.data.CommandParamType.INT)});
        addCommandParameters("cleanCancel", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"clean"}), CommandParameter.newEnum("cancel", new String[]{"cancel"})});
        addCommandParameters("kill", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"kill"}), CommandParameter.newType("player", CommandParamType.TARGET)});
        addCommandParameters("run", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"run"}), CommandParameter.newType("player", CommandParamType.TARGET),
                CommandParameter.newType("command", CommandParamType.RAWTEXT)});
        addCommandParameters("maintenance", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"maintenance"}),
                CommandParameter.newEnum("state", new String[]{"on", "off"}), CommandParameter.newType("message", true, CommandParamType.MESSAGE)});
        this.banCommands = banCommands;
        help = new HelpPages("anchor", List.of(
                new HelpPages.Section("basics", null, List.of("helpCmd")),
                new HelpPages.Section("ban", "tokaanchor.ban", List.of("b", "bTimed", "ub", "list", "status", "fallback")),
                new HelpPages.Section("link", "tokaanchor.ban", List.of("link", "linkRule")),
                new HelpPages.Section("upkeep", "tokaanchor.admin", List.of("restart", "restartStatus", "restartCancel", "clean", "maintenance", "kill", "run", "access", "reload")),
                new HelpPages.Section("settings", "tokaanchor.admin", List.of("accessCfg", "enterPerm", "keepmode", "cleanCfg", "afkCfg", "restartCfg", "maintCfg")),
                new HelpPages.Section("perms", "tokaanchor.admin", List.of("permList"))), plugin::text);
        menu = new AdminMenu(plugin, bans, actions, this, help);
    }

    public Ui ui() { return menu.ui(); }

    private void send(CommandSender to, String key, Object... args) { to.sendMessage(plugin.text(to, key, args)); }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("tokaanchor.admin")) return true;
        send(sender, "cmd.denied");
        return false;
    }

    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermissionSilent(sender)) { send(sender, "cmd.denied"); return true; }
        if (args.length == 0 && sender instanceof Player player && sender.hasPermission("tokaanchor.admin")) { menu.open(player); return true; }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "ban", "unban", "list", "link" -> {
                var kind = switch (action) { case "ban" -> BanCommand.Kind.BAN; case "unban" -> BanCommand.Kind.LIFT; case "list" -> BanCommand.Kind.LIST; default -> BanCommand.Kind.LINK; };
                banCommands.get(kind).execute(sender, label, Arrays.copyOfRange(args, 1, args.length));
            }
            case "reload" -> { if (admin(sender)) { plugin.reload(); plugin.log("reloaded"); send(sender, "reload.done"); } }
            case "access" -> { if (admin(sender)) access(sender, args.length > 1 ? args[1] : ""); }
            case "help" -> help.show(sender, args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0]);
            case "status" -> {
                if (!sender.hasPermission("tokaanchor.ban")) { send(sender, "cmd.denied"); break; }
                send(sender, "status", Readable.number(plugin.bans().book().list(System.currentTimeMillis()).size()), Readable.number(plugin.bans().sightings().snapshot().size()));
            }
            case "restart" -> { if (admin(sender)) restart(sender, args); }
            case "clean" -> { if (admin(sender)) clean(sender, args); }
            case "kill" -> { if (admin(sender)) kill(sender, args); }
            case "run" -> { if (admin(sender)) runAs(plugin, sender, Arrays.copyOfRange(args, 1, args.length)); }
            case "maintenance" -> { if (admin(sender)) maintenance(sender, args); }
            default -> send(sender, "cmd.usage");
        }
        return true;
    }

    /** Switches the login rule while the server runs and keeps it in config.yml. */
    void access(CommandSender sender, String mode) {
        if (!mode.equalsIgnoreCase("open") && !mode.equalsIgnoreCase("linked")) { send(sender, "access.usage"); return; }
        try {
            plugin.setAccessMode(mode.equalsIgnoreCase("linked"));
            plugin.log("accessMode", sender.getName(), mode.toLowerCase(Locale.ROOT));
            send(sender, "access.set", mode.toLowerCase(Locale.ROOT));
        } catch (java.io.IOException failure) {
            send(sender, "cmd.failed", failure.getMessage());
        }
    }

    void restart(CommandSender sender, String[] args) {
        var restarter = plugin.restarter();
        if (restarter == null) { send(sender, "restart.off"); return; }
        String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (what) {
            case "status" -> restarter.target().ifPresentOrElse(t -> {
                long left = RestartPlan.secondsLeft(t, Instant.now());
                send(sender, "restart.next", Main.restartTime(t, restarter.zone()));
                send(sender, "restart.in", plugin.duration(sender, left), Readable.number(left));
            }, () -> send(sender, "restart.none"));
            case "cancel" -> { restarter.cancel(); plugin.log("restartCancelled", sender.getName()); send(sender, "restart.cancelled"); }
            default -> {
                long seconds;
                try { seconds = Long.parseLong(what); } catch (NumberFormatException notANumber) { send(sender, "cmd.usage"); return; }
                if (seconds < 1 || seconds > 86_400) { send(sender, "cmd.usage"); return; }
                restarter.restartIn(seconds);
                plugin.log("restartPlanned", sender.getName(), Readable.number(seconds));
                send(sender, "restart.set", Readable.number(seconds));
            }
        }
    }

    private void kill(CommandSender sender, String[] args) {
        Player target = args.length < 2 ? null : plugin.getServer().getPlayerExact(args[1]);
        if (target == null) { send(sender, "run.noPlayer"); return; }
        target.kill();
        plugin.log("killed", sender.getName(), target.getName());
        send(sender, "kill.done", target.getName());
    }

    /** Carries a command out as an online player: the administrator's way of doing for someone what only they can do. */
    static void runAs(Main plugin, CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage(plugin.text(sender, "run.usage")); return; }
        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null) { sender.sendMessage(plugin.text(sender, "run.noPlayer")); return; }
        String command = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        plugin.log("ranAs", sender.getName(), target.getName(), command);
        sender.sendMessage(plugin.text(sender, "run.done", target.getName(), command));
        plugin.getServer().dispatchCommand(target, command);
    }

    void clean(CommandSender sender, String[] args) {
        var countdown = plugin.cleanCountdown();
        if (args.length > 1 && args[1].equalsIgnoreCase("cancel")) {
            if (!countdown.cancel()) send(sender, "clean.none");
            return;
        }
        int seconds = 0;
        if (args.length > 1) {
            try { seconds = Integer.parseInt(args[1]); } catch (NumberFormatException bad) { send(sender, "cmd.usage"); return; }
        }
        if (!countdown.start(seconds)) send(sender, "clean.already");
    }

    void maintenance(CommandSender sender, String[] args) {
        if (args.length < 2) { send(sender, "cmd.usage"); return; }
        var mode = plugin.maintenanceMode();
        boolean on = args[1].equalsIgnoreCase("on");
        if (!on && !args[1].equalsIgnoreCase("off")) { send(sender, "cmd.usage"); return; }
        mode.set(on, args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null);
        plugin.log(on ? "maintenanceOn" : "maintenanceOff", sender.getName());
        if (on) {
            for (Player player : plugin.getServer().getOnlinePlayers().values())
                if (player != sender && !player.hasPermission("tokaanchor.admin") && !mode.admits(player.getName())) player.kick(plugin.text(player, "maintenance.kick", mode.message()), false);
            send(sender, "maintenance.on");
        } else {
            send(sender, "maintenance.off");
        }
    }
}

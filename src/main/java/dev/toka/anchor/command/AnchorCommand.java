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

    public AnchorCommand(Main plugin) {
        super("anchor", plugin.text(null, "desc.anchor"), "/anchor help");
        this.plugin = plugin;
        setPermission("tokaanchor.use;tokaanchor.ban;tokaanchor.admin");
        getCommandParameters().clear();
        addCommandParameters("help", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"help"}),
                CommandParameter.newEnum("section", true, new String[]{"ban", "link", "upkeep"}), CommandParameter.newType("page", true, CommandParamType.INT)});
        addCommandParameters("status", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"status"})});
        addCommandParameters("restartIn", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"restart"}), CommandParameter.newType("seconds", CommandParamType.INT)});
        addCommandParameters("restartOther", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"restart"}), CommandParameter.newEnum("what", new String[]{"cancel", "status"})});
        addCommandParameters("clean", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"clean"})});
        addCommandParameters("kill", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"kill"}), CommandParameter.newType("player", CommandParamType.TARGET)});
        addCommandParameters("run", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"run"}), CommandParameter.newType("player", CommandParamType.TARGET),
                CommandParameter.newType("command", CommandParamType.RAWTEXT)});
        addCommandParameters("maintenance", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"maintenance"}),
                CommandParameter.newEnum("state", new String[]{"on", "off"}), CommandParameter.newType("message", true, CommandParamType.MESSAGE)});
        help = new HelpPages("anchor", List.of(
                new HelpPages.Section("ban", "tokaanchor.ban", List.of("b", "bTimed", "ub", "blist", "status")),
                new HelpPages.Section("link", "tokaanchor.ban", List.of("blink", "linkRule")),
                new HelpPages.Section("upkeep", "tokaanchor.admin", List.of("restart", "restartStatus", "restartCancel", "clean", "maintenance", "kill", "run"))), plugin::text);
    }

    private void send(CommandSender to, String key, Object... args) { to.sendMessage(plugin.text(to, key, args)); }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("tokaanchor.admin")) return true;
        send(sender, "cmd.denied");
        return false;
    }

    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermissionSilent(sender)) { send(sender, "cmd.denied"); return true; }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "help" -> help.show(sender, args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0]);
            case "status" -> {
                if (!sender.hasPermission("tokaanchor.ban")) { send(sender, "cmd.denied"); break; }
                send(sender, "status", Readable.number(plugin.bans().book().list(System.currentTimeMillis()).size()), Readable.number(plugin.bans().sightings().snapshot().size()));
            }
            case "restart" -> { if (admin(sender)) restart(sender, args); }
            case "clean" -> { if (admin(sender)) clean(sender); }
            case "kill" -> { if (admin(sender)) kill(sender, args); }
            case "run" -> { if (admin(sender)) runAs(plugin, sender, Arrays.copyOfRange(args, 1, args.length)); }
            case "maintenance" -> { if (admin(sender)) maintenance(sender, args); }
            default -> send(sender, "cmd.usage");
        }
        return true;
    }

    private void restart(CommandSender sender, String[] args) {
        var restarter = plugin.restarter();
        if (restarter == null) { send(sender, "restart.off"); return; }
        String what = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (what) {
            case "status" -> restarter.target().ifPresentOrElse(t -> {
                long left = RestartPlan.secondsLeft(t, Instant.now());
                send(sender, "restart.next", Main.restartTime(t, restarter.zone()));
                send(sender, "restart.in", plugin.duration(sender, left), Readable.number(left));
            }, () -> send(sender, "restart.none"));
            case "cancel" -> { restarter.cancel(); plugin.getLogger().info(sender.getName() + " 取消了手動重啟。"); send(sender, "restart.cancelled"); }
            default -> {
                long seconds;
                try { seconds = Long.parseLong(what); } catch (NumberFormatException notANumber) { send(sender, "cmd.usage"); return; }
                if (seconds < 1 || seconds > 86_400) { send(sender, "cmd.usage"); return; }
                restarter.restartIn(seconds);
                plugin.getLogger().info(sender.getName() + " 排定 " + seconds + " 秒後重啟。");
                send(sender, "restart.set", Readable.number(seconds));
            }
        }
    }

    private void kill(CommandSender sender, String[] args) {
        Player target = args.length < 2 ? null : plugin.getServer().getPlayerExact(args[1]);
        if (target == null) { send(sender, "run.noPlayer"); return; }
        target.kill();
        plugin.getLogger().info(sender.getName() + " 讓 " + target.getName() + " 死亡。");
        send(sender, "kill.done", target.getName());
    }

    /** Carries a command out as an online player: the administrator's way of doing for someone what only they can do. */
    static void runAs(Main plugin, CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage(plugin.text(sender, "run.usage")); return; }
        Player target = plugin.getServer().getPlayer(args[0]);
        if (target == null) { sender.sendMessage(plugin.text(sender, "run.noPlayer")); return; }
        String command = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        plugin.getLogger().info(sender.getName() + " 以 " + target.getName() + " 的身分執行：" + command);
        sender.sendMessage(plugin.text(sender, "run.done", target.getName(), command));
        plugin.getServer().dispatchCommand(target, command);
    }

    private void clean(CommandSender sender) {
        var result = plugin.cleaner().clean();
        plugin.getLogger().info(sender.getName() + " 執行清理，共 " + result.total() + " 個。");
        plugin.broadcast("clean.done", result.items() + result.projectiles(), result.creatures(), result.orbs());
    }

    private void maintenance(CommandSender sender, String[] args) {
        if (args.length < 2) { send(sender, "cmd.usage"); return; }
        var mode = plugin.maintenanceMode();
        boolean on = args[1].equalsIgnoreCase("on");
        if (!on && !args[1].equalsIgnoreCase("off")) { send(sender, "cmd.usage"); return; }
        mode.set(on, args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)) : null);
        plugin.getLogger().info(sender.getName() + (on ? " 開啟" : " 關閉") + "維護模式。");
        if (on) {
            for (Player player : plugin.getServer().getOnlinePlayers().values())
                if (player != sender && !player.hasPermission("tokaanchor.admin") && !mode.admits(player.getName())) player.kick(plugin.text(player, "maintenance.kick", mode.message()), false);
            send(sender, "maintenance.on");
        } else {
            send(sender, "maintenance.off");
        }
    }
}

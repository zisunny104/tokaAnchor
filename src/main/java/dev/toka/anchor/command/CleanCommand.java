package dev.toka.anchor.command;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParameter;
import dev.toka.anchor.Main;
import java.util.Locale;

/** /clean [seconds|cancel]: clean up loose items and creatures after a countdown that can be cancelled. */
public final class CleanCommand extends Command {
    private final Main plugin;

    public CleanCommand(Main plugin) {
        super("clean", "清理掉落物與沒有名字的生物（倒數後執行，可取消）");
        this.plugin = plugin;
        setPermission("tokaanchor.admin");
        getCommandParameters().clear();
        addCommandParameters("start", new CommandParameter[]{CommandParameter.newType("seconds", true, cn.nukkit.command.data.CommandParamType.INT)});
        addCommandParameters("cancel", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"cancel"})});
    }

    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermissionSilent(sender)) { sender.sendMessage(plugin.text(sender, "cmd.denied")); return true; }
        var countdown = plugin.cleanCountdown();
        if (args.length > 0 && args[0].toLowerCase(Locale.ROOT).equals("cancel")) {
            if (!countdown.cancel()) sender.sendMessage(plugin.text(sender, "clean.none"));
            return true;
        }
        int seconds = 0;
        if (args.length > 0) {
            try { seconds = Integer.parseInt(args[0]); } catch (NumberFormatException bad) { sender.sendMessage(plugin.text(sender, "cmd.usage")); return true; }
        }
        if (!countdown.start(seconds)) sender.sendMessage(plugin.text(sender, "clean.already"));
        return true;
    }
}

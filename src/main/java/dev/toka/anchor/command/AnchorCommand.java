package dev.toka.anchor.command;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.command.data.CommandParameter;
import dev.toka.anchor.Main;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** /anchor: the help for everything in tokaAnchor and its status. */
public final class AnchorCommand extends Command {
    private final Main plugin;
    private final HelpPages help;

    public AnchorCommand(Main plugin) {
        super("anchor", plugin.text(null, "desc.anchor"), "/anchor help");
        this.plugin = plugin;
        setPermission("tokaanchor.use;tokaanchor.ban");
        getCommandParameters().clear();
        addCommandParameters("help", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"help"}),
                CommandParameter.newEnum("section", true, new String[]{"ban", "link"}), CommandParameter.newType("page", true, CommandParamType.INT)});
        addCommandParameters("status", new CommandParameter[]{CommandParameter.newEnum("action", new String[]{"status"})});
        help = new HelpPages("anchor", List.of(
                new HelpPages.Section("ban", "tokaanchor.ban", List.of("b", "bTimed", "ub", "blist")),
                new HelpPages.Section("link", "tokaanchor.ban", List.of("blink", "linkRule"))), plugin::text);
    }

    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermissionSilent(sender)) { sender.sendMessage(plugin.text(sender, "cmd.denied")); return true; }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "help" -> help.show(sender, args.length > 1 ? Arrays.copyOfRange(args, 1, args.length) : new String[0]);
            case "status" -> {
                if (!sender.hasPermission("tokaanchor.ban")) { sender.sendMessage(plugin.text(sender, "cmd.denied")); break; }
                long now = System.currentTimeMillis();
                sender.sendMessage(plugin.text(sender, "status", plugin.bans().book().list(now).size(), plugin.bans().sightings().snapshot().size()));
            }
            default -> sender.sendMessage(plugin.text(sender, "cmd.usage"));
        }
        return true;
    }
}

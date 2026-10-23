package dev.toka.anchor.command;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.command.data.CommandParameter;
import dev.toka.anchor.Main;

/** /c <player> <command>: the short way to {@code /anchor run}. */
public final class RunCommand extends Command {
    private final Main plugin;

    public RunCommand(Main plugin) {
        super("c", plugin.text(null, "desc.run"), "/c <player> <command>");
        this.plugin = plugin;
        setPermission("tokaanchor.admin");
        getCommandParameters().clear();
        addCommandParameters("default", new CommandParameter[]{CommandParameter.newType("player", CommandParamType.TARGET), CommandParameter.newType("command", CommandParamType.RAWTEXT)});
    }

    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermissionSilent(sender)) { sender.sendMessage(plugin.text(sender, "cmd.denied")); return true; }
        AnchorCommand.runAs(plugin, sender, args);
        return true;
    }
}

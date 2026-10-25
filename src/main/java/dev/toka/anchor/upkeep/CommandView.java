package dev.toka.anchor.upkeep;

import cn.nukkit.command.data.CommandEnum;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.server.DataPacketSendEvent;
import cn.nukkit.network.protocol.AvailableCommandsPacket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/**
 * What the client lists when a player types a slash: the full name and the short names of one command (the aliases) both fill the list.
 * This keeps every name working but shows only the main one, and can leave whole commands out of the list too. Nothing is removed from
 * the server's command map, so a player who types a hidden name still gets the command.
 */
public final class CommandView implements Listener {
    private final boolean hideAliases;
    private final Set<String> hidden;

    public CommandView(boolean hideAliases, Set<String> hiddenCommands) {
        this.hideAliases = hideAliases;
        this.hidden = Set.copyOf(hiddenCommands);
    }

    @EventHandler(priority = EventPriority.HIGHEST) public void onSend(DataPacketSendEvent event) {
        if (!(event.getPacket() instanceof AvailableCommandsPacket packet) || packet.commands == null) return;
        hidden.forEach(packet.commands::remove);
        uniqueEnumNames(packet);
        lowerCaseEnums(packet);
        if (!hideAliases) return;
        packet.commands.forEach((name, versions) -> versions.versions.forEach(data -> {
            if (data.aliases != null) data.aliases = new CommandEnum(data.aliases.getName(), List.of(name));
        }));
    }

    /**
     * The client knows an option list by its name. Nukkit names the list of every {@code newEnum("action", values)} "actionEnums", so
     * two sub-commands (or two plugins) with different words under the same name overwrite each other on the client and the words stop
     * completing. A name that already stands for a different list of words gets a short suffix made from the words, the same list always the same name.
     */
    private static void uniqueEnumNames(AvailableCommandsPacket packet) {
        Map<String, List<String>> owner = new HashMap<>();
        Map<String, CommandEnum> renamed = new HashMap<>();
        packet.commands.values().forEach(versions -> versions.versions.forEach(data -> {
            if (data.aliases != null) data.aliases = unique(data.aliases, owner, renamed);
            data.overloads.values().forEach(overload -> {
                if (overload.input == null || overload.input.parameters == null) return;
                for (var parameter : overload.input.parameters)
                    if (parameter != null && parameter.enumData != null) parameter.enumData = unique(parameter.enumData, owner, renamed);
            });
        }));
    }

    static CommandEnum unique(CommandEnum list, Map<String, List<String>> owner, Map<String, CommandEnum> renamed) {
        List<String> words = list.getValues();
        List<String> first = owner.putIfAbsent(list.getName(), words);
        if (first == null || first.equals(words)) return list;
        String name = list.getName() + "_" + Integer.toHexString(words.hashCode());
        CommandEnum known = renamed.get(name);
        if (known != null && known.getValues().equals(words)) return known;
        while (owner.containsKey(name) && !owner.get(name).equals(words)) name = name + "x";
        owner.putIfAbsent(name, words);
        CommandEnum fresh = new CommandEnum(name, new ArrayList<>(words));
        renamed.put(name, fresh);
        return fresh;
    }

    /**
     * The client crashes on a command list whose words have a capital letter in them ("Keywords in commands need to be lower case").
     * Whatever a plugin offers, the words are made lower case here, so one careless list can never take a player out of the game.
     */
    private static void lowerCaseEnums(AvailableCommandsPacket packet) {
        packet.commands.values().forEach(versions -> versions.versions.forEach(data -> {
            if (data.aliases != null && needsLowering(data.aliases)) data.aliases = lowered(data.aliases);
            data.overloads.values().forEach(overload -> {
                if (overload.input == null || overload.input.parameters == null) return;
                for (var parameter : overload.input.parameters)
                    if (parameter != null && parameter.enumData != null && needsLowering(parameter.enumData)) parameter.enumData = lowered(parameter.enumData);
            });
        }));
    }

    private static boolean needsLowering(CommandEnum list) {
        return list.getValues().stream().anyMatch(word -> !word.equals(word.toLowerCase(Locale.ROOT)));
    }

    private static CommandEnum lowered(CommandEnum list) {
        return new CommandEnum(list.getName(), list.getValues().stream().map(word -> word.toLowerCase(Locale.ROOT)).distinct().toList());
    }
}

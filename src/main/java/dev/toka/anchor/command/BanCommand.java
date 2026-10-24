package dev.toka.anchor.command;

import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.command.data.CommandParamType;
import cn.nukkit.command.data.CommandParameter;
import dev.toka.anchor.Main;
import dev.toka.anchor.ban.Ban;
import dev.toka.anchor.ban.BanService;
import dev.toka.anchor.ban.Span;
import dev.toka.anchor.link.LinkFinder;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** The four ban commands: /b (ban), /ub (lift), /blist (list, paged) and /blink (accounts that look like the same person). */
public final class BanCommand extends Command {
    public enum Kind { BAN, LIFT, LIST, LINK }

    private static final int PER_PAGE = 10;
    private final Main plugin;
    private final BanService bans;
    private final Kind kind;
    private final BanActions actions;

    public BanCommand(Main plugin, BanService bans, BanActions actions, Kind kind, String name) {
        super(name, plugin.text(null, "desc." + kind.name().toLowerCase()), "/" + name);
        this.plugin = plugin;
        this.bans = bans;
        this.kind = kind;
        this.actions = actions;
        setPermission("tokaanchor.ban");
        getCommandParameters().clear();
        switch (kind) {
            case BAN -> {
                addCommandParameters("preset", new CommandParameter[]{CommandParameter.newType("player", CommandParamType.TARGET), CommandParameter.newEnum("length", Span.PRESETS),
                        CommandParameter.newType("reason", CommandParamType.MESSAGE)});
                addCommandParameters("timed", new CommandParameter[]{CommandParameter.newType("player", CommandParamType.TARGET), CommandParameter.newType("length", CommandParamType.STRING),
                        CommandParameter.newType("reason", CommandParamType.MESSAGE)});
                addCommandParameters("forever", new CommandParameter[]{CommandParameter.newType("player", CommandParamType.TARGET), CommandParameter.newType("reason", CommandParamType.MESSAGE)});
            }
            case LIFT, LINK -> addCommandParameters("default", new CommandParameter[]{CommandParameter.newType("player", CommandParamType.TARGET)});
            case LIST -> addCommandParameters("default", new CommandParameter[]{CommandParameter.newType("page", true, CommandParamType.INT)});
        }
    }

    private void send(CommandSender to, String key, Object... args) { to.sendMessage(plugin.text(to, key, args)); }

    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        if (!testPermissionSilent(sender)) { send(sender, "cmd.denied"); return true; }
        try {
            switch (kind) {
                case BAN -> ban(sender, args);
                case LIFT -> lift(sender, args);
                case LIST -> list(sender, args);
                case LINK -> link(sender, args);
            }
        } catch (IllegalArgumentException | IOException failure) {
            if (failure instanceof IllegalArgumentException) send(sender, "cmd.usage");
            else send(sender, "cmd.failed", failure.getMessage());
        }
        return true;
    }

    private void ban(CommandSender sender, String[] args) throws IOException {
        if (args.length < 2) throw new IllegalArgumentException("USAGE");
        Long length = Span.parse(args[1]);
        int from = length == null ? 1 : 2;
        if (args.length <= from) throw new IllegalArgumentException("USAGE");
        String reason = String.join(" ", Arrays.copyOfRange(args, from, args.length));
        actions.ban(sender, args[0], reason, length == null || length < 0 ? null : length, bans.autoBan());
    }

    private void lift(CommandSender sender, String[] args) throws IOException {
        if (args.length < 1) throw new IllegalArgumentException("USAGE");
        actions.lift(sender, args[0]);
    }

    private void list(CommandSender sender, String[] args) {
        List<Ban> all = bans.book().list(System.currentTimeMillis());
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        int page = 1;
        if (args.length > 0) try { page = Math.min(Math.max(Integer.parseInt(args[0]), 1), pages); } catch (NumberFormatException ignored) { /* first page */ }
        send(sender, "list.header", all.size(), page, pages);
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd");
        for (Ban ban : all.subList((page - 1) * PER_PAGE, Math.min(all.size(), page * PER_PAGE)))
            send(sender, "list.row", ban.name(), ban.reason(), format.format(new Date(ban.created())), ban.source(),
                    ban.expires() == null ? plugin.text(sender, "ban.forever") : plugin.text(sender, "ban.until", format.format(new Date(ban.expires()))));
    }

    private void link(CommandSender sender, String[] args) {
        if (args.length < 1) throw new IllegalArgumentException("USAGE");
        Map<String, LinkFinder.Strength> related = bans.related(args[0]);
        if (related.isEmpty()) { send(sender, "link.none", args[0]); return; }
        send(sender, "link.header", args[0]);
        related.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry ->
                send(sender, "link.row", entry.getKey(), plugin.text(sender, "link." + entry.getValue().name().toLowerCase())));
    }
}

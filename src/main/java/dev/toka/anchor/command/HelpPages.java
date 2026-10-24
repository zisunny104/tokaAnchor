package dev.toka.anchor.command;

import cn.nukkit.command.CommandSender;
import java.util.ArrayList;
import java.util.List;

/**
 * Chat help as numbered pages: the first page is the basics, every later page is one topic that starts with a line saying what
 * the topic is. A topic whose permission the sender lacks is left out, so nobody reads about commands they cannot use.
 */
public final class HelpPages {
    public interface Text { String of(CommandSender sender, String key, Object... args); }

    /** @param permission null when everyone may see the topic */
    public record Section(String id, String permission, List<String> keys) {}

    private static final int PER_PAGE = 7;
    private final String command;
    private final List<Section> sections;
    private final Text text;

    public HelpPages(String command, List<Section> sections, Text text) {
        this.command = command;
        this.sections = List.copyOf(sections);
        this.text = text;
    }

    private List<Section> visible(CommandSender sender) {
        return sections.stream().filter(s -> s.permission() == null || sender.hasPermission(s.permission())).toList();
    }

    /** Topic ids the sender may read; the window shows one button for each. */
    public List<String> ids(CommandSender sender) { return visible(sender).stream().map(Section::id).toList(); }

    /** The lines of one topic: the line that says what it is, then its commands. */
    public List<String> lines(CommandSender sender, String id) {
        List<String> out = new ArrayList<>();
        out.add(text.of(sender, "help.intro." + id));
        for (Section section : visible(sender)) if (section.id().equals(id)) for (String key : section.keys()) out.add(text.of(sender, "help." + key));
        return out;
    }

    private List<List<String>> pages(CommandSender sender) {
        List<List<String>> pages = new ArrayList<>();
        for (Section section : visible(sender)) {
            List<String> keys = section.keys();
            for (int from = 0; from < keys.size(); from += PER_PAGE) {
                List<String> page = new ArrayList<>();
                if (from == 0) page.add(text.of(sender, "help.intro." + section.id()));
                for (String key : keys.subList(from, Math.min(keys.size(), from + PER_PAGE))) page.add(text.of(sender, "help." + key));
                pages.add(page);
            }
        }
        return pages;
    }

    /** @param args what follows the word "help": a page number; anything else shows the first page */
    public void show(CommandSender sender, String[] args) {
        List<List<String>> pages = pages(sender);
        int page = 1;
        if (args.length > 0) try { page = Integer.parseInt(args[0]); } catch (NumberFormatException first) { page = 1; }
        page = Math.min(Math.max(page, 1), pages.size());
        sender.sendMessage(text.of(sender, "help.head", command, page, pages.size()));
        for (String line : pages.get(page - 1)) sender.sendMessage(line);
    }
}

package dev.toka.anchor.command;

import cn.nukkit.Player;
import cn.nukkit.form.element.ElementDropdown;
import cn.nukkit.form.element.ElementHeader;
import cn.nukkit.form.element.ElementInput;
import cn.nukkit.form.element.ElementLabel;
import cn.nukkit.form.element.ElementSlider;
import cn.nukkit.form.element.ElementToggle;
import cn.nukkit.form.response.FormResponse;
import cn.nukkit.form.response.FormResponseCustom;
import cn.nukkit.form.response.FormResponseModal;
import cn.nukkit.form.response.FormResponseSimple;
import cn.nukkit.form.window.FormWindowCustom;
import cn.nukkit.form.window.FormWindowModal;
import cn.nukkit.form.window.FormWindowSimple;
import dev.toka.anchor.Main;
import dev.toka.anchor.ban.Ban;
import dev.toka.anchor.ban.BanService;
import dev.toka.anchor.ban.Span;
import dev.toka.anchor.link.LinkFinder;
import dev.toka.anchor.upkeep.Readable;
import dev.toka.anchor.upkeep.RestartPlan;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The admin window of /anchor. It only collects input and shows state: every action goes to the service or the command code that
 * the commands use, so a window and a command can never do different things.
 */
final class AdminMenu {
    private static final int PAGE = 8;
    private static final String ICON_BANS = "textures/items/book_writable", ICON_NEW = "textures/ui/color_plus", ICON_RESTART = "textures/items/clock_item",
            ICON_CLEAN = "textures/items/bucket_empty", ICON_MAINT = "textures/items/iron_pickaxe", ICON_ACCESS = "textures/items/key",
            ICON_HELP = "textures/items/map_filled", ICON_SEARCH = "textures/items/compass_item";
    private final Main plugin;
    private final BanService bans;
    private final BanActions actions;
    private final AnchorCommand command;
    private final HelpPages help;
    private final Ui ui = new Ui();

    AdminMenu(Main plugin, BanService bans, BanActions actions, AnchorCommand command, HelpPages help) {
        this.plugin = plugin;
        this.bans = bans;
        this.actions = actions;
        this.command = command;
        this.help = help;
    }

    Ui ui() { return ui; }

    private String t(Player p, String key, Object... args) { return plugin.text(p, key, args); }

    private boolean may(Player p, String permission) {
        if (p.hasPermission(permission)) return true;
        p.sendMessage(t(p, "cmd.denied"));
        return false;
    }

    private static int clicked(FormResponse answer) { return ((FormResponseSimple) answer).getClickedButtonId(); }

    private static boolean yes(FormResponse answer) { return ((FormResponseModal) answer).getClickedButtonId() == 0; }

    void open(Player p) {
        if (!may(p, "tokaanchor.admin")) return;
        var window = new FormWindowSimple(t(p, "ui.title"), "");
        String[] labels = {"bans", "newBan", "restart", "clean", "maintenance", "access", "help"};
        String[] icons = {ICON_BANS, ICON_NEW, ICON_RESTART, ICON_CLEAN, ICON_MAINT, ICON_ACCESS, ICON_HELP};
        for (int i = 0; i < labels.length; i++) window.addButton(Ui.button(t(p, "ui.menu." + labels[i]), icons[i]));
        window.addButton(Ui.button(t(p, "ui.close"), null));
        ui.show(p, window, answer -> {
            switch (clicked(answer)) {
                case 0 -> bans(p, "", 0);
                case 1 -> newBan(p);
                case 2 -> restart(p);
                case 3 -> clean(p);
                case 4 -> maintenance(p);
                case 5 -> access(p);
                case 6 -> helpWindow(p);
                default -> { }
            }
        });
    }

    // ---- bans

    private String until(Player p, Ban ban) {
        return ban.expires() == null ? t(p, "ban.forever") : t(p, "ban.until", new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date(ban.expires())));
    }

    private void bans(Player p, String query, int page) {
        if (!may(p, "tokaanchor.ban")) return;
        String needle = query.toLowerCase(Locale.ROOT);
        List<Ban> all = bans.book().list(System.currentTimeMillis());
        List<Ban> hits = all.stream().filter(b -> needle.isEmpty() || b.name().toLowerCase(Locale.ROOT).contains(needle)
                || b.reason().toLowerCase(Locale.ROOT).contains(needle) || b.source().toLowerCase(Locale.ROOT).contains(needle)).toList();
        int pages = Math.max(1, (hits.size() + PAGE - 1) / PAGE);
        int current = Math.max(0, Math.min(page, pages - 1));
        var window = new FormWindowSimple(t(p, "ui.bans.title", current + 1, pages),
                t(p, query.isEmpty() ? "ui.bans.body" : "ui.bans.bodyQuery", Readable.number(hits.size()), Readable.number(all.size()), query));
        List<Runnable> actionsList = new ArrayList<>();
        window.addButton(Ui.button(t(p, "ui.back"), null));
        actionsList.add(() -> open(p));
        window.addButton(Ui.button(t(p, "ui.search"), ICON_SEARCH));
        actionsList.add(() -> search(p, query));
        if (current > 0) { window.addButton(Ui.button(t(p, "ui.prev"), null)); actionsList.add(() -> bans(p, query, current - 1)); }
        for (Ban ban : hits.subList(current * PAGE, Math.min(hits.size(), (current + 1) * PAGE))) {
            window.addButton(Ui.button(t(p, "ui.bans.entry", ban.name(), until(p, ban)), null));
            actionsList.add(() -> detail(p, ban.name()));
        }
        if (current < pages - 1) { window.addButton(Ui.button(t(p, "ui.next"), null)); actionsList.add(() -> bans(p, query, current + 1)); }
        ui.show(p, window, answer -> { int id = clicked(answer); if (id >= 0 && id < actionsList.size()) actionsList.get(id).run(); });
    }

    private void search(Player p, String query) {
        var window = new FormWindowCustom(t(p, "ui.search"));
        window.addElement(new ElementHeader(t(p, "ui.search")));
        window.addElement(new ElementInput(t(p, "ui.searchLabel"), t(p, "ui.searchHint"), query));
        ui.show(p, window, answer -> bans(p, ((FormResponseCustom) answer).getInputResponse(1).trim(), 0));
    }

    private void detail(Player p, String name) {
        Ban ban = bans.book().active(name, System.currentTimeMillis()).orElse(null);
        if (ban == null) { bans(p, "", 0); return; }
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        StringBuilder body = new StringBuilder();
        body.append(t(p, "ui.ban.name", ban.name())).append('\n');
        body.append(t(p, "ui.ban.reason", ban.reason())).append('\n');
        body.append(t(p, "ui.ban.by", ban.source())).append('\n');
        body.append(t(p, "ui.ban.since", format.format(new Date(ban.created())))).append('\n');
        body.append(t(p, "ui.ban.until", until(p, ban))).append('\n');
        if (ban.via() != null) body.append(t(p, "ui.ban.via", ban.via())).append('\n');
        Map<String, LinkFinder.Strength> related = bans.related(ban.name());
        if (related.isEmpty()) body.append(t(p, "ui.ban.linkedNone"));
        else {
            body.append(t(p, "ui.ban.linkedHead"));
            related.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e -> body.append('\n').append(e.getKey()).append("　").append(t(p, "link." + e.getValue().name().toLowerCase())));
        }
        var window = new FormWindowSimple(ban.name(), body.toString());
        window.addButton(Ui.button(t(p, "ui.back"), null));
        window.addButton(Ui.button(t(p, "ui.ban.lift"), "textures/ui/check"));
        window.addButton(Ui.button(t(p, "ui.close"), null));
        ui.show(p, window, answer -> {
            switch (clicked(answer)) {
                case 0 -> bans(p, "", 0);
                case 1 -> confirm(p, t(p, "ui.ban.confirm", ban.name()), () -> {
                    try { actions.lift(p, ban.name()); } catch (IOException failure) { p.sendMessage(t(p, "cmd.failed", failure.getMessage())); }
                    bans(p, "", 0);
                }, () -> detail(p, name));
                default -> { }
            }
        });
    }

    private void confirm(Player p, String body, Runnable yes, Runnable no) {
        var window = new FormWindowModal(t(p, "ui.confirm"), body, t(p, "ui.yes"), t(p, "ui.no"));
        ui.show(p, window, answer -> { if (yes(answer)) yes.run(); else no.run(); });
    }

    private void newBan(Player p) {
        if (!may(p, "tokaanchor.ban")) return;
        List<String> online = new ArrayList<>();
        online.add(t(p, "ui.new.typed"));
        for (Player other : plugin.getServer().getOnlinePlayers().values()) online.add(other.getName());
        var window = new FormWindowCustom(t(p, "ui.new.title"));
        window.addElement(new ElementHeader(t(p, "ui.new.headTarget")));
        window.addElement(new ElementDropdown(t(p, "ui.new.online"), online));
        window.addElement(new ElementInput(t(p, "ui.new.name"), "", ""));
        window.addElement(new ElementHeader(t(p, "ui.new.headReason")));
        window.addElement(new ElementDropdown(t(p, "ui.new.length"), List.of(Span.PRESETS), 2));
        window.addElement(new ElementInput(t(p, "ui.new.reason"), "", ""));
        window.addElement(new ElementToggle(t(p, "ui.new.linked"), bans.autoBan()));
        ui.show(p, window, answer -> {
            var response = (FormResponseCustom) answer;
            int picked = response.getDropdownResponse(1).getElementID();
            String typed = response.getInputResponse(2).trim();
            String name = !typed.isEmpty() ? typed : picked > 0 ? online.get(picked) : "";
            String reason = response.getInputResponse(5).trim();
            if (name.isEmpty() || reason.isEmpty()) { p.sendMessage(t(p, "ui.new.missing")); return; }
            Long length = Span.parse(Span.PRESETS[response.getDropdownResponse(4).getElementID()]);
            try { actions.ban(p, name, reason, length == null || length < 0 ? null : length, response.getToggleResponse(6)); }
            catch (IOException failure) { p.sendMessage(t(p, "cmd.failed", failure.getMessage())); }
        });
    }

    // ---- restart

    private void restart(Player p) {
        if (!may(p, "tokaanchor.admin")) return;
        var restarter = plugin.restarter();
        if (restarter == null) {
            var window = new FormWindowSimple(t(p, "ui.restart.title"), t(p, "restart.off"));
            window.addButton(Ui.button(t(p, "ui.back"), null));
            ui.show(p, window, answer -> open(p));
            return;
        }
        String next = restarter.target().map(at -> t(p, "restart.next", Main.restartTime(at, restarter.zone()))
                + "\n" + t(p, "restart.in", plugin.duration(p, RestartPlan.secondsLeft(at, Instant.now())), Readable.number(RestartPlan.secondsLeft(at, Instant.now()))))
                .orElse(t(p, "restart.none"));
        var window = new FormWindowCustom(t(p, "ui.restart.title"));
        window.addElement(new ElementHeader(t(p, "ui.restart.title")));
        window.addElement(new ElementLabel(next));
        window.addElement(new ElementDropdown(t(p, "ui.restart.action"), List.of(t(p, "ui.restart.plan"), t(p, "ui.restart.cancel"))));
        window.addElement(new ElementSlider(t(p, "ui.restart.minutes"), 1, 120, 1, 5));
        ui.show(p, window, answer -> {
            var response = (FormResponseCustom) answer;
            boolean plan = response.getDropdownResponse(2).getElementID() == 0;
            int minutes = (int) response.getSliderResponse(3);
            confirm(p, plan ? t(p, "ui.restart.confirmPlan", minutes) : t(p, "ui.restart.confirmCancel"), () -> {
                command.restart(p, plan ? new String[]{"restart", String.valueOf(minutes * 60L)} : new String[]{"restart", "cancel"});
                open(p);
            }, () -> restart(p));
        });
    }

    // ---- clean

    private void clean(Player p) {
        if (!may(p, "tokaanchor.admin")) return;
        var cleaner = plugin.cleaner();
        var window = new FormWindowCustom(t(p, "ui.clean.title"));
        window.addElement(new ElementHeader(t(p, "ui.clean.kinds")));
        window.addElement(new ElementToggle(t(p, "ui.clean.items"), cleaner.items()));
        window.addElement(new ElementToggle(t(p, "ui.clean.creatures"), cleaner.creatures()));
        window.addElement(new ElementToggle(t(p, "ui.clean.projectiles"), cleaner.projectiles()));
        window.addElement(new ElementToggle(t(p, "ui.clean.orbs"), cleaner.orbs()));
        window.addElement(new ElementHeader(t(p, "ui.clean.run")));
        window.addElement(new ElementToggle(t(p, "ui.clean.now"), false));
        ui.show(p, window, answer -> {
            var response = (FormResponseCustom) answer;
            plugin.setCleanKinds(response.getToggleResponse(1), response.getToggleResponse(2), response.getToggleResponse(3), response.getToggleResponse(4));
            p.sendMessage(t(p, "ui.clean.saved"));
            if (response.getToggleResponse(6)) confirm(p, t(p, "ui.clean.confirm"), () -> command.clean(p, new String[]{"clean"}), () -> open(p));
            else open(p);
        });
    }

    // ---- maintenance

    private void maintenance(Player p) {
        if (!may(p, "tokaanchor.admin")) return;
        var mode = plugin.maintenanceMode();
        var window = new FormWindowCustom(t(p, "ui.maint.title"));
        window.addElement(new ElementHeader(t(p, "ui.maint.title")));
        window.addElement(new ElementToggle(t(p, "ui.maint.on"), mode.on()));
        window.addElement(new ElementInput(t(p, "ui.maint.message"), "", mode.message()));
        ui.show(p, window, answer -> {
            var response = (FormResponseCustom) answer;
            boolean on = response.getToggleResponse(1);
            String message = response.getInputResponse(2).trim();
            confirm(p, t(p, on ? "ui.maint.confirmOn" : "ui.maint.confirmOff"), () -> {
                command.maintenance(p, message.isEmpty() ? new String[]{"maintenance", on ? "on" : "off"} : new String[]{"maintenance", on ? "on" : "off", message});
                open(p);
            }, () -> maintenance(p));
        });
    }

    // ---- login access

    private void access(Player p) {
        if (!may(p, "tokaanchor.admin")) return;
        var window = new FormWindowCustom(t(p, "ui.access.title"));
        window.addElement(new ElementHeader(t(p, "ui.access.title")));
        window.addElement(new ElementDropdown(t(p, "ui.access.mode"), List.of(t(p, "ui.access.open"), t(p, "ui.access.linked")), plugin.access().restricted() ? 1 : 0));
        window.addElement(new ElementLabel(t(p, "ui.access.warn")));
        ui.show(p, window, answer -> {
            boolean linked = ((FormResponseCustom) answer).getDropdownResponse(1).getElementID() == 1;
            if (linked == plugin.access().restricted()) { open(p); return; }
            if (linked) confirm(p, t(p, "ui.access.confirm"), () -> applyAccess(p, true), () -> access(p));
            else applyAccess(p, false);
        });
    }

    private void applyAccess(Player p, boolean linked) {
        command.access(p, linked ? "linked" : "open");
        open(p);
    }

    // ---- help

    private void helpWindow(Player p) {
        List<String> ids = help.ids(p);
        var window = new FormWindowSimple(t(p, "ui.help.title"), "");
        window.addButton(Ui.button(t(p, "ui.back"), null));
        for (String id : ids) window.addButton(Ui.button(t(p, "help.topic." + id), null));
        ui.show(p, window, answer -> {
            int id = clicked(answer);
            if (id == 0) { open(p); return; }
            if (id - 1 >= ids.size()) return;
            String topic = ids.get(id - 1);
            var page = new FormWindowSimple(t(p, "help.topic." + topic), String.join("\n", help.lines(p, topic)));
            page.addButton(Ui.button(t(p, "ui.back"), null));
            ui.show(p, page, ignored -> helpWindow(p));
        });
    }
}

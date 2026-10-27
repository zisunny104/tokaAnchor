package dev.toka.anchor;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.plugin.PluginBase;
import dev.toka.anchor.ban.BanBook;
import dev.toka.anchor.ban.BanService;
import dev.toka.anchor.command.AnchorCommand;
import dev.toka.anchor.command.BanActions;
import dev.toka.anchor.command.BanCommand;
import dev.toka.anchor.command.RunCommand;
import dev.toka.anchor.link.Sightings;
import dev.toka.anchor.listener.GateListener;
import dev.toka.anchor.upkeep.Access;
import dev.toka.anchor.upkeep.Cleaner;
import dev.toka.anchor.upkeep.Maintenance;
import dev.toka.anchor.upkeep.Readable;
import dev.toka.anchor.upkeep.RestartPlan;
import dev.toka.anchor.upkeep.Restarter;
import dev.toka.i18n.Messages;
import dev.toka.storage.Backend;
import dev.toka.storage.DocumentStore;
import dev.toka.storage.StoreConfig;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Switchboard for the server's upkeep: bans and the check at login, scheduled restarts, clean-up of loose entities and
 * maintenance mode. Each part works on its own and is switched on in the configuration; if the ban data cannot be read the
 * plugin refuses to start rather than start with an empty list.
 */
public final class Main extends PluginBase {
    private static volatile Main instance;
    private DocumentStore store;
    private BanService bans;
    private Restarter restarter;
    private Cleaner cleaner;
    private dev.toka.anchor.upkeep.CleanCountdown cleanCountdown;
    private Maintenance maintenance;
    private dev.toka.anchor.upkeep.Access access;
    private cn.nukkit.scheduler.TaskHandler cleanTask;
    private dev.toka.anchor.upkeep.Afk afk;
    private dev.toka.anchor.upkeep.ModeKeeper modes;
    private dev.toka.anchor.run.PendingRuns pendingRuns;
    private dev.toka.anchor.listener.RunQueueListener runQueue;
    private volatile java.lang.reflect.Method profileLookup;
    private final List<Command> commands = new ArrayList<>();
    private final Map<String, Messages> languages = new HashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            var config = getConfig();
            Path pluginDirectory = getDataFolder().toPath().toAbsolutePath().normalize();
            Path directory = pluginDirectory.resolve(config.getString("storage.directory", "storage")).normalize();
            if (!directory.startsWith(pluginDirectory)) throw new IllegalArgumentException("storage.directory must remain inside the plugin data folder");
            Backend backend = Backend.valueOf(config.getString("storage.backend", "SQLITE").strip().toUpperCase(Locale.ROOT));
            store = DocumentStore.open(new StoreConfig(backend, directory, config.getInt("storage.schema-version", 1)));
            Sightings sightings;
            try { sightings = new Sightings(store, config.getInt("link.keep", 10)); }
            catch (Exception | LinkageError unreadable) {
                getLogger().error(text(null, "log.sightingsUnreadable"), unreadable);
                sightings = Sightings.unavailable(config.getInt("link.keep", 10));
            }
            var limits = queueLimits(config);
            try { pendingRuns = new dev.toka.anchor.run.PendingRuns(store, limits, System.currentTimeMillis(), failure -> getLogger().warning(text(null, "log.pendingSaveFailed", failure.getClass().getSimpleName()))); }
            catch (Exception | LinkageError unreadable) {
                getLogger().error(text(null, "log.pendingUnreadable"), unreadable);
                pendingRuns = dev.toka.anchor.run.PendingRuns.unavailable(limits);
            }
            runQueue = new dev.toka.anchor.listener.RunQueueListener(this, pendingRuns);
            getServer().getPluginManager().registerEvents(runQueue, this);
            bans = new BanService(new BanBook(store), sightings, autoBan(config), (key, detail) -> getLogger().warning(text(null, "log." + key, detail)));
            getServer().getPluginManager().registerEvents(new GateListener(this, bans), this);
            modes = new dev.toka.anchor.upkeep.ModeKeeper(new java.io.File(getDataFolder(), "modes.yml"));
            getServer().getPluginManager().registerEvents(modes, this);
            build(config);
            var actions = new BanActions(this, bans);
            Map<BanCommand.Kind, BanCommand> banCommands = new EnumMap<>(BanCommand.Kind.class);
            banCommands.put(BanCommand.Kind.BAN, new BanCommand(this, bans, actions, BanCommand.Kind.BAN, "b"));
            banCommands.put(BanCommand.Kind.LIFT, new BanCommand(this, bans, actions, BanCommand.Kind.LIFT, "ub"));
            banCommands.put(BanCommand.Kind.LIST, new BanCommand(this, bans, actions, BanCommand.Kind.LIST, "blist"));
            banCommands.put(BanCommand.Kind.LINK, new BanCommand(this, bans, actions, BanCommand.Kind.LINK, "blink"));
            register(banCommands.get(BanCommand.Kind.BAN), "ban");
            register(banCommands.get(BanCommand.Kind.LIFT), "unban");
            register(banCommands.get(BanCommand.Kind.LIST), "list");
            register(banCommands.get(BanCommand.Kind.LINK), "link");
            getServer().getPluginManager().registerEvents(new dev.toka.anchor.upkeep.CommandView(config.getBoolean("commands.hide-aliases", true),
                    new HashSet<>(config.exists("commands.hide") ? config.getStringList("commands.hide")
                            : List.of("b", "ub", "blist", "blink", "c", "bal", "money", "topmoney", "seemoney", "givemoney", "takemoney", "setmoney"))), this);
            var anchorCommand = new AnchorCommand(this, bans, actions, banCommands);
            getServer().getPluginManager().registerEvents(anchorCommand.ui(), this);
            register(anchorCommand, null);
            register(new RunCommand(this), "run");
            register(new dev.toka.anchor.command.CleanCommand(this), "clean");
            instance = this;
        } catch (Exception | LinkageError error) {
            getLogger().error(text(null, "log.loadFailed"), error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private static boolean autoBan(cn.nukkit.utils.Config config) { return config.getString("link.auto-ban", "direct").equalsIgnoreCase("direct"); }

    private static dev.toka.anchor.run.PendingRuns.Limits queueLimits(cn.nukkit.utils.Config config) {
        return dev.toka.anchor.run.PendingRuns.Limits.ofDays(config.getInt("run.queue.max-per-player", 10), config.getInt("run.queue.max-length", 256), config.getInt("run.queue.max-age-days", 30));
    }

    /** Commands waiting for offline players. */
    public dev.toka.anchor.run.PendingRuns pendingRuns() { return pendingRuns; }

    /**
     * The account with this name according to tokaProfiles (a soft link, by reflection), or null when the name is unknown or
     * tokaProfiles is not there.
     */
    public java.util.UUID knownPlayer(String name) {
        var profiles = getServer().getPluginManager().getPlugin("tokaProfiles");
        if (profiles == null || !profiles.isEnabled()) { profileLookup = null; return null; }
        try {
            java.lang.reflect.Method method = profileLookup;
            if (method == null || method.getDeclaringClass().getClassLoader() != profiles.getClass().getClassLoader()) {
                method = Class.forName("dev.toka.profiles.Main", true, profiles.getClass().getClassLoader()).getMethod("findPlayerByName", String.class);
                profileLookup = method;
            }
            return (java.util.UUID) method.invoke(null, name);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException older) {
            return null;
        }
    }

    /** Builds everything that is derived from config.yml; a reload runs it again after dropping the old pieces. */
    private void build(cn.nukkit.utils.Config config) {
        pendingRuns.limits(queueLimits(config));
        maintenance = new Maintenance(getServer(), new HashSet<>(config.getStringList("maintenance.allow")), config.getBoolean("maintenance.enabled", false),
                config.getString("maintenance.message", ""));
        access = new Access(getServer(), config.getString("access.mode", "open").equalsIgnoreCase("linked"), new HashSet<>(config.getStringList("access.allow")));
        cleaner = new Cleaner(getServer(), config.getBoolean("clean.items", true), config.getBoolean("clean.creatures", true),
                config.getBoolean("clean.projectiles", true), config.getBoolean("clean.orbs", true));
        cleanCountdown = new dev.toka.anchor.upkeep.CleanCountdown(this, config.getInt("clean.countdown-seconds", 10));
        afk = new dev.toka.anchor.upkeep.Afk(System::currentTimeMillis, config.getInt("afk.seconds", 300));
        getServer().getPluginManager().registerEvents(afk, this);
        afk.touchAll(getServer().getOnlinePlayers().values());
        try { startRestarter(config); } catch (Exception | LinkageError failure) { getLogger().error(text(null, "log.restartFailed"), failure); }
        try { startCleaning(config); } catch (Exception | LinkageError failure) { getLogger().error(text(null, "log.cleanStartFailed"), failure); }
    }

    /** Re-reads config.yml and the texts and rebuilds the pieces that captured them; bans and commands stay. */
    public void reload() {
        reloadConfig();
        languages.clear();
        if (restarter != null) { restarter.shutdown(); restarter = null; }
        if (cleanTask != null) { cleanTask.cancel(); cleanTask = null; }
        if (cleanCountdown != null) cleanCountdown.shutdown();
        if (afk != null) cn.nukkit.event.HandlerList.unregisterAll(afk);
        bans.autoBan(autoBan(getConfig()));
        build(getConfig());
    }

    /** Logs a line from the language file (log.*) to the console. */
    public void log(String key, Object... args) { getLogger().info(text(null, "log." + key, args)); }

    public Access access() { return access; }

    /** Switches the login rule now and writes it to config.yml so it survives a restart. */
    public void setAccessMode(boolean linked) throws java.io.IOException {
        ConfigEdit.set(new java.io.File(getDataFolder(), "config.yml").toPath(), "access", "mode", linked ? "linked" : "open");
        access.linkedOnly(linked);
    }

    /** Changes which kinds the clean-up removes and keeps the choice in config.yml. */
    public void setCleanKinds(boolean items, boolean creatures, boolean projectiles, boolean orbs) {
        cleaner.kinds(items, creatures, projectiles, orbs);
        var file = new java.io.File(getDataFolder(), "config.yml").toPath();
        try {
            ConfigEdit.set(file, "clean", "items", String.valueOf(items));
            ConfigEdit.set(file, "clean", "creatures", String.valueOf(creatures));
            ConfigEdit.set(file, "clean", "projectiles", String.valueOf(projectiles));
            ConfigEdit.set(file, "clean", "orbs", String.valueOf(orbs));
        } catch (java.io.IOException failure) {
            getLogger().warning(text(null, "log.configWriteFailed", failure.getClass().getSimpleName()));
        }
    }

    private void startRestarter(cn.nukkit.utils.Config config) {
        if (!config.getBoolean("restart.enabled", true)) return;
        List<String> rejected = new ArrayList<>();
        List<LocalTime> times = RestartPlan.parse(config.getStringList("restart.times"), rejected);
        if (!rejected.isEmpty()) getLogger().warning(text(null, "log.restartTimesRejected", rejected));
        ZoneId chosen;
        try {
            String id = config.getString("restart.zone", "");
            chosen = id.isBlank() ? ZoneId.systemDefault() : ZoneId.of(id);
        } catch (java.time.DateTimeException bad) {
            getLogger().warning(text(null, "log.restartZoneBad"));
            chosen = ZoneId.systemDefault();
        }
        ZoneId zone = chosen;
        Set<Integer> steps = new HashSet<>(config.getIntegerList("restart.countdown"));
        restarter = new Restarter(this, times, zone, steps, config.getString("restart.transfer-address", ""), config.getInt("restart.transfer-port", 19132));
        restarter.start();
        restarter.target().ifPresentOrElse(t -> {
            long left = RestartPlan.secondsLeft(t, java.time.Instant.now());
            getLogger().info(text(null, "restart.next", restartTime(t, zone)));
            getLogger().info(text(null, "restart.in", duration(null, left), Readable.number(left)));
        }, () -> getLogger().info(text(null, "log.restartNone")));
    }

    private void startCleaning(cn.nukkit.utils.Config config) {
        int minutes = config.getInt("clean.interval-minutes", 0);
        if (minutes <= 0) return;
        int ticks = (int) Math.min(Integer.MAX_VALUE, minutes * 1200L);
        cleanTask = getServer().getScheduler().scheduleDelayedRepeatingTask(this, () -> {
            try { cleanCountdown.start(cleanCountdown.minimum()); }
            catch (RuntimeException failure) { getLogger().warning(text(null, "log.cleanFailed", failure.getClass().getSimpleName())); }
        }, ticks, ticks);
    }

    /**
     * Registers a command unless another plugin already owns the name, and says so instead of taking it.
     *
     * @param fallback the /anchor word that does the same, named in the console line when the name is taken; null for none
     */
    private void register(Command command, String fallback) {
        var map = getServer().getCommandMap();
        var existing = map.getCommand(command.getName());
        if (existing != null) {
            String owner = existing instanceof cn.nukkit.command.PluginCommand<?> pluginCommand ? pluginCommand.getPlugin().getName() : existing.getClass().getSimpleName();
            getLogger().warning(text(null, "log.commandTaken", command.getName(), owner, fallback == null ? "help" : fallback));
            return;
        }
        if (map.register("tokaAnchor", command) && map.getCommand(command.getName()) == command) commands.add(command);
        else getLogger().error(text(null, "log.commandFailed", command.getName()));
    }

    @Override public void onDisable() {
        if (cleanCountdown != null) cleanCountdown.shutdown();
        if (restarter != null) restarter.shutdown();
        if (modes != null) modes.rememberAll(getServer().getOnlinePlayers().values());
        var map = getServer().getCommandMap();
        for (Command command : commands) {
            map.getCommands().entrySet().removeIf(entry -> entry.getValue() == command);
            command.unregister(map);
        }
        commands.clear();
        if (instance == this) instance = null;
        if (runQueue != null) runQueue.shutdown();
        if (pendingRuns != null) pendingRuns.close();
        if (bans != null) bans.close();
        if (store != null) try { store.close(); } catch (java.io.IOException error) { getLogger().error(text(null, "log.storageCloseFailed"), error); }
    }

    public BanService bans() { return bans; }

    /** Null when restarts are not switched on. */
    public Restarter restarter() { return restarter; }

    public Cleaner cleaner() { return cleaner; }

    public dev.toka.anchor.upkeep.CleanCountdown cleanCountdown() { return cleanCountdown; }

    public Maintenance maintenanceMode() { return maintenance; }

    /** Whether the player has been still for the set time; false when tokaAnchor is not running. For other plugins, by reflection. */
    public static boolean isAfk(java.util.UUID player) {
        Main main = instance;
        return main != null && main.afk != null && main.afk.isAfk(player);
    }

    public static boolean maintenance() {
        Main main = instance;
        return main != null && main.maintenance != null && main.maintenance.on();
    }

    /** A moment as people read it: date and time in the configured zone. */
    public static String restartTime(java.time.Instant moment, ZoneId zone) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(moment.atZone(zone));
    }

    /** A span as words in the sender's language, two units at most, for example "13 小時 44 分鐘". */
    public String duration(CommandSender sender, long seconds) {
        return Readable.span(seconds, (unit, count) -> text(sender, "time." + unit, Readable.number(count)));
    }

    /** Tells every player in their own language; the console gets console.* instead, without the player tag. */
    public void broadcast(String key, Object... args) {
        for (Player player : getServer().getOnlinePlayers().values()) player.sendMessage(text(player, key, args));
        getLogger().info(text(null, "console." + key, args));
    }

    /** A line at the top of every player's screen (tokaDisplay), in their language; without tokaDisplay it goes to the action bar. */
    public void top(String key, Object... args) {
        java.lang.reflect.Method flash = null;
        var display = getServer().getPluginManager().getPlugin("tokaDisplay");
        if (display != null && display.isEnabled()) {
            try { flash = Class.forName("dev.toka.display.Main", true, display.getClass().getClassLoader()).getMethod("flash", Player.class, String.class, int.class, float.class, String.class); }
            catch (ReflectiveOperationException | LinkageError older) { flash = null; }
        }
        for (Player player : getServer().getOnlinePlayers().values()) {
            String line = text(player, key, args);
            try { if (flash != null) { flash.invoke(null, player, line, 2, -1f, null); continue; } }
            catch (ReflectiveOperationException | LinkageError unavailable) { /* action bar below */ }
            player.sendActionBar(line);
        }
    }

    /** Text in the sender's language; a null sender (a login that has not finished) gets the configured one. */
    public String text(CommandSender sender, String key, Object... args) {
        String tag = sender instanceof Player player && player.getLocale() != null ? player.getLocale().toLanguageTag() : getConfig().getString("language", "zh-TW");
        String selected = tag.startsWith("en") ? "en-US" : "zh-TW";
        return languages.computeIfAbsent(selected, t -> new Messages(getClass().getClassLoader(), getDataFolder().toPath(), t, line -> getLogger().warning(line))).text(key, args);
    }
}

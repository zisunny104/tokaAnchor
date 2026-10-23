package dev.toka.anchor;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.plugin.PluginBase;
import dev.toka.anchor.ban.BanBook;
import dev.toka.anchor.ban.BanService;
import dev.toka.anchor.command.AnchorCommand;
import dev.toka.anchor.command.BanCommand;
import dev.toka.anchor.command.RunCommand;
import dev.toka.anchor.link.Sightings;
import dev.toka.anchor.listener.GateListener;
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
    private Maintenance maintenance;
    private dev.toka.anchor.upkeep.Afk afk;
    private dev.toka.anchor.upkeep.ModeKeeper modes;
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
                getLogger().error("登入位址與裝置的紀錄讀不出來；封禁照常運作，但暫時不辨識關聯帳號，也不會覆寫該紀錄。", unreadable);
                sightings = Sightings.unavailable(config.getInt("link.keep", 10));
            }
            bans = new BanService(new BanBook(store), sightings, config.getString("link.auto-ban", "direct").equalsIgnoreCase("direct"), getLogger()::warning);
            maintenance = new Maintenance(getServer(), new HashSet<>(config.getStringList("maintenance.allow")), config.getBoolean("maintenance.enabled", false),
                    config.getString("maintenance.message", ""));
            cleaner = new Cleaner(getServer(), config.getBoolean("clean.items", true), config.getBoolean("clean.creatures", true),
                    config.getBoolean("clean.projectiles", true), config.getBoolean("clean.orbs", true));
            getServer().getPluginManager().registerEvents(new GateListener(this, bans, maintenance, new dev.toka.anchor.upkeep.Access(getServer(), config.getString("access.mode", "open").equalsIgnoreCase("linked"), new HashSet<>(config.getStringList("access.allow")))), this);
            afk = new dev.toka.anchor.upkeep.Afk(System::currentTimeMillis, config.getInt("afk.seconds", 300));
            getServer().getPluginManager().registerEvents(afk, this);
            modes = new dev.toka.anchor.upkeep.ModeKeeper(new java.io.File(getDataFolder(), "modes.yml"));
            getServer().getPluginManager().registerEvents(modes, this);
            afk.touchAll(getServer().getOnlinePlayers().values());
            try { startRestarter(config); } catch (Exception | LinkageError failure) { getLogger().error("排程重啟無法啟動，其餘功能照常。", failure); }
            try { startCleaning(config); } catch (Exception | LinkageError failure) { getLogger().error("定時清理無法啟動，其餘功能照常。", failure); }
            register(new BanCommand(this, bans, BanCommand.Kind.BAN, "b"));
            register(new BanCommand(this, bans, BanCommand.Kind.LIFT, "ub"));
            register(new BanCommand(this, bans, BanCommand.Kind.LIST, "blist"));
            register(new BanCommand(this, bans, BanCommand.Kind.LINK, "blink"));
            register(new AnchorCommand(this));
            register(new RunCommand(this));
            instance = this;
        } catch (Exception | LinkageError error) {
            getLogger().error("tokaAnchor 未能載入；為避免以空資料取代封禁名單，插件將停用。", error);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void startRestarter(cn.nukkit.utils.Config config) {
        if (!config.getBoolean("restart.enabled", true)) return;
        List<String> rejected = new ArrayList<>();
        List<LocalTime> times = RestartPlan.parse(config.getStringList("restart.times"), rejected);
        if (!rejected.isEmpty()) getLogger().warning("restart.times 有無法讀取的時間（要寫成 HH:mm），已略過：" + rejected);
        ZoneId chosen;
        try {
            String id = config.getString("restart.zone", "");
            chosen = id.isBlank() ? ZoneId.systemDefault() : ZoneId.of(id);
        } catch (java.time.DateTimeException bad) {
            getLogger().warning("restart.zone 不是有效的時區，改用系統時區。");
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
        }, () -> getLogger().info("沒有設定重啟時間，只能手動 /anchor restart。"));
    }

    private void startCleaning(cn.nukkit.utils.Config config) {
        int minutes = config.getInt("clean.interval-minutes", 0);
        if (minutes <= 0) return;
        int ticks = (int) Math.min(Integer.MAX_VALUE, minutes * 1200L);
        getServer().getScheduler().scheduleDelayedRepeatingTask(this, () -> {
            try {
                var result = cleaner.clean();
                if (result.total() > 0) broadcast("clean.done", result.items() + result.projectiles(), result.creatures(), result.orbs());
            } catch (RuntimeException failure) {
                getLogger().warning("定時清理出錯，這次略過：" + failure);
            }
        }, ticks, ticks);
    }

    /** Registers a command unless another plugin already owns the name, and says so instead of taking it. */
    private void register(Command command) {
        var map = getServer().getCommandMap();
        var existing = map.getCommand(command.getName());
        if (existing != null) { getLogger().warning("/" + command.getName() + " 已由其他插件註冊；請先停用該入口再重啟。此次不註冊。"); return; }
        if (map.register("tokaAnchor", command) && map.getCommand(command.getName()) == command) commands.add(command);
        else getLogger().error("無法註冊指令 /" + command.getName() + "；請檢查指令衝突。");
    }

    @Override public void onDisable() {
        if (modes != null) modes.rememberAll(getServer().getOnlinePlayers().values());
        var map = getServer().getCommandMap();
        for (Command command : commands) {
            map.getCommands().entrySet().removeIf(entry -> entry.getValue() == command);
            command.unregister(map);
        }
        commands.clear();
        if (instance == this) instance = null;
        if (bans != null) bans.close();
        if (store != null) try { store.close(); } catch (java.io.IOException error) { getLogger().error("關閉儲存庫失敗。", error); }
    }

    public BanService bans() { return bans; }

    /** Null when restarts are not switched on. */
    public Restarter restarter() { return restarter; }

    public Cleaner cleaner() { return cleaner; }

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

    /** Tells every player in their own language, and the console in the configured one. */
    public void broadcast(String key, Object... args) {
        for (Player player : getServer().getOnlinePlayers().values()) player.sendMessage(text(player, key, args));
        getLogger().info(text(null, key, args));
    }

    /** Text in the sender's language; a null sender (a login that has not finished) gets the configured one. */
    public String text(CommandSender sender, String key, Object... args) {
        String tag = sender instanceof Player player && player.getLocale() != null ? player.getLocale().toLanguageTag() : getConfig().getString("language", "zh-TW");
        String selected = tag.startsWith("en") ? "en-US" : "zh-TW";
        return languages.computeIfAbsent(selected, t -> new Messages(getClass().getClassLoader(), getDataFolder().toPath(), t, line -> getLogger().warning(line))).text(key, args);
    }
}

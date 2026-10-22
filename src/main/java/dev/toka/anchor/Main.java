package dev.toka.anchor;

import cn.nukkit.Player;
import cn.nukkit.command.Command;
import cn.nukkit.command.CommandSender;
import cn.nukkit.plugin.PluginBase;
import dev.toka.anchor.ban.BanBook;
import dev.toka.anchor.ban.BanService;
import dev.toka.anchor.command.AnchorCommand;
import dev.toka.anchor.command.BanCommand;
import dev.toka.anchor.link.Sightings;
import dev.toka.anchor.listener.GateListener;
import dev.toka.i18n.Messages;
import dev.toka.storage.Backend;
import dev.toka.storage.DocumentStore;
import dev.toka.storage.StoreConfig;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Switchboard for the server's upkeep: bans and the check at login. Each part works on its own; if the ban data cannot be
 * read the plugin refuses to start rather than start with an empty list.
 */
public final class Main extends PluginBase {
    private DocumentStore store;
    private BanService bans;
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
            bans = new BanService(new BanBook(store), new Sightings(store, config.getInt("link.keep", 10)),
                    config.getString("link.auto-ban", "direct").equalsIgnoreCase("direct"), getLogger()::warning);
            getServer().getPluginManager().registerEvents(new GateListener(this, bans), this);
            register(new BanCommand(this, bans, BanCommand.Kind.BAN, "b"));
            register(new BanCommand(this, bans, BanCommand.Kind.LIFT, "ub"));
            register(new BanCommand(this, bans, BanCommand.Kind.LIST, "blist"));
            register(new BanCommand(this, bans, BanCommand.Kind.LINK, "blink"));
            register(new AnchorCommand(this));
        } catch (Exception error) {
            getLogger().error("tokaAnchor 未能載入；為避免以空資料取代封禁名單，插件將停用。", error);
            getServer().getPluginManager().disablePlugin(this);
        }
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
        var map = getServer().getCommandMap();
        for (Command command : commands) {
            map.getCommands().entrySet().removeIf(entry -> entry.getValue() == command);
            command.unregister(map);
        }
        commands.clear();
        if (bans != null) bans.close();
        if (store != null) try { store.close(); } catch (java.io.IOException error) { getLogger().error("關閉儲存庫失敗。", error); }
    }

    public BanService bans() { return bans; }

    /**
     * Entry point for the one-time hand-over from zero (called by reflection with plain JSON): {"bans":[{name,reason,source,created}],
     * "sightings":[{name,address,device}]}. Only adds what is missing, so running it twice changes nothing. Returns a one-line report.
     */
    public static String importJson(String json) throws java.io.IOException {
        Main main = (Main) cn.nukkit.Server.getInstance().getPluginManager().getPlugin("tokaAnchor");
        if (main == null || !main.isEnabled() || main.bans == null) throw new IllegalStateException("tokaAnchor is not running");
        var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        long now = System.currentTimeMillis();
        List<dev.toka.anchor.ban.Ban> bans = new ArrayList<>();
        for (var item : root.getAsJsonArray("bans")) {
            var row = item.getAsJsonObject();
            bans.add(new dev.toka.anchor.ban.Ban(row.get("name").getAsString(), row.get("reason").getAsString(), row.get("source").getAsString(), row.get("created").getAsLong(), null));
        }
        int added = main.bans.book().addMissing(bans, now);
        int noted = 0;
        for (var item : root.getAsJsonArray("sightings")) {
            var row = item.getAsJsonObject();
            if (main.bans.sightings().note(row.get("name").getAsString(), row.get("address").getAsString(), row.get("device").getAsString())) noted++;
        }
        main.bans.sightings().save();
        return "bans added " + added + " of " + bans.size() + ", accounts with new login data " + noted;
    }

    /** Text in the sender's language; a null sender (a login that has not finished) gets the configured one. */
    public String text(CommandSender sender, String key, Object... args) {
        String tag = sender instanceof Player player ? player.getLocale().toLanguageTag() : getConfig().getString("language", "zh-TW");
        String selected = tag.startsWith("en") ? "en-US" : "zh-TW";
        return languages.computeIfAbsent(selected, t -> new Messages(getClass().getClassLoader(), getDataFolder().toPath(), t, line -> getLogger().warning(line))).text(key, args);
    }
}

package dev.toka.anchor.upkeep;

import cn.nukkit.Player;
import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerJoinEvent;
import cn.nukkit.event.player.PlayerQuitEvent;
import cn.nukkit.utils.Config;
import java.io.File;

/**
 * The server puts every player in its default game mode when they log in (force-gamemode), which is right for players. Whoever has
 * tokaanchor.keepmode gets the mode they had when they left, put back once they have joined.
 */
public final class ModeKeeper implements Listener {
    public static final String PERMISSION = "tokaanchor.keepmode";
    private final Config saved;

    public ModeKeeper(File file) { this.saved = new Config(file, Config.YAML); }

    private static boolean keeps(Player player) { return player.hasPermission(PERMISSION); }

    /** Remembers the mode of everyone here who keeps it; called on quit and when the plugin stops */
    public void remember(Player player) {
        if (!keeps(player)) return;
        saved.set(player.getUniqueId().toString(), player.getGamemode());
        saved.save();
    }

    @EventHandler(priority = EventPriority.HIGH) public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String key = player.getUniqueId().toString();
        if (!keeps(player) || !saved.exists(key)) return;
        int mode = saved.getInt(key, player.getGamemode());
        if (mode != player.getGamemode() && mode >= 0 && mode <= 3) player.setGamemode(mode);
    }

    @EventHandler(priority = EventPriority.MONITOR) public void onQuit(PlayerQuitEvent event) { remember(event.getPlayer()); }

    public void rememberAll(Iterable<Player> players) { for (Player player : players) remember(player); }
}

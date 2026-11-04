package dev.toka.anchor.listener;

import cn.nukkit.event.EventHandler;
import cn.nukkit.event.EventPriority;
import cn.nukkit.event.Listener;
import cn.nukkit.event.player.PlayerCommandPreprocessEvent;

/** The game's clear command wants a name; typed alone by a player it means themselves. */
public final class ClearListener implements Listener {
    @EventHandler(priority = EventPriority.LOW) public void onCommand(PlayerCommandPreprocessEvent event) {
        String text = event.getMessage() == null ? "" : event.getMessage().strip();
        if (text.equalsIgnoreCase("/clear") || text.equalsIgnoreCase("/minecraft:clear")) event.setMessage(text + " @s");
    }
}

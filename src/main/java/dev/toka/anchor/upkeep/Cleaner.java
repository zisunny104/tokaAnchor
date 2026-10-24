package dev.toka.anchor.upkeep;

import cn.nukkit.Player;
import cn.nukkit.Server;
import cn.nukkit.entity.Entity;
import cn.nukkit.entity.EntityCreature;
import cn.nukkit.entity.item.EntityItem;
import cn.nukkit.entity.item.EntityXPOrb;
import cn.nukkit.entity.projectile.EntityProjectile;
import cn.nukkit.level.Level;

/**
 * Removes loose entities to lighten the server: dropped items, experience orbs, arrows and the like, and wandering creatures.
 * A creature with a name tag is somebody's pet and stays. Each kind can be switched off. Must run on the server thread.
 */
public final class Cleaner {
    public record Result(int items, int creatures, int projectiles, int orbs) {
        public int total() { return items + creatures + projectiles + orbs; }
    }

    private final Server server;
    private volatile boolean items, creatures, projectiles, orbs;

    public Cleaner(Server server, boolean items, boolean creatures, boolean projectiles, boolean orbs) {
        this.server = server;
        this.items = items;
        this.creatures = creatures;
        this.projectiles = projectiles;
        this.orbs = orbs;
    }

    public boolean items() { return items; }

    public boolean creatures() { return creatures; }

    public boolean projectiles() { return projectiles; }

    public boolean orbs() { return orbs; }

    /** Changes which kinds the next clean-up removes. */
    public void kinds(boolean items, boolean creatures, boolean projectiles, boolean orbs) {
        this.items = items;
        this.creatures = creatures;
        this.projectiles = projectiles;
        this.orbs = orbs;
    }

    public Result clean() {
        int item = 0, creature = 0, projectile = 0, orb = 0;
        for (Level level : server.getLevels().values()) {
            for (Entity entity : level.getEntities()) {
                if (entity.closed || entity instanceof Player) continue;
                if (items && entity instanceof EntityItem) { entity.close(); item++; }
                else if (orbs && entity instanceof EntityXPOrb) { entity.close(); orb++; }
                else if (projectiles && entity instanceof EntityProjectile) { entity.close(); projectile++; }
                else if (creatures && entity instanceof EntityCreature && !entity.hasCustomName()) { entity.close(); creature++; }
            }
        }
        return new Result(item, creature, projectile, orb);
    }
}

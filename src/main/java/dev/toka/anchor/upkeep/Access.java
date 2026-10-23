package dev.toka.anchor.upkeep;

import cn.nukkit.Server;
import cn.nukkit.plugin.Plugin;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Who may log in at all. In "linked" mode only operators, the names on the allow list and players who have the permission
 * tokaanchor.enter get in; the permission is what the Discord-linked group (and the administrators) are given in LuckPerms, so
 * the rule follows the groups instead of a list of names. It replaces the game's own allow list, which stays off.
 */
public final class Access {
    public static final String PERMISSION = "tokaanchor.enter";
    private final Server server;
    private final boolean linkedOnly;
    private final Set<String> allowed;

    public Access(Server server, boolean linkedOnly, Set<String> allowedNames) {
        this.server = server;
        this.linkedOnly = linkedOnly;
        this.allowed = Set.copyOf(allowedNames.stream().map(n -> n.toLowerCase(Locale.ROOT)).toList());
    }

    public boolean restricted() { return linkedOnly; }

    /** Whether this player may log in. Without an answer from LuckPerms only operators and listed names pass. */
    public boolean admits(UUID id, String name) {
        if (!linkedOnly || server.isOp(name) || allowed.contains(name.toLowerCase(Locale.ROOT))) return true;
        try { return hasPermission(id); }
        catch (ReflectiveOperationException | LinkageError | RuntimeException | java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException unknown) { return false; }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return false; }
    }

    private boolean hasPermission(UUID id) throws ReflectiveOperationException, java.util.concurrent.ExecutionException, java.util.concurrent.TimeoutException, InterruptedException {
        Plugin luckPerms = server.getPluginManager().getPlugin("LuckPerms");
        if (luckPerms == null || !luckPerms.isEnabled()) throw new IllegalStateException("LuckPerms is not running");
        ClassLoader loader = luckPerms.getClass().getClassLoader();
        Object api = Class.forName("net.luckperms.api.LuckPermsProvider", true, loader).getMethod("get").invoke(null);
        Object users = Class.forName("net.luckperms.api.LuckPerms", true, loader).getMethod("getUserManager").invoke(api);
        Object user = ((java.util.concurrent.CompletableFuture<?>) Class.forName("net.luckperms.api.model.user.UserManager", true, loader)
                .getMethod("loadUser", UUID.class).invoke(users, id)).get(5, TimeUnit.SECONDS);
        Object data = Class.forName("net.luckperms.api.model.user.User", true, loader).getMethod("getCachedData").invoke(user);
        Object permissions = Class.forName("net.luckperms.api.cacheddata.CachedDataManager", true, loader).getMethod("getPermissionData").invoke(data);
        Object answer = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData", true, loader).getMethod("checkPermission", String.class).invoke(permissions, PERMISSION);
        return Boolean.TRUE.equals(Class.forName("net.luckperms.api.util.Tristate", true, loader).getMethod("asBoolean").invoke(answer));
    }
}

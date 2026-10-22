package dev.toka.anchor.ban;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.toka.storage.DocumentStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Every ban, kept in one stored document and in memory. Lookups read an immutable map so the login check never waits; a
 * change is saved and read back before it replaces that map, so a failed save leaves the bans exactly as they were. Expired
 * bans are dropped whenever something is saved.
 */
public final class BanBook {
    private static final String KEY = "bans";
    private final DocumentStore store;
    private volatile Map<String, Ban> bans;

    public BanBook(DocumentStore store) throws IOException {
        this.store = store;
        this.bans = load();
    }

    private Map<String, Ban> load() throws IOException {
        Optional<JsonObject> document = store.load(KEY);
        if (document.isEmpty()) return Map.of();
        JsonObject root = document.get();
        if (!root.has("format") || root.get("format").getAsInt() != 1) throw new IOException("Unsupported bans format");
        Map<String, Ban> read = new HashMap<>();
        try {
            for (var item : root.getAsJsonArray("bans")) {
                JsonObject row = item.getAsJsonObject();
                Ban ban = new Ban(row.get("name").getAsString(), row.get("reason").getAsString(), row.get("source").getAsString(),
                        row.get("created").getAsLong(), row.has("expires") && !row.get("expires").isJsonNull() ? row.get("expires").getAsLong() : null,
                        row.has("via") && !row.get("via").isJsonNull() ? row.get("via").getAsString() : null);
                read.put(ban.key(), ban);
            }
        } catch (RuntimeException bad) {
            throw new IOException("Unreadable bans", bad);
        }
        return Map.copyOf(read);
    }

    private void save(Map<String, Ban> next) throws IOException {
        JsonArray rows = new JsonArray();
        next.values().stream().sorted(Comparator.comparing(Ban::key)).forEach(ban -> {
            JsonObject row = new JsonObject();
            row.addProperty("name", ban.name());
            row.addProperty("reason", ban.reason());
            row.addProperty("source", ban.source());
            row.addProperty("created", ban.created());
            if (ban.expires() != null) row.addProperty("expires", ban.expires());
            if (ban.via() != null) row.addProperty("via", ban.via());
            rows.add(row);
        });
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.add("bans", rows);
        store.save(KEY, root);
        if (!load().equals(next)) throw new IOException("Ban readback mismatch");
    }

    /** The ban that applies to this name right now, if any. */
    public Optional<Ban> active(String name, long now) {
        Ban ban = bans.get(name.toLowerCase(java.util.Locale.ROOT));
        return ban != null && ban.activeAt(now) ? Optional.of(ban) : Optional.empty();
    }

    /** Adds or replaces bans in one save. */
    public synchronized void add(List<Ban> added, long now) throws IOException {
        Map<String, Ban> next = new HashMap<>(bans);
        next.values().removeIf(ban -> !ban.activeAt(now));
        for (Ban ban : added) next.put(ban.key(), ban);
        Map<String, Ban> immutable = Map.copyOf(next);
        save(immutable);
        bans = immutable;
    }

    /** Adds only the bans whose account has no active ban yet; returns how many were added. Safe to run again. */
    public synchronized int addMissing(List<Ban> candidates, long now) throws IOException {
        List<Ban> fresh = new ArrayList<>();
        for (Ban ban : candidates) if (active(ban.name(), now).isEmpty() && fresh.stream().noneMatch(b -> b.key().equals(ban.key()))) fresh.add(ban);
        if (!fresh.isEmpty()) add(fresh, now);
        return fresh.size();
    }

    /** True when a ban was removed. */
    public synchronized boolean remove(String name, long now) throws IOException {
        Map<String, Ban> next = new HashMap<>(bans);
        boolean had = next.remove(name.toLowerCase(java.util.Locale.ROOT)) != null;
        if (!had) return false;
        next.values().removeIf(ban -> !ban.activeAt(now));
        Map<String, Ban> immutable = Map.copyOf(next);
        save(immutable);
        bans = immutable;
        return true;
    }

    /** Lifts the ban on this account and the bans copied from it for linked accounts; returns the names lifted. */
    public synchronized List<String> removeWithLinked(String name, long now) throws IOException {
        String key = name.toLowerCase(java.util.Locale.ROOT);
        Map<String, Ban> next = new HashMap<>(bans);
        List<String> lifted = new ArrayList<>();
        Ban own = next.remove(key);
        if (own != null) lifted.add(own.name());
        next.values().removeIf(ban -> {
            boolean copy = ban.via() != null && ban.via().toLowerCase(java.util.Locale.ROOT).equals(key);
            if (copy) lifted.add(ban.name());
            return copy || !ban.activeAt(now);
        });
        if (lifted.isEmpty()) return List.of();
        Map<String, Ban> immutable = Map.copyOf(next);
        save(immutable);
        bans = immutable;
        return lifted;
    }

    /** Active bans, newest first. */
    public List<Ban> list(long now) {
        List<Ban> out = new ArrayList<>();
        for (Ban ban : bans.values()) if (ban.activeAt(now)) out.add(ban);
        out.sort(Comparator.comparingLong(Ban::created).reversed());
        return out;
    }
}

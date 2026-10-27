package dev.toka.anchor.run;

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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Commands waiting for players who are offline, to be run as that player at their next login. Memory is updated at once; the
 * document is rewritten in the background after every change (a failed write is reported once and tried again at the next
 * change), so the login path is one map lookup.
 */
public final class PendingRuns {
    /** @param by the name of whoever queued it; @param at when, in milliseconds */
    public record Entry(String command, String by, long at) {}

    /** @param name the player's name when queued, only for listings */
    public record Queue(String name, List<Entry> entries) {}

    public enum Result { QUEUED, FULL, TOO_LONG }

    /** @param waiting how many commands wait for that player after this call (when queued) */
    public record Added(Result result, int waiting) {}

    public record Limits(int perPlayer, int maxLength, long maxAgeMillis) {
        public static Limits ofDays(int perPlayer, int maxLength, int days) { return new Limits(Math.max(1, perPlayer), Math.max(1, maxLength), Math.max(1, days) * 86_400_000L); }
    }

    private static final String KEY = "pendingRuns";
    private final DocumentStore store;
    private final Consumer<Exception> failed;
    private final Map<UUID, Queue> queues = new ConcurrentHashMap<>();
    private final AtomicBoolean writeQueued = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "tokaAnchor-pending"); t.setDaemon(true); return t; });
    private volatile Limits limits;
    private volatile boolean warned;

    /** An empty queue that is never written, for when the stored one cannot be read. */
    public static PendingRuns unavailable(Limits limits) { return new PendingRuns(null, limits, e -> {}); }

    private PendingRuns(DocumentStore store, Limits limits, Consumer<Exception> failed) {
        this.store = store;
        this.limits = limits;
        this.failed = failed;
    }

    /** @param failed told once when a write fails, until a write succeeds again */
    public PendingRuns(DocumentStore store, Limits limits, long now, Consumer<Exception> failed) throws IOException {
        this(store, limits, failed);
        Optional<JsonObject> document = store.load(KEY);
        if (document.isEmpty()) return;
        int[] dropped = {0};
        queues.putAll(parse(document.get(), now, limits.maxAgeMillis(), dropped));
        if (dropped[0] > 0) writeLater();
    }

    public void limits(Limits next) { limits = next; }

    /** Queues a command; nothing changes when it is too long or the player's queue is full. */
    public Added add(UUID player, String name, Entry entry, long now) {
        Limits l = limits;
        if (entry.command().length() > l.maxLength()) return new Added(Result.TOO_LONG, 0);
        int[] outcome = {0};
        queues.compute(player, (id, old) -> {
            List<Entry> kept = old == null ? new ArrayList<>() : fresh(old.entries(), now, l.maxAgeMillis());
            if (kept.size() >= l.perPlayer()) { outcome[0] = -1; return kept.isEmpty() ? null : new Queue(old.name(), List.copyOf(kept)); }
            kept.add(entry);
            outcome[0] = kept.size();
            return new Queue(name, List.copyOf(kept));
        });
        if (outcome[0] < 0) return new Added(Result.FULL, 0);
        writeLater();
        return new Added(Result.QUEUED, outcome[0]);
    }

    /** Removes and returns what waits for the player, oldest first, without what has expired; the write follows in the background. */
    public List<Entry> take(UUID player, long now) {
        if (queues.isEmpty()) return List.of();
        Queue queue = queues.remove(player);
        if (queue == null) return List.of();
        writeLater();
        return fresh(queue.entries(), now, limits.maxAgeMillis());
    }

    /** Gives back what was taken but not run; it goes in front of anything queued since. */
    public void putBack(UUID player, String name, List<Entry> entries) {
        if (entries.isEmpty()) return;
        queues.compute(player, (id, old) -> {
            List<Entry> merged = new ArrayList<>(entries);
            if (old != null) merged.addAll(old.entries());
            return new Queue(name, List.copyOf(merged));
        });
        writeLater();
    }

    /** Drops the player's queue; returns how many commands that was. */
    public int cancel(UUID player) {
        Queue queue = queues.remove(player);
        if (queue == null) return 0;
        writeLater();
        return queue.entries().size();
    }

    public Map<UUID, Queue> snapshot() { return Map.copyOf(queues); }

    /** The player who has a queue under this name (any case), if any. */
    public Optional<UUID> find(String name) {
        for (var entry : queues.entrySet()) if (entry.getValue().name().equalsIgnoreCase(name)) return Optional.of(entry.getKey());
        return Optional.empty();
    }

    static List<Entry> fresh(List<Entry> entries, long now, long maxAge) {
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries) if (now - entry.at() <= maxAge) out.add(entry);
        return out;
    }

    public static JsonObject toJson(Map<UUID, Queue> all) {
        JsonArray rows = new JsonArray();
        all.entrySet().stream().sorted(Map.Entry.comparingByKey(Comparator.comparing(UUID::toString))).forEach(item -> {
            JsonObject row = new JsonObject();
            row.addProperty("uuid", item.getKey().toString());
            row.addProperty("name", item.getValue().name());
            JsonArray list = new JsonArray();
            for (Entry entry : item.getValue().entries()) {
                JsonObject e = new JsonObject();
                e.addProperty("command", entry.command());
                e.addProperty("by", entry.by());
                e.addProperty("at", entry.at());
                list.add(e);
            }
            row.add("entries", list);
            rows.add(row);
        });
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.add("players", rows);
        return root;
    }

    /** @param dropped element 0 is raised by the number of expired commands left out */
    public static Map<UUID, Queue> parse(JsonObject root, long now, long maxAge, int[] dropped) throws IOException {
        if (!root.has("format") || root.get("format").getAsInt() != 1) throw new IOException("Unsupported pending runs format");
        Map<UUID, Queue> read = new HashMap<>();
        try {
            for (var item : root.getAsJsonArray("players")) {
                JsonObject row = item.getAsJsonObject();
                List<Entry> all = new ArrayList<>();
                for (var e : row.getAsJsonArray("entries")) {
                    JsonObject o = e.getAsJsonObject();
                    all.add(new Entry(o.get("command").getAsString(), o.get("by").getAsString(), o.get("at").getAsLong()));
                }
                List<Entry> kept = fresh(all, now, maxAge);
                dropped[0] += all.size() - kept.size();
                if (!kept.isEmpty()) read.put(UUID.fromString(row.get("uuid").getAsString()), new Queue(row.get("name").getAsString(), List.copyOf(kept)));
            }
        } catch (RuntimeException bad) {
            throw new IOException("Unreadable pending runs", bad);
        }
        return read;
    }

    private void writeLater() {
        if (store == null || !writeQueued.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                writeQueued.set(false);
                write();
            });
        } catch (RejectedExecutionException closed) {
            writeQueued.set(false);
        }
    }

    private synchronized void write() {
        try {
            store.save(KEY, toJson(Map.copyOf(queues)));
            warned = false;
        } catch (IOException | RuntimeException failure) {
            if (!warned) { warned = true; failed.accept(failure); }
        }
    }

    /** Stops the background writer and writes what is left. */
    public void close() {
        worker.shutdown();
        try { worker.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (store != null) write();
    }
}

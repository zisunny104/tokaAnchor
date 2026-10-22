package dev.toka.anchor.link;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.toka.storage.DocumentStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which addresses and devices each account has logged in from, kept so accounts of one person can be recognised. Only the
 * last few of each are kept per account. Memory is updated at once; the document is rewritten in the background when
 * something new appears, never for a repeat login, so the login path does no disk work.
 */
public final class Sightings {
    /** What was seen for one account (lower-case name). */
    public record Seen(Set<String> addresses, Set<String> devices) {}

    private static final String KEY = "sightings";
    private final DocumentStore store;
    private final int keep;
    private final Map<String, Seen> seen = new ConcurrentHashMap<>();

    public Sightings(DocumentStore store, int keep) throws IOException {
        this.store = store;
        this.keep = keep;
        Optional<JsonObject> document = store.load(KEY);
        if (document.isEmpty()) return;
        try {
            for (var item : document.get().getAsJsonArray("accounts")) {
                JsonObject row = item.getAsJsonObject();
                seen.put(row.get("name").getAsString(), new Seen(strings(row.getAsJsonArray("addresses")), strings(row.getAsJsonArray("devices"))));
            }
        } catch (RuntimeException bad) {
            throw new IOException("Unreadable sightings", bad);
        }
    }

    private static Set<String> strings(JsonArray array) {
        Set<String> out = new LinkedHashSet<>();
        for (var item : array) out.add(item.getAsString());
        return out;
    }

    /** Notes a login. Returns true when it added something new (so the caller should save). */
    public boolean note(String name, String address, String device) {
        String key = name.toLowerCase(Locale.ROOT);
        boolean[] changed = {false};
        seen.compute(key, (k, old) -> {
            Set<String> addresses = old == null ? new LinkedHashSet<>() : new LinkedHashSet<>(old.addresses());
            Set<String> devices = old == null ? new LinkedHashSet<>() : new LinkedHashSet<>(old.devices());
            changed[0] = add(addresses, address) | add(devices, device);
            return changed[0] || old == null ? new Seen(addresses, devices) : old;
        });
        return changed[0];
    }

    private boolean add(Set<String> into, String value) {
        if (value == null || value.isBlank() || into.contains(value)) return false;
        into.add(value);
        while (into.size() > keep) into.remove(into.iterator().next());
        return true;
    }

    public Map<String, Seen> snapshot() { return Map.copyOf(seen); }

    public synchronized void save() throws IOException {
        JsonArray rows = new JsonArray();
        List<Map.Entry<String, Seen>> entries = new ArrayList<>(seen.entrySet());
        entries.sort(Map.Entry.comparingByKey());
        for (var entry : entries) {
            JsonObject row = new JsonObject();
            row.addProperty("name", entry.getKey());
            JsonArray addresses = new JsonArray(), devices = new JsonArray();
            entry.getValue().addresses().forEach(addresses::add);
            entry.getValue().devices().forEach(devices::add);
            row.add("addresses", addresses);
            row.add("devices", devices);
            rows.add(row);
        }
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.add("accounts", rows);
        store.save(KEY, root);
    }
}

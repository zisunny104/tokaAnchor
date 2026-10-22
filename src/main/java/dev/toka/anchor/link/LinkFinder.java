package dev.toka.anchor.link;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Finds the accounts that look like the same person. A shared device with a shared address is DIRECT, which is strong enough
 * to act on. A shared address alone (a dorm, a family) or a shared device alone is only POSSIBLE and is shown, never acted on.
 */
public final class LinkFinder {
    public enum Strength { DIRECT, POSSIBLE }

    private LinkFinder() {}

    /** Other accounts related to {@code name} (compared in lower case), strongest link per account. */
    public static Map<String, Strength> related(Map<String, Sightings.Seen> all, String name) {
        Sightings.Seen mine = all.get(name.toLowerCase(Locale.ROOT));
        if (mine == null) return Map.of();
        Map<String, Strength> found = new HashMap<>();
        String self = name.toLowerCase(Locale.ROOT);
        for (var entry : all.entrySet()) {
            if (entry.getKey().equals(self)) continue;
            boolean address = !Collections.disjoint(mine.addresses(), entry.getValue().addresses());
            boolean device = !Collections.disjoint(mine.devices(), entry.getValue().devices());
            if (address && device) found.put(entry.getKey(), Strength.DIRECT);
            else if (address || device) found.put(entry.getKey(), Strength.POSSIBLE);
        }
        return found;
    }

    public static Set<String> direct(Map<String, Strength> related) {
        Set<String> out = new java.util.TreeSet<>();
        related.forEach((name, strength) -> { if (strength == Strength.DIRECT) out.add(name); });
        return out;
    }
}

package dev.toka.anchor.ban;

import dev.toka.anchor.link.LinkFinder;
import dev.toka.anchor.link.Sightings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/**
 * Bans, and the link between accounts of one person. Banning an account also bans the accounts that share both its device and
 * an address (when {@code autoBan} is on); accounts with a weaker link are only reported. A login from an account that
 * directly matches a banned one is banned the same way, so a new account does not get around a ban.
 */
public final class BanService implements AutoCloseable {
    public record Outcome(List<String> banned, List<String> possible) {}

    private final BanBook book;
    private final Sightings sightings;
    private volatile boolean autoBan;
    /** Receives a log key and the detail of the fault; the plugin turns it into a line of the console. */
    private final BiConsumer<String, Object> warn;
    private final AtomicBoolean saving = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "tokaAnchor-save"); t.setDaemon(true); return t; });

    public BanService(BanBook book, Sightings sightings, boolean autoBan, BiConsumer<String, Object> warn) {
        this.book = book;
        this.sightings = sightings;
        this.autoBan = autoBan;
        this.warn = warn;
    }

    public boolean autoBan() { return autoBan; }

    public void autoBan(boolean autoBan) { this.autoBan = autoBan; }

    public BanBook book() { return book; }

    public Sightings sightings() { return sightings; }

    /** @param lengthMillis null for permanent */
    public Outcome ban(String name, String reason, String source, Long lengthMillis, long now) throws IOException {
        return ban(name, reason, source, lengthMillis, now, autoBan);
    }

    /** @param withLinked whether accounts with the same address and device are banned along */
    public Outcome ban(String name, String reason, String source, Long lengthMillis, long now, boolean withLinked) throws IOException {
        Long expires = lengthMillis == null ? null : now + lengthMillis;
        List<Ban> added = new ArrayList<>();
        added.add(new Ban(name, reason, source, now, expires));
        Map<String, LinkFinder.Strength> related = LinkFinder.related(sightings.snapshot(), name);
        List<String> banned = new ArrayList<>(), possible = new ArrayList<>();
        banned.add(name);
        related.forEach((other, strength) -> {
            if (strength == LinkFinder.Strength.DIRECT && withLinked) { banned.add(other); added.add(new Ban(other, name + " 關聯帳號", source, now, expires, name)); }
            else possible.add(other);
        });
        book.add(added, now);
        return new Outcome(banned, possible);
    }

    /** The ban to refuse this login with, if any. Never throws: a fault lets the login through and is logged. */
    public Optional<Ban> checkLogin(String name, String address, String device, long now) {
        try {
            Optional<Ban> own = book.active(name, now);
            if (sightings.note(name, address, device)) saveSightingsLater();
            if (own.isPresent() || !autoBan) return own;
            for (String other : LinkFinder.direct(LinkFinder.related(sightings.snapshot(), name))) {
                Optional<Ban> theirs = book.active(other, now);
                if (theirs.isEmpty()) continue;
                Ban copy = new Ban(name, other + " 關聯帳號", "Anchor", now, theirs.get().expires(), other);
                // The login is refused even when the copy cannot be saved; it is simply made again at the next attempt.
                try { book.add(List.of(copy), now); } catch (IOException failure) { warn.accept("linkedBanFailed", failure); }
                return Optional.of(copy);
            }
            return Optional.empty();
        } catch (RuntimeException fault) {
            warn.accept("loginCheckFailed", fault);
            return Optional.empty();
        }
    }

    private void saveSightingsLater() {
        if (!saving.compareAndSet(false, true)) return;
        worker.execute(() -> {
            saving.set(false);
            try { sightings.save(); } catch (IOException failure) { warn.accept("sightingsSaveFailed", failure); }
        });
    }

    public Map<String, LinkFinder.Strength> related(String name) { return LinkFinder.related(sightings.snapshot(), name.toLowerCase(Locale.ROOT)); }

    @Override public void close() {
        worker.shutdown();
        try { sightings.save(); } catch (IOException failure) { warn.accept("sightingsSaveFailed", failure); }
    }
}

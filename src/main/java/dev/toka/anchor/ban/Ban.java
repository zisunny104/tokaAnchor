package dev.toka.anchor.ban;

/**
 * One ban. {@code expires} is null for a permanent ban. {@code via} is the account this ban was copied from because the two
 * accounts are linked, or null; lifting that account lifts the copies. The name is kept as typed; lookups ignore case.
 */
public record Ban(String name, String reason, String source, long created, Long expires, String via) {
    public Ban(String name, String reason, String source, long created, Long expires) { this(name, reason, source, created, expires, null); }

    public boolean activeAt(long now) { return expires == null || expires > now; }

    public String key() { return name.toLowerCase(java.util.Locale.ROOT); }
}

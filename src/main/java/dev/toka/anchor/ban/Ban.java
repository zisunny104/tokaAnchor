package dev.toka.anchor.ban;

/** One ban. {@code expires} is null for a permanent ban. The name is kept as typed; lookups ignore case. */
public record Ban(String name, String reason, String source, long created, Long expires) {
    public boolean activeAt(long now) { return expires == null || expires > now; }

    public String key() { return name.toLowerCase(java.util.Locale.ROOT); }
}

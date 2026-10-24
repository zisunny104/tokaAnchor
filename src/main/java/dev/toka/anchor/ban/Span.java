package dev.toka.anchor.ban;

import java.util.Locale;

/** Ban lengths such as 30m, 12h, 7d, 2w; "perm" (or "permanent") means no end. */
public final class Span {
    /** The lengths offered in Tab completion and in the ban window. */
    public static final String[] PRESETS = {"30m", "12h", "7d", "2w", "perm"};

    private Span() {}

    /** Milliseconds, or -1 for permanent; null when the text is not a length at all. */
    public static Long parse(String text) {
        if (text == null) return null;
        String t = text.strip().toLowerCase(Locale.ROOT);
        if (t.equals("perm") || t.equals("permanent")) return -1L;
        if (t.length() < 2) return null;
        long unit = switch (t.charAt(t.length() - 1)) {
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            case 'w' -> 604_800_000L;
            default -> 0L;
        };
        if (unit == 0) return null;
        try {
            long count = Long.parseLong(t.substring(0, t.length() - 1));
            return count > 0 && count <= Long.MAX_VALUE / unit / 2 ? count * unit : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}

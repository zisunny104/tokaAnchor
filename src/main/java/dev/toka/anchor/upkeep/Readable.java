package dev.toka.anchor.upkeep;

import java.util.Locale;
import java.util.function.BiFunction;

/** Numbers and spans of time the way people read them: 3,000,000 and "13 hours 44 minutes". */
public final class Readable {
    private Readable() {}

    /** Thousands separators, the same as money is shown. */
    public static String number(long value) { return String.format(Locale.ROOT, "%,d", value); }

    /** {days, hours, minutes, seconds} of a span. */
    public static long[] split(long seconds) {
        long s = Math.max(0, seconds);
        return new long[]{s / 86_400, s % 86_400 / 3_600, s % 3_600 / 60, s % 60};
    }

    /**
     * The two largest units that are not zero (a span under a minute shows its seconds). The words come from the caller, which
     * is given "day", "hour", "minute" or "second" and the count, so they stay in the language files.
     */
    public static String span(long seconds, BiFunction<String, Long, String> unit) {
        long[] parts = split(seconds);
        String[] names = {"day", "hour", "minute", "second"};
        StringBuilder out = new StringBuilder();
        int shown = 0;
        for (int i = 0; i < 4 && shown < 2; i++) {
            if (parts[i] == 0 && !(i == 3 && shown == 0)) continue;
            if (shown > 0) out.append(' ');
            out.append(unit.apply(names[i], parts[i]));
            shown++;
        }
        return out.toString();
    }
}

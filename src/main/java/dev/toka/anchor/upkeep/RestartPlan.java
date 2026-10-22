package dev.toka.anchor.upkeep;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** When the next restart is: the earliest of the configured daily times that is still ahead. Pure, so it can be tested. */
public final class RestartPlan {
    private RestartPlan() {}

    /** Reads "HH:mm" entries; anything else is returned in {@code rejected} instead of being guessed at. */
    public static List<LocalTime> parse(List<String> texts, List<String> rejected) {
        List<LocalTime> out = new ArrayList<>();
        for (String text : texts) {
            try { out.add(LocalTime.parse(text.strip())); }
            catch (java.time.format.DateTimeParseException invalid) { rejected.add(text); }
        }
        return out;
    }

    public static Optional<Instant> next(List<LocalTime> times, ZoneId zone, Instant now) {
        ZonedDateTime from = now.atZone(zone);
        Instant best = null;
        for (LocalTime time : times) {
            ZonedDateTime candidate = from.with(time.withSecond(0).withNano(0));
            if (!candidate.toInstant().isAfter(now)) candidate = candidate.plusDays(1);
            if (best == null || candidate.toInstant().isBefore(best)) best = candidate.toInstant();
        }
        return Optional.ofNullable(best);
    }

    /** Whole seconds left, rounded up, so the countdown says 1 until the moment it reaches 0. */
    public static long secondsLeft(Instant target, Instant now) {
        long millis = target.toEpochMilli() - now.toEpochMilli();
        return millis <= 0 ? 0 : (millis + 999) / 1000;
    }
}

package dev.toka.anchor;

import com.google.gson.JsonObject;
import dev.toka.anchor.run.PendingRuns;
import dev.toka.anchor.run.PendingRuns.Entry;
import dev.toka.anchor.run.PendingRuns.Result;
import dev.toka.storage.Backend;
import dev.toka.storage.DocumentStore;
import dev.toka.storage.StoreConfig;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import static org.junit.Assert.*;

public class PendingRunsTest {
    private static final long DAY = 86_400_000L;
    private static final long NOW = 100 * DAY;
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static PendingRuns book(int perPlayer, int maxLength, int days) {
        return PendingRuns.unavailable(PendingRuns.Limits.ofDays(perPlayer, maxLength, days));
    }

    private static Entry entry(String command, long at) { return new Entry(command, "op", at); }

    @Test public void theQueueIsCappedPerPlayer() {
        var runs = book(2, 50, 30);
        assertEquals(1, runs.add(ALEX, "Alex", entry("a", NOW), NOW).waiting());
        assertEquals(2, runs.add(ALEX, "Alex", entry("b", NOW), NOW).waiting());
        assertEquals(Result.FULL, runs.add(ALEX, "Alex", entry("c", NOW), NOW).result());
        assertEquals(Result.QUEUED, runs.add(SAM, "Sam", entry("c", NOW), NOW).result());
        assertEquals(2, runs.take(ALEX, NOW).size());
    }

    @Test public void aTooLongCommandIsRefused() {
        var runs = book(5, 5, 30);
        assertEquals(Result.QUEUED, runs.add(ALEX, "Alex", entry("12345", NOW), NOW).result());
        assertEquals(Result.TOO_LONG, runs.add(ALEX, "Alex", entry("123456", NOW), NOW).result());
        assertEquals(1, runs.take(ALEX, NOW).size());
    }

    @Test public void oldEntriesExpireWhenTakenAndWhenAdding() {
        var runs = book(2, 50, 30);
        runs.add(ALEX, "Alex", entry("old1", NOW - 31 * DAY), NOW - 31 * DAY);
        runs.add(ALEX, "Alex", entry("old2", NOW - 31 * DAY), NOW - 31 * DAY);
        // full of expired entries: adding makes room
        assertEquals(1, runs.add(ALEX, "Alex", entry("new", NOW), NOW).waiting());
        runs.add(SAM, "Sam", entry("old", NOW - 40 * DAY), NOW - 40 * DAY);
        assertTrue(runs.take(SAM, NOW).isEmpty());
        assertTrue(runs.snapshot().containsKey(ALEX));
        assertFalse(runs.snapshot().containsKey(SAM));
    }

    @Test public void entriesComeOutInTheOrderTheyWereQueued() {
        var runs = book(5, 50, 30);
        for (String c : List.of("one", "two", "three")) runs.add(ALEX, "Alex", entry(c, NOW), NOW);
        assertEquals(List.of("one", "two", "three"), runs.take(ALEX, NOW).stream().map(Entry::command).toList());
        assertTrue(runs.take(ALEX, NOW).isEmpty());
    }

    @Test public void whatWasNotRunGoesBackToTheFront() {
        var runs = book(5, 50, 30);
        runs.add(ALEX, "Alex", entry("one", NOW), NOW);
        var taken = runs.take(ALEX, NOW);
        runs.add(ALEX, "Alex", entry("late", NOW), NOW);
        runs.putBack(ALEX, "Alex", taken);
        assertEquals(List.of("one", "late"), runs.take(ALEX, NOW).stream().map(Entry::command).toList());
    }

    @Test public void cancelDropsTheWholeQueue() {
        var runs = book(5, 50, 30);
        runs.add(ALEX, "Alex", entry("one", NOW), NOW);
        runs.add(ALEX, "Alex", entry("two", NOW), NOW);
        assertEquals(2, runs.cancel(ALEX));
        assertEquals(0, runs.cancel(ALEX));
        assertTrue(runs.find("alex").isEmpty());
    }

    @Test public void theDocumentRoundTripsAndDropsExpiredEntries() throws Exception {
        var runs = book(5, 50, 30);
        runs.add(ALEX, "Alex", new Entry("say \"hi\" 你好", "op", NOW), NOW);
        runs.add(SAM, "Sam", entry("two", NOW - 10 * DAY), NOW - 10 * DAY);
        JsonObject json = PendingRuns.toJson(runs.snapshot());
        int[] dropped = {0};
        Map<UUID, PendingRuns.Queue> back = PendingRuns.parse(json, NOW, 30 * DAY, dropped);
        assertEquals(runs.snapshot(), back);
        assertEquals(0, dropped[0]);
        back = PendingRuns.parse(json, NOW + 25 * DAY, 30 * DAY, dropped);
        assertEquals(1, dropped[0]);
        assertEquals(1, back.size());
        assertTrue(back.containsKey(ALEX));
    }

    @Test public void theQueueSurvivesAReopen() throws Exception {
        var dir = Files.createTempDirectory("pending");
        var limits = PendingRuns.Limits.ofDays(5, 50, 30);
        try (var store = DocumentStore.open(new StoreConfig(Backend.valueOf("SQLITE"), dir, 1))) {
            var runs = new PendingRuns(store, limits, NOW, e -> fail(e.toString()));
            runs.add(ALEX, "Alex", entry("one", NOW), NOW);
            runs.close();
        }
        try (var store = DocumentStore.open(new StoreConfig(Backend.valueOf("SQLITE"), dir, 1))) {
            var runs = new PendingRuns(store, limits, NOW, e -> fail(e.toString()));
            assertEquals(List.of("one"), runs.take(ALEX, NOW).stream().map(Entry::command).toList());
            runs.close();
        }
    }

    @Test public void newTextKeysExistInBothLanguages() throws Exception {
        for (String file : new String[]{"zh-TW.yml", "en-US.yml"}) {
            try (InputStream in = PendingRunsTest.class.getResourceAsStream("/lang/" + file)) {
                Object node = new Yaml().load(in);
                for (String key : List.of("run.queued", "run.queuedDone", "run.tooLong", "run.full", "run.cancelled", "run.list.none", "run.list.empty",
                        "run.list.head", "run.list.row", "run.list.player", "run.list.entry", "help.runList", "help.runCancel", "log.queued",
                        "log.ranAsQueued", "log.queuedFailed", "log.queueCancelled", "log.pendingSaveFailed", "log.pendingUnreadable")) {
                    Object at = node;
                    for (String part : key.split(Pattern.quote("."))) at = ((Map<?, ?>) at).get(part);
                    assertTrue(file + " lacks " + key, at instanceof String);
                }
            }
        }
    }
}

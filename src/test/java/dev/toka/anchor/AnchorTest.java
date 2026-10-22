package dev.toka.anchor;

import dev.toka.anchor.ban.Ban;
import dev.toka.anchor.ban.BanBook;
import dev.toka.anchor.ban.BanService;
import dev.toka.anchor.ban.Span;
import dev.toka.anchor.link.LinkFinder;
import dev.toka.anchor.link.Sightings;
import dev.toka.anchor.upkeep.RestartPlan;
import dev.toka.storage.Backend;
import dev.toka.storage.DocumentStore;
import dev.toka.storage.StoreConfig;
import org.junit.Test;
import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import static org.junit.Assert.*;

public class AnchorTest {
    private static final long NOW = 1_000_000L;

    private static DocumentStore open(Path dir) throws Exception { return DocumentStore.open(new StoreConfig(Backend.valueOf("SQLITE"), dir, 1)); }

    @Test public void lengthsParseAndNonsenseIsRejected() {
        assertEquals(Long.valueOf(1_800_000L), Span.parse("30m"));
        assertEquals(Long.valueOf(604_800_000L), Span.parse("1w"));
        assertEquals(Long.valueOf(-1L), Span.parse("perm"));
        assertNull(Span.parse("soon"));
        assertNull(Span.parse("0d"));
        assertNull(Span.parse("-5m"));
        assertNull(Span.parse("d"));
    }

    @Test public void bansSurviveAReopenAndExpiredOnesAreIgnored() throws Exception {
        Path dir = Files.createTempDirectory("anchor");
        try (var store = open(dir)) {
            BanBook book = new BanBook(store);
            book.add(List.of(new Ban("Alex", "grief", "op", NOW, null), new Ban("Sam", "spam", "op", NOW, NOW + 10)), NOW);
        }
        try (var store = open(dir)) {
            BanBook book = new BanBook(store);
            assertTrue(book.active("alex", NOW + 5).isPresent());
            assertTrue(book.active("SAM", NOW + 5).isPresent());
            assertTrue(book.active("sam", NOW + 11).isEmpty());
            assertEquals(1, book.list(NOW + 11).size());
            assertTrue(book.remove("Alex", NOW + 11));
            assertFalse(book.remove("Alex", NOW + 11));
        }
    }

    private static Map<String, Sightings.Seen> world() {
        return Map.of(
                "alex", new Sightings.Seen(java.util.Set.of("1.1.1.1"), java.util.Set.of("dev-a")),
                "alt", new Sightings.Seen(java.util.Set.of("1.1.1.1", "2.2.2.2"), java.util.Set.of("dev-a")),
                "roommate", new Sightings.Seen(java.util.Set.of("1.1.1.1"), java.util.Set.of("dev-b")),
                "stranger", new Sightings.Seen(java.util.Set.of("9.9.9.9"), java.util.Set.of("dev-z")));
    }

    @Test public void sameDeviceAndAddressIsDirectAndOneMatchIsOnlyPossible() {
        var related = LinkFinder.related(world(), "Alex");
        assertEquals(LinkFinder.Strength.DIRECT, related.get("alt"));
        assertEquals(LinkFinder.Strength.POSSIBLE, related.get("roommate"));
        assertFalse(related.containsKey("stranger"));
        assertEquals(java.util.Set.of("alt"), LinkFinder.direct(related));
    }

    @Test public void banningAnAccountAlsoBansItsDirectAlternatesButNotRoommates() throws Exception {
        try (var store = open(Files.createTempDirectory("anchor")); var service = serviceWith(store, true)) {
            var outcome = service.ban("Alex", "grief", "op", null, NOW);
            assertEquals(List.of("Alex", "alt"), outcome.banned());
            assertEquals(List.of("roommate"), outcome.possible());
            assertTrue(service.book().active("alt", NOW).isPresent());
            assertTrue(service.book().active("roommate", NOW).isEmpty());
        }
    }

    @Test public void withAutoBanOffOnlyTheNamedAccountIsBanned() throws Exception {
        try (var store = open(Files.createTempDirectory("anchor")); var service = serviceWith(store, false)) {
            assertEquals(List.of("Alex"), service.ban("Alex", "grief", "op", null, NOW).banned());
            assertTrue(service.book().active("alt", NOW).isEmpty());
        }
    }

    @Test public void aNewAccountOnTheSameDeviceAndAddressIsRefusedAtLogin() throws Exception {
        try (var store = open(Files.createTempDirectory("anchor")); var service = serviceWith(store, true)) {
            service.ban("Alex", "grief", "op", null, NOW);
            Optional<Ban> refused = service.checkLogin("Fresh", "1.1.1.1", "dev-a", NOW + 1);
            assertTrue(refused.isPresent());
            assertTrue(service.book().active("fresh", NOW + 2).isPresent());
            assertTrue(service.checkLogin("Visitor", "5.5.5.5", "dev-q", NOW + 3).isEmpty());
        }
    }

    private static BanService serviceWith(DocumentStore store, boolean autoBan) throws Exception {
        Sightings sightings = new Sightings(store, 10);
        sightings.note("Alex", "1.1.1.1", "dev-a");
        sightings.note("alt", "1.1.1.1", "dev-a");
        sightings.note("roommate", "1.1.1.1", "dev-b");
        return new BanService(new BanBook(store), sightings, autoBan, line -> {});
    }

    private static TreeSet<String> keys(String file) throws Exception {
        try (InputStream in = AnchorTest.class.getResourceAsStream("/lang/" + file)) {
            TreeSet<String> out = new TreeSet<>();
            flatten("", new Yaml().load(in), out);
            return out;
        }
    }

    private static void flatten(String prefix, Object node, TreeSet<String> out) {
        if (node instanceof Map<?, ?> map) map.forEach((k, v) -> flatten(prefix.isEmpty() ? k.toString() : prefix + "." + k, v, out));
        else out.add(prefix);
    }

    @Test public void bothLanguagesHaveTheSameKeys() throws Exception {
        assertEquals(keys("zh-TW.yml"), keys("en-US.yml"));
    }

    @Test public void importingTwiceAddsEachBanOnce() throws Exception {
        try (var store = open(Files.createTempDirectory("anchor"))) {
            BanBook book = new BanBook(store);
            List<Ban> batch = List.of(new Ban("Alex", "grief", "zero", NOW, null), new Ban("alex", "dup", "zero", NOW, null), new Ban("Sam", "spam", "zero", NOW, null));
            assertEquals(2, book.addMissing(batch, NOW));
            assertEquals(0, book.addMissing(batch, NOW));
            assertEquals("grief", book.active("alex", NOW).get().reason());
        }
    }

    @Test public void theNextRestartIsTheEarliestTimeStillAhead() {
        var zone = java.time.ZoneId.of("UTC");
        var times = java.util.List.of(java.time.LocalTime.of(3, 15), java.time.LocalTime.of(15, 0));
        var at = (java.util.function.Function<String, java.time.Instant>) text -> java.time.Instant.parse(text);
        assertEquals(at.apply("2026-10-23T03:15:00Z"), RestartPlan.next(times, zone, at.apply("2026-10-23T02:00:00Z")).get());
        assertEquals(at.apply("2026-10-23T15:00:00Z"), RestartPlan.next(times, zone, at.apply("2026-10-23T03:15:00Z")).get());
        assertEquals(at.apply("2026-10-24T03:15:00Z"), RestartPlan.next(times, zone, at.apply("2026-10-23T16:00:00Z")).get());
        assertTrue(RestartPlan.next(java.util.List.of(), zone, java.time.Instant.now()).isEmpty());
    }

    @Test public void badRestartTimesAreReportedNotGuessed() {
        var rejected = new java.util.ArrayList<String>();
        var times = RestartPlan.parse(java.util.List.of("03:15", "25:99", "noon"), rejected);
        assertEquals(1, times.size());
        assertEquals(java.util.List.of("25:99", "noon"), rejected);
    }

    @Test public void theCountdownRoundsUpAndReachesZero() {
        var now = java.time.Instant.parse("2026-10-23T00:00:00Z");
        assertEquals(10, RestartPlan.secondsLeft(now.plusMillis(9_100), now));
        assertEquals(1, RestartPlan.secondsLeft(now.plusMillis(1), now));
        assertEquals(0, RestartPlan.secondsLeft(now.minusMillis(5), now));
    }

    private static void noBooleanKeys(Object node, String path) {
        if (!(node instanceof Map<?, ?> map)) return;
        for (var entry : map.entrySet()) {
            assertTrue("YAML turned the key into a boolean under " + path + " (quote on/off/yes/no): " + entry.getKey(), entry.getKey() instanceof String);
            noBooleanKeys(entry.getValue(), path + "." + entry.getKey());
        }
    }

    @Test public void noKeyIsReadAsABoolean() throws Exception {
        for (String file : new String[]{"zh-TW.yml", "en-US.yml"})
            try (InputStream in = AnchorTest.class.getResourceAsStream("/lang/" + file)) { noBooleanKeys(new Yaml().load(in), file); }
    }

    @Test public void liftingAnAccountLiftsTheCopiesMadeFromIt() throws Exception {
        try (var store = open(Files.createTempDirectory("anchor")); var service = serviceWith(store, true)) {
            service.ban("Alex", "grief", "op", null, NOW);
            service.checkLogin("Fresh", "1.1.1.1", "dev-a", NOW + 1);
            assertEquals(List.of("Alex", "alt", "Fresh").stream().sorted().toList(), service.book().list(NOW + 2).stream().map(Ban::name).sorted().toList());
            var lifted = service.book().removeWithLinked("Alex", NOW + 3);
            assertEquals(3, lifted.size());
            assertTrue(service.book().list(NOW + 4).isEmpty());
            assertTrue(service.checkLogin("Fresh", "1.1.1.1", "dev-a", NOW + 5).isEmpty());
        }
    }

    @Test public void aBanThatCannotBeSavedStillRefusesTheLinkedLogin() throws Exception {
        var store = open(Files.createTempDirectory("anchor"));
        var service = serviceWith(store, true);
        service.ban("Alex", "grief", "op", null, NOW);
        store.close();
        assertTrue(service.checkLogin("Fresh", "1.1.1.1", "dev-a", NOW + 1).isPresent());
    }
}

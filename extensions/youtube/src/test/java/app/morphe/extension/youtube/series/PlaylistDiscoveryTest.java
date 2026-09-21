package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public class PlaylistDiscoveryTest {
    interface TestTransport {
        CatalogClient.Reply request(String path, String body, int timeout, int bytes)
                throws Exception;
    }

    private static PlaylistDiscovery discovery(
            TestTransport transport, java.util.function.LongSupplier clock) {
        return new PlaylistDiscovery(
                (path, body, timeout, bytes) -> {
                    try {
                        return transport.request(path, body, timeout, bytes);
                    } catch (IOException e) {
                        throw e;
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                },
                clock);
    }

    private static final String VIDEO = "aaaaaaaaaaa", CHANNEL = "UCcreator";

    private static CatalogClient.Reply reply(String body) {
        return new CatalogClient.Reply(200, body, body.length(), 0);
    }

    private static String metadata(String description) throws JSONException {
        return new JSONObject()
                .put(
                        "videoDetails",
                        new JSONObject()
                                .put("videoId", VIDEO)
                                .put("title", "An episode | My series [12]")
                                .put("author", "Creator")
                                .put("channelId", CHANNEL)
                                .put("shortDescription", description))
                .toString();
    }

    private static JSONObject candidate(String id, String channel) throws JSONException {
        return new JSONObject()
                .put(
                        "compactPlaylistRenderer",
                        new JSONObject()
                                .put("playlistId", id)
                                .put("title", new JSONObject().put("simpleText", "My series"))
                                .put(
                                        "shortBylineText",
                                        new JSONObject()
                                                .put(
                                                        "runs",
                                                        new JSONArray()
                                                                .put(
                                                                        new JSONObject()
                                                                                .put(
                                                                                        "text",
                                                                                        "Creator")
                                                                                .put(
                                                                                        "navigationEndpoint",
                                                                                        new JSONObject()
                                                                                                .put(
                                                                                                        "browseEndpoint",
                                                                                                        new JSONObject()
                                                                                                                .put(
                                                                                                                        "browseId",
                                                                                                                        channel)))))));
    }

    private static String search(JSONObject... rows) throws JSONException {
        return new JSONObject().put("contents", new JSONArray(Arrays.asList(rows))).toString();
    }

    private static String catalog(String video) throws JSONException {
        JSONObject row =
                new JSONObject()
                        .put(
                                "playlistVideoRenderer",
                                new JSONObject().put("videoId", video).put("lengthSeconds", "60"));
        JSONObject list =
                new JSONObject()
                        .put(
                                "playlistVideoListRenderer",
                                new JSONObject().put("contents", new JSONArray().put(row)));
        JSONObject tab = new JSONObject().put("tabRenderer", new JSONObject().put("content", list));
        JSONObject contents =
                new JSONObject()
                        .put(
                                "singleColumnBrowseResultsRenderer",
                                new JSONObject().put("tabs", new JSONArray().put(tab)));
        return new JSONObject()
                .put("contents", contents)
                .put(
                        "metadata",
                        new JSONObject()
                                .put(
                                        "playlistMetadataRenderer",
                                        new JSONObject().put("title", "Verified title")))
                .toString();
    }

    @Test
    public void taskmasterSeasonSurvivesTheFormatSuffixAndSkipsWeakerCatalogs() throws Exception {
        List<String> requests = new ArrayList<>();
        AtomicInteger page = new AtomicInteger();
        PlaylistDiscovery finder =
                discovery(
                        (path, body, timeout, bytes) -> {
                            requests.add(path);
                            if (new JSONObject(body)
                                    .getJSONObject("context")
                                    .getJSONObject("client")
                                    .getString("clientName")
                                    .equals("WEB")) return reply("{}");
                            if (path.equals("player"))
                                return reply(CatalogTest.fixture("taskmaster_player.json"));
                            if (path.equals("search")) {
                                assertEquals(
                                        "Taskmaster Season 22",
                                        new JSONObject(body).getString("query"));
                                return reply(CatalogTest.fixture("taskmaster_search.json"));
                            }
                            JSONObject request = new JSONObject(body);
                            int current = page.getAndIncrement();
                            if (current == 0)
                                assertEquals(
                                        "VLPLRWvNQVqAeWIH8s4IZfWLylrMoIO379df",
                                        request.getString("browseId"));
                            else
                                assertEquals(
                                        "taskmaster-page-" + current,
                                        request.getString("continuation"));
                            return reply(
                                    CatalogTest.fixture("taskmaster_browse_" + current + ".json"));
                        },
                        () -> 0);
        List<PlaylistDiscovery.Match> matches = finder.find("ASzE5CuYNks");
        assertEquals(1, matches.size());
        assertEquals("Taskmaster - Full Episodes", matches.get(0).title);
        assertEquals(4, page.get());
        assertEquals(7, requests.size());
    }

    @Test
    public void equallyRankedSeasonMatchesStillRequireMembershipAndKeepAlternatives()
            throws Exception {
        JSONObject[] rows = new JSONObject[12];
        for (int i = 0; i < rows.length; i++) {
            rows[i] = candidate("PLseason" + i, CHANNEL);
            rows[i].getJSONObject("compactPlaylistRenderer")
                    .put(
                            "title",
                            new JSONObject().put("simpleText", "Season " + (i < 9 ? i + 1 : 22)));
        }
        List<String> checked = new ArrayList<>();
        PlaylistDiscovery finder =
                discovery(
                        (path, body, timeout, bytes) -> {
                            if (path.equals("player"))
                                return reply(
                                        metadata("")
                                                .replace(
                                                        "An episode | My series [12]",
                                                        "My show Season 22, Episode 2 | Full"
                                                                + " Episode"));
                            if (path.equals("search")) return reply(search(rows));
                            String id = new JSONObject(body).getString("browseId");
                            checked.add(id);
                            return reply(catalog(id.equals("VLPLseason9") ? "bbbbbbbbbbb" : VIDEO));
                        },
                        () -> 0);
        assertEquals(2, finder.find(VIDEO).size());
        assertEquals(Arrays.asList("VLPLseason9", "VLPLseason10", "VLPLseason11"), checked);
        assertEquals(
                "Creator My series",
                PlaylistDiscovery.queries("An episode | My series [12] | Full Episode", "Creator")
                        .get(0));
    }

    @Test
    public void transientFailuresCanBeRetriedAndClocksNeedNoPositiveOrigin() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PlaylistDiscovery finder =
                discovery(
                        (path, body, timeout, bytes) -> {
                            if (calls.incrementAndGet() == 1)
                                throw new java.net.SocketTimeoutException("cold connection");
                            if (path.equals("player"))
                                return reply(metadata("https://youtube.com/playlist?list=PLone"));
                            return reply(catalog(VIDEO));
                        },
                        () -> -1000000);
        assertTrue(finder.find(VIDEO).isEmpty());
        assertEquals(1, finder.find(VIDEO).size());
        assertEquals(3, calls.get());
    }

    @Test
    public void capturedAndroidSearchFindsAndVerifiesTheCreatorsSeries() throws Exception {
        String player = CatalogTest.fixture("discovery_player.json");
        String search = CatalogTest.fixture("discovery_search.json");
        String first = CatalogTest.fixture("browse_page1.json"),
                next = CatalogTest.fixture("browse_continuation.json");
        PlaylistDiscovery discovery =
                discovery(
                        (path, body, timeout, bytes) -> {
                            if (path.equals("player")) return reply(player);
                            if (path.equals("search")) return reply(search);
                            JSONObject request = new JSONObject(body);
                            if (request.has("continuation")) return reply(next);
                            return reply(
                                    request.optString("browseId")
                                                    .equals("VLPLRQGRBgN_Eno6oGezhHWG1Z0aLR_tGQm3")
                                            ? first
                                            : catalog("bbbbbbbbbbb"));
                        },
                        () -> 0);
        List<PlaylistDiscovery.Match> found = discovery.find("1QbXxw7yX7w");
        assertEquals(1, found.size());
        assertEquals("PLRQGRBgN_Eno6oGezhHWG1Z0aLR_tGQm3", found.get(0).id);
        assertEquals("GameGrumps", found.get(0).owner);
    }

    @Test
    public void continuationRequestsCannotEscapeTheGlobalRequestLimit() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        PlaylistDiscovery discovery =
                discovery(
                        (path, body, timeout, bytes) -> {
                            int n = calls.incrementAndGet();
                            if (path.equals("player"))
                                return reply(metadata("https://youtube.com/playlist?list=PLlong"));
                            JSONObject container =
                                    new JSONObject()
                                            .put(
                                                    "contents",
                                                    new JSONArray()
                                                            .put(
                                                                    new JSONObject()
                                                                            .put(
                                                                                    "playlistVideoRenderer",
                                                                                    new JSONObject()
                                                                                            .put(
                                                                                                    "videoId",
                                                                                                    "bbbbbbbbbbb"))))
                                            .put(
                                                    "continuations",
                                                    new JSONArray()
                                                            .put(
                                                                    new JSONObject()
                                                                            .put(
                                                                                    "nextContinuationData",
                                                                                    new JSONObject()
                                                                                            .put(
                                                                                                    "continuation",
                                                                                                    "page"
                                                                                                            + n))));
                            if (new JSONObject(body).has("continuation"))
                                return reply(
                                        new JSONObject()
                                                .put(
                                                        "continuationContents",
                                                        new JSONObject()
                                                                .put(
                                                                        "playlistVideoListContinuation",
                                                                        container))
                                                .toString());
                            JSONObject first = new JSONObject(catalog("bbbbbbbbbbb"));
                            first.getJSONObject("contents")
                                    .getJSONObject("singleColumnBrowseResultsRenderer")
                                    .getJSONArray("tabs")
                                    .getJSONObject(0)
                                    .getJSONObject("tabRenderer")
                                    .getJSONObject("content")
                                    .put("playlistVideoListRenderer", container);
                            return reply(first.toString());
                        },
                        () -> 0);
        assertTrue(discovery.find(VIDEO).isEmpty());
        assertEquals(PlaylistDiscovery.MAX_REQUESTS, calls.get());
    }

    @Test
    public void descriptionLinksAreValidatedDeduplicatedAndExcludeCollections() {
        assertEquals(
                Arrays.asList("PLone", "PLtwo"),
                PlaylistDiscovery.descriptionPlaylists(
                        "https://evil.example/?list=PLbad https://youtube.com/playlist?list=PLone\n"
                                + "https://youtu.be/aaaaaaaaaaa?list=PLone"
                                + " https://www.youtube.com/playlist?list=PLtwo."
                                + " https://youtube.com/playlist?list=RDmix"
                                + " https://youtube.com/playlist?list=UUuploads"));
        assertFalse(PlaylistDiscovery.eligible("WL"));
        assertFalse(PlaylistDiscovery.eligible("UULuploads"));
    }

    @Test
    public void descriptionMatchesSkipSearchAndEveryResultNeedsMembership() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        PlaylistDiscovery discovery =
                discovery(
                        (path, body, timeout, bytes) -> {
                            if (path.equals("player"))
                                return reply(
                                        metadata(
                                                "https://youtube.com/playlist?list=PLwrong"
                                                    + " https://youtube.com/playlist?list=PLright"));
                            if (path.equals("search")) {
                                searches.incrementAndGet();
                                return reply(search());
                            }
                            return reply(catalog(body.contains("PLright") ? VIDEO : "bbbbbbbbbbb"));
                        },
                        () -> 0);
        List<PlaylistDiscovery.Match> matches = discovery.find(VIDEO);
        assertEquals(1, matches.size());
        assertEquals("PLright", matches.get(0).id);
        assertEquals("Verified title", matches.get(0).title);
        assertEquals(0, searches.get());
    }

    @Test
    public void creatorIsPrioritizedAndPositiveResultsAreCachedWithAnExpiry() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        List<String> checked = new ArrayList<>();
        PlaylistDiscovery discovery =
                discovery(
                        (path, body, timeout, bytes) -> {
                            requests.incrementAndGet();
                            if (path.equals("player")) return reply(metadata(""));
                            if (path.equals("search"))
                                return reply(
                                        search(
                                                candidate("PLfan", "UCfan"),
                                                candidate("PLcreator", CHANNEL)));
                            checked.add(new JSONObject(body).getString("browseId"));
                            return reply(catalog(VIDEO));
                        },
                        clock::get);
        assertEquals("PLcreator", discovery.find(VIDEO).get(0).id);
        assertEquals(Arrays.asList("VLPLcreator"), checked);
        int count = requests.get();
        discovery.find(VIDEO);
        assertEquals(count, requests.get());
        clock.set(PlaylistDiscovery.HIT_TTL + 1);
        discovery.find(VIDEO);
        assertTrue(requests.get() > count);
    }

    @Test
    public void multipleVerifiedLinksAreKeptAndDuplicateIdsAreNotFetchedTwice() throws Exception {
        AtomicInteger browses = new AtomicInteger();
        PlaylistDiscovery discovery =
                discovery(
                        (path, body, timeout, bytes) -> {
                            if (path.equals("player"))
                                return reply(
                                        metadata(
                                                "https://youtube.com/playlist?list=PLone"
                                                    + " https://youtube.com/playlist?list=PLtwo"
                                                    + " https://youtube.com/playlist?list=PLone"));
                            assertEquals("browse", path);
                            browses.incrementAndGet();
                            return reply(catalog(VIDEO));
                        },
                        () -> 0);
        assertEquals(2, discovery.find(VIDEO).size());
        assertEquals(2, browses.get());
    }

    @Test
    public void plausibleTitlesAloneNeverProduceASuggestionAndMissesExpire() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        PlaylistDiscovery discovery =
                discovery(
                        (path, body, timeout, bytes) -> {
                            requests.incrementAndGet();
                            if (path.equals("player")) return reply(metadata(""));
                            if (path.equals("search"))
                                return reply(search(candidate("PLwrong", CHANNEL)));
                            return reply(catalog("bbbbbbbbbbb"));
                        },
                        clock::get);
        assertTrue(discovery.find(VIDEO).isEmpty());
        int count = requests.get();
        assertTrue(discovery.find(VIDEO).isEmpty());
        assertEquals(count, requests.get());
        clock.set(PlaylistDiscovery.MISS_TTL + 1);
        discovery.find(VIDEO);
        assertTrue(requests.get() > count);
    }

    @Test
    public void requestAndByteBudgetsAreSharedAcrossCatalogsAndSearches() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        JSONObject[] rows = new JSONObject[32];
        for (int i = 0; i < rows.length; i++) rows[i] = candidate("PLcandidate" + i, CHANNEL);
        PlaylistDiscovery discovery =
                discovery(
                        (path, body, timeout, bytes) -> {
                            assertTrue(timeout <= PlaylistDiscovery.TIME_MS);
                            assertTrue(bytes <= CatalogClient.PAGE_BYTES);
                            calls.incrementAndGet();
                            if (path.equals("player")) return reply(metadata(""));
                            if (path.equals("search")) return reply(search(rows));
                            return reply(catalog("bbbbbbbbbbb"));
                        },
                        () -> 0);
        assertTrue(discovery.find(VIDEO).isEmpty());
        assertTrue(calls.get() <= PlaylistDiscovery.MAX_CANDIDATES + 2);
        PlaylistDiscovery oversized =
                discovery(
                        (p, b, t, max) -> new CatalogClient.Reply(200, metadata(""), max + 1, 0),
                        () -> 0);
        assertTrue(oversized.find(VIDEO).isEmpty());
    }

    @Test
    public void throttlingStopsOtherVideosAndCancellationIsNotCached() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicLong clock = new AtomicLong();
        PlaylistDiscovery throttled =
                discovery(
                        (p, b, t, max) -> {
                            calls.incrementAndGet();
                            return new CatalogClient.Reply(429, "", 0, 60000);
                        },
                        clock::get);
        throttled.find(VIDEO);
        throttled.find("bbbbbbbbbbb");
        assertEquals(1, calls.get());
        clock.set(60001);
        throttled.find("bbbbbbbbbbb");
        assertEquals(2, calls.get());
        PlaylistDiscovery interrupted =
                discovery(
                        (p, b, t, max) -> {
                            throw new AssertionError("Cancelled work must not issue requests");
                        },
                        () -> 0);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> interrupted.find(VIDEO));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void lateResponsesCannotBypassDeadlineAndMetadataMustIdentifyTheVideo()
            throws Exception {
        AtomicLong clock = new AtomicLong();
        PlaylistDiscovery expired =
                discovery(
                        (p, b, t, max) -> {
                            clock.set(PlaylistDiscovery.TIME_MS + 1);
                            return reply(metadata(""));
                        },
                        clock::get);
        assertTrue(expired.find(VIDEO).isEmpty());
        AtomicInteger calls = new AtomicInteger();
        PlaylistDiscovery mismatched =
                discovery(
                        (p, b, t, max) -> {
                            calls.incrementAndGet();
                            return reply(metadata("").replace(VIDEO, "bbbbbbbbbbb"));
                        },
                        () -> 0);
        assertTrue(mismatched.find(VIDEO).isEmpty());
        assertEquals(1, calls.get());
    }
}

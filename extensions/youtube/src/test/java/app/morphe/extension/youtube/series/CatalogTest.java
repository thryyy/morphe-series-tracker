package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public class CatalogTest {
    static String fixture(String name) throws Exception {
        try (java.io.InputStream in =
                CatalogTest.class.getClassLoader().getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    public void capturedPagesProduce52OrderedEpisodes() throws Exception {
        CatalogParser.Page first = CatalogParser.parse(fixture("browse_page1.json"), 0, false);
        CatalogParser.Page next =
                CatalogParser.parse(
                        fixture("browse_continuation.json"), first.episodes.size(), true);
        assertEquals(20, first.episodes.size());
        assertEquals(32, next.episodes.size());
        assertFalse(first.continuation.isEmpty());
        assertEquals("", next.continuation);
        assertEquals(52, next.episodes.get(31).ordinal);
        assertTrue(first.title.contains("GRUMPLOCKE"));
        assertEquals(2903000, first.episodes.get(0).durationMs);
    }

    @Test
    public void duplicatesPlaceholdersMissingDurationAndRecommendations() throws Exception {
        String json =
                "{\"contents\":{\"singleColumnBrowseResultsRenderer\":{\"tabs\":[{\"tabRenderer\":{\"content\":{\"itemSectionRenderer\":{\"contents\":[{\"playlistVideoListRenderer\":{\"contents\":[{\"playlistVideoRenderer\":{\"videoId\":\"aaaaaaaaaaa\",\"title\":{\"runs\":[{\"text\":\"One"
                    + " \"},{\"text\":\"two\"}]}}},{\"playlistVideoRenderer\":{\"videoId\":\"aaaaaaaaaaa\"}},{\"playlistVideoRenderer\":{\"isPlayable\":false}}]}}]}}}}]}},\"recommendations\":{\"playlistVideoRenderer\":{\"videoId\":\"bbbbbbbbbbb\"}}}";
        CatalogParser.Page page = CatalogParser.parse(json, 0, false);
        assertEquals(3, page.episodes.size());
        assertEquals("One two", page.episodes.get(0).title);
        assertEquals(page.episodes.get(0).videoId, page.episodes.get(1).videoId);
        assertEquals(0, page.episodes.get(1).durationMs);
        assertEquals("", page.episodes.get(1).title);
        assertEquals("", page.episodes.get(2).title);
        assertFalse(page.episodes.get(2).available);
    }

    @Test
    public void emptyAndUnknownResponsesAreDifferent() throws Exception {
        String empty =
                "{\"continuationContents\":{\"playlistVideoListContinuation\":{\"contents\":[]}}}";
        assertTrue(CatalogParser.parse(empty, 0, true).episodes.isEmpty());
        assertThrows(JSONException.class, () -> CatalogParser.parse("{}", 0, false));
        assertThrows(
                JSONException.class,
                () ->
                        CatalogParser.parse(
                                "{\"alerts\":[{\"alertRenderer\":{\"type\":\"ERROR\",\"text\":{\"simpleText\":\"Unavailable\"}}}]}",
                                0,
                                false));
    }

    @Test
    public void clientFollowsTokensRatherThanPageSize() throws Exception {
        String first = fixture("browse_page1.json"), second = fixture("browse_continuation.json");
        AtomicInteger requests = new AtomicInteger();
        CatalogClient client =
                new CatalogClient(
                        (body, timeout, limit) -> {
                            int n = requests.getAndIncrement();
                            assertTrue(
                                    n == 0
                                            ? body.contains("browseId")
                                            : body.contains("continuation"));
                            String result = n == 0 ? first : second;
                            return new CatalogClient.Reply(200, result, result.length(), 0);
                        },
                        () -> 0);
        assertEquals(52, client.fetch("PLfixture").episodes.size());
        assertEquals(2, requests.get());
    }

    @Test
    public void clientRejectsCyclesThrottlesAndTimeouts() throws Exception {
        String first = fixture("browse_page1.json");
        String token = CatalogParser.parse(first, 0, false).continuation;
        String repeat =
                new JSONObject()
                        .put(
                                "continuationContents",
                                new JSONObject()
                                        .put(
                                                "playlistVideoListContinuation",
                                                new JSONObject()
                                                        .put("contents", new JSONArray())
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
                                                                                                                token))))))
                        .toString();
        AtomicInteger count = new AtomicInteger();
        CatalogClient cycle =
                new CatalogClient(
                        (b, t, l) -> {
                            String body = count.getAndIncrement() == 0 ? first : repeat;
                            return new CatalogClient.Reply(200, body, body.length(), 0);
                        },
                        () -> 0);
        assertTrue(
                assertThrows(CatalogClient.Failure.class, () -> cycle.fetch("PLtest"))
                        .getMessage()
                        .contains("repeated"));
        assertEquals(2, count.get());
        CatalogClient throttle =
                new CatalogClient((b, t, l) -> new CatalogClient.Reply(429, "", 0, 65000), () -> 0);
        assertEquals(
                65000,
                assertThrows(CatalogClient.Failure.class, () -> throttle.fetch("PLtest"))
                        .retryAfterMs);
        AtomicInteger clock = new AtomicInteger();
        CatalogClient timeout =
                new CatalogClient(
                        (b, t, l) -> {
                            throw new AssertionError("No request after deadline");
                        },
                        () -> clock.getAndAdd(61000));
        assertThrows(CatalogClient.Failure.class, () -> timeout.fetch("PLtest"));
    }

    @Test
    public void retryAfterSupportsSecondsAndHttpDates() {
        assertEquals(65000, CatalogClient.retryDelay("65", 0));
        assertEquals(65000, CatalogClient.retryDelay("Thu, 1 Jan 1970 00:01:05 GMT", 0));
        assertEquals(30000, CatalogClient.retryDelay("nonsense", 0));
        assertEquals(3600000, CatalogClient.retryDelay("999999", 0));
    }

    @Test
    public void playlistInputOnlyAcceptsYoutubeLinksAndExplicitIds() {
        assertEquals(
                "PLabc",
                PlaylistInput.parse("https://www.youtube.com/watch?v=aaaaaaaaaaa&list=PLabc"));
        assertEquals("OLAK5xyz", PlaylistInput.parse("OLAK5xyz"));
        assertThrows(
                IllegalArgumentException.class,
                () -> PlaylistInput.parse("https://evil.example/?list=PLabc"));
        assertFalse(PlaylistInput.suggestible("RD123"));
        assertFalse(PlaylistInput.suggestible("WL"));
    }
}

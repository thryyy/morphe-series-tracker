package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.json.*;
import org.junit.Test;

import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class ChannelPlaylistsTest {
    private static final String CHANNEL = "UCT5C7yaO3RVuOgwP8JVAujQ", VIDEO = "ASzE5CuYNks";

    private static JSONObject fixture(String name) throws Exception {
        return new JSONObject(CatalogTest.fixture(name));
    }

    private static CatalogClient.Reply reply(String body) {
        return new CatalogClient.Reply(200, body, body.length(), 0);
    }

    @Test
    public void findsMissingSeasonOnChannelAndValidatesTheCompleteCatalog() throws Exception {
        List<String> calls = new ArrayList<>();
        PlaylistDiscovery finder =
                new PlaylistDiscovery(
                        (path, body, timeout, bytes) -> {
                            try {
                                JSONObject q = new JSONObject(body);
                                calls.add(path);
                                if (path.equals("player"))
                                    return reply(CatalogTest.fixture("taskmaster_player.json"));
                                assertEquals(
                                        "browse",
                                        path); // Public search never exposes this season in the
                                // fixture.
                                if (q.getJSONObject("context")
                                        .getJSONObject("client")
                                        .getString("clientName")
                                        .equals("WEB")) {
                                    if (q.has("continuation")) return reply("{}");
                                    assertEquals(CHANNEL, q.getString("browseId"));
                                    if (q.has("params")) {
                                        assertEquals(
                                                "EglwbGF5bGlzdHPyBgoKCEIGCgIQaCIA",
                                                q.getString("params"));
                                        return reply(
                                                CatalogTest.fixture("taskmaster_playlists.json"));
                                    }
                                    return reply(CatalogTest.fixture("taskmaster_channel.json"));
                                }
                                assertEquals("VLPLFVQxZZUqPnA", q.getString("browseId"));
                                assertEquals("wgYCCAA%3D", q.getString("params"));
                                return reply(CatalogTest.fixture("taskmaster_season22.json"));
                            } catch (Exception e) {
                                throw new IOException(e);
                            }
                        },
                        () -> 0);
        List<PlaylistDiscovery.Match> found = finder.find(VIDEO);
        assertEquals(1, found.size());
        assertEquals("PLFVQxZZUqPnA", found.get(0).id);
        assertEquals("Season 22 - Full Episodes", found.get(0).title);
        assertEquals(5, calls.size());
    }

    @Test
    public void unavailableEpisodeKeepsItsSlotButCannotBePlayed() throws Exception {
        CatalogParser.Page page =
                CatalogParser.parse(CatalogTest.fixture("taskmaster_season22.json"), 0, false);
        assertEquals(3, page.episodes.size());
        assertEquals(VIDEO, page.episodes.get(1).videoId);
        assertEquals(2, page.episodes.get(1).ordinal);
        assertTrue(page.episodes.get(1).available);
        assertFalse(page.episodes.get(2).available);
        assertEquals(3, page.episodes.get(2).ordinal);
        String hidden =
                CatalogTest.fixture("taskmaster_season22.json")
                        .replace(
                                "Unavailable videos will be hidden during playback",
                                "1 unavailable video is hidden");
        assertThrows(JSONException.class, () -> CatalogParser.parse(hidden, 0, false));
    }

    @Test
    public void channelIdentityAndTabEndpointsCannotSwitchChannels() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        JSONObject home = fixture("taskmaster_channel.json");
        JSONObject wrong =
                new JSONObject(home.toString().replace(CHANNEL, "UCaaaaaaaaaaaaaaaaaaaaaa"));
        assertEquals(
                0,
                ChannelPlaylists.fetch(
                                CHANNEL,
                                q -> {
                                    calls.incrementAndGet();
                                    return wrong;
                                })
                        .getJSONArray("contents")
                        .length());
        assertEquals(1, calls.get());
        assertEquals(
                0,
                ChannelPlaylists.fetch(
                                "invalid",
                                q -> {
                                    throw new AssertionError();
                                })
                        .getJSONArray("contents")
                        .length());
        JSONArray tabs =
                home.getJSONObject("contents")
                        .getJSONObject("twoColumnBrowseResultsRenderer")
                        .getJSONArray("tabs");
        for (int i = 0; i < tabs.length(); i++) {
            JSONObject tab = CatalogParser.object(tabs.getJSONObject(i), "tabRenderer");
            if (tab.optString("title").equals("Playlists"))
                tab.getJSONObject("endpoint")
                        .getJSONObject("browseEndpoint")
                        .put("browseId", "UCaaaaaaaaaaaaaaaaaaaaaa");
        }
        calls.set(0);
        assertEquals(
                0,
                ChannelPlaylists.fetch(
                                CHANNEL,
                                q -> {
                                    calls.incrementAndGet();
                                    return home;
                                })
                        .getJSONArray("contents")
                        .length());
        assertEquals(1, calls.get());
    }

    @Test
    public void playlistPaginationIsBoundedAndDeduplicated() throws Exception {
        JSONObject home = fixture("taskmaster_channel.json"),
                page = fixture("taskmaster_playlists.json");
        JSONArray tabs =
                page.getJSONObject("contents")
                        .getJSONObject("twoColumnBrowseResultsRenderer")
                        .getJSONArray("tabs");
        JSONObject selected = null;
        for (int i = 0; i < tabs.length(); i++)
            if (CatalogParser.object(tabs.getJSONObject(i), "tabRenderer").optBoolean("selected"))
                selected =
                        CatalogParser.object(tabs.getJSONObject(i), "tabRenderer")
                                .getJSONObject("content");
        JSONObject continuation =
                new JSONObject()
                        .put(
                                "onResponseReceivedActions",
                                new JSONArray()
                                        .put(
                                                new JSONObject()
                                                        .put(
                                                                "appendContinuationItemsAction",
                                                                new JSONObject()
                                                                        .put(
                                                                                "continuationItems",
                                                                                new JSONArray()
                                                                                        .put(
                                                                                                selected)))));
        AtomicInteger calls = new AtomicInteger();
        JSONObject result =
                ChannelPlaylists.fetch(
                        CHANNEL,
                        q -> {
                            int n = calls.incrementAndGet();
                            return n == 1 ? home : n == 2 ? page : continuation;
                        });
        assertEquals(3, calls.get());
        Set<String> ids = new HashSet<>();
        JSONArray rows = result.getJSONArray("contents");
        assertTrue(rows.length() > 1);
        assertTrue(rows.length() <= ChannelPlaylists.MAX_ITEMS);
        for (int i = 0; i < rows.length(); i++)
            assertTrue(
                    ids.add(
                            rows.getJSONObject(i)
                                    .getJSONObject("playlistRenderer")
                                    .getString("playlistId")));
    }

    @Test
    public void channelFailureDoesNotDisableSearchFallback() throws Exception {
        AtomicInteger searches = new AtomicInteger();
        PlaylistDiscovery finder =
                new PlaylistDiscovery(
                        (path, body, timeout, bytes) -> {
                            try {
                                JSONObject q = new JSONObject(body);
                                if (path.equals("player"))
                                    return reply(CatalogTest.fixture("taskmaster_player.json"));
                                if (q.getJSONObject("context")
                                        .getJSONObject("client")
                                        .getString("clientName")
                                        .equals("WEB"))
                                    throw new IOException("channel unavailable");
                                if (path.equals("search")) {
                                    searches.incrementAndGet();
                                    return reply(
                                            "{\"contents\":[{\"playlistRenderer\":{\"playlistId\":\"PLFVQxZZUqPnA\",\"title\":{\"simpleText\":\"Season"
                                                + " 22\"}}}]}");
                                }
                                return reply(CatalogTest.fixture("taskmaster_season22.json"));
                            } catch (Exception e) {
                                throw new IOException(e);
                            }
                        },
                        () -> 0);
        assertEquals("PLFVQxZZUqPnA", finder.find(VIDEO).get(0).id);
        assertEquals(1, searches.get());
    }
}

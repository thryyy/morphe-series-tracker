package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class EpisodeOrderTest {
    private List<TrackerModels.Episode> rows(String... titles) {
        List<TrackerModels.Episode> rows = new ArrayList<>();
        for (String title : titles)
            rows.add(
                    new TrackerModels.Episode(
                            rows.size() + 1, "video" + rows.size(), title, 10000, true));
        return rows;
    }

    private List<TrackerModels.Episode> dates(String... ages) {
        List<TrackerModels.Episode> rows = new ArrayList<>();
        for (String age : ages)
            rows.add(
                    new TrackerModels.Episode(
                            rows.size() + 1,
                            "video" + rows.size(),
                            "A day in my life",
                            10000,
                            true,
                            "10K views • " + age));
        return rows;
    }

    @Test
    public void taskmasterUsesEpisodeNumbersAndKeepsTheFirstEpisodeFirst() throws Exception {
        List<TrackerModels.Episode> episodes =
                CatalogParser.parse(CatalogTest.fixture("taskmaster_season22.json"), 0, false)
                        .episodes;
        assertEquals(EpisodeOrder.Direction.PLAYLIST, EpisodeOrder.infer(episodes));
        assertEquals("w-pNR-MeIDc", EpisodeOrder.first(episodes, false).videoId);
    }

    @Test
    public void realLifeInChinaUsesPublicAgesToReverseTheCatalog() throws Exception {
        List<TrackerModels.Episode> episodes =
                new ArrayList<>(
                        CatalogParser.parse(CatalogTest.fixture("china_browse_0.json"), 0, false)
                                .episodes);
        episodes.addAll(
                CatalogParser.parse(
                                CatalogTest.fixture("china_browse_1.json"), episodes.size(), true)
                        .episodes);
        assertEquals(EpisodeOrder.Direction.REVERSE, EpisodeOrder.infer(episodes));
        assertEquals("I_hTOyE41Ic", EpisodeOrder.first(episodes, true).videoId);
        Collections.reverse(episodes);
        assertEquals(EpisodeOrder.Direction.PLAYLIST, EpisodeOrder.infer(episodes));
    }

    @Test
    public void numberingSupportsSeasonBoundariesCompactAndFrenchTitles() {
        assertEquals(
                EpisodeOrder.Direction.REVERSE,
                EpisodeOrder.infer(rows("S02E02", "S02E01", "S01E12")));
        assertEquals(
                EpisodeOrder.Direction.PLAYLIST,
                EpisodeOrder.infer(rows("Saison 1, Épisode 9", "Saison 2, Épisode 1")));
        assertEquals(
                EpisodeOrder.Direction.PLAYLIST,
                EpisodeOrder.infer(rows("Ep. 1", "Ep. 2", "Ep. 3")));
    }

    @Test
    public void conflictingNumbersDoNotFallBackToUploadDates() {
        List<TrackerModels.Episode> episodes =
                Arrays.asList(
                        new TrackerModels.Episode(1, "a", "Episode 1", 1000, true, "1 day ago"),
                        new TrackerModels.Episode(2, "b", "Episode 3", 1000, true, "3 days ago"),
                        new TrackerModels.Episode(3, "c", "Episode 2", 1000, true, "5 days ago"));
        assertEquals(EpisodeOrder.Direction.UNKNOWN, EpisodeOrder.infer(episodes));
        assertEquals(
                EpisodeOrder.Direction.UNKNOWN,
                EpisodeOrder.infer(rows("Season 1 Episode 1", "Episode 2")));
    }

    @Test
    public void numberedStoryOrderWinsOverReuploadDates() {
        List<TrackerModels.Episode> episodes =
                Arrays.asList(
                        new TrackerModels.Episode(1, "a", "Episode 1", 1000, true, "1 day ago"),
                        new TrackerModels.Episode(2, "b", "Episode 2", 1000, true, "3 days ago"),
                        new TrackerModels.Episode(3, "c", "Episode 3", 1000, true, "5 days ago"));
        assertEquals(EpisodeOrder.Direction.PLAYLIST, EpisodeOrder.infer(episodes));
    }

    @Test
    public void mixedUploadsMissingDatesAndRoundedTiesRemainUnknown() {
        assertEquals(
                EpisodeOrder.Direction.UNKNOWN,
                EpisodeOrder.infer(dates("1 day ago", "5 days ago", "2 days ago")));
        assertEquals(
                EpisodeOrder.Direction.UNKNOWN,
                EpisodeOrder.infer(dates("2 weeks ago", "2 weeks ago", "2 weeks ago")));
        assertEquals(
                EpisodeOrder.Direction.UNKNOWN,
                EpisodeOrder.infer(dates("1 day ago", "3 days ago")));
        assertEquals(
                EpisodeOrder.Direction.UNKNOWN,
                EpisodeOrder.infer(
                        dates("1 day ago", "2 weeks ago", "1 month ago", "", "", "", "")));
        assertEquals(
                EpisodeOrder.Direction.UNKNOWN,
                EpisodeOrder.infer(rows("At 36, I lost 7 years", "What $3 buys", "My 7m² room")));
    }

    @Test
    public void partiallyOverlappingAgeRangesDoNotHideAConflictingPair() {
        assertEquals(
                EpisodeOrder.Direction.UNKNOWN,
                EpisodeOrder.infer(
                        dates(
                                "3 days ago",
                                "1 week ago",
                                "2 weeks ago",
                                "1 week ago",
                                "1 day ago")));
        assertEquals(
                EpisodeOrder.Direction.UNKNOWN,
                EpisodeOrder.infer(dates("28 days ago", "1 month ago", "30 days ago")));
    }

    @Test
    public void privateAndDuplicateRowsDoNotManufactureDirectionEvidence() {
        List<TrackerModels.Episode> episodes = new ArrayList<>(rows("Episode 1", "Episode 2"));
        episodes.add(0, new TrackerModels.Episode(3, "private", "Episode 10", 0, false));
        episodes.add(episodes.get(1));
        assertEquals(EpisodeOrder.Direction.PLAYLIST, EpisodeOrder.infer(episodes));
        assertEquals("video0", EpisodeOrder.first(episodes, false).videoId);
        assertNull(EpisodeOrder.first(Collections.emptyList(), false));
    }
}

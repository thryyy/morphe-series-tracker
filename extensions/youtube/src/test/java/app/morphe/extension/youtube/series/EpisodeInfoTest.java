package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class EpisodeInfoTest {
    @Test
    public void publicViewCountsRemainOptionalAndSupportYouTubeAbbreviations() {
        assertEquals(2300000, EpisodeInfo.views("2.3M views"), 0);
        assertEquals(1234, EpisodeInfo.views("1,234 views"), 0);
        assertEquals(1200, EpisodeInfo.views("1.2K views"), 0);
        assertEquals(1200000000, EpisodeInfo.views("1.2B views"), 0);
        assertEquals(1, EpisodeInfo.views("1 view"), 0);
        assertEquals(0, EpisodeInfo.views("No views"), 0);
        assertEquals(-1, EpisodeInfo.views("10 watching"), 0);
        assertEquals(-1, EpisodeInfo.views("unknown"), 0);
    }

    @Test
    public void relativeAgesAreLocalizedAndAdvanceSinceTheCatalogWasFetched() {
        assertEquals("9 days ago", EpisodeInfo.age("9 days ago", Locale.US, 1000, 1000));
        assertEquals("2 days ago", EpisodeInfo.age("2 days ago", Locale.US, 1000, 1000));
        assertEquals("il y a 2 jours", EpisodeInfo.age("2 days ago", Locale.FRANCE, 1000, 1000));
        assertEquals("3 hours ago", EpisodeInfo.age("2 hours ago", Locale.US, 1000, 3601000));
        assertEquals("1 day ago", EpisodeInfo.age("23 hours ago", Locale.US, 1000, 3601000));
        assertEquals(
                "2 months ago", EpisodeInfo.age("Streamed 2 months ago", Locale.US, 1000, 1000));
        assertEquals("", EpisodeInfo.age("Live", Locale.US, 1000, 1000));
        assertEquals("", EpisodeInfo.age("Tomorrow", Locale.US, 1000, 1000));
    }

    @Test
    public void overviewReleaseAgeOmitsViewsAndMissingOrUpcomingDates() {
        assertEquals(
                "5 days ago",
                EpisodeInfo.releaseAge("2.3M views • 4 days ago", Locale.US, 1000, 86401000));
        assertEquals(
                "il y a 1 minute",
                EpisodeInfo.releaseAge("1 view · 1 minute ago", Locale.FRANCE, 1000, 1000));
        assertEquals(
                "2 hours ago",
                EpisodeInfo.releaseAge("Premiered 2 hours ago", Locale.US, 1000, 1000));
        for (String raw : new String[] {"", "2.3M views", "Live", "Tomorrow"})
            assertEquals("", EpisodeInfo.releaseAge(raw, Locale.US, 1000, 1000));
    }

    @Test
    public void capturedCatalogKeepsMetadataWithoutAttachingItToPrivateEntries() throws Exception {
        List<TrackerModels.Episode> rows =
                CatalogParser.parse(CatalogTest.fixture("taskmaster_season22.json"), 0, false)
                        .episodes;
        assertEquals("2.3M views • 9 days ago", rows.get(0).videoInfo);
        assertEquals("1M views • 2 days ago", rows.get(1).videoInfo);
        assertEquals("", rows.get(2).videoInfo);
        assertEquals(
                rows.get(1).videoInfo,
                EpisodeInfo.decode(EpisodeInfo.encode(rows)).getString("ASzE5CuYNks"));
        assertEquals(0, EpisodeInfo.decode("invalid").length());
    }
}

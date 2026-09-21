package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.*;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class TrackerRepositoryTest {
    TrackerRepository db;
    Context context;
    String file;

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
        file = "test-" + UUID.randomUUID() + ".db";
        db = new TrackerRepository(context, file);
    }

    @After
    public void close() {
        db.close();
        context.deleteDatabase(file);
    }

    static CatalogClient.Catalog catalog(String... ids) {
        List<Episode> rows = new ArrayList<>();
        for (String id : ids) rows.add(new Episode(rows.size() + 1, id, id, 100000, !id.isEmpty()));
        return new CatalogClient.Catalog("Series", rows);
    }

    long tickTime;

    void tick(String id, long pos, boolean completed) {
        tickTime = Math.max(tickTime + 1, 1000 + pos);
        assertTrue(
                db.checkpoint(
                        new PlaybackReducer.Snapshot(id, 1, 1, pos, 100000, completed, tickTime),
                        db.historyEpoch()));
    }

    @Test
    public void followSavesCurrentPositionAndOnlyEarlierPlayableEpisodes() {
        db.saveSeries(
                "p",
                "Series",
                "",
                catalog("a", "", "b", "c"),
                new FollowStart("b", 42000, false, true),
                1000);
        Series series = db.series("p");
        assertEquals("b", series.cursorId);
        assertEquals(3, series.cursorOrdinal);
        assertTrue(series.startHere);
        assertTrue(db.progress("a").watched());
        assertFalse(db.progress("b").watched());
        assertFalse(db.progress("c").watched());
        assertEquals(42000, ResumePlanner.plan(series).positionMs);
        assertEquals(
                2,
                db.getReadableDatabase()
                        .compileStatement("SELECT COUNT(*) FROM video_progress")
                        .simpleQueryForLong());
        db.close();
        db = new TrackerRepository(context, file);
        assertEquals(42000, ResumePlanner.plan(db.series("p")).positionMs);
        assertEquals(1, db.getReadableDatabase().getVersion());
    }

    @Test
    public void reverseFollowMarksOnlyEarlierEpisodesInSelectedOrder() {
        db.saveSeries(
                "p",
                "Series",
                "",
                catalog("a", "b", "c", "", "d"),
                new FollowStart("b", 12345, true, true),
                1000);
        Series series = db.series("p");
        assertTrue(series.reverseOrder);
        assertEquals(2, series.cursorOrdinal);
        assertFalse(db.progress("a").watched());
        assertFalse(db.progress("b").watched());
        assertTrue(db.progress("c").watched());
        assertTrue(db.progress("d").watched());
        assertEquals("b", ResumePlanner.plan(series).videoId);
        assertEquals(12345, ResumePlanner.plan(series).positionMs);
    }

    @Test
    public void followWithoutPreviousSelectionDoesNotMarkEarlierEpisodes() {
        db.saveSeries(
                "p",
                "Series",
                "",
                catalog("a", "b"),
                new FollowStart("b", 17000, false, false),
                1000);
        assertFalse(db.progress("a").watched());
        assertEquals(17000, db.progress("b").positionMs);
    }

    @Test
    public void followCurrentRewatchResumesEvenWhenPreviouslyCompletedElsewhere() {
        db.saveSeries("old", "Old", "", catalog("b"), 1);
        tick("b", 99000, true);
        db.mark("b", TrackerModels.Override.WATCHED, 2);
        db.saveSeries(
                "p",
                "Series",
                "",
                catalog("a", "b"),
                new FollowStart("b", 23000, false, false),
                1000);
        assertFalse(db.progress("b").watched());
        assertEquals(TrackerModels.Override.AUTO, db.progress("b").override);
        assertEquals(23000, ResumePlanner.plan(db.series("p")).positionMs);
        tick("b", 26000, false);
        assertEquals(26000, ResumePlanner.plan(db.series("p")).positionMs);
    }

    @Test
    public void missingPlayerPositionPreservesExistingProgressAndInvalidFollowRollsBack() {
        db.saveSeries("old", "Old", "", catalog("b"), 1);
        tick("b", 27000, false);
        db.saveSeries(
                "p", "Series", "", catalog("a", "b"), new FollowStart("b", -1, false, true), 1000);
        assertEquals(27000, ResumePlanner.plan(db.series("p")).positionMs);
        try {
            db.saveSeries(
                    "bad",
                    "Bad",
                    "",
                    catalog("a", "b"),
                    new FollowStart("missing", 14000, false, true),
                    1000);
            fail("Must reject missing current video");
        } catch (IllegalArgumentException expected) {
            assertEquals("series_tracker_video_not_in_playlist", expected.getMessage());
        }
        assertEquals(
                2,
                db.getReadableDatabase()
                        .compileStatement("SELECT COUNT(*) FROM series")
                        .simpleQueryForLong());
        assertEquals(27000, db.progress("b").positionMs);
    }

    @Test
    public void episodeMetadataIsCachedRefreshedAndRemovedWithoutChangingProgress()
            throws Exception {
        List<Episode> rows =
                CatalogParser.parse(CatalogTest.fixture("taskmaster_season22.json"), 0, false)
                        .episodes;
        CatalogClient.Catalog catalog = new CatalogClient.Catalog("Season 22", rows);
        db.saveSeries("PLinfo", "Info", "", catalog, 1);
        db.mark("ASzE5CuYNks", TrackerModels.Override.WATCHED, 2);
        assertTrue(db.hasEpisodeInfo("PLinfo"));
        assertEquals("1M views • 2 days ago", db.series("PLinfo").episodes.get(1).videoInfo);
        db.close();
        db = new TrackerRepository(context, file);
        assertEquals("1M views • 2 days ago", db.series("PLinfo").episodes.get(1).videoInfo);
        List<Episode> updated =
                Collections.singletonList(
                        new Episode(
                                1,
                                "ASzE5CuYNks",
                                "Episode",
                                1000,
                                true,
                                "1.1M views • 3 days ago"));
        db.publish(db.beginRefresh("PLinfo"), new CatalogClient.Catalog("Season 22", updated), 3);
        assertEquals("1.1M views • 3 days ago", db.series("PLinfo").episodes.get(0).videoInfo);
        assertTrue(db.progress("ASzE5CuYNks").watched());
        db.remove("PLinfo");
        assertFalse(db.hasEpisodeInfo("PLinfo"));
        db.saveSeries("PLemptyInfo", "Empty info", "", catalog("aaaaaaaaaaa"), 4);
        assertTrue(
                db.hasEpisodeInfo(
                        "PLemptyInfo")); // A successful catalog with no stats must not loop.
        assertEquals("", db.series("PLemptyInfo").episodes.get(0).videoInfo);
    }

    @Test
    public void orderPreferenceCannotTurnABookmarkIntoACompleteCatalog() {
        db.saveSeries("PLbookmark", "Bookmark", "aaaaaaaaaaa", null, 1);
        db.seriesOptions("PLbookmark", 0, true, true);
        Series bookmark = db.series("PLbookmark");
        assertFalse(bookmark.complete());
        assertTrue(bookmark.reverseOrder);
        assertEquals("aaaaaaaaaaa", ResumePlanner.plan(bookmark).videoId);
    }

    @Test
    public void reverseChinaOrderPreservesSourceIdentityAndSurvivesRefreshAndReopen()
            throws Exception {
        List<Episode> episodes =
                new ArrayList<>(
                        CatalogParser.parse(CatalogTest.fixture("china_browse_0.json"), 0, false)
                                .episodes);
        episodes.addAll(
                CatalogParser.parse(
                                CatalogTest.fixture("china_browse_1.json"), episodes.size(), true)
                        .episodes);
        CatalogClient.Catalog china = new CatalogClient.Catalog("Real Life in China", episodes);
        db.saveSeries("PLchina", "China", "", china, "ccXVgWAW19w", 1);
        Series original = db.series("PLchina");
        assertEquals("ccXVgWAW19w", original.visibleEpisodes().get(0).videoId);
        db.seriesOptions("PLchina", original.revision, true, false);
        Series reversed = db.series("PLchina");
        assertEquals("I_hTOyE41Ic", reversed.visibleEpisodes().get(0).videoId);
        assertEquals("ccXVgWAW19w", reversed.cursorId);
        assertEquals(1, reversed.cursorOrdinal);
        assertEquals(25, reversed.episodeNumber(1));
        assertTrue(ResumePlanner.plan(reversed).playable());
        assertThrows(
                IllegalStateException.class,
                () -> db.markThrough("PLchina", original.revision, 1, 2));
        db.publish(db.beginRefresh("PLchina"), china, 3);
        db.close();
        db = new TrackerRepository(context, file);
        Series reloaded = db.series("PLchina");
        assertTrue(reloaded.reverseOrder);
        assertEquals("I_hTOyE41Ic", reloaded.visibleEpisodes().get(0).videoId);
        assertEquals("ccXVgWAW19w", reloaded.cursorId);
        assertEquals(TrackerModels.Override.AUTO, db.progress("ccXVgWAW19w").override);
    }

    @Test
    public void reverseContinueAndWatchedThroughUseTheSameOrderWithoutLosingUndo() {
        db.saveSeries(
                "PLreverse",
                "Reverse",
                "",
                catalog("newest00000", "middle00000", "oldest00000"),
                1);
        db.seriesOptions("PLreverse", db.series("PLreverse").revision, true, true);
        Series series = db.series("PLreverse");
        assertEquals("oldest00000", ResumePlanner.plan(series).videoId);
        db.select(series.id, series.revision, 3, "oldest00000", false);
        db.mark("oldest00000", TrackerModels.Override.WATCHED, 2);
        assertEquals("middle00000", ResumePlanner.plan(db.series(series.id)).videoId);
        TrackerRepository.Undo undo = db.markThrough(series.id, series.revision, 2, 3);
        assertTrue(db.progress("middle00000").watched());
        assertFalse(db.progress("newest00000").watched());
        assertEquals(1, db.series(series.id).visibleEpisodes().size());
        db.undo(undo, 4);
        assertFalse(db.progress("middle00000").watched());
        assertTrue(db.progress("oldest00000").watched());
        assertEquals(2, db.series(series.id).visibleEpisodes().size());
    }

    @Test
    public void watchedFilterIsPerSeriesKeepsProgressAndFiltersBeforePagination() {
        List<Episode> episodes = new ArrayList<>();
        for (int i = 1; i <= 125; i++)
            episodes.add(new Episode(i, "video" + i, "Episode " + i, 60000, i != 125));
        CatalogClient.Catalog catalog = new CatalogClient.Catalog("Many", episodes);
        db.saveSeries("PLfiltered", "Filtered", "", catalog, 1);
        db.saveSeries("PLother", "Other", "", catalog, 1);
        Series series = db.series("PLfiltered");
        db.markThrough(series.id, series.revision, 110, 2);
        db.seriesOptions(series.id, series.revision, false, true);
        Series filtered = db.series(series.id);
        assertEquals(14, filtered.visibleEpisodes().size());
        assertEquals(111, filtered.visibleEpisodes().get(0).ordinal);
        assertEquals(124, db.series("PLother").visibleEpisodes().size());
        assertTrue(db.progress("video1").watched());
        db.seriesOptions(series.id, filtered.revision, false, false);
        assertEquals(124, db.series(series.id).visibleEpisodes().size());
        assertTrue(db.progress("video1").watched());
        db.remove(series.id);
        db.saveSeries(series.id, "Again", "", catalog, 3);
        assertFalse(db.series(series.id).hideWatched);
        assertFalse(db.series(series.id).reverseOrder);
    }

    @Test
    public void playerFindsKnownSeriesButDoesNotGuessBetweenPlaylists() {
        db.saveSeries("PLone", "One", "", catalog("aaaaaaaaaaa"), 1);
        assertEquals("PLone", PlayerSeriesAction.followedSeries(db.library(), "", "aaaaaaaaaaa"));
        assertEquals("", PlayerSeriesAction.followedSeries(db.library(), "PLnew", "aaaaaaaaaaa"));
        assertEquals("", PlayerSeriesAction.followedSeries(db.library(), "", "bbbbbbbbbbb"));
        db.saveSeries("PLtwo", "Two", "", catalog("aaaaaaaaaaa"), 2);
        assertEquals("", PlayerSeriesAction.followedSeries(db.library(), "", "aaaaaaaaaaa"));
        assertEquals(
                "PLtwo", PlayerSeriesAction.followedSeries(db.library(), "PLtwo", "aaaaaaaaaaa"));
    }

    @Test
    public void followingFromPlayerStartsAtCurrentEpisodeWithoutInventingProgress() {
        db.saveSeries(
                "PLplayer", "Player", "", catalog("aaaaaaaaaaa", "bbbbbbbbbbb"), "bbbbbbbbbbb", 1);
        Series series = db.series("PLplayer");
        assertEquals("bbbbbbbbbbb", series.cursorId);
        assertEquals(2, series.cursorOrdinal);
        assertTrue(series.startHere);
        try (android.database.Cursor rows =
                db.getReadableDatabase().rawQuery("SELECT COUNT(*) FROM video_progress", null)) {
            assertTrue(rows.moveToFirst());
            assertEquals(0, rows.getInt(0));
        }
    }

    @Test
    public void unrelatedPlayerVideoDoesNotCreateASeries() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        db.saveSeries(
                                "PLwrong", "Wrong", "", catalog("aaaaaaaaaaa"), "bbbbbbbbbbb", 1));
        assertTrue(db.library().isEmpty());
    }

    @Test
    public void durableProgressManualOverridesBulkUndoAndSharedCatalogs() {
        db.saveSeries("PLone", "One", "", catalog("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), 1);
        db.saveSeries("PLtwo", "Two", "", catalog("aaaaaaaaaaa"), 1);
        tick("aaaaaaaaaaa", 95000, true);
        db.mark("aaaaaaaaaaa", TrackerModels.Override.UNWATCHED, 2);
        tick("aaaaaaaaaaa", 98000, true);
        assertFalse(db.series("PLtwo").progress("aaaaaaaaaaa").watched());
        TrackerRepository.Undo undo = db.markThrough("PLone", 1, 2, 3);
        assertTrue(db.progress("aaaaaaaaaaa").watched());
        db.mark("bbbbbbbbbbb", TrackerModels.Override.UNWATCHED, 4);
        tick("aaaaaaaaaaa", 0, true);
        db.undo(undo, 5);
        assertEquals(TrackerModels.Override.UNWATCHED, db.progress("aaaaaaaaaaa").override);
        assertEquals(TrackerModels.Override.UNWATCHED, db.progress("bbbbbbbbbbb").override);
        assertEquals(0, db.progress("aaaaaaaaaaa").positionMs);
        assertTrue(db.progress("aaaaaaaaaaa").autoCompleted);
        db.close();
        db = new TrackerRepository(context, file);
        assertEquals(TrackerModels.Override.UNWATCHED, db.progress("aaaaaaaaaaa").override);
        db.mark("aaaaaaaaaaa", TrackerModels.Override.AUTO, 6);
        assertTrue(db.series("PLtwo").progress("aaaaaaaaaaa").watched());
    }

    @Test
    public void forwardCursorDoesNotWrapAndExplicitSelectionCanMoveBack() {
        db.saveSeries("PLone", "One", "", catalog("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), 1);
        tick("bbbbbbbbbbb", 20000, false);
        tick("aaaaaaaaaaa", 40000, false);
        assertEquals(2, db.series("PLone").cursorOrdinal);
        db.mark("bbbbbbbbbbb", TrackerModels.Override.WATCHED, 2);
        assertEquals(3, ResumePlanner.plan(db.series("PLone")).ordinal);
        db.mark("ccccccccccc", TrackerModels.Override.WATCHED, 3);
        assertEquals(ResumePlanner.Kind.CAUGHT_UP, ResumePlanner.plan(db.series("PLone")).kind);
        db.select("PLone", 1, 1, "aaaaaaaaaaa", true);
        assertEquals(1, db.series("PLone").cursorOrdinal);
        assertEquals(0, db.progress("aaaaaaaaaaa").positionMs);
    }

    @Test
    public void refreshRelocatesByVideoAndDoesNotGuessDuplicateOccurrences() {
        db.saveSeries("PLone", "One", "", catalog("aaaaaaaaaaa", "bbbbbbbbbbb"), 1);
        tick("bbbbbbbbbbb", 40000, false);
        db.publish(db.beginRefresh("PLone"), catalog("bbbbbbbbbbb", "aaaaaaaaaaa"), 2);
        assertEquals(1, db.series("PLone").cursorOrdinal);
        db.publish(
                db.beginRefresh("PLone"), catalog("bbbbbbbbbbb", "aaaaaaaaaaa", "bbbbbbbbbbb"), 3);
        assertEquals(ResumePlanner.Kind.CHOOSE, ResumePlanner.plan(db.series("PLone")).kind);
        tick("bbbbbbbbbbb", 50000, false);
        assertEquals(-1, db.series("PLone").cursorOrdinal);
        db.select("PLone", 3, 3, "bbbbbbbbbbb", false);
        assertEquals(3, ResumePlanner.plan(db.series("PLone")).ordinal);
        db.publish(db.beginRefresh("PLone"), catalog("aaaaaaaaaaa"), 4);
        assertEquals("bbbbbbbbbbb", db.series("PLone").bookmarkId);
        assertEquals(ResumePlanner.Kind.CHOOSE, ResumePlanner.plan(db.series("PLone")).kind);
    }

    @Test
    public void removedAutomaticCursorRetainsExplicitVideoLink() {
        db.saveSeries("PLone", "One", "", catalog("aaaaaaaaaaa", "bbbbbbbbbbb"), 1);
        tick("bbbbbbbbbbb", 40000, false);
        db.publish(db.beginRefresh("PLone"), catalog("aaaaaaaaaaa"), 2);
        assertEquals("bbbbbbbbbbb", db.series("PLone").bookmarkId);
        assertEquals(40000, db.series("PLone").progress("bbbbbbbbbbb").positionMs);
        assertEquals(ResumePlanner.Kind.CHOOSE, ResumePlanner.plan(db.series("PLone")).kind);
    }

    @Test
    public void failedAndStaleRefreshesKeepCompleteCatalogAndCannotResurrect() {
        db.saveSeries("PLone", "One", "", catalog("aaaaaaaaaaa"), 1);
        TrackerRepository.FetchTicket old = db.beginRefresh("PLone"),
                fresh = db.beginRefresh("PLone");
        assertFalse(db.publish(old, catalog("bbbbbbbbbbb"), 2));
        db.fetchFailed(fresh, "HTTP 429");
        assertEquals(1, db.series("PLone").revision);
        assertEquals("aaaaaaaaaaa", db.series("PLone").episodes.get(0).videoId);
        old = db.beginRefresh("PLone");
        db.remove("PLone");
        db.saveSeries("PLone", "New", "", catalog("ccccccccccc"), 3);
        assertFalse(db.publish(old, catalog("bbbbbbbbbbb"), 4));
        assertEquals("ccccccccccc", db.series("PLone").episodes.get(0).videoId);
    }

    @Test
    public void clearEpochRejectsQueuedSnapshotsAndOldUndo() {
        db.saveSeries("PLone", "One", "aaaaaaaaaaa", catalog("aaaaaaaaaaa"), 1);
        String old = db.historyEpoch();
        TrackerRepository.Undo undo = db.mark("aaaaaaaaaaa", TrackerModels.Override.WATCHED, 2);
        db.clearHistory("new-epoch");
        assertFalse(
                db.checkpoint(
                        new PlaybackReducer.Snapshot("aaaaaaaaaaa", 1, 1, 99999, 100000, true, 9),
                        old));
        db.undo(undo, 3);
        assertEquals(0, db.progress("aaaaaaaaaaa").positionMs);
        assertEquals(TrackerModels.Override.AUTO, db.progress("aaaaaaaaaaa").override);
        assertEquals("", db.series("PLone").bookmarkId);
        assertEquals(1, db.series("PLone").episodes.size());
        tick("aaaaaaaaaaa", 10000, false);
        assertEquals(10000, db.progress("aaaaaaaaaaa").positionMs);
    }

    @Test
    public void failedWriteRollsBackAndNewerSchemaIsNeverDeleted() {
        db.saveSeries("PLone", "One", "", catalog("aaaaaaaaaaa"), 1);
        tick("aaaaaaaaaaa", 12000, false);
        db.getWritableDatabase()
                .execSQL(
                        "CREATE TRIGGER fail_update BEFORE UPDATE ON video_progress BEGIN SELECT"
                                + " RAISE(ABORT,'injected failure'); END");
        assertThrows(SQLiteException.class, () -> tick("aaaaaaaaaaa", 99000, true));
        assertEquals(12000, db.progress("aaaaaaaaaaa").positionMs);
        db.getWritableDatabase().execSQL("DROP TRIGGER fail_update");
        db.getWritableDatabase().setVersion(2);
        db.close();
        db = new TrackerRepository(context, file);
        assertThrows(SQLiteException.class, () -> db.getReadableDatabase());
        db.close();
        try (SQLiteDatabase raw =
                SQLiteDatabase.openDatabase(
                        context.getDatabasePath(file).toString(),
                        null,
                        SQLiteDatabase.OPEN_READWRITE)) {
            assertEquals(2, raw.getVersion());
            try (android.database.Cursor c =
                    raw.rawQuery("SELECT position_ms FROM video_progress", null)) {
                assertTrue(c.moveToFirst());
                assertEquals(12000, c.getLong(0));
            }
            raw.setVersion(1);
        }
    }

    @Test
    public void fiveThousandEntriesAndRetentionProtectSavedReferences() {
        List<Episode> rows = new ArrayList<>();
        for (int i = 0; i < 5000; i++)
            rows.add(
                    new Episode(
                            i + 1,
                            String.format(Locale.ROOT, "%011d", i),
                            "Episode " + i,
                            100000,
                            true));
        db.saveSeries("PLbig", "Large", "", new CatalogClient.Catalog("Large", rows), 1);
        long now = 100L * 24 * 3600000;
        SQLiteDatabase sql = db.getWritableDatabase();
        sql.beginTransaction();
        try {
            for (int i = 0; i < 2100; i++)
                sql.execSQL(
                        "INSERT INTO video_progress(video_id,played_at) VALUES (?,?)",
                        new Object[] {"extra" + i, now + i});
            sql.execSQL(
                    "INSERT INTO video_progress(video_id,played_at) VALUES"
                            + " ('00000000000',1),('expired',1)");
            sql.setTransactionSuccessful();
        } finally {
            sql.endTransaction();
        }
        db.prune(now);
        assertEquals(5000, db.series("PLbig").episodes.size());
        try (android.database.Cursor c =
                sql.rawQuery("SELECT COUNT(*) FROM video_progress", null)) {
            c.moveToFirst();
            assertEquals(1, c.getInt(0));
        }
        assertEquals(1, db.progress("00000000000").playedAt);
        assertEquals(0, db.progress("extra0").playedAt);
    }

    @Test
    public void ignoresUnfollowedVideosAndRejectsCheckpointsAfterRemoval() {
        String video = "aaaaaaaaaaa";
        PlaybackReducer.Snapshot tick =
                new PlaybackReducer.Snapshot(video, 1, 1, 12000, 100000, false, 10);
        assertFalse(db.checkpoint(tick, db.historyEpoch()));
        assertEquals(0, db.progress(video).positionMs);
        db.saveSeries("PLone", "One", "", catalog(video), 1);
        assertTrue(db.checkpoint(tick, db.historyEpoch()));
        db.remove("PLone");
        assertFalse(db.checkpoint(tick, db.historyEpoch()));
        assertEquals(0, db.progress(video).positionMs);
        assertTrue(db.trackedVideoIds().isEmpty());
    }

    @Test
    public void removalPreservesSharedVideosAndExplicitBookmarks() {
        db.saveSeries("PLone", "One", "", catalog("aaaaaaaaaaa", "bbbbbbbbbbb"), 1);
        db.saveSeries("PLtwo", "Two", "aaaaaaaaaaa", null, 1);
        tick("aaaaaaaaaaa", 20000, false);
        tick("bbbbbbbbbbb", 30000, false);
        db.remove("PLone");
        assertEquals(20000, db.progress("aaaaaaaaaaa").positionMs);
        assertEquals(0, db.progress("bbbbbbbbbbb").positionMs);
        assertEquals(Collections.singleton("aaaaaaaaaaa"), db.trackedVideoIds());
    }

    @Test
    public void startHereIsDurableAndDoesNotInventHistoryOrLaunchPlayback() {
        db.saveSeries("PLone", "One", "", catalog("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), 1);
        tick("aaaaaaaaaaa", 12000, false);
        db.mark("bbbbbbbbbbb", TrackerModels.Override.WATCHED, 2);
        db.startHere("PLone", 1, 2, "bbbbbbbbbbb", 3);
        assertEquals(12000, db.progress("aaaaaaaaaaa").positionMs);
        assertFalse(db.progress("aaaaaaaaaaa").watched());
        assertTrue(db.progress("bbbbbbbbbbb").watched());
        assertEquals(0, db.progress("ccccccccccc").playedAt);
        db.close();
        db = new TrackerRepository(context, file);
        assertTrue(db.series("PLone").startHere);
        assertEquals("bbbbbbbbbbb", ResumePlanner.plan(db.series("PLone")).videoId);
        assertEquals(0, ResumePlanner.plan(db.series("PLone")).positionMs);
        // A queued checkpoint for a different episode cannot move the explicit choice.
        tick("ccccccccccc", 15000, false);
        assertEquals(2, ResumePlanner.plan(db.series("PLone")).ordinal);
        db.select("PLone", 1, 2, "bbbbbbbbbbb", false);
        assertFalse(db.series("PLone").startHere);
        assertEquals(3, ResumePlanner.plan(db.series("PLone")).ordinal);
    }

    @Test
    public void startHereSurvivesReorderAndRejectsUnavailableOrStaleSelection() {
        db.saveSeries("PLone", "One", "", catalog("aaaaaaaaaaa", "bbbbbbbbbbb", ""), 1);
        assertThrows(IllegalStateException.class, () -> db.startHere("PLone", 1, 3, "", 2));
        db.startHere("PLone", 1, 2, "bbbbbbbbbbb", 2);
        db.publish(db.beginRefresh("PLone"), catalog("bbbbbbbbbbb", "aaaaaaaaaaa"), 3);
        assertEquals(1, ResumePlanner.plan(db.series("PLone")).ordinal);
        assertThrows(
                IllegalStateException.class, () -> db.startHere("PLone", 1, 2, "aaaaaaaaaaa", 4));
        db.clearHistory("cleared");
        assertFalse(db.series("PLone").startHere);
        db.startHere("PLone", 2, 1, "bbbbbbbbbbb", 5);
        db.remove("PLone");
        db.saveSeries("PLone", "Again", "", catalog("bbbbbbbbbbb"), 6);
        assertFalse(db.series("PLone").startHere);
    }

    @Test
    public void quietRefreshCountsOnlyNewAvailableVideosAndAcknowledgesDurably() {
        db.saveSeries("PLone", "One", "", catalog("aaaaaaaaaaa", "bbbbbbbbbbb"), 1);
        assertEquals(0, db.series("PLone").newEpisodeCount);
        tick("bbbbbbbbbbb", 99000, true);
        db.publish(db.beginRefresh("PLone"), catalog("bbbbbbbbbbb", "aaaaaaaaaaa"), 2);
        assertEquals(0, db.series("PLone").newEpisodeCount);
        db.publish(
                db.beginRefresh("PLone"),
                catalog("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc", "ccccccccccc", ""),
                3);
        assertEquals(1, db.series("PLone").newEpisodeCount);
        assertEquals("ccccccccccc", ResumePlanner.plan(db.series("PLone")).videoId);
        db.close();
        db = new TrackerRepository(context, file);
        assertEquals(1, db.series("PLone").newEpisodeCount);
        db.fetchFailed(db.beginRefresh("PLone"), "Offline");
        assertEquals(1, db.series("PLone").newEpisodeCount);
        db.acknowledgeEpisodes("PLone");
        assertEquals(0, db.series("PLone").newEpisodeCount);
        db.publish(
                db.beginRefresh("PLone"),
                catalog("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc", "ddddddddddd"),
                4);
        assertEquals(1, db.series("PLone").newEpisodeCount);
        db.mark("ddddddddddd", TrackerModels.Override.WATCHED, 5);
        assertEquals(0, db.series("PLone").newEpisodeCount);
        db.remove("PLone");
        db.saveSeries("PLone", "Again", "", catalog("ddddddddddd"), 6);
        assertEquals(0, db.series("PLone").newEpisodeCount);
    }

    @Test
    public void bulkMarkAfterStartHereAdvancesWithoutMarkingOnSelection() {
        db.saveSeries("PLone", "One", "", catalog("aaaaaaaaaaa", "bbbbbbbbbbb", "ccccccccccc"), 1);
        db.startHere("PLone", 1, 2, "bbbbbbbbbbb", 2);
        assertEquals(0, db.series("PLone").watchedCount());
        TrackerRepository.Undo undo = db.markThrough("PLone", 1, 2, 3);
        assertEquals(3, ResumePlanner.plan(db.series("PLone")).ordinal);
        db.undo(undo, 4);
        assertEquals(0, db.series("PLone").watchedCount());
        assertEquals(2, ResumePlanner.plan(db.series("PLone")).ordinal);
    }

    @Test
    public void quietRefreshBackoffSurvivesRestartAndRemovalClearsIt() {
        db.saveSeries("PLone", "One", "", catalog("aaaaaaaaaaa"), 1);
        long now = CatalogRefreshPolicy.STALE_MS * 2;
        db.refreshAttempt("PLone", now);
        db.refreshRetry("PLone", now + CatalogRefreshPolicy.RETRY_MS);
        db.close();
        db = new TrackerRepository(context, file);
        assertFalse(
                CatalogRefreshPolicy.due(
                        1, db.lastRefreshAttempt("PLone"), db.refreshRetryAt("PLone"), now + 1));
        db.remove("PLone");
        db.saveSeries("PLone", "Again", "", catalog("aaaaaaaaaaa"), 1);
        assertEquals(0, db.lastRefreshAttempt("PLone"));
        assertEquals(0, db.refreshRetryAt("PLone"));
    }

    NativeProgressSync.Result remote(String id, long pos, long time, boolean completed) {
        NativeProgressSync.Result result = new NativeProgressSync.Result();
        result.account = "test-account";
        result.rows.put(id, new NativeHistoryPage.Row(id, completed ? 100 : 50, pos));
        result.observedAt.put(id, time);
        result.durations.put(id, 100000L);
        return result;
    }

    @Test
    public void remoteResumeMovesSeriesToNewerEpisodeAndSurvivesReopen() {
        db.saveSeries("p", "Series", "", catalog("a", "b", "c"), 1);
        tick("a", 5000, false);
        assertEquals(
                1,
                db.mergeRemote(
                        db.historyEpoch(),
                        db.manualRevision(),
                        db.library(),
                        remote("b", 50002, 100000, false),
                        100001));
        assertEquals("b", ResumePlanner.plan(db.series("p")).videoId);
        assertEquals(50002, ResumePlanner.plan(db.series("p")).positionMs);
        assertFalse(
                db.checkpoint(
                        new PlaybackReducer.Snapshot("b", 1, 1, 1234, 100000, false, 90000),
                        db.historyEpoch()));
        db.close();
        db = new TrackerRepository(context, file);
        assertEquals(50002, ResumePlanner.plan(db.series("p")).positionMs);
    }

    @Test
    public void remoteCompletedEpisodeContinuesAtNextAndMissingRowsStayUnchanged() {
        db.saveSeries("p", "Series", "", catalog("a", "b", "c"), 1);
        tick("a", 5000, false);
        db.mergeRemote(
                db.historyEpoch(),
                db.manualRevision(),
                db.library(),
                remote("b", 100000, 100000, true),
                100001);
        assertTrue(db.progress("b").watched());
        assertFalse(db.progress("a").watched());
        assertEquals("c", ResumePlanner.plan(db.series("p")).videoId);
    }

    @Test
    public void remoteCannotOverwriteManualChoiceOrExplicitStartHere() {
        db.saveSeries("p", "Series", "", catalog("a", "b", "c"), 1);
        db.mark("b", TrackerModels.Override.UNWATCHED, 2);
        db.startHere("p", 1, 1, "a", 3);
        assertEquals(
                0,
                db.mergeRemote(
                        db.historyEpoch(),
                        db.manualRevision(),
                        db.library(),
                        remote("b", 100000, 100000, true),
                        100001));
        db.mergeRemote(
                db.historyEpoch(),
                db.manualRevision(),
                db.library(),
                remote("c", 50000, 100000, false),
                100001);
        assertEquals("a", ResumePlanner.plan(db.series("p")).videoId);
    }

    @Test
    public void queuedSyncCannotRestoreClearedOrManuallyChangedProgress() {
        db.saveSeries("p", "Series", "", catalog("a", "b"), 1);
        String epoch = db.historyEpoch();
        long revision = db.manualRevision();
        List<Series> before = db.library();
        db.mark("a", TrackerModels.Override.WATCHED, 2);
        assertEquals(
                0,
                db.mergeRemote(epoch, revision, before, remote("b", 50000, 100000, false), 100001));
        assertEquals(
                0,
                db.mergeRemote(
                        "old-epoch",
                        db.manualRevision(),
                        before,
                        remote("b", 50000, 100000, false),
                        100001));
        assertEquals(0, db.progress("b").positionMs);
        assertEquals(
                0,
                db.mergeRemote(
                        db.historyEpoch(),
                        db.manualRevision(),
                        before,
                        remote("unrelated", 50000, 100000, false),
                        100001));
    }

    @Test
    public void unchangedNativeHistoryDoesNotRestoreLocalClearEvenAfterRestart() {
        db.saveSeries("p", "Series", "", catalog("a", "b"), 1);
        db.mergeRemote(
                db.historyEpoch(),
                db.manualRevision(),
                db.library(),
                remote("b", 50000, 100000, false),
                100001);
        db.clearHistory("cleared");
        db.close();
        db = new TrackerRepository(context, file);
        db.mergeRemote(
                db.historyEpoch(),
                db.manualRevision(),
                db.library(),
                remote("b", 50000, 110000, false),
                110001);
        assertEquals(0, db.progress("b").positionMs);
        db.mergeRemote(
                db.historyEpoch(),
                db.manualRevision(),
                db.library(),
                remote("b", 70000, 120000, false),
                120001);
        assertEquals(70000, db.progress("b").positionMs);
    }

    @Test
    public void firstSnapshotAfterClearSeedsBaselineWithoutRestoringOldHistory() {
        db.saveSeries("p", "Series", "", catalog("a", "b"), 1);
        db.clearHistory("cleared");
        db.mergeRemote(
                db.historyEpoch(),
                db.manualRevision(),
                db.library(),
                remote("b", 50000, 100000, false),
                100001);
        assertEquals(0, db.progress("b").positionMs);
    }

    @Test
    public void olderChangedRowCannotMoveCursorPastUnchangedNewestHistoryRow() {
        db.saveSeries("p", "Series", "", catalog("a", "b", "c"), 1);
        db.mergeRemote(
                db.historyEpoch(),
                db.manualRevision(),
                db.library(),
                remote("c", 50000, 100000, false),
                100001);
        NativeProgressSync.Result result = remote("c", 50000, 110000, false);
        result.rows.put("a", new NativeHistoryPage.Row("a", 100, 100000L));
        result.observedAt.put("a", 110000L);
        result.durations.put("a", 100000L);
        db.mergeRemote(db.historyEpoch(), db.manualRevision(), db.library(), result, 110001);
        assertEquals("c", db.series("p").cursorId);
    }

    @Test
    public void clearDoesNotSuppressNewlyFollowedSeriesFirstNativeObservation() {
        db.saveSeries("p", "Series", "", catalog("a"), 1);
        db.clearHistory("cleared");
        db.saveSeries("q", "New series", "", catalog("b"), 2);
        db.mergeRemote(
                db.historyEpoch(),
                db.manualRevision(),
                db.library(),
                remote("b", 50000, 100000, false),
                100001);
        assertEquals(50000, db.progress("b").positionMs);
    }
}

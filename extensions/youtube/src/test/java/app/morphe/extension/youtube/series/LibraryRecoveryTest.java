package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.*;

import static org.junit.Assert.*;

import android.content.Context;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class LibraryRecoveryTest {
    static final String A = "aaaaaaaaaaa", B = "bbbbbbbbbbb", C = "ccccccccccc", D = "ddddddddddd";
    Context context;
    TrackerRepository db, other;
    String file, otherFile;

    @Before
    public void setup() {
        context = RuntimeEnvironment.getApplication();
        file = "recovery-" + UUID.randomUUID() + ".db";
        otherFile = file + "-other";
        db = new TrackerRepository(context, file);
        other = new TrackerRepository(context, otherFile);
    }

    @After
    public void close() {
        db.close();
        other.close();
        context.deleteDatabase(file);
        context.deleteDatabase(otherFile);
    }

    void save(String id, String... videos) {
        db.saveSeries(id, id, "", TrackerRepositoryTest.catalog(videos), 1);
    }

    void tick(String video, long position, long at) {
        assertTrue(
                db.checkpoint(
                        new PlaybackReducer.Snapshot(video, 1, 1, position, 100000, false, at),
                        db.historyEpoch()));
    }

    @Test
    public void backupRestoresCatalogOptionsPositionsAndManualChoicesAcrossRestart() {
        save("PLone", A, B, B, C);
        tick(B, 42000, 2);
        db.seriesOptions("PLone", 1, true, true);
        Series s = db.series("PLone");
        db.startHere(s.id, s.revision, 3, B, 3);
        db.mark(A, TrackerModels.Override.WATCHED, 4);
        db.getWritableDatabase()
                .execSQL(
                        "INSERT INTO meta VALUES"
                                + " ('native_seen:aaaaaaaaaaa','private-account|snapshot|999')");
        String json = db.backup();
        assertFalse(json.contains("private-account"));
        assertFalse(json.contains("native_seen"));
        LibraryBackup.Data data = LibraryBackup.decode(json);
        assertEquals(1, other.restoreMissing(data, 100));
        other.close();
        other = new TrackerRepository(context, otherFile);
        Series restored = other.series("PLone");
        assertEquals(4, restored.episodes.size());
        assertTrue(restored.reverseOrder);
        assertTrue(restored.hideWatched);
        assertTrue(restored.startHere);
        assertEquals(3, restored.cursorOrdinal);
        assertEquals(42000, other.progress(B).positionMs);
        assertTrue(other.progress(A).watched());
        assertEquals(
                0,
                other.getReadableDatabase()
                        .compileStatement("SELECT COUNT(*) FROM meta WHERE key LIKE 'native_%'")
                        .simpleQueryForLong());
        NativeProgressSync.Result remote = new NativeProgressSync.Result();
        remote.account = "new-phone";
        remote.rows.put(B, new NativeHistoryPage.Row(B, 60, 60000L));
        remote.observedAt.put(B, 200L);
        remote.durations.put(B, 100000L);
        assertEquals(
                1,
                other.mergeRemote(
                        other.historyEpoch(),
                        other.manualRevision(),
                        other.library(),
                        remote,
                        201));
        assertEquals(60000, other.progress(B).positionMs);
    }

    @Test
    public void restoreAddsMissingSeriesWithoutOverwritingExistingSeriesOrSharedProgress() {
        save("PLone", A, B);
        save("PLtwo", B, C);
        tick(B, 40000, 2);
        LibraryBackup.Data data = LibraryBackup.decode(db.backup());
        other.saveSeries("PLone", "Existing title", "", TrackerRepositoryTest.catalog(A, B), 3);
        other.checkpoint(
                new PlaybackReducer.Snapshot(B, 1, 1, 75000, 100000, false, 4),
                other.historyEpoch());
        assertEquals(1, other.missingSeries(data));
        assertEquals(1, other.restoreMissing(data, 100));
        assertEquals("Existing title", other.series("PLone").name);
        assertEquals(75000, other.progress(B).positionMs);
        assertEquals(0, other.restoreMissing(data, 100));
    }

    @Test
    public void invalidBackupsAreRejectedBeforeAnyMutation() throws Exception {
        save("PLone", A, B);
        String text = db.backup();
        org.json.JSONObject doc = new org.json.JSONObject(text);
        doc.put("version", 2);
        assertThrows(IllegalArgumentException.class, () -> LibraryBackup.decode(doc.toString()));
        org.json.JSONObject duplicate = new org.json.JSONObject(text);
        duplicate.getJSONArray("series").put(duplicate.getJSONArray("series").get(0));
        assertThrows(
                IllegalArgumentException.class, () -> LibraryBackup.decode(duplicate.toString()));
        org.json.JSONObject invalid = new org.json.JSONObject(text);
        invalid.getJSONArray("series")
                .getJSONObject(0)
                .getJSONArray("episodes")
                .getJSONObject(0)
                .put("id", "../../other");
        assertThrows(
                IllegalArgumentException.class, () -> LibraryBackup.decode(invalid.toString()));
        assertThrows(
                IllegalArgumentException.class,
                () -> LibraryBackup.decode("x".repeat(LibraryBackup.MAX_CHARS + 1)));
        assertEquals(0, other.library().size());
        assertEquals(1, db.library().size());
    }

    @Test
    public void undoSingleMarkPreservesLaterPlaybackAndLaterManualEdits() {
        save("PLone", A, B);
        tick(A, 10000, 2);
        TrackerRepository.Undo undo = db.mark(A, TrackerModels.Override.WATCHED, 3);
        tick(A, 20000, 4);
        db.undo(undo, 5);
        assertEquals(20000, db.progress(A).positionMs);
        assertEquals(TrackerModels.Override.AUTO, db.progress(A).override);
        undo = db.mark(A, TrackerModels.Override.WATCHED, 6);
        db.mark(A, TrackerModels.Override.UNWATCHED, 7);
        db.undo(undo, 8);
        assertEquals(TrackerModels.Override.UNWATCHED, db.progress(A).override);
    }

    @Test
    public void undoStartHereRestoresCursorButNeverOverwritesAnotherSelection() {
        save("PLone", A, B, C);
        db.startHere("PLone", 1, 1, A, 2);
        Series before = db.series("PLone");
        TrackerRepository.Undo undo = db.startHereWithUndo(before, before.episodes.get(2), 3);
        db.undo(undo, 4);
        assertEquals(A, db.series("PLone").cursorId);
        assertTrue(db.series("PLone").startHere);
        before = db.series("PLone");
        undo = db.startHereWithUndo(before, before.episodes.get(2), 5);
        db.select("PLone", 1, 2, B, false);
        db.undo(undo, 6);
        assertEquals(B, db.series("PLone").cursorId);
    }

    @Test
    public void undoWatchedRestoresStartHereWhenNoNewNavigationOccurred() {
        save("PLone", A, B);
        db.startHere("PLone", 1, 1, A, 2);
        TrackerRepository.Undo undo = db.mark(A, TrackerModels.Override.WATCHED, 3);
        assertFalse(db.series("PLone").startHere);
        db.undo(undo, 4);
        assertTrue(db.series("PLone").startHere);
    }

    @Test
    public void undoRemovalRestoresOnlyMissingStateAndCannotUndoClear() {
        save("PLone", A, B);
        save("PLtwo", B, C);
        tick(A, 10000, 2);
        tick(B, 20000, 3);
        db.seriesOptions("PLone", 1, true, true);
        TrackerRepository.Undo undo = db.removeWithUndo("PLone");
        assertEquals(0, db.progress(A).positionMs);
        tick(B, 30000, 4);
        db.undo(undo, 5);
        assertEquals(10000, db.progress(A).positionMs);
        assertEquals(30000, db.progress(B).positionMs);
        assertTrue(db.series("PLone").reverseOrder);
        assertTrue(db.series("PLone").hideWatched);
        undo = db.removeWithUndo("PLone");
        db.clearHistory("cleared");
        db.undo(undo, 6);
        assertEquals(1, db.library().size());
        assertEquals(0, db.progress(B).positionMs);
    }

    @Test
    public void removedCurrentEpisodeSuggestsNearestSurvivingSuccessorWithoutMovingCursor() {
        save("PLone", A, B, C, D);
        db.startHere("PLone", 1, 2, B, 2);
        TrackerRepository.FetchTicket ticket = db.beginRefresh("PLone");
        assertTrue(db.publish(ticket, TrackerRepositoryTest.catalog(A, D), 3));
        Series changed = db.series("PLone");
        assertEquals(B, changed.cursorId);
        assertEquals(D, changed.recoveryId);
        assertEquals(ResumePlanner.Kind.CHOOSE, ResumePlanner.plan(changed).kind);
        db.select("PLone", changed.revision, 2, D, false);
        assertEquals("", db.series("PLone").recoveryId);
    }

    @Test
    public void recoveryFollowsReverseOrderAndDuplicatesDoNotChooseAnOccurrence() {
        save("PLone", A, B, C, D);
        db.seriesOptions("PLone", 1, true, false);
        db.startHere("PLone", 2, 3, C, 2);
        TrackerRepository.FetchTicket ticket = db.beginRefresh("PLone");
        db.publish(ticket, TrackerRepositoryTest.catalog(A, B, D), 3);
        assertEquals(B, db.series("PLone").recoveryId);
        save("PLtwo", A, B);
        db.startHere("PLtwo", 1, 2, B, 2);
        ticket = db.beginRefresh("PLtwo");
        db.publish(ticket, TrackerRepositoryTest.catalog(A, B, B), 3);
        assertEquals(ResumePlanner.Kind.CHOOSE, ResumePlanner.plan(db.series("PLtwo")).kind);
        assertEquals("", db.series("PLtwo").recoveryId);
    }

    @Test
    public void explicitLaunchRejectsAnOlderInFlightRemoteResult() {
        save("PLone", A, B);
        long revision = db.manualRevision();
        List<Series> before = db.library();
        db.select("PLone", 1, 1, A, false);
        NativeProgressSync.Result remote = new NativeProgressSync.Result();
        remote.account = "a";
        remote.rows.put(B, new NativeHistoryPage.Row(B, 50, 50000L));
        remote.observedAt.put(B, 10L);
        remote.durations.put(B, 100000L);
        assertEquals(0, db.mergeRemote(db.historyEpoch(), revision, before, remote, 11));
        assertEquals(A, db.series("PLone").cursorId);
    }

    @Test
    public void playerSuccessorsHonorOrderOccurrencesAvailabilityAndBoundaries() {
        save("PLone", A, B, B, C);
        Series s = db.series("PLone");
        assertEquals(3, ResumePlanner.adjacent(s, B, 2, false).ordinal);
        assertNull(ResumePlanner.adjacent(s, C, 4, false));
        db.seriesOptions(s.id, s.revision, true, false);
        s = db.series(s.id);
        assertEquals(2, ResumePlanner.adjacent(s, B, 3, false).ordinal);
        assertEquals(C, ResumePlanner.adjacent(s, B, 3, true).videoId);
        assertNull(ResumePlanner.adjacent(s, A, 1, false));
    }

    @Test
    public void activePlayerFollowsAnUnambiguousRefreshButRejectsRemovalOrRefollow() {
        save("PLone", A, B, C);
        Series before = db.series("PLone");
        TrackerRepository.FetchTicket ticket = db.beginRefresh("PLone");
        db.publish(ticket, TrackerRepositoryTest.catalog(D, A, B, C), 2);
        assertEquals(3, ResumePlanner.occurrence(before, B, 2, db.series("PLone")));
        ticket = db.beginRefresh("PLone");
        db.publish(ticket, TrackerRepositoryTest.catalog(B, B), 3);
        assertEquals(-1, ResumePlanner.occurrence(before, B, 2, db.series("PLone")));
        db.remove("PLone");
        save("PLone", A, B, C);
        assertEquals(-1, ResumePlanner.occurrence(before, B, 2, db.series("PLone")));
    }
}

package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.*;

import static org.junit.Assert.*;

import org.junit.Test;

public class HistoryMergePolicyTest {
    Progress local(long position, long time, boolean completed) {
        return new Progress(
                "abcdefghijk", position, 300_000, completed, TrackerModels.Override.AUTO, time, 7);
    }

    NativeHistoryPage.Row row(Long position, Integer percent) {
        return new NativeHistoryPage.Row("abcdefghijk", percent, position);
    }

    HistoryMergePolicy.Baseline baseline(NativeHistoryPage.Row row, long at) {
        return new HistoryMergePolicy.Baseline(row.signature(), at);
    }

    Progress merge(
            Progress local,
            NativeHistoryPage.Row row,
            HistoryMergePolicy.Baseline previous,
            long at) {
        return HistoryMergePolicy.merge(local, row, previous, at, 300_000, 92, 30);
    }

    @Test
    public void firstForwardObservationImportsExactNativePoint() {
        assertEquals(
                90_000, merge(local(60_000, 10, false), row(90_000L, 30), null, 20).positionMs);
    }

    @Test
    public void repeatedSnapshotDoesNotBecomeANewWatchAfterPhonePlayback() {
        NativeHistoryPage.Row remote = row(60_000L, 20);
        Progress local = local(90_000, 30, false);
        assertSame(local, merge(local, remote, baseline(remote, 20), 40));
    }

    @Test
    public void initialAndConcurrentBackstepsCannotErasePhoneProgress() {
        Progress local = local(90_000, 30, false);
        assertSame(local, merge(local, row(10_000L, 3), null, 40));
        assertSame(local, merge(local, row(10_000L, 3), baseline(row(60_000L, 20), 20), 40));
        assertSame(local, merge(local, row(100_000L, 33), null, 29));
    }

    @Test
    public void changedRemoteSnapshotCanEstablishARewatch() {
        Progress merged =
                merge(
                        local(290_000, 10, true),
                        row(10_000L, 3),
                        baseline(row(290_000L, 96), 20),
                        30);
        assertEquals(10_000, merged.positionMs);
        assertFalse(merged.watched());
    }

    @Test
    public void smallNativeResumeBackstepKeepsPreciseLocalPoint() {
        Progress local = local(90_000, 10, false);
        assertSame(local, merge(local, row(70_000L, 23), baseline(row(60_000L, 20), 20), 30));
    }

    @Test
    public void percentageNeverInventsAPartialTimestampAndZeroIsNotCompletion() {
        Progress local = local(0, 0, false);
        assertSame(local, merge(local, row(null, 60), null, 30));
        assertSame(local, merge(local, row(0L, null), null, 30));
        assertTrue(merge(local, row(null, 100), null, 30).watched());
    }

    @Test
    public void manualChoicesRemainAuthoritative() {
        for (TrackerModels.Override override :
                new TrackerModels.Override[] {
                    TrackerModels.Override.WATCHED, TrackerModels.Override.UNWATCHED
                }) {
            Progress local = new Progress("abcdefghijk", 1, 300_000, false, override, 10, 7);
            assertSame(local, merge(local, row(290_000L, 96), null, 30));
        }
    }

    @Test
    public void completionUsesConfiguredPercentageAndRemainingSeconds() {
        Progress local = local(0, 0, false);
        assertTrue(
                HistoryMergePolicy.merge(local, row(271_000L, 90), null, 30, 300_000, 99, 30)
                        .watched());
        assertFalse(
                HistoryMergePolicy.merge(local, row(271_000L, 90), null, 30, 300_000, 99, 0)
                        .watched());
    }

    @Test
    public void staleCachedResponseCannotBeatLaterObservedSnapshot() {
        Progress local = local(0, 0, false);
        assertSame(local, merge(local, row(60_000L, 20), baseline(row(90_000L, 30), 50), 40));
    }
}

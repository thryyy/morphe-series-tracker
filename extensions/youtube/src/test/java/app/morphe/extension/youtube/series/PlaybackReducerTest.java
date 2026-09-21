package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

public class PlaybackReducerTest {
    static final String A = "aaaaaaaaaaa", B = "bbbbbbbbbbb";

    private void sample(PlaybackReducer r, String id, long p, long duration, long at) {
        r.sample(id, id, p, duration, false, at, at);
    }

    @Test
    public void transitionsFreezeOutgoingAndWaitForAdvancingIncomingIdentity() {
        List<PlaybackReducer.Snapshot> writes = new ArrayList<>();
        PlaybackReducer r = new PlaybackReducer(writes::add);
        sample(r, A, 5000, 100000, 0);
        sample(r, A, 6000, 100000, 1000);
        r.newVideo(1100);
        sample(r, B, 0, 0, 1101);
        sample(r, B, 0, 0, 1601);
        assertEquals(1, writes.size());
        assertEquals(A, writes.get(0).videoId);
        sample(r, B, 1000, 200000, 2101);
        r.newVideo(2200);
        sample(r, A, 6000, 0, 2300);
        sample(r, A, 7000, 100000, 3300);
        r.flush(3301);
        assertEquals(
                Arrays.asList(A, B, A),
                Arrays.asList(writes.get(0).videoId, writes.get(1).videoId, writes.get(2).videoId));
        assertTrue(writes.get(0).generation < writes.get(2).generation);
    }

    @Test
    public void backwardSeekAndZeroAreRecordedAfterReadiness() {
        List<PlaybackReducer.Snapshot> writes = new ArrayList<>();
        PlaybackReducer r = new PlaybackReducer(writes::add);
        sample(r, A, 5000, 100000, 0);
        sample(r, A, 6000, 100000, 1000);
        sample(r, A, 0, 100000, 2000);
        r.state("PAUSED", 2001);
        assertEquals(0, writes.get(0).positionMs);
    }

    @Test
    public void unknownDurationRetainsOnlyCurrentSessionsDurationAndLateEndDoesNotComplete() {
        List<PlaybackReducer.Snapshot> writes = new ArrayList<>();
        PlaybackReducer r = new PlaybackReducer(writes::add);
        sample(r, A, 5000, 100000, 0);
        sample(r, A, 6000, 100000, 1000);
        sample(r, A, 7000, 0, 6000);
        assertEquals(100000, writes.get(0).durationMs);
        r.newVideo(6001);
        sample(r, B, 0, 0, 6002);
        r.state("ENDED", 6003);
        sample(r, B, 1000, 0, 7002);
        r.flush(7003);
        PlaybackReducer.Snapshot last = writes.get(writes.size() - 1);
        assertEquals(0, last.durationMs);
        assertFalse(last.completed);
    }

    @Test
    public void finiteCompletionIsStickyButGrowingDurationAndShortsAreExcluded() {
        List<PlaybackReducer.Snapshot> writes = new ArrayList<>();
        PlaybackReducer r = new PlaybackReducer(writes::add);
        sample(r, A, 90000, 100000, 0);
        sample(r, A, 91000, 100000, 1000);
        sample(r, A, 95000, 100000, 5000);
        assertTrue(writes.get(0).completed);
        sample(r, A, 0, 100000, 6000);
        r.flush(6001);
        assertTrue(writes.get(writes.size() - 1).completed);
        r.newVideo(7000);
        sample(r, B, 90000, 100000, 8000);
        sample(r, B, 99000, 105000, 12000);
        r.flush(12001);
        assertFalse(writes.get(writes.size() - 1).completed);
        int previous = writes.size();
        r.clear();
        r.sample(A, A, 99000, 100000, true, 15000, 15000);
        r.flush(15001);
        assertEquals(previous, writes.size());
    }

    @Test
    public void oldAcknowledgmentDoesNotDiscardNewerDirtyPositionAndClearDropsIt() {
        List<PlaybackReducer.Snapshot> writes = new ArrayList<>();
        PlaybackReducer r = new PlaybackReducer(writes::add);
        sample(r, A, 1000, 100000, 0);
        sample(r, A, 2000, 100000, 1000);
        r.flush(1001);
        PlaybackReducer.Snapshot first = writes.get(0);
        sample(r, A, 3000, 100000, 2000);
        r.acknowledge(first.generation, first.sequence);
        r.flush(2001);
        assertEquals(3000, writes.get(1).positionMs);
        r.clear();
        r.flush(3000);
        assertEquals(2, writes.size());
    }
}

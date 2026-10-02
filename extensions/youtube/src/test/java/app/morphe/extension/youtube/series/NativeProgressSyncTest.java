package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.*;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

public class NativeProgressSyncTest {
    Series series(String id, String video) {
        return new Series(
                id,
                id,
                "",
                1,
                "",
                -1,
                1,
                "",
                "complete",
                "",
                1,
                1,
                Collections.singletonList(new Episode(1, video, video, 100000, true)),
                Collections.emptyMap());
    }

    NativeHistoryTransport.Page page(String continuation, String video) {
        NativeHistoryPage p = new NativeHistoryPage();
        p.continuation = continuation;
        if (!video.isEmpty()) p.rows.put(video, new NativeHistoryPage.Row(video, 50, 50000L));
        return new NativeHistoryTransport.Page(p, 100);
    }

    @Test
    public void selectedSeriesDoesNotWaitForUnrelatedLibraryEntries() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NativeProgressSync.Result result =
                NativeProgressSync.fetchPages(
                        Arrays.asList(
                                series("PLone", "aaaaaaaaaaa"), series("PLtwo", "bbbbbbbbbbb")),
                        true,
                        "PLone",
                        new NativeProgressSync.Result(),
                        (token, age, remaining) -> {
                            calls.incrementAndGet();
                            assertEquals(10_000, age);
                            assertTrue(remaining <= TimeUnit.MILLISECONDS.toNanos(2500));
                            return page("next", "aaaaaaaaaaa");
                        },
                        () -> 0);
        assertEquals(1, calls.get());
        assertEquals(1, result.rows.size());
        assertFalse(result.durations.containsKey("bbbbbbbbbbb"));
    }

    @Test
    public void selectedSeriesCanBeFoundOnLaterPageWithinSameBudget() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NativeProgressSync.Result result =
                NativeProgressSync.fetchPages(
                        Collections.singletonList(series("PLone", "aaaaaaaaaaa")),
                        true,
                        "PLone",
                        new NativeProgressSync.Result(),
                        (token, age, remaining) ->
                                calls.incrementAndGet() == 1
                                        ? page("next", "bbbbbbbbbbb")
                                        : page("", "aaaaaaaaaaa"),
                        () -> 0);
        assertEquals(2, calls.get());
        assertEquals(1, result.rows.size());
    }

    @Test
    public void deadlineStopsPaginationAndDeletedSelectionSendsNothing() throws Exception {
        AtomicLong clock = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        NativeProgressSync.Result result =
                NativeProgressSync.fetchPages(
                        Collections.singletonList(series("PLone", "aaaaaaaaaaa")),
                        true,
                        "PLone",
                        new NativeProgressSync.Result(),
                        (token, age, remaining) -> {
                            calls.incrementAndGet();
                            clock.set(TimeUnit.SECONDS.toNanos(3));
                            return page("next", "");
                        },
                        clock::get);
        assertFalse(result.complete);
        assertEquals(1, calls.get());
        NativeProgressSync.fetchPages(
                Collections.emptyList(),
                true,
                "gone",
                new NativeProgressSync.Result(),
                (token, age, remaining) -> {
                    fail("No selected series");
                    return null;
                },
                () -> 0);
    }
}

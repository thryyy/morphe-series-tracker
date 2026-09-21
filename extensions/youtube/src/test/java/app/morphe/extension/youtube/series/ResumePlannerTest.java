package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.*;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

public class ResumePlannerTest {
    private Series series(
            List<Episode> rows,
            Map<String, Progress> p,
            String cursor,
            int ordinal,
            String bookmark,
            long revision) {
        return new Series(
                "PLone",
                "One",
                "epoch",
                revision,
                cursor,
                ordinal,
                revision,
                bookmark,
                "complete",
                "",
                1,
                1,
                rows,
                p);
    }

    private Episode e(int ordinal, String id, boolean available) {
        return new Episode(ordinal, id, id, 100000, available);
    }

    private Progress p(String id, long position, boolean watched, long played) {
        return new Progress(id, position, 100000, watched, TrackerModels.Override.AUTO, played, 0);
    }

    @Test
    public void bookmarkAndUnfetchedPlaylistNeverInventSuccessors() {
        assertEquals(
                ResumePlanner.Kind.OPEN_PLAYLIST,
                ResumePlanner.plan(
                                series(
                                        Collections.emptyList(),
                                        Collections.emptyMap(),
                                        "",
                                        -1,
                                        "",
                                        0))
                        .kind);
        ResumePlanner.Plan plan =
                ResumePlanner.plan(
                        series(
                                Collections.emptyList(),
                                Collections.singletonMap("a", p("a", 42000, false, 1)),
                                "",
                                -1,
                                "a",
                                0));
        assertEquals("a", plan.videoId);
        assertEquals(42000, plan.positionMs);
        assertEquals(-1, plan.ordinal);
    }

    @Test
    public void noCursorChoosesRecentUnfinishedThenCatalogOrder() {
        List<Episode> rows = Arrays.asList(e(1, "a", true), e(2, "b", true), e(3, "c", true));
        Map<String, Progress> progress = new HashMap<>();
        progress.put("b", p("b", 40000, false, 100));
        progress.put("c", p("c", 99000, true, 200));
        assertEquals("b", ResumePlanner.plan(series(rows, progress, "", -1, "", 1)).videoId);
        progress.put("a", p("a", 20000, false, 100));
        assertEquals("a", ResumePlanner.plan(series(rows, progress, "", -1, "", 1)).videoId);
    }

    @Test
    public void duplicatesCannotSupplyImplicitRecentCursor() {
        List<Episode> rows = Arrays.asList(e(1, "a", true), e(2, "b", true), e(3, "b", true));
        assertEquals(
                "a",
                ResumePlanner.plan(
                                series(
                                        rows,
                                        Collections.singletonMap("b", p("b", 50000, false, 100)),
                                        "",
                                        -1,
                                        "",
                                        1))
                        .videoId);
    }

    @Test
    public void unknownUnavailableAndEmptyHaveExplicitStates() {
        assertEquals(
                ResumePlanner.Kind.NO_PLAYABLE,
                ResumePlanner.plan(
                                series(
                                        Collections.emptyList(),
                                        Collections.emptyMap(),
                                        "",
                                        -1,
                                        "",
                                        1))
                        .kind);
        assertEquals(
                ResumePlanner.Kind.NO_PLAYABLE,
                ResumePlanner.plan(
                                series(
                                        Collections.singletonList(e(1, "", false)),
                                        Collections.emptyMap(),
                                        "",
                                        -1,
                                        "",
                                        1))
                        .kind);
        assertEquals(
                ResumePlanner.Kind.CHOOSE,
                ResumePlanner.plan(
                                series(
                                        Collections.singletonList(e(1, "a", false)),
                                        Collections.emptyMap(),
                                        "a",
                                        1,
                                        "a",
                                        1))
                        .kind);
    }

    @Test
    public void completedLastEpisodeCatchesUpUntilNewUpload() {
        Map<String, Progress> p = Collections.singletonMap("a", p("a", 95000, true, 1));
        assertEquals(
                ResumePlanner.Kind.CAUGHT_UP,
                ResumePlanner.plan(
                                series(
                                        Collections.singletonList(e(1, "a", true)),
                                        p,
                                        "a",
                                        1,
                                        "a",
                                        1))
                        .kind);
        ResumePlanner.Plan next =
                ResumePlanner.plan(
                        series(Arrays.asList(e(1, "a", true), e(2, "b", true)), p, "a", 1, "a", 2));
        assertEquals(ResumePlanner.Kind.NEXT, next.kind);
        assertEquals("b", next.videoId);
        assertEquals(0, next.positionMs);
    }
}

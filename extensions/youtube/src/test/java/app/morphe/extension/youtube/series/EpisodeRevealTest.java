package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.*;

import static org.junit.Assert.*;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class EpisodeRevealTest {
    private Series series(
            int cursor, boolean reverse, boolean hide, Map<String, Progress> progress) {
        List<Episode> episodes = new ArrayList<>();
        for (int i = 1; i <= 205; i++)
            episodes.add(new Episode(i, "video" + i, "Episode " + i, 100000, i != 2));
        // Two playlist occurrences share progress, but the cursor identifies the right row.
        episodes.set(3, new Episode(4, "video151", "Earlier occurrence", 100000, true));
        return new Series(
                "series",
                "Series",
                "epoch",
                1,
                "video" + cursor,
                cursor,
                1,
                "",
                "complete",
                "",
                1,
                1,
                episodes,
                progress,
                false,
                0,
                reverse,
                hide);
    }

    @Test
    public void entryTargetsExactOccurrenceOnLaterPageInDisplayedOrder() {
        EpisodeReveal reveal = new EpisodeReveal();
        Series s = series(151, false, false, Collections.emptyMap());
        reveal.request();
        int index = reveal.targetIndex(ResumePlanner.plan(s), s.visibleEpisodes());
        assertEquals(149, index); // One unavailable occurrence is excluded.
        assertEquals(1, index / 100);
        assertEquals(49, index % 100);
        Series reversed = series(151, true, false, Collections.emptyMap());
        reveal.request();
        assertEquals(
                54, reveal.targetIndex(ResumePlanner.plan(reversed), reversed.visibleEpisodes()));
    }

    @Test
    public void watchedFilteringAndNextEpisodeUseTheContinuePlan() {
        Map<String, Progress> progress = new HashMap<>();
        progress.put(
                "video151",
                new Progress("video151", 100000, 100000, true, TrackerModels.Override.AUTO, 1, 0));
        Series s = series(151, false, true, progress);
        ResumePlanner.Plan plan = ResumePlanner.plan(s);
        assertEquals(ResumePlanner.Kind.NEXT, plan.kind);
        assertEquals(152, plan.ordinal);
        EpisodeReveal reveal = new EpisodeReveal();
        reveal.request();
        int index = reveal.targetIndex(plan, s.visibleEpisodes());
        assertEquals(148, index); // Unavailable row and both watched duplicates are hidden.
        assertEquals(152, s.visibleEpisodes().get(index).ordinal);
    }

    @Test
    public void missingTargetAndExplicitCancellationDoNotJumpOnRefresh() {
        EpisodeReveal reveal = new EpisodeReveal();
        Series s = series(151, false, false, Collections.emptyMap());
        reveal.request();
        assertEquals(-1, reveal.targetIndex(ResumePlanner.plan(s), Collections.emptyList()));
        assertEquals(-1, reveal.targetIndex(ResumePlanner.plan(s), s.visibleEpisodes()));
        reveal.request();
        reveal.cancel();
        assertEquals(-1, reveal.targetIndex(ResumePlanner.plan(s), s.visibleEpisodes()));
    }

    private ScrollView list(Activity activity) {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < 12; i++)
            rows.addView(new View(activity), new LinearLayout.LayoutParams(320, 80));
        scroll.addView(rows);
        FrameLayout host = new FrameLayout(activity);
        host.addView(scroll, new FrameLayout.LayoutParams(320, 240));
        activity.setContentView(host);
        return scroll;
    }

    private View row(ScrollView scroll, int index) {
        return ((LinearLayout) scroll.getChildAt(0)).getChildAt(index);
    }

    private void draw(ScrollView scroll) {
        scroll.measure(
                View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(240, View.MeasureSpec.EXACTLY));
        scroll.layout(0, 0, 320, 240);
        scroll.getViewTreeObserver().dispatchOnPreDraw();
    }

    @Test
    public void jumpWaitsForMeasuredRowsAndOnlyHappensOnce() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            ScrollView scroll = list(controller.get());
            EpisodeReveal reveal = new EpisodeReveal();
            reveal.request();
            int render = reveal.beginRender();
            reveal.restoreAfterLayout(scroll, 0, render);
            reveal.revealAfterLayout(scroll, row(scroll, 8), render);
            assertEquals(0, scroll.getScrollY());
            draw(scroll);
            assertEquals(640, scroll.getScrollY());
            scroll.scrollTo(0, 160);
            render = reveal.beginRender();
            reveal.restoreAfterLayout(scroll, scroll.getScrollY(), render);
            reveal.revealAfterLayout(scroll, row(scroll, 8), render);
            draw(scroll);
            assertEquals("Refresh must preserve manual browsing", 160, scroll.getScrollY());
        }
    }

    @Test
    public void newerRenderWinsAndCancelledNavigationCannotScroll() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            ScrollView scroll = list(controller.get());
            EpisodeReveal reveal = new EpisodeReveal();
            reveal.request();
            reveal.revealAfterLayout(scroll, row(scroll, 8), reveal.beginRender());
            reveal.revealAfterLayout(scroll, row(scroll, 3), reveal.beginRender());
            draw(scroll);
            assertEquals(240, scroll.getScrollY());
            reveal.request();
            reveal.revealAfterLayout(scroll, row(scroll, 8), reveal.beginRender());
            reveal.cancel();
            draw(scroll);
            assertEquals(240, scroll.getScrollY());
        }
    }
}

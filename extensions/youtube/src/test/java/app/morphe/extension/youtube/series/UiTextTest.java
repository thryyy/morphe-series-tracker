package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.junit.Test;

public class UiTextTest {
    private static TrackerModels.Episode episode(String title, boolean available) {
        return new TrackerModels.Episode(1, "aaaaaaaaaaa", title, 0, available);
    }

    @Test
    public void missingAndPreviouslyCachedFallbacksUseResourceKeys() {
        for (String title : new String[] {"", "Untitled episode", "Unavailable episode"}) {
            assertEquals(
                    "series_tracker_untitled_episode",
                    UiText.episodeTitleValue(episode(title, true)));
            assertEquals(
                    "series_tracker_unavailable_episode",
                    UiText.episodeTitleValue(episode(title, false)));
        }
    }

    @Test
    public void suppliedTitlesArePreserved() {
        for (String title : new String[] {"Episode 1", "Voyage au Japon", "日本の旅"}) {
            assertEquals(title, UiText.episodeTitleValue(episode(title, true)));
        }
    }
}

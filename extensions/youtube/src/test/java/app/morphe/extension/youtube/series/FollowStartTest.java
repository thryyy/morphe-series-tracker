package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.*;

public class FollowStartTest {
    private PlaybackBridge.Source player(String id, long position) {
        return new PlaybackBridge.Source() {
            public String seriesTrackerVideoId() {
                return id;
            }

            public long seriesTrackerPosition() {
                return position;
            }
        };
    }

    @Test
    public void positionComesFromMatchingPlayerAndRejectsTransitionDuringRead() {
        assertEquals(42000, FollowStart.position(player("current", 42000), "current"));
        assertEquals(0, FollowStart.position(player("current", 0), "current"));
        assertEquals(-1, FollowStart.position(player("other", 42000), "current"));
        assertEquals(-1, FollowStart.position(null, "current"));
        assertEquals(
                -1,
                FollowStart.position(
                        new PlaybackBridge.Source() {
                            boolean changed;

                            public String seriesTrackerVideoId() {
                                return changed ? "next" : "current";
                            }

                            public long seriesTrackerPosition() {
                                changed = true;
                                return 100;
                            }
                        },
                        "current"));
        assertEquals(
                -1,
                FollowStart.position(
                        new PlaybackBridge.Source() {
                            public String seriesTrackerVideoId() {
                                return "current";
                            }

                            public long seriesTrackerPosition() {
                                throw new IllegalStateException();
                            }
                        },
                        "current"));
    }

    @Test
    public void previousEpisodesExcludePrivateSlotsAndStopAtFirstOccurrenceInChosenOrder() {
        List<TrackerModels.Episode> episodes =
                Arrays.asList(
                        new TrackerModels.Episode(1, "a", "A", 100, true),
                        new TrackerModels.Episode(2, "b", "B", 100, true),
                        new TrackerModels.Episode(3, "private", "Private", 0, false),
                        new TrackerModels.Episode(4, "b", "B", 100, true),
                        new TrackerModels.Episode(5, "c", "C", 100, true));
        assertEquals(Collections.singleton("a"), FollowStart.previous(episodes, "b", false));
        assertEquals(Collections.singleton("c"), FollowStart.previous(episodes, "b", true));
        assertTrue(FollowStart.previous(episodes, "a", false).isEmpty());
    }
}

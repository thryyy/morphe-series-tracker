package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import org.junit.Test;

public class PlayerSeriesActionTest {
    @Test
    public void onlySuggestsAnEligiblePlaylistForTheActiveVideo() {
        assertEquals(
                "PLseries", PlayerSeriesAction.playlist("aaaaaaaaaaa", "aaaaaaaaaaa", "PLseries"));
        assertEquals("", PlayerSeriesAction.playlist("aaaaaaaaaaa", "bbbbbbbbbbb", "PLstale"));
        assertEquals("", PlayerSeriesAction.playlist("", "", "PLstale"));
        for (String id : new String[] {"", "RDmix", "ULqueue", "WL", "LL"}) {
            assertEquals("", PlayerSeriesAction.playlist("aaaaaaaaaaa", "aaaaaaaaaaa", id));
        }
    }
}

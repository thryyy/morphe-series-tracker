package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import android.os.Parcel;
import android.view.View;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class HistoryStateTest {
    @Test
    public void recreationRetainsModeSeriesPageAndScroll() {
        HistoryUi.Saved saved = new HistoryUi.Saved(View.BaseSavedState.EMPTY_STATE);
        saved.series = true;
        saved.selected = "PLtest";
        saved.page = 3;
        saved.scroll = 517;
        saved.query = "JápAn";
        Parcel parcel = Parcel.obtain();
        try {
            saved.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            HistoryUi.Saved restored = HistoryUi.Saved.CREATOR.createFromParcel(parcel);
            assertTrue(restored.series);
            assertEquals("PLtest", restored.selected);
            assertEquals(3, restored.page);
            assertEquals(517, restored.scroll);
            assertEquals("JápAn", restored.query);
        } finally {
            parcel.recycle();
        }
    }
}

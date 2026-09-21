package app.morphe.extension.youtube.series;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

@SuppressWarnings("deprecation")
public final class SeriesTrackerPreference extends Preference {
    public SeriesTrackerPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onClick() {
        LibraryDialog.show(getContext());
    }
}

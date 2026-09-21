package app.morphe.extension.youtube.series;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
public class SeriesScrollViewTest {
    /** Models a native header that can consume movement even when the child has no overflow. */
    private static final class Header extends FrameLayout {
        int up, down, stops;

        Header(Context context) {
            super(context);
        }

        @Override
        public boolean onStartNestedScroll(View child, View target, int axes) {
            return (axes & View.SCROLL_AXIS_VERTICAL) != 0;
        }

        @Override
        public void onNestedPreScroll(View target, int dx, int dy, int[] consumed) {
            if (dy > 0) up += dy;
            else down -= dy;
            consumed[1] = dy;
        }

        @Override
        public void onStopNestedScroll(View target) {
            super.onStopNestedScroll(target);
            stops++;
        }
    }

    private void send(View parent, long start, int action, float y, long delay) {
        MotionEvent event = MotionEvent.obtain(start, start + delay, action, 100, y, 0);
        try {
            parent.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    @Test
    public void shortLibraryDragsReachHeaderInBothDirectionsWithoutClickingRows() {
        try (var controller = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = controller.get();
            Header header = new Header(activity);
            SeriesScrollView scroll = new SeriesScrollView(activity);
            Button row = new Button(activity);
            row.setMinHeight(400);
            int[] clicks = {0};
            row.setOnClickListener(view -> clicks[0]++);
            scroll.addView(row, new ScrollView.LayoutParams(300, 400));
            header.addView(scroll, new FrameLayout.LayoutParams(300, 500));
            activity.setContentView(header);
            header.measure(
                    View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY));
            header.layout(0, 0, 300, 500);
            assertFalse(scroll.canScrollVertically(1));
            assertFalse(scroll.canScrollVertically(-1));
            long start = SystemClock.uptimeMillis();
            send(header, start, MotionEvent.ACTION_DOWN, 150, 0);
            send(header, start, MotionEvent.ACTION_UP, 150, 50);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("A normal row tap must still work", 1, clicks[0]);
            assertFalse(scroll.hasNestedScrollingParent());
            start += 100;
            send(header, start, MotionEvent.ACTION_DOWN, 300, 0);
            send(header, start, MotionEvent.ACTION_MOVE, 220, 50);
            send(header, start, MotionEvent.ACTION_MOVE, 120, 100);
            send(header, start, MotionEvent.ACTION_UP, 120, 150);
            assertTrue("Collapsing drag must reach header", header.up > 0);
            assertFalse(scroll.hasNestedScrollingParent());
            send(header, start + 200, MotionEvent.ACTION_DOWN, 100, 0);
            send(header, start + 200, MotionEvent.ACTION_MOVE, 180, 50);
            send(header, start + 200, MotionEvent.ACTION_MOVE, 280, 100);
            send(header, start + 200, MotionEvent.ACTION_UP, 280, 150);
            assertTrue(
                    "Expanding drag must reach header even with no inner scroll range",
                    header.down > 0);
            assertEquals("Drags must not click the row", 1, clicks[0]);
            assertFalse(scroll.hasNestedScrollingParent());
            assertFalse(scroll.canScrollVertically(1));
            assertFalse(scroll.canScrollVertically(-1));
            send(header, start + 500, MotionEvent.ACTION_DOWN, 150, 0);
            send(header, start + 500, MotionEvent.ACTION_CANCEL, 150, 50);
            assertFalse(scroll.hasNestedScrollingParent());
            assertTrue(header.stops >= 4);
        }
    }
}

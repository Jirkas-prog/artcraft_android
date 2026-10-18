package cz.jirkas.craftgestureprobe;

import android.app.Instrumentation;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;

/** QA-only injector for the isolated Android emulator. Never bundled with Craft apps. */
public final class GestureProbe extends Instrumentation {
    private Bundle args;
    private long down;

    @Override public void onCreate(Bundle arguments) { args = arguments; start(); }

    private float number(String key, float fallback) {
        String value = args.getString(key);
        return value == null ? fallback : Float.parseFloat(value);
    }

    private void send(int action, float[][] points) {
        MotionEvent.PointerProperties[] props = new MotionEvent.PointerProperties[points.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[points.length];
        for (int i = 0; i < points.length; i++) {
            props[i] = new MotionEvent.PointerProperties();
            props[i].id = i;
            props[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = points[i][0]; coords[i].y = points[i][1];
            coords[i].pressure = 1; coords[i].size = 0.1f;
        }
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                points.length, props, coords, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        try {
            if (!getUiAutomation().injectInputEvent(event, true)) throw new IllegalStateException("Input injection rejected");
        } finally { event.recycle(); }
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            String mode = args.getString("mode", "swipe");
            float x = number("x", 800), y = number("y", 550);
            float dx = number("dx", -200), dy = number("dy", -180);
            float span = number("span", 240), scale = number("scale", 1.5f);
            boolean pinch = mode.equals("pinch");
            float[][] points = pinch ? new float[][]{{x - span/2, y}, {x + span/2, y}} : new float[][]{{x, y}};
            down = SystemClock.uptimeMillis();
            send(MotionEvent.ACTION_DOWN, new float[][]{points[0]});
            SystemClock.sleep(30);
            if (pinch) send(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), points);
            if (mode.equals("hold")) SystemClock.sleep(650);
            int steps = mode.equals("tap") ? 0 : 30;
            for (int i = 1; i <= steps; i++) {
                float f = i / (float) steps;
                if (pinch) {
                    float half = span * (1 + (scale - 1) * f) / 2;
                    points[0] = new float[]{x + dx*f - half, y + dy*f};
                    points[1] = new float[]{x + dx*f + half, y + dy*f};
                } else points[0] = new float[]{x + dx*f, y + dy*f};
                send(MotionEvent.ACTION_MOVE, points);
                SystemClock.sleep(25);
            }
            if (pinch) send(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), points);
            send(MotionEvent.ACTION_UP, new float[][]{points[0]});
            result.putString("result", "Gesture delivered: " + mode);
            finish(0, result);
        } catch (Exception e) {
            result.putString("error", e.toString());
            finish(1, result);
        }
    }
}

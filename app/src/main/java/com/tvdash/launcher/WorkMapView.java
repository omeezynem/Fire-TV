package com.tvdash.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import java.util.List;

/** Continental US outline with one dot per open job. D-pad moves between dots. Nothing here edits a job. */
final class WorkMapView extends View {
    interface Select { void onJob(WorkClient.Job job); }

    private static final double LON0 = -125.0;
    private static final double LON1 = -66.5;
    private static final double LAT0 = 24.2;
    private static final double LAT1 = 49.6;

    /** Rough coastline, lon/lat pairs. Enough to read as the lower 48. */
    private static final float[] OUTLINE = {
            -124.6f, 48.4f, -124.4f, 47.2f, -124.1f, 46.2f, -124.0f, 44.5f, -124.4f, 43.3f,
            -124.3f, 42.0f, -124.2f, 40.4f, -123.8f, 39.0f, -122.5f, 37.1f, -121.8f, 36.2f,
            -120.6f, 34.4f, -119.0f, 34.1f, -117.2f, 32.5f, -114.7f, 32.6f, -111.0f, 31.3f,
            -108.2f, 31.3f, -106.5f, 31.8f, -104.7f, 29.4f, -103.0f, 29.0f, -101.4f, 29.8f,
            -99.2f, 27.5f, -97.4f, 25.9f, -96.8f, 28.2f, -94.8f, 29.5f, -93.8f, 29.7f,
            -90.0f, 29.2f, -89.4f, 29.0f, -89.2f, 30.2f, -87.6f, 30.3f, -86.5f, 30.4f,
            -85.0f, 29.7f, -83.8f, 29.2f, -82.6f, 27.8f, -82.0f, 26.4f, -81.4f, 25.4f,
            -80.2f, 25.3f, -80.0f, 26.8f, -80.0f, 28.2f, -81.0f, 30.3f, -81.4f, 31.2f,
            -81.2f, 32.1f, -80.8f, 32.7f, -79.8f, 32.8f, -79.0f, 33.5f, -77.9f, 34.2f,
            -76.0f, 35.2f, -75.5f, 35.6f, -75.6f, 36.9f, -75.9f, 37.2f, -76.3f, 38.0f,
            -75.0f, 38.4f, -74.4f, 39.3f, -74.0f, 40.5f, -73.2f, 40.6f, -71.9f, 41.3f,
            -70.6f, 41.6f, -70.0f, 42.0f, -70.6f, 43.1f, -70.2f, 43.7f, -68.2f, 44.4f,
            -67.0f, 44.8f, -67.8f, 46.0f, -69.0f, 47.1f, -71.2f, 45.3f, -73.3f, 45.0f,
            -74.8f, 45.0f, -76.5f, 44.2f, -79.0f, 43.3f, -81.2f, 43.0f, -82.4f, 42.0f,
            -83.0f, 42.3f, -83.4f, 43.6f, -84.5f, 45.8f, -85.0f, 46.0f, -86.5f, 45.3f,
            -87.5f, 45.2f, -88.0f, 44.0f, -87.8f, 42.5f, -89.0f, 42.5f, -90.2f, 42.5f,
            -92.0f, 46.7f, -94.6f, 48.8f, -97.2f, 49.0f, -104.0f, 49.0f, -116.0f, 49.0f,
            -123.0f, 49.0f, -124.6f, 48.4f
    };

    private final Paint land = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();

    private List<WorkClient.Job> jobs = new java.util.ArrayList<WorkClient.Job>();
    private float[] xs = new float[0];
    private float[] ys = new float[0];
    private int selected;
    private Select listener;
    private boolean running;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, 32);
        }
    };

    WorkMapView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        land.setStyle(Paint.Style.FILL);
        land.setColor(0xFF101010);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(2f);
        edge.setColor(0xFF3A3A3A);
        fill.setStyle(Paint.Style.FILL);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3f);
        dot.setStyle(Paint.Style.FILL);
        pick.setStyle(Paint.Style.STROKE);
        pick.setStrokeWidth(2f);
        pick.setColor(0xFFEDEDED);
    }

    void setJobs(List<WorkClient.Job> next) {
        jobs = next == null ? new java.util.ArrayList<WorkClient.Job>() : next;
        if (selected >= jobs.size()) selected = 0;
        xs = new float[jobs.size()];
        ys = new float[jobs.size()];
        layoutDots();
        tell();
        invalidate();
    }

    void setListener(Select select) {
        listener = select;
        tell();
    }

    WorkClient.Job selected() {
        if (jobs.isEmpty() || selected < 0 || selected >= jobs.size()) return null;
        return jobs.get(selected);
    }

    void startAmbient() {
        if (running) return;
        running = true;
        removeCallbacks(tick);
        post(tick);
    }

    void stopAmbient() {
        running = false;
        removeCallbacks(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAmbient();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        layoutDots();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (jobs.isEmpty()) return false;
        int dirX = 0;
        int dirY = 0;
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) dirX = 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) dirX = -1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) dirY = 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) dirY = -1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) return true;
        else return false;
        int next = neighbor(dirX, dirY);
        if (next < 0) next = (selected + (dirX < 0 || dirY < 0 ? jobs.size() - 1 : 1)) % jobs.size();
        if (next != selected) {
            selected = next;
            tell();
            invalidate();
        }
        return true;
    }

    private int neighbor(int dirX, int dirY) {
        if (xs.length != jobs.size()) return -1;
        float cx = xs[selected];
        float cy = ys[selected];
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < jobs.size(); i++) {
            if (i == selected) continue;
            float vx = xs[i] - cx;
            float vy = ys[i] - cy;
            if (dirX != 0 && Math.signum(vx) != dirX) continue;
            if (dirY != 0 && Math.signum(vy) != dirY) continue;
            float primary = dirX != 0 ? Math.abs(vx) : Math.abs(vy);
            float secondary = dirX != 0 ? Math.abs(vy) : Math.abs(vx);
            if (primary < 6f) continue;
            if (secondary > primary * 2.2f + 28f) continue;
            float score = primary * 2f + secondary;
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private void tell() {
        if (listener != null) listener.onJob(selected());
    }

    private void layoutDots() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || xs.length != jobs.size()) return;
        float pad = Ui.dp(getContext(), 18);
        for (int i = 0; i < jobs.size(); i++) {
            float[] p = project(jobs.get(i).lon, jobs.get(i).lat, w, h, pad);
            xs[i] = p[0];
            ys[i] = p[1];
        }
        float min = Ui.dp(getContext(), 16);
        for (int i = 0; i < jobs.size(); i++) {
            for (int j = 0; j < i; j++) {
                float dx = xs[i] - xs[j];
                float dy = ys[i] - ys[j];
                if (dx * dx + dy * dy < min * min) {
                    double ang = i * 0.9;
                    xs[i] += (float) (Math.cos(ang) * min);
                    ys[i] += (float) (Math.sin(ang) * min);
                }
            }
        }
    }

    private static float[] project(double lon, double lat, int w, int h, float pad) {
        float x = (float) ((lon - LON0) / (LON1 - LON0));
        float y = (float) ((LAT1 - lat) / (LAT1 - LAT0));
        float[] out = new float[2];
        out[0] = pad + x * (w - pad * 2f);
        out[1] = pad + y * (h - pad * 2f);
        return out;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float pad = Ui.dp(getContext(), 18);
        shape.rewind();
        boolean moved = false;
        for (int i = 0; i + 1 < OUTLINE.length; i += 2) {
            float[] p = project(OUTLINE[i], OUTLINE[i + 1], w, h, pad);
            if (!moved) {
                shape.moveTo(p[0], p[1]);
                moved = true;
            } else {
                shape.lineTo(p[0], p[1]);
            }
        }
        shape.close();
        canvas.drawPath(shape, land);
        canvas.drawPath(shape, edge);

        float pulse = (SystemClock.uptimeMillis() % 1800L) / 1800f;
        float breathe = 0.82f + 0.18f * (float) Math.sin(pulse * Math.PI * 2.0);
        float dotR = Ui.dp(getContext(), 5);
        if (xs.length != jobs.size()) layoutDots();
        for (int i = 0; i < jobs.size() && i < xs.length; i++) {
            WorkClient.Job job = jobs.get(i);
            int color = WorkClient.color(job.trade);
            if (job.hot) {
                float grow = Ui.dp(getContext(), 6) + Ui.dp(getContext(), 12) * pulse;
                ring.setColor(WorkClient.CORAL);
                ring.setAlpha((int) (200 * (1f - pulse)));
                canvas.drawCircle(xs[i], ys[i], dotR + grow, ring);
            }
            dot.setColor(color);
            dot.setAlpha((int) (255 * (i == selected ? 1f : breathe)));
            canvas.drawCircle(xs[i], ys[i], i == selected ? dotR + Ui.dp(getContext(), 2) : dotR, dot);
            if (i == selected) {
                pick.setAlpha(230);
                canvas.drawCircle(xs[i], ys[i], dotR + Ui.dp(getContext(), 5), pick);
            }
        }
    }
}

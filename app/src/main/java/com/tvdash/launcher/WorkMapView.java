package com.tvdash.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** US states from the bundled outlines, with one dot per work order. No map tiles. */
final class WorkMapView extends View {
    interface Select {
        void onJob(WorkOrder job);
        void onOpen(WorkOrder job);
    }

    private final Paint land = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint landOn = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pick = new Paint(Paint.ANTI_ALIAS_FLAG);

    private List<WorkGeo.State> states = new ArrayList<WorkGeo.State>();
    private List<Path> paths = new ArrayList<Path>();
    private List<WorkOrder> jobs = new ArrayList<WorkOrder>();
    private float[] xs = new float[0];
    private float[] ys = new float[0];
    private int selected;
    private String zoom;
    private Select listener;
    private boolean running;
    private boolean laidOut;
    private int laidW;
    private int laidH;
    private double lon0 = -125;
    private double lon1 = -66.5;
    private double lat0 = 24.2;
    private double lat1 = 49.6;
    private double scale;
    private float originX;
    private float originY;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, 48);
        }
    };

    WorkMapView(Context context) {
        super(context);
        setFocusable(true);
        setFocusableInTouchMode(true);
        land.setStyle(Paint.Style.FILL);
        land.setColor(0xFF0A3A72);
        landOn.setStyle(Paint.Style.FILL);
        landOn.setColor(0xFF0E4E78);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1.5f);
        edge.setColor(0xFF43C7D8);
        fill.setStyle(Paint.Style.FILL);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(3f);
        dot.setStyle(Paint.Style.FILL);
        pick.setStyle(Paint.Style.STROKE);
        pick.setStrokeWidth(2f);
        pick.setColor(0xFFF4F7FB);
    }

    void setJobs(List<WorkOrder> next, String keepWo) {
        jobs = next == null ? new ArrayList<WorkOrder>() : next;
        selected = 0;
        if (keepWo != null) {
            for (int i = 0; i < jobs.size(); i++) {
                if (keepWo.equals(jobs.get(i).woNumber)) selected = i;
            }
        }
        xs = new float[jobs.size()];
        ys = new float[jobs.size()];
        laidOut = false;
        layoutDots();
        tell();
        invalidate();
    }

    void setZoom(String stateCode) {
        zoom = stateCode == null || stateCode.length() == 0 ? null : WorkGeo.stateCode(stateCode);
        laidOut = false;
        layoutDots();
        invalidate();
    }

    String zoom() { return zoom; }

    void setListener(Select select) {
        listener = select;
        tell();
    }

    WorkOrder selected() {
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
        laidOut = false;
        layoutDots();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (listener != null) listener.onOpen(selected());
            return true;
        }
        if (jobs.isEmpty()) return false;
        int dirX = 0;
        int dirY = 0;
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) dirX = 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) dirX = -1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) dirY = 1;
        else if (keyCode == KeyEvent.KEYCODE_DPAD_UP) dirY = -1;
        else return false;
        int next = neighbor(dirX, dirY);
        if (next < 0 && dirY < 0) return false;
        if (next < 0) next = (selected + (dirX < 0 || dirY < 0 ? jobs.size() - 1 : 1)) % jobs.size();
        if (next != selected) {
            selected = next;
            tell();
            invalidate();
        }
        return true;
    }

    private int neighbor(int dirX, int dirY) {
        if (xs.length != jobs.size() || jobs.isEmpty()) return -1;
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
            if (primary < 4f) continue;
            if (secondary > primary * 2.4f + 36f) continue;
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
        if (w <= 0 || h <= 0) return;
        if (states.isEmpty()) states = WorkGeo.states(getContext());
        fit(w, h);
        if (!laidOut || laidW != w || laidH != h) {
            paths.clear();
            for (int i = 0; i < states.size(); i++) {
                WorkGeo.State st = states.get(i);
                for (int r = 0; r < st.rings.size(); r++) {
                    float[] ring = st.rings.get(r);
                    Path path = new Path();
                    boolean moved = false;
                    for (int p = 0; p + 1 < ring.length; p += 2) {
                        float[] xy = project(ring[p], ring[p + 1]);
                        if (!moved) {
                            path.moveTo(xy[0], xy[1]);
                            moved = true;
                        } else {
                            path.lineTo(xy[0], xy[1]);
                        }
                    }
                    path.close();
                    paths.add(path);
                }
            }
            laidOut = true;
            laidW = w;
            laidH = h;
        }
        if (xs.length != jobs.size()) return;
        for (int i = 0; i < jobs.size(); i++) {
            if (!jobs.get(i).located) {
                xs[i] = -1000;
                ys[i] = -1000;
                continue;
            }
            float[] xy = project(jobs.get(i).lon, jobs.get(i).lat);
            xs[i] = xy[0];
            ys[i] = xy[1];
        }
    }

    private void fit(int w, int h) {
        lon0 = -125;
        lon1 = -66.5;
        lat0 = 24.2;
        lat1 = 49.6;
        if (zoom != null) {
            for (int i = 0; i < states.size(); i++) {
                WorkGeo.State st = states.get(i);
                if (!st.id.equals(zoom)) continue;
                double cx = (st.box[0] + st.box[2]) / 2.0;
                double cy = (st.box[1] + st.box[3]) / 2.0;
                double spanLon = Math.max(1.6, (st.box[2] - st.box[0]) * 1.35);
                double spanLat = Math.max(1.3, (st.box[3] - st.box[1]) * 1.35);
                lon0 = cx - spanLon / 2.0;
                lon1 = cx + spanLon / 2.0;
                lat0 = cy - spanLat / 2.0;
                lat1 = cy + spanLat / 2.0;
                break;
            }
        }
        float pad = Ui.dp(getContext(), 16);
        double sx = (w - pad * 2f) / (lon1 - lon0);
        double sy = (h - pad * 2f) / (lat1 - lat0);
        scale = Math.min(sx, sy);
        double usedW = (lon1 - lon0) * scale;
        double usedH = (lat1 - lat0) * scale;
        originX = (float) ((w - usedW) / 2.0);
        originY = (float) ((h - usedH) / 2.0);
    }

    private float[] project(double lon, double lat) {
        float[] out = new float[2];
        out[0] = (float) (originX + (lon - lon0) * scale);
        out[1] = (float) (originY + (lat1 - lat) * scale);
        return out;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (!laidOut || xs.length != jobs.size()) layoutDots();
        canvas.drawColor(Vigo.NAVY);
        int pathAt = 0;
        for (int i = 0; i < states.size() && pathAt < paths.size(); i++) {
            WorkGeo.State st = states.get(i);
            boolean on = zoom != null && st.id.equals(zoom);
            for (int r = 0; r < st.rings.size() && pathAt < paths.size(); r++) {
                canvas.drawPath(paths.get(pathAt), on ? landOn : land);
                canvas.drawPath(paths.get(pathAt), edge);
                pathAt++;
            }
        }
        float pulse = (SystemClock.uptimeMillis() % 1600L) / 1600f;
        float dotR = Ui.dp(getContext(), 6);
        for (int i = 0; i < jobs.size() && i < xs.length; i++) {
            if (xs[i] < -100) continue;
            WorkOrder job = jobs.get(i);
            if (job.hotJob) {
                float grow = Ui.dp(getContext(), 5) + Ui.dp(getContext(), 14) * pulse;
                ring.setColor(Vigo.CORAL);
                ring.setAlpha((int) (210 * (1f - pulse)));
                canvas.drawCircle(xs[i], ys[i], dotR + grow, ring);
            }
            dot.setColor(job.dotColor());
            canvas.drawCircle(xs[i], ys[i], i == selected ? dotR + Ui.dp(getContext(), 2) : dotR, dot);
            if (i == selected) {
                pick.setAlpha(230);
                canvas.drawCircle(xs[i], ys[i], dotR + Ui.dp(getContext(), 6), pick);
            }
        }
    }
}

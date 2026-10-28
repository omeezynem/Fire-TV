package com.tvdash.launcher;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;

/**
 * Cross-fading photo background. Uses photos sent from the phone (file:// paths) and the user's photo links,
 * or random scenic photos from picsum.photos when there are none.
 */
final class Slideshow {
    private static final int MAX_BYTES = 20 * 1024 * 1024;

    private final ImageView a, b;
    private final Handler ui;
    private final ExecutorService io;
    private final int width, height;
    private final Random random = new Random();

    private boolean showingA;
    private boolean running;
    private int generation;
    private int intervalMs = 30000;
    private List<String> urls;
    private int nextIndex;

    private final Runnable tick = this::loadNext;

    Slideshow(ImageView a, ImageView b, Handler ui, ExecutorService io, int width, int height) {
        this.a = a;
        this.b = b;
        this.ui = ui;
        this.io = io;
        this.width = Math.min(width, 1920);
        this.height = Math.min(height, 1080);
    }

    void start(List<String> customUrls, int seconds) {
        boolean sourceChanged = urls == null || !urls.equals(customUrls);
        urls = customUrls;
        intervalMs = seconds * 1000;
        if (running && !sourceChanged) return;
        if (sourceChanged) nextIndex = 0;
        running = true;
        generation++;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    void stop() {
        running = false;
        generation++;
        ui.removeCallbacks(tick);
    }

    /** Show the next photo now. */
    void skip() {
        if (!running) return;
        generation++;
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    private String nextUrl() {
        if (urls != null && !urls.isEmpty()) {
            String u = urls.get(nextIndex % urls.size());
            nextIndex++;
            return u;
        }
        return "https://picsum.photos/seed/" + random.nextInt(1000000) + "/" + width + "/" + height;
    }

    /** Show this photo right away, then carry on with the normal rotation. */
    void showNow(String url) {
        if (!running) return;
        generation++;
        ui.removeCallbacks(tick);
        load(url);
    }

    private void loadNext() {
        if (!running) return;
        load(nextUrl());
    }

    private void load(final String url) {
        final int gen = generation;
        io.execute(() -> {
            final Bitmap bm = download(url);
            ui.post(() -> {
                if (!running || gen != generation) return;
                if (bm != null) show(bm);
                ui.postDelayed(tick, bm != null ? intervalMs : 15000);
            });
        });
    }

    private void show(Bitmap bm) {
        final ImageView next = showingA ? b : a;
        final ImageView current = showingA ? a : b;
        showingA = !showingA;
        next.setImageBitmap(bm);
        next.setAlpha(0f);
        next.animate().alpha(1f).setDuration(1500).start();
        current.animate().alpha(0f).setDuration(1500).withEndAction(() -> {
            if (current.getAlpha() == 0f) current.setImageDrawable(null);
        }).start();
    }

    private Bitmap download(String url) {
        HttpURLConnection c = null;
        try {
            byte[] data;
            if (url.startsWith("file://")) {
                try (InputStream in = new FileInputStream(url.substring("file://".length()))) {
                    data = readAll(in);
                }
            } else {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(20000);
                c.setInstanceFollowRedirects(true);
                c.setRequestProperty("User-Agent", "TVDash/1.0 (Android TV launcher)");
                if (c.getResponseCode() / 100 != 2) return null;
                data = readAll(c.getInputStream());
            }
            if (data == null) return null;

            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= width
                    && bounds.outHeight / (opts.inSampleSize * 2) >= height) {
                opts.inSampleSize *= 2;
            }
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception | OutOfMemoryError e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[16384];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > MAX_BYTES) return null;
        }
        return buf.toByteArray();
    }
}

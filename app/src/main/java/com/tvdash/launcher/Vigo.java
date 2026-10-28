package com.tvdash.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Vigo colors and type for Work Mode. The rest of TV Dash stays on its own look. */
final class Vigo {
    static final int NAVY = 0xFF062C5A;
    static final int NAVY_2 = 0xFF0A3A72;
    static final int NAVY_3 = 0xFF0A4F73;
    static final int TEAL = 0xFF0D8AA3;
    static final int TEAL_2 = 0xFF18A8B8;
    static final int CYAN = 0xFF43C7D8;
    static final int AQUA = 0xFF7BE0DC;
    static final int GOLD = 0xFFF2B84B;
    static final int CORAL = 0xFFF06C5B;
    static final int GREEN = 0xFF1F9D6A;
    static final int RED = 0xFFD64545;
    static final int MUTED = 0xFF8AA0B5;
    static final int TEXT = 0xFFF4F7FB;
    static final int LINE = 0xFF1E5A86;

    private static final String LOGO_URL = "https://vigo.fm/brand/emblem-light-128.png";

    private static Typeface manrope;
    private static Typeface dm;

    private Vigo() {}

    static Typeface manrope(Context c) {
        if (manrope == null) {
            try {
                manrope = Typeface.createFromAsset(c.getApplicationContext().getAssets(), "fonts/Manrope-ExtraBold.ttf");
            } catch (RuntimeException e) {
                manrope = Typeface.DEFAULT_BOLD;
            }
        }
        return manrope;
    }

    static Typeface dm(Context c) {
        if (dm == null) {
            try {
                dm = Typeface.createFromAsset(c.getApplicationContext().getAssets(), "fonts/DMSans-Regular.ttf");
            } catch (RuntimeException e) {
                dm = Typeface.SANS_SERIF;
            }
        }
        return dm;
    }

    static TextView text(Context c, String value, int sp, int color, boolean heading) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(heading ? manrope(c) : dm(c));
        t.setIncludeFontPadding(false);
        return t;
    }

    static TextView pill(Context c, String value, int bg, int fg) {
        TextView t = text(c, value == null ? "" : value, 12, fg, true);
        int h = Ui.dp(c, 8);
        int v = Ui.dp(c, 4);
        t.setPadding(h, v, h, v);
        GradientDrawable d = new GradientDrawable();
        d.setColor(bg);
        d.setCornerRadius(Ui.dp(c, 6));
        t.setBackground(d);
        return t;
    }

    static GradientDrawable panel(Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(NAVY_2);
        d.setCornerRadius(Ui.dp(c, 8));
        d.setStroke(1, LINE);
        return d;
    }

    /** Downloads the public emblem once and keeps a copy on the TV. Failure leaves the wordmark. */
    static void logo(final Context c, final ImageView into) {
        final File cached = new File(c.getApplicationContext().getCacheDir(), "vigo-emblem.png");
        if (cached.exists() && cached.length() > 100) {
            Bitmap ready = BitmapFactory.decodeFile(cached.getAbsolutePath());
            if (ready != null) into.setImageBitmap(ready);
        }
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(LOGO_URL).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestProperty("User-Agent", "TVDash/1.5 (Android TV launcher)");
                    if (conn.getResponseCode() / 100 != 2) return;
                    InputStream in = conn.getInputStream();
                    File tmp = new File(cached.getAbsolutePath() + ".tmp");
                    FileOutputStream out = new FileOutputStream(tmp);
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) >= 0) if (n > 0) out.write(buf, 0, n);
                    out.close();
                    in.close();
                    if (!tmp.renameTo(cached)) return;
                    final Bitmap bmp = BitmapFactory.decodeFile(cached.getAbsolutePath());
                    if (bmp == null) return;
                    into.post(new Runnable() {
                        @Override public void run() {
                            into.setImageBitmap(bmp);
                        }
                    });
                } catch (Exception ignored) {
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
        }).start();
    }
}

package com.tvdash.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers for building the TV screens in code. */
final class Ui {
    static final int ACCENT = 0xFF5AC8FA;
    static final int TILE_BG = 0x40FFFFFF;

    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics()));
    }

    static TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setShadowLayer(dp(c, 3), 0, dp(c, 1), 0x99000000);
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    static GradientDrawable ring(Context c, float radiusPx) {
        GradientDrawable d = round(Color.TRANSPARENT, radiusPx);
        d.setStroke(dp(c, 3), Color.WHITE);
        return d;
    }

    /** Focusable 16:9 card that grows and gets a white ring when the remote lands on it. */
    static FrameLayout tile(Context c, int w, int h, int marginEnd, final Runnable onFocused) {
        final FrameLayout tile = new FrameLayout(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMarginEnd(marginEnd);
        tile.setLayoutParams(lp);
        tile.setFocusable(true);
        tile.setClickable(true);
        float radius = dp(c, 10);
        tile.setBackground(round(TILE_BG, radius));
        tile.setClipToOutline(true);
        final View ring = new View(c);
        ring.setBackground(ring(c, radius));
        ring.setVisibility(View.INVISIBLE);
        ring.setTag("ring");
        tile.addView(ring, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        final float lift = dp(c, 8);
        tile.setOnFocusChangeListener((v, has) -> {
            v.animate().scaleX(has ? 1.1f : 1f).scaleY(has ? 1.1f : 1f).translationZ(has ? lift : 0f).setDuration(150).start();
            ring.setVisibility(has ? View.VISIBLE : View.INVISIBLE);
            if (has && onFocused != null) onFocused.run();
        });
        return tile;
    }

    /** Adds content under the focus ring so the ring stays on top. */
    static void fill(FrameLayout tile, View content, FrameLayout.LayoutParams lp) {
        tile.addView(content, tile.getChildCount() - 1, lp);
    }

    static void fillBanner(Context c, FrameLayout tile, Drawable banner) {
        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageDrawable(banner);
        fill(tile, iv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /** Icon or big symbol above a label, for apps without TV banners and for system tiles. */
    static void fillIconLabel(Context c, FrameLayout tile, Drawable icon, String symbol, String label, int h) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER);
        int pad = dp(c, 6);
        col.setPadding(pad, pad, pad, pad);
        if (icon != null) {
            ImageView iv = new ImageView(c);
            iv.setImageDrawable(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            int s = (int) (h * 0.48f);
            col.addView(iv, new LinearLayout.LayoutParams(s, s));
        } else {
            TextView sym = text(c, symbol, 28, Color.WHITE, false);
            sym.setGravity(Gravity.CENTER);
            col.addView(sym);
        }
        TextView t = text(c, label, 13, Color.WHITE, true);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(c, 6);
        col.addView(t, tlp);
        fill(tile, col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
}

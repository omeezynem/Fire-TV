package com.tvdash.launcher;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.ViewOutlineProvider;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers for building the TV screens in code. */
final class Ui {
    /** One accent, the system blue used on a dark screen. */
    static final int ACCENT = 0xFF0A84FF;
    static final int TEXT = 0xFFFFFFFF;
    static final int TEXT_2 = 0xB3EBEBF5;
    static final int TEXT_3 = 0x73EBEBF5;
    static final int GLASS = 0xB81C1C1E;
    static final int TILE_BG = 0x8C1C1C1E;
    private static final Interpolator EASE = new DecelerateInterpolator();

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
        else t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        return t;
    }

    static GradientDrawable round(int color, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusPx);
        return d;
    }

    /** Dark translucent panel. A real blur is too heavy for a Fire TV stick, so this is the glass. */
    static GradientDrawable glass(Context c) {
        return glass(c, dp(c, 24));
    }

    static GradientDrawable glass(Context c, float radiusPx) {
        GradientDrawable d = round(GLASS, radiusPx);
        d.setStroke(Math.max(1, dp(c, 1)), 0x22FFFFFF);
        return d;
    }

    /** Soft edge shown only while focused. Not a solid white ring. */
    static GradientDrawable focusEdge(Context c, float radiusPx) {
        GradientDrawable d = round(0x14FFFFFF, radiusPx);
        d.setStroke(Math.max(1, dp(c, 1)), 0x88FFFFFF);
        return d;
    }

    static void noSystemHighlight(View v) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                v.setDefaultFocusHighlightEnabled(false);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /** Round clip that does not depend on the background drawable's outline (that crashes some Fire OS builds). */
    static void roundClip(View v, final float radiusPx) {
        if (v == null) return;
        try {
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w <= 0 || h <= 0) {
                        outline.setRect(0, 0, 0, 0);
                        return;
                    }
                    float r = Math.min(radiusPx, Math.min(w, h) / 2f);
                    outline.setRoundRect(0, 0, w, h, r);
                }
            });
            v.setClipToOutline(true);
        } catch (RuntimeException ignored) {
        }
    }

    /** Scale, lift, and a soft edge. Draw-only animation so focus stays quick on a stick. */
    static void lift(View v, View edge, boolean has, float scale) {
        v.animate().scaleX(has ? scale : 1f).scaleY(has ? scale : 1f)
                .translationZ(has ? dp(v.getContext(), 10) : 0f)
                .setDuration(180).setInterpolator(EASE).start();
        if (edge != null) edge.animate().alpha(has ? 1f : 0f).setDuration(180).setInterpolator(EASE).start();
    }

    /** Focusable 16:9 card. Grows slightly and lifts when the remote lands on it. */
    static FrameLayout tile(Context c, int w, int h, int marginEnd, final Runnable onFocused) {
        final FrameLayout tile = new FrameLayout(c);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.setMarginEnd(marginEnd);
        tile.setLayoutParams(lp);
        tile.setFocusable(true);
        tile.setClickable(true);
        noSystemHighlight(tile);
        float radius = dp(c, 20);
        tile.setBackground(round(TILE_BG, radius));
        roundClip(tile, radius);
        final View edge = new View(c);
        edge.setBackground(focusEdge(c, radius));
        edge.setAlpha(0f);
        edge.setTag("ring");
        tile.addView(edge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tile.setOnFocusChangeListener((v, has) -> {
            lift(v, edge, has, 1.06f);
            if (has && onFocused != null) onFocused.run();
        });
        return tile;
    }

    /** Adds content under the focus edge so the edge stays on top. */
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
            TextView sym = text(c, symbol, 26, TEXT, false);
            sym.setGravity(Gravity.CENTER);
            col.addView(sym);
        }
        TextView t = text(c, label, 13, TEXT_2, false);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(c, 6);
        col.addView(t, tlp);
        fill(tile, col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }
}

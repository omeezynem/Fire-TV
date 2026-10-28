package com.tvdash.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.Settings;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The home screen: clock, weather, market ticker, favorite apps, all apps, system shortcuts,
 * photo background and screensaver. Also takes live commands from the phone remote through {@link Hub}.
 */
public class MainActivity extends Activity implements Hub.Screen {
    private static final long WEATHER_REFRESH_MS = 20 * 60 * 1000;
    private static final long MARKET_REFRESH_MS = 90 * 1000;
    private static final int UP_COLOR = 0xFF30D158;
    private static final int DOWN_COLOR = 0xFFFF453A;
    private static final int FLAT_COLOR = 0x73EBEBF5;
    private static final int FAV_VISIBLE = 5;
    private static final int GRID_COLUMNS = 6;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(2);

    private Prefs prefs;
    private Slideshow slideshow;

    private View dashboard;
    private View scrim;
    private ScrollView scroll;
    private TextView clock, ampm, date, status;
    private TextView weatherSymbol, weatherTemp, weatherDesc, weatherPlace;
    private LinearLayout forecast;
    private TextView favHeading, appsHeading;
    private LinearLayout favRow, appsGrid, systemRow;
    private View appsSection;
    private View marketsBar;
    private HorizontalScrollView marketsScroll;
    private HorizontalScrollView saverMarketsScroll;
    private LinearLayout marketsRow;
    private LinearLayout saverMarketsRow;
    private LinearLayout saverClockBox;
    private TextView saverClock, saverWeather;
    private TextView messageBanner;

    private List<AppEntry> apps = new ArrayList<>();
    private String refocusPkg;
    private boolean saverOn, swallowKeyUp, resumed;
    private long weatherAt;
    private String weatherKey = "";
    private String marketKey = "";
    private int marketGen;
    private int sidePad, gap;

    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            updateClock();
            long now = System.currentTimeMillis();
            ui.postDelayed(this, 60000 - now % 60000 + 50);
        }
    };
    private final Runnable weatherTick = new Runnable() {
        @Override public void run() {
            refreshWeather(true);
        }
    };
    private final Runnable marketTick = new Runnable() {
        @Override public void run() {
            refreshMarkets();
        }
    };
    private final Runnable marketScrollTick = new Runnable() {
        @Override public void run() {
            tickMarketScroll(marketsScroll);
            tickMarketScroll(saverMarketsScroll);
            ui.postDelayed(this, 45);
        }
    };
    private final Runnable idle = this::enterScreensaver;
    private final Runnable hideMessage = () -> messageBanner.animate().alpha(0f).setDuration(500).withEndAction(() -> {
        if (messageBanner.getAlpha() == 0f) messageBanner.setVisibility(View.GONE);
    }).start();

    private final BroadcastReceiver packagesChanged = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            loadApps();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);
        sidePad = Ui.dp(this, 56);
        gap = Ui.dp(this, 22);
        setContentView(buildScreen());

        DisplayMetrics dm = getResources().getDisplayMetrics();
        slideshow = new Slideshow((ImageView) findViewById(1), (ImageView) findViewById(2), ui, io,
                Math.max(dm.widthPixels, dm.heightPixels), Math.min(dm.widthPixels, dm.heightPixels));

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packagesChanged, f);
        RemoteService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        appsSection.setVisibility(prefs.showAllApps() ? View.VISIBLE : View.GONE);
        loadApps();
        ui.removeCallbacks(clockTick);
        ui.post(clockTick);
        slideshow.start(Photos.slideshowSources(this, prefs), prefs.slideSeconds());
        refreshWeather(false);
        exitScreensaver();
        refreshMarkets();
        ui.removeCallbacks(marketScrollTick);
        ui.post(marketScrollTick);
        Hub.screen = this;
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
        Hub.screen = null;
        ui.removeCallbacks(clockTick);
        ui.removeCallbacks(weatherTick);
        ui.removeCallbacks(marketTick);
        ui.removeCallbacks(marketScrollTick);
        ui.removeCallbacks(idle);
        slideshow.stop();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(packagesChanged);
        io.shutdownNow();
    }

    /** Pressing Home while already home jumps back to the top. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        scroll.smoothScrollTo(0, 0);
        focusFirstTile();
    }

    @Override
    public void onBackPressed() {
        // A home screen has nowhere to go back to; just return to the top.
        if (scroll.getScrollY() > 0) {
            scroll.smoothScrollTo(0, 0);
            focusFirstTile();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        if (saverOn) {
            if (ev.getAction() == KeyEvent.ACTION_DOWN) {
                exitScreensaver();
                swallowKeyUp = true;
            }
            return true;
        }
        if (swallowKeyUp && ev.getAction() == KeyEvent.ACTION_UP) {
            swallowKeyUp = false;
            return true;
        }
        resetIdle();
        if (ev.getAction() == KeyEvent.ACTION_UP && ev.getKeyCode() == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) {
            slideshow.skip();
            return true;
        }
        return super.dispatchKeyEvent(ev);
    }

    // ---------------------------------------------------------------- layout

    private View buildScreen() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);

        for (int id = 1; id <= 2; id++) {
            ImageView bg = new ImageView(this);
            bg.setId(id);
            bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bg.setAlpha(0f);
            root.addView(bg, matchParent());
        }

        scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x66000000, 0x22000000, 0x99000000}));
        root.addView(scrim, matchParent());

        LinearLayout dash = new LinearLayout(this);
        dash.setOrientation(LinearLayout.VERTICAL);
        dash.addView(buildTopBar());
        dash.addView(buildMarketsBar());

        scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipChildren(false);
        scroll.setSmoothScrollingEnabled(true);
        LinearLayout sections = new LinearLayout(this);
        sections.setOrientation(LinearLayout.VERTICAL);
        sections.setClipChildren(false);
        sections.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 64));

        favHeading = heading("Favorites");
        sections.addView(favHeading);
        favRow = row();
        sections.addView(scroller(favRow));

        LinearLayout appsBox = new LinearLayout(this);
        appsBox.setOrientation(LinearLayout.VERTICAL);
        appsBox.setClipChildren(false);
        appsHeading = heading("All apps");
        appsBox.addView(appsHeading);
        appsGrid = new LinearLayout(this);
        appsGrid.setOrientation(LinearLayout.VERTICAL);
        appsGrid.setClipChildren(false);
        appsGrid.setPadding(sidePad, 0, sidePad, 0);
        appsBox.addView(appsGrid);
        appsSection = appsBox;
        sections.addView(appsBox);

        sections.addView(heading("System"));
        systemRow = row();
        sections.addView(scroller(systemRow));
        buildSystemRow();

        scroll.addView(sections);
        dash.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        dashboard = dash;
        root.addView(dash, matchParent());

        // Screensaver prices, along the bottom. The clock sits above this strip.
        saverMarketsScroll = quietScroller();
        saverMarketsRow = new LinearLayout(this);
        saverMarketsRow.setOrientation(LinearLayout.HORIZONTAL);
        saverMarketsRow.setGravity(Gravity.CENTER_VERTICAL);
        saverMarketsRow.setPadding(0, Ui.dp(this, 4), Ui.dp(this, 24), Ui.dp(this, 4));
        saverMarketsScroll.addView(saverMarketsRow);
        saverMarketsScroll.setAlpha(0f);
        saverMarketsScroll.setVisibility(View.GONE);
        FrameLayout.LayoutParams tickerLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM);
        tickerLp.setMargins(sidePad, 0, sidePad, Ui.dp(this, 16));
        root.addView(saverMarketsScroll, tickerLp);

        // Screensaver clock, shown only while the dashboard is hidden.
        saverClockBox = new LinearLayout(this);
        saverClockBox.setOrientation(LinearLayout.VERTICAL);
        saverClockBox.setGravity(Gravity.END);
        saverClock = Ui.text(this, "", 72, Color.WHITE, false);
        saverClock.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        saverClock.setLetterSpacing(-0.02f);
        saverClock.setShadowLayer(Ui.dp(this, 16), 0, Ui.dp(this, 2), 0x88000000);
        saverWeather = Ui.text(this, "", 18, Ui.TEXT_2, false);
        saverClockBox.addView(saverClock);
        saverClockBox.addView(saverWeather);
        saverClockBox.setAlpha(0f);
        saverClockBox.setVisibility(View.GONE);
        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        sl.setMargins(0, 0, sidePad, Ui.dp(this, 36));
        root.addView(saverClockBox, sl);

        // Messages sent from the phone.
        messageBanner = Ui.text(this, "", 26, Color.WHITE, true);
        messageBanner.setGravity(Gravity.CENTER);
        int mp = Ui.dp(this, 22);
        messageBanner.setPadding(mp * 2, mp, mp * 2, mp);
        messageBanner.setBackground(Ui.glass(this));
        messageBanner.setElevation(Ui.dp(this, 8));
        messageBanner.setMaxWidth(screenWidth() * 3 / 4);
        messageBanner.setVisibility(View.GONE);
        root.addView(messageBanner, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        return root;
    }

    private View buildTopBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(sidePad, Ui.dp(this, 36), sidePad, Ui.dp(this, 6));
        bar.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout timeRow = new LinearLayout(this);
        timeRow.setGravity(Gravity.BOTTOM);
        clock = Ui.text(this, "", 78, Color.WHITE, false);
        clock.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        clock.setLetterSpacing(-0.03f);
        clock.setShadowLayer(Ui.dp(this, 12), 0, Ui.dp(this, 2), 0x66000000);
        ampm = Ui.text(this, "", 20, Ui.TEXT_2, false);
        ampm.setPadding(Ui.dp(this, 8), 0, 0, Ui.dp(this, 12));
        timeRow.addView(clock);
        timeRow.addView(ampm);
        left.addView(timeRow);
        date = Ui.text(this, "", 18, Ui.TEXT_2, false);
        date.setPadding(0, Ui.dp(this, 2), 0, 0);
        left.addView(date);
        status = Ui.text(this, "", 12, Ui.TEXT_3, false);
        status.setPadding(0, Ui.dp(this, 10), 0, 0);
        left.addView(status);
        bar.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setGravity(Gravity.END);
        int inset = Ui.dp(this, 18);
        right.setPadding(inset, Ui.dp(this, 14), inset, Ui.dp(this, 14));
        right.setBackground(Ui.glass(this));
        right.setClipToOutline(true);
        LinearLayout now = new LinearLayout(this);
        now.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        weatherSymbol = Ui.text(this, "", 32, Color.WHITE, false);
        weatherSymbol.setPadding(0, 0, Ui.dp(this, 8), 0);
        weatherTemp = Ui.text(this, "--°", 40, Color.WHITE, false);
        weatherTemp.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
        now.addView(weatherSymbol);
        now.addView(weatherTemp);
        right.addView(now);
        weatherDesc = Ui.text(this, "Loading weather…", 15, Ui.TEXT_2, false);
        weatherDesc.setGravity(Gravity.END);
        weatherDesc.setPadding(0, Ui.dp(this, 2), 0, 0);
        right.addView(weatherDesc);
        weatherPlace = Ui.text(this, "", 13, Ui.TEXT_3, false);
        weatherPlace.setGravity(Gravity.END);
        weatherPlace.setPadding(0, Ui.dp(this, 2), 0, 0);
        right.addView(weatherPlace);
        forecast = new LinearLayout(this);
        forecast.setPadding(0, Ui.dp(this, 12), 0, 0);
        right.addView(forecast);
        bar.addView(right);
        return bar;
    }

    /** A thin strip under the clock. Hidden until the user adds at least one symbol. */
    private View buildMarketsBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int hm = sidePad;
        int vm = Ui.dp(this, 4);
        bar.setPadding(hm, vm, hm, Ui.dp(this, 2));
        bar.setFocusable(false);
        bar.setVisibility(View.GONE);

        marketsScroll = quietScroller();
        marketsRow = new LinearLayout(this);
        marketsRow.setOrientation(LinearLayout.HORIZONTAL);
        marketsRow.setGravity(Gravity.CENTER_VERTICAL);
        marketsRow.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 8), Ui.dp(this, 8));
        marketsRow.setBackground(Ui.glass(this, Ui.dp(this, 18)));
        marketsRow.setClipToOutline(true);
        marketsScroll.addView(marketsRow);
        bar.addView(marketsScroll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        marketsBar = bar;
        return bar;
    }

    /** Not focusable, so the remote keeps moving between app tiles. */
    private HorizontalScrollView quietScroller() {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setFocusable(false);
        h.setFocusableInTouchMode(false);
        h.setClickable(false);
        h.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        h.setOverScrollMode(View.OVER_SCROLL_NEVER);
        h.setSmoothScrollingEnabled(false);
        return h;
    }

    private TextView heading(String s) {
        TextView t = Ui.text(this, s, 15, Ui.TEXT_2, false);
        t.setPadding(sidePad, Ui.dp(this, 22), sidePad, Ui.dp(this, 2));
        return t;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setClipChildren(false);
        r.setPadding(sidePad, Ui.dp(this, 14), sidePad, Ui.dp(this, 8));
        return r;
    }

    private HorizontalScrollView scroller(LinearLayout row) {
        HorizontalScrollView h = new HorizontalScrollView(this);
        h.setHorizontalScrollBarEnabled(false);
        h.setClipChildren(false);
        h.setClipToPadding(false);
        h.setSmoothScrollingEnabled(true);
        h.setFocusable(false);
        h.setFocusableInTouchMode(false);
        h.addView(row);
        return h;
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int screenWidth() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return Math.max(dm.widthPixels, dm.heightPixels);
    }

    // ---------------------------------------------------------------- apps

    private void loadApps() {
        final android.content.pm.PackageManager pm = getPackageManager();
        final String self = getPackageName();
        io.execute(() -> {
            final List<AppEntry> loaded = AppEntry.loadAll(pm, self);
            ui.post(() -> {
                if (isFinishing()) return;
                apps = loaded;
                View focused = getCurrentFocus();
                if (refocusPkg == null && focused != null && focused.getTag() instanceof String) {
                    refocusPkg = (String) focused.getTag();
                }
                rebuildApps();
            });
        });
    }

    private List<String> favorites() {
        List<String> favs = prefs.favorites();
        if (favs == null) {
            // First run: pin the popular streaming apps that are installed.
            favs = new ArrayList<>();
            for (String p : Prefs.DEFAULT_FAVORITES) {
                if (find(p) != null && favs.size() < 8) favs.add(p);
            }
            prefs.setFavorites(favs);
        }
        return favs;
    }

    private AppEntry find(String pkg) {
        for (AppEntry e : apps) if (e.pkg.equals(pkg)) return e;
        return null;
    }

    private void rebuildApps() {
        int width = screenWidth() - 2 * sidePad;
        int favW = (width - (FAV_VISIBLE - 1) * gap) / FAV_VISIBLE;
        int favH = favW * 9 / 16;
        int gridGap = Ui.dp(this, 14);
        int gridW = (width - (GRID_COLUMNS - 1) * gridGap) / GRID_COLUMNS;
        int gridH = gridW * 9 / 16;

        List<String> favs = favorites();
        favRow.removeAllViews();
        int shown = 0;
        for (String p : favs) {
            AppEntry e = find(p);
            if (e == null) continue;
            favRow.addView(appTile(e, favW, favH, gap, true, favHeading, "Favorites"));
            shown++;
        }
        if (shown == 0) {
            TextView hint = Ui.text(this, "Highlight any app below and press the Menu (☰) button to pin it here.",
                    16, 0xCCFFFFFF, false);
            hint.setPadding(0, Ui.dp(this, 20), 0, Ui.dp(this, 20));
            favRow.addView(hint);
        }

        appsGrid.removeAllViews();
        LinearLayout line = null;
        for (int i = 0; i < apps.size(); i++) {
            if (i % GRID_COLUMNS == 0) {
                line = new LinearLayout(this);
                line.setClipChildren(false);
                line.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
                appsGrid.addView(line);
            }
            boolean last = i % GRID_COLUMNS == GRID_COLUMNS - 1;
            line.addView(appTile(apps.get(i), gridW, gridH, last ? 0 : gridGap, false, appsHeading, "All apps"));
        }
        appsHeading.setText("All apps (" + apps.size() + ")");

        if (refocusPkg != null) {
            View v = findTileFor(favRow, refocusPkg);
            if (v == null) v = findTileFor(appsGrid, refocusPkg);
            refocusPkg = null;
            if (v != null) {
                v.requestFocus();
                return;
            }
        }
        if (getCurrentFocus() == null) focusFirstTile();
    }

    private View findTileFor(ViewGroup parent, String pkg) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View c = parent.getChildAt(i);
            if (pkg.equals(c.getTag()) && c.isFocusable()) return c;
            if (c instanceof LinearLayout) {
                View inner = findTileFor((ViewGroup) c, pkg);
                if (inner != null) return inner;
            }
        }
        return null;
    }

    private void focusFirstTile() {
        View first = favRow.getChildCount() > 0 && favRow.getChildAt(0).isFocusable() ? favRow.getChildAt(0) : null;
        if (first == null && appsGrid.getChildCount() > 0) first = ((ViewGroup) appsGrid.getChildAt(0)).getChildAt(0);
        if (first == null) first = systemRow.getChildAt(0);
        if (first != null) first.requestFocus();
    }

    private View appTile(final AppEntry e, int w, int h, int margin, final boolean inFavorites,
                         final TextView heading, final String headingText) {
        FrameLayout tile = Ui.tile(this, w, h, margin, () -> heading.setText(headingText + "  ·  " + e.label));
        tile.setTag(e.pkg);
        if (e.banner != null) Ui.fillBanner(this, tile, e.banner);
        else Ui.fillIconLabel(this, tile, e.icon, null, e.label, h);
        tile.setOnClickListener(v -> launch(e));
        tile.setOnLongClickListener(v -> {
            showAppMenu(e, inFavorites);
            return true;
        });
        tile.setOnKeyListener((v, code, ev) -> {
            if (code != KeyEvent.KEYCODE_MENU) return false;
            if (ev.getAction() == KeyEvent.ACTION_UP) showAppMenu(e, inFavorites);
            return true;
        });
        return tile;
    }

    private void launch(AppEntry e) {
        try {
            startActivity(e.launch);
        } catch (ActivityNotFoundException | SecurityException ex) {
            Intent fallback = getPackageManager().getLaunchIntentForPackage(e.pkg);
            if (fallback != null) {
                startActivity(fallback);
            } else {
                Toast.makeText(this, "Couldn't open " + e.label, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showAppMenu(final AppEntry e, boolean inFavorites) {
        final List<String> favs = new ArrayList<>(favorites());
        final int index = favs.indexOf(e.pkg);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        if (index < 0) {
            labels.add("★  Add to Favorites");
            actions.add(() -> { favs.add(e.pkg); saveFavorites(favs, e.pkg); });
        } else {
            if (inFavorites && index > 0) {
                labels.add("←  Move left");
                actions.add(() -> { favs.remove(index); favs.add(index - 1, e.pkg); saveFavorites(favs, e.pkg); });
            }
            if (inFavorites && index < favs.size() - 1) {
                labels.add("→  Move right");
                actions.add(() -> { favs.remove(index); favs.add(index + 1, e.pkg); saveFavorites(favs, e.pkg); });
            }
            if (inFavorites && index > 0) {
                labels.add("⇤  Move to front");
                actions.add(() -> { favs.remove(index); favs.add(0, e.pkg); saveFavorites(favs, e.pkg); });
            }
            labels.add("✕  Remove from Favorites");
            actions.add(() -> { favs.remove(index); saveFavorites(favs, null); });
        }
        labels.add("ℹ  App info");
        actions.add(() -> openSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", e.pkg, null))));
        new AlertDialog.Builder(this)
                .setTitle(e.label)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .show();
    }

    private void saveFavorites(List<String> favs, String focusPkg) {
        prefs.setFavorites(favs);
        refocusPkg = focusPkg;
        rebuildApps();
        if (focusPkg != null) {
            View v = findTileFor(favRow, focusPkg);
            if (v != null) v.requestFocus();
        }
    }

    // ---------------------------------------------------------------- system row

    private void buildSystemRow() {
        int width = screenWidth() - 2 * sidePad;
        int w = (width - (GRID_COLUMNS - 1) * gap) / GRID_COLUMNS;
        int h = w * 9 / 16;
        addSystemTile("⚙", "Fire TV settings", w, h, () -> openSettings(new Intent(Settings.ACTION_SETTINGS)));
        addSystemTile("⇅", "Network", w, h, () -> openSettings(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        addSystemTile("▦", "Manage apps", w, h,
                () -> openSettings(new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)));
        addSystemTile("\u260E", "Phone remote", w, h, this::showPhoneRemote);
        addSystemTile("\u2605", "Dashboard settings", w, h,
                () -> startActivity(new Intent(this, SettingsActivity.class)));
        addSystemTile("◐", "Screensaver now", w, h, this::enterScreensaver);
        addSystemTile("↻", "New photo", w, h, () -> slideshow.skip());
    }

    private void addSystemTile(String symbol, final String label, int w, int h, final Runnable action) {
        boolean first = systemRow.getChildCount() == 0;
        FrameLayout tile = Ui.tile(this, w, h, gap, null);
        if (first) tile.setTag("system");
        Ui.fillIconLabel(this, tile, null, symbol, label, h);
        tile.setOnClickListener(v -> action.run());
        systemRow.addView(tile);
    }

    private void openSettings(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(this, "That settings screen isn't available on this device", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ---------------------------------------------------------------- clock and status

    private void updateClock() {
        Date now = new Date();
        boolean h24 = prefs.clock24();
        String time = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.getDefault()).format(now);
        clock.setText(time);
        ampm.setText(h24 ? "" : new SimpleDateFormat("a", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(now));
        saverClock.setText(time + (h24 ? "" : " " + ampm.getText()));
        status.setText(statusLine());
    }

    @SuppressWarnings("deprecation")
    private String statusLine() {
        StringBuilder b = new StringBuilder();
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo net = cm == null ? null : cm.getActiveNetworkInfo();
        if (net != null && net.isConnected()) {
            b.append(net.getType() == ConnectivityManager.TYPE_ETHERNET ? "Ethernet connected" : "Wi‑Fi connected");
        } else {
            b.append("Offline");
        }
        try {
            StatFs fs = new StatFs(Environment.getDataDirectory().getPath());
            b.append("   ·   ").append(gb(fs.getAvailableBytes())).append(" storage free");
        } catch (RuntimeException ignored) {
        }
        ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (am != null) {
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            b.append("   \u00B7   ").append(gb(mi.availMem)).append(" memory free");
        }
        TvServer server = TvServer.running;
        if (server != null) {
            for (String url : server.urls()) {
                String host = url.substring("http://".length());
                b.append("   \u00B7   ").append(host.startsWith("100.") ? "Tailscale " : "Phone remote: ").append(host);
            }
        }
        return b.toString();
    }

    private static String gb(long bytes) {
        return String.format(Locale.getDefault(), "%.1f GB", bytes / 1073741824.0);
    }

    // ---------------------------------------------------------------- weather

    private void refreshWeather(boolean force) {
        final String city = prefs.city();
        final boolean f = prefs.fahrenheit();
        final String key = city + "|" + f;
        long age = System.currentTimeMillis() - weatherAt;
        ui.removeCallbacks(weatherTick);
        if (!force && key.equals(weatherKey) && age < WEATHER_REFRESH_MS) {
            ui.postDelayed(weatherTick, WEATHER_REFRESH_MS - age);
            return;
        }
        io.execute(() -> {
            try {
                final Weather w = Weather.fetch(city, f);
                ui.post(() -> {
                    weatherAt = System.currentTimeMillis();
                    weatherKey = key;
                    showWeather(w, f);
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    weatherDesc.setText("Weather unavailable");
                    weatherPlace.setText(city.isEmpty() ? "Check your internet connection" : e.getMessage());
                });
            }
        });
        ui.postDelayed(weatherTick, WEATHER_REFRESH_MS);
    }

    private void showWeather(Weather w, boolean f) {
        weatherSymbol.setText(w.symbol);
        weatherTemp.setText(w.temp + "°");
        weatherDesc.setText(w.condition + "  ·  Feels " + w.feelsLike + "°");
        weatherPlace.setText(w.place + "  ·  " + w.humidity + "% humidity  ·  wind " + w.wind + (f ? " mph" : " km/h"));
        saverWeather.setText(w.symbol + "  " + w.temp + "°  " + w.condition + "  \u00B7  " + w.place);
        Hub.weatherLine = w.symbol + " " + w.temp + "° " + w.condition + " \u00B7 " + w.place;
        forecast.removeAllViews();
        for (int i = 1; i < w.days.size(); i++) {
            Weather.Day d = w.days.get(i);
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(Ui.dp(this, 14), 0, 0, 0);
            col.addView(Ui.text(this, d.name, 12, Ui.TEXT_3, false));
            TextView sym = Ui.text(this, d.symbol, 16, Color.WHITE, false);
            sym.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 2));
            col.addView(sym);
            col.addView(Ui.text(this, d.high + "°", 13, Ui.TEXT_2, false));
            forecast.addView(col);
        }
    }

    // ---------------------------------------------------------------- markets

    private void refreshMarkets() {
        ui.removeCallbacks(marketTick);
        final List<String> stocks = prefs.stocks();
        final List<String> crypto = prefs.crypto();
        final int gen = ++marketGen;
        if (stocks.isEmpty() && crypto.isEmpty()) {
            showMarkets(new ArrayList<Markets.Quote>());
            return;
        }
        showMarkets(Markets.remembered(stocks, crypto));
        io.execute(() -> {
            final List<Markets.Quote> quotes = Markets.fetch(stocks, crypto);
            ui.post(() -> {
                if (!resumed || gen != marketGen || isFinishing()) return;
                showMarkets(quotes);
            });
        });
        ui.postDelayed(marketTick, MARKET_REFRESH_MS);
    }

    private void showMarkets(List<Markets.Quote> quotes) {
        String key = marketKey(quotes);
        boolean rebuild = !key.equals(marketKey);
        if (quotes.isEmpty()) {
            marketKey = "";
            marketsBar.setVisibility(View.GONE);
            marketsRow.removeAllViews();
            saverMarketsRow.removeAllViews();
            syncSaverMarkets();
            return;
        }
        marketKey = key;
        marketsBar.setVisibility(View.VISIBLE);
        renderQuotes(marketsRow, quotes, false, false, rebuild);
        renderQuotes(saverMarketsRow, quotes, true, false, rebuild);
        if (rebuild) {
            marketsScroll.scrollTo(0, 0);
            marketsScroll.setTag(0);
            saverMarketsScroll.scrollTo(0, 0);
            saverMarketsScroll.setTag(0);
        }
        syncSaverMarkets();
    }

    private static String marketKey(List<Markets.Quote> quotes) {
        StringBuilder b = new StringBuilder();
        for (Markets.Quote q : quotes) b.append(q.symbol).append('|');
        return b.toString();
    }

    private void renderQuotes(LinearLayout row, List<Markets.Quote> quotes, boolean compact,
                              boolean withLabel, boolean rebuild) {
        int offset = withLabel ? 1 : 0;
        if (!rebuild && row.getChildCount() == quotes.size() + offset) {
            for (int i = 0; i < quotes.size(); i++) {
                View child = row.getChildAt(i + offset);
                if (child instanceof TextView) updateChip((TextView) child, quotes.get(i));
            }
            return;
        }
        row.removeAllViews();
        if (withLabel) row.addView(marketLabel(compact));
        for (Markets.Quote q : quotes) row.addView(chip(q, compact));
    }

    private TextView marketLabel(boolean compact) {
        TextView label = Ui.text(this, "Markets", compact ? 14 : 15, 0xCCFFFFFF, true);
        label.setPadding(0, 0, Ui.dp(this, 12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        label.setLayoutParams(lp);
        return label;
    }

    private TextView chip(Markets.Quote q, boolean compact) {
        TextView t = Ui.text(this, "", compact ? 13 : 14, Color.WHITE, false);
        t.setMaxLines(1);
        t.setFocusable(false);
        int h = Ui.dp(this, compact ? 8 : 12);
        t.setPadding(h, 0, h, 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        t.setLayoutParams(lp);
        updateChip(t, q);
        return t;
    }

    private void updateChip(TextView t, Markets.Quote q) {
        t.setText(quoteText(q));
        t.setContentDescription(quoteDescription(q));
    }

    /** Symbol, price, and today's move only. No quantity or account value. */
    private CharSequence quoteText(Markets.Quote q) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        if (!q.hasPrice) {
            appendColored(b, q.symbol, Color.WHITE);
            appendColored(b, "   \u2014", FLAT_COLOR);
            return b;
        }
        int direction = Markets.direction(q);
        int color = direction > 0 ? UP_COLOR : direction < 0 ? DOWN_COLOR : FLAT_COLOR;
        appendColored(b, q.symbol, Color.WHITE);
        appendColored(b, "  " + Markets.formatPrice(q.price), Ui.TEXT_2);
        if (q.hasChange) appendColored(b, "  " + Markets.arrow(q) + " " + Markets.formatChange(q.changePct), color);
        else appendColored(b, "  \u2014", FLAT_COLOR);
        return b;
    }

    private static void appendColored(SpannableStringBuilder b, String text, int color) {
        int start = b.length();
        b.append(text);
        b.setSpan(new ForegroundColorSpan(color), start, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static String quoteDescription(Markets.Quote q) {
        if (!q.hasPrice) return q.symbol + ", price unavailable";
        String price = Markets.formatPrice(q.price);
        if (!q.hasChange) return q.symbol + " " + price;
        String dir = Markets.direction(q) > 0 ? "up " : Markets.direction(q) < 0 ? "down " : "unchanged ";
        return q.symbol + " " + price + ", " + dir + Markets.formatChange(q.changePct);
    }

    private void tickMarketScroll(HorizontalScrollView h) {
        if (h == null || h.getVisibility() != View.VISIBLE || h.getWidth() <= 0) return;
        View child = h.getChildCount() == 0 ? null : h.getChildAt(0);
        if (child == null) return;
        int max = child.getWidth() - h.getWidth();
        if (max <= Ui.dp(this, 12)) {
            if (h.getScrollX() != 0) h.scrollTo(0, 0);
            return;
        }
        int hold = h.getTag() instanceof Integer ? (Integer) h.getTag() : 0;
        if (h.getScrollX() >= max - 2) {
            h.setTag(hold + 1);
            if (hold > 40) {
                h.setTag(0);
                h.scrollTo(0, 0);
            }
            return;
        }
        if (hold != 0) h.setTag(0);
        h.scrollBy(Math.max(1, Ui.dp(this, 1)), 0);
    }

    private void syncSaverMarkets() {
        boolean show = saverOn && saverMarketsRow.getChildCount() > 0;
        FrameLayout.LayoutParams clockLp = (FrameLayout.LayoutParams) saverClockBox.getLayoutParams();
        clockLp.bottomMargin = Ui.dp(this, show ? 86 : 36);
        saverClockBox.setLayoutParams(clockLp);
        if (show) {
            if (saverMarketsScroll.getVisibility() != View.VISIBLE) {
                saverMarketsScroll.setVisibility(View.VISIBLE);
                saverMarketsScroll.setAlpha(0f);
                saverMarketsScroll.animate().alpha(1f).setDuration(800).start();
            }
        } else if (saverMarketsScroll.getVisibility() == View.VISIBLE) {
            saverMarketsScroll.animate().cancel();
            saverMarketsScroll.setAlpha(0f);
            saverMarketsScroll.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------- screensaver

    private void resetIdle() {
        ui.removeCallbacks(idle);
        int minutes = prefs.saverMinutes();
        if (minutes > 0 && !saverOn) ui.postDelayed(idle, minutes * 60000L);
    }

    private void enterScreensaver() {
        if (saverOn) return;
        saverOn = true;
        Hub.saverOn = true;
        ui.removeCallbacks(idle);
        dashboard.animate().alpha(0f).setDuration(800).start();
        scrim.animate().alpha(0.35f).setDuration(800).start();
        saverClockBox.setVisibility(View.VISIBLE);
        saverClockBox.animate().alpha(1f).setDuration(800).start();
        syncSaverMarkets();
    }

    private void exitScreensaver() {
        if (saverOn) {
            saverOn = false;
            Hub.saverOn = false;
            dashboard.animate().alpha(1f).setDuration(300).start();
            scrim.animate().alpha(1f).setDuration(300).start();
            saverClockBox.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                if (!saverOn) saverClockBox.setVisibility(View.GONE);
            }).start();
            syncSaverMarkets();
        }
        resetIdle();
    }

    // ---------------------------------------------------------------- phone remote

    private void showPhoneRemote() {
        TvServer server = TvServer.running;
        String address = server == null ? null : server.address();
        if (address == null) {
            RemoteService.start(this);
            new AlertDialog.Builder(this)
                    .setTitle("Phone remote")
                    .setMessage("The phone remote is still starting, or the TV isn't connected to Wi‑Fi. Try again in a few seconds.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        String pin = prefs.pin();
        java.util.List<String> urls = server.urls();
        ScrollView scroll = new ScrollView(this);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int outer = Ui.dp(this, 8);
        column.setPadding(outer, outer, outer, outer);
        if (!AdbRemote.ready()) {
            TextView steps = Ui.text(this, RemoteControl.SETUP, 15, Color.WHITE, false);
            steps.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
            column.addView(steps);
        } else {
            TextView ready = Ui.text(this, "ADB debugging is connected. The phone's direction pad controls any app.", 15, 0xFFB7F5C8, false);
            ready.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
            column.addView(ready);
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 24);
        box.setPadding(pad, pad, pad, pad);

        ImageView qr = new ImageView(this);
        qr.setImageBitmap(qrBitmap(address + "/#pin=" + pin));
        int qrSize = Ui.dp(this, 200);
        box.addView(qr, new LinearLayout.LayoutParams(qrSize, qrSize));

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(pad, 0, 0, 0);
        text.addView(Ui.text(this, "Scan with your phone's camera", 20, Color.WHITE, true));
        TextView or = Ui.text(this, "or open this in your phone's browser:", 15, 0xCCFFFFFF, false);
        or.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
        text.addView(or);
        text.addView(Ui.text(this, address.substring("http://".length()), 24, Ui.ACCENT, true));
        String vpn = null;
        for (String url : urls) {
            if (url.startsWith("http://100.") && !url.equals(address)) vpn = url.substring("http://".length());
        }
        if (vpn != null) {
            TextView away = Ui.text(this, "Away from home (Tailscale)", 15, 0xCCFFFFFF, false);
            away.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 4));
            text.addView(away);
            text.addView(Ui.text(this, vpn, 18, Ui.ACCENT, true));
        }
        TextView pinLabel = Ui.text(this, "PIN", 15, 0xCCFFFFFF, false);
        pinLabel.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 4));
        text.addView(pinLabel);
        TextView pinText = Ui.text(this, pin, 34, Color.WHITE, true);
        pinText.setLetterSpacing(0.3f);
        text.addView(pinText);
        TextView tip = Ui.text(this, "You only enter the PIN once. The phone stays signed in, at home or over Tailscale, "
                + "until you tap Sign out phones. Use Add to Home Screen to keep it like an app.", 13, 0xB3FFFFFF, false);
        tip.setPadding(0, Ui.dp(this, 14), 0, 0);
        tip.setMaxWidth(Ui.dp(this, 340));
        text.addView(tip);
        box.addView(text);
        column.addView(box);
        scroll.addView(column);

        new AlertDialog.Builder(this)
                .setTitle("Phone remote")
                .setView(scroll)
                .setPositiveButton("Done", null)
                .setNegativeButton(AdbRemote.ready() ? "ADB connected" : "Connect ADB", (d, w) -> {
                    if (AdbRemote.ready()) return;
                    Toast.makeText(this, "If the TV asks, tap Allow.", Toast.LENGTH_LONG).show();
                    io.execute(() -> {
                        final String err = RemoteControl.connect(this);
                        ui.post(() -> Toast.makeText(this,
                                err == null ? "The phone pad can control any app." : err,
                                Toast.LENGTH_LONG).show());
                    });
                })
                .setNeutralButton("Sign out phones", (d, w) -> {
                    prefs.newPin();
                    showPhoneRemote();
                })
                .show();
    }

    private static Bitmap qrBitmap(String text) {
        QrCode qr = QrCode.encode(text);
        int border = 2;
        int dim = qr.size + border * 2;
        int[] pixels = new int[dim * dim];
        for (int y = 0; y < dim; y++) {
            for (int x = 0; x < dim; x++) {
                int qx = x - border, qy = y - border;
                boolean dark = qx >= 0 && qy >= 0 && qx < qr.size && qy < qr.size && qr.get(qx, qy);
                pixels[y * dim + x] = dark ? Color.BLACK : Color.WHITE;
            }
        }
        Bitmap small = Bitmap.createBitmap(pixels, dim, dim, Bitmap.Config.ARGB_8888);
        return Bitmap.createScaledBitmap(small, dim * 8, dim * 8, false);
    }

    @Override
    public void remoteSettingsChanged() {
        appsSection.setVisibility(prefs.showAllApps() ? View.VISIBLE : View.GONE);
        updateClock();
        slideshow.start(Photos.slideshowSources(this, prefs), prefs.slideSeconds());
        refreshWeather(false);
        refreshMarkets();
        resetIdle();
    }

    @Override
    public void remoteFavoritesChanged() {
        rebuildApps();
    }

    @Override
    public void remoteMessage(String text, int seconds) {
        if (saverOn) exitScreensaver();
        ui.removeCallbacks(hideMessage);
        messageBanner.setText(text);
        messageBanner.setVisibility(View.VISIBLE);
        messageBanner.setAlpha(0f);
        messageBanner.animate().alpha(1f).setDuration(300).start();
        ui.postDelayed(hideMessage, seconds * 1000L);
    }

    @Override
    public void remoteScreensaver(boolean on) {
        if (on) enterScreensaver();
        else exitScreensaver();
    }

    @Override
    public void remoteNextPhoto() {
        slideshow.skip();
    }

    @Override
    public void remotePhotosChanged(File newest) {
        slideshow.start(Photos.slideshowSources(this, prefs), prefs.slideSeconds());
        if (newest != null) slideshow.showNow("file://" + newest.getAbsolutePath());
        else slideshow.skip();
    }
}

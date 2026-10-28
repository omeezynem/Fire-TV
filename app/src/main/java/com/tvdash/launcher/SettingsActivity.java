package com.tvdash.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Remote-friendly settings list. Every row opens a simple picker or text box. */
public class SettingsActivity extends Activity {
    private interface Value { String get(); }

    private Prefs prefs;
    private final List<Runnable> refreshers = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new Prefs(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF0B1426, 0xFF1F2A55, 0xFF3A1F4F}));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        int side = Ui.dp(this, 120);
        list.setPadding(side, Ui.dp(this, 36), side, Ui.dp(this, 48));
        scroll.addView(list);

        TextView title = Ui.text(this, "Dashboard settings", 30, Color.WHITE, true);
        title.setPadding(0, 0, 0, Ui.dp(this, 20));
        list.addView(title);

        section(list, "Weather");
        row(list, "Weather city", () -> prefs.city().isEmpty() ? "Find automatically" : prefs.city(), () ->
                askText("Weather city", "City name, e.g. Dallas or London. Leave blank to find automatically.",
                        prefs.city(), false, prefs::setCity));
        row(list, "Temperature", () -> prefs.fahrenheit() ? "°F" : "°C", () ->
                choose("Temperature", new String[]{"Fahrenheit (°F)", "Celsius (°C)"}, prefs.fahrenheit() ? 0 : 1,
                        i -> prefs.setFahrenheit(i == 0)));

        section(list, "Clock");
        row(list, "Clock style", () -> prefs.clock24() ? "24-hour" : "12-hour", () ->
                choose("Clock style", new String[]{"12-hour (3:45 PM)", "24-hour (15:45)"}, prefs.clock24() ? 1 : 0,
                        i -> prefs.setClock24(i == 1)));

        section(list, "Photos and screensaver");
        row(list, "Photos", () -> prefs.photoUrls().isEmpty() ? "Random scenic photos" : prefs.photoUrls().size() + " of my photo links", () ->
                askText("My photo links", "One image link per line (https://…). Leave empty for random scenic photos.",
                        prefs.photoUrlsRaw(), true, prefs::setPhotoUrls));
        row(list, "Change photo every", () -> seconds(prefs.slideSeconds()), () -> {
            String[] names = new String[Prefs.SLIDE_SECONDS.length];
            int sel = 0;
            for (int i = 0; i < names.length; i++) {
                names[i] = seconds(Prefs.SLIDE_SECONDS[i]);
                if (Prefs.SLIDE_SECONDS[i] == prefs.slideSeconds()) sel = i;
            }
            choose("Change photo every", names, sel, i -> prefs.setSlideSeconds(Prefs.SLIDE_SECONDS[i]));
        });
        row(list, "Screensaver after", () -> minutes(prefs.saverMinutes()), () -> {
            String[] names = new String[Prefs.SAVER_MINUTES.length];
            int sel = 0;
            for (int i = 0; i < names.length; i++) {
                names[i] = minutes(Prefs.SAVER_MINUTES[i]);
                if (Prefs.SAVER_MINUTES[i] == prefs.saverMinutes()) sel = i;
            }
            choose("Screensaver after", names, sel, i -> prefs.setSaverMinutes(Prefs.SAVER_MINUTES[i]));
        });

        section(list, "Apps");
        row(list, "\"All apps\" section", () -> prefs.showAllApps() ? "Shown" : "Hidden", () ->
                choose("\"All apps\" section", new String[]{"Show", "Hide"}, prefs.showAllApps() ? 0 : 1,
                        i -> prefs.setShowAllApps(i == 0)));
        row(list, "Reset favorites", () -> "Go back to the default streaming apps", () ->
                new AlertDialog.Builder(this)
                        .setTitle("Reset favorites?")
                        .setMessage("Your pinned apps and their order will be replaced with the default streaming apps.")
                        .setPositiveButton("Reset", (d, w) -> {
                            prefs.resetFavorites();
                            Toast.makeText(this, "Favorites reset", Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton("Cancel", null)
                        .show());

        section(list, "About");
        row(list, "Use as the Home button", () -> "How to open TV Dash with Home", () ->
                new AlertDialog.Builder(this)
                        .setTitle("Opening TV Dash with Home")
                        .setMessage("Fire TV always sends the Home button to Amazon's home screen and doesn't "
                                + "let you choose another one.\n\n"
                                + "• Easiest: move TV Dash to the front of Your Apps & Channels so it's one click away.\n"
                                + "• Or install a button-remapping app from the Appstore and set Home "
                                + "(or a double-press) to open TV Dash.\n\n"
                                + "Don't disable Amazon's launcher with adb. On some models that leaves the TV "
                                + "with no home screen at all.")
                        .setPositiveButton("OK", null)
                        .show());
        row(list, "Version", this::version, null);

        setContentView(scroll);
        if (list.getChildCount() > 2) list.getChildAt(2).requestFocus();
    }

    private String version() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "TV Dash " + pi.versionName;
        } catch (Exception e) {
            return "TV Dash";
        }
    }

    private static String seconds(int s) {
        return s < 60 ? s + " seconds" : s == 60 ? "1 minute" : (s / 60) + " minutes";
    }

    private static String minutes(int m) {
        return m == 0 ? "Off" : m + " minutes of no remote use";
    }

    private void section(LinearLayout list, String name) {
        TextView t = Ui.text(this, name.toUpperCase(), 13, Ui.ACCENT, true);
        t.setLetterSpacing(0.08f);
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 18), 0, Ui.dp(this, 6));
        list.addView(t);
    }

    private void row(LinearLayout list, String label, final Value value, final Runnable onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        int p = Ui.dp(this, 16);
        r.setPadding(p, Ui.dp(this, 14), p, Ui.dp(this, 14));
        r.setFocusable(true);
        r.setClickable(onClick != null);

        StateListDrawable bg = new StateListDrawable();
        GradientDrawable focused = Ui.round(0x33FFFFFF, Ui.dp(this, 10));
        focused.setStroke(Ui.dp(this, 2), Color.WHITE);
        bg.addState(new int[]{android.R.attr.state_focused}, focused);
        bg.addState(new int[]{}, Ui.round(0x14FFFFFF, Ui.dp(this, 10)));
        r.setBackground(bg);

        TextView l = Ui.text(this, label, 18, Color.WHITE, true);
        r.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView v = Ui.text(this, value.get(), 16, 0xCCFFFFFF, false);
        v.setGravity(Gravity.END);
        r.addView(v);
        refreshers.add(() -> v.setText(value.get()));
        if (onClick != null) r.setOnClickListener(x -> onClick.run());

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 6);
        list.addView(r, lp);
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
    }

    private interface IntChoice { void pick(int i); }
    private interface TextChoice { void save(String s); }

    private void choose(String title, String[] options, int selected, final IntChoice onPick) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(options, selected, (d, which) -> {
                    onPick.pick(which);
                    refresh();
                    d.dismiss();
                })
                .show();
    }

    private void askText(String title, String hint, String current, boolean multiLine, final TextChoice onSave) {
        final EditText input = new EditText(this);
        input.setText(current);
        input.setSelection(input.getText().length());
        input.setHint(hint);
        if (multiLine) {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_VARIATION_URI);
            input.setMinLines(3);
        } else {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            input.setSingleLine(true);
        }
        FrameLayout box = new FrameLayout(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        box.addView(input);
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(hint)
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    onSave.save(input.getText().toString());
                    refresh();
                })
                .setNeutralButton("Clear", (d, w) -> {
                    onSave.save("");
                    refresh();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}

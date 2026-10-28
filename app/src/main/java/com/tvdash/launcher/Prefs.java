package com.tvdash.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Everything the user can change, stored on the TV. */
final class Prefs {
    static final int[] SLIDE_SECONDS = {15, 30, 60, 300};
    static final int[] SAVER_MINUTES = {0, 2, 5, 10, 30};

    private final SharedPreferences sp;

    Prefs(Context c) {
        sp = c.getSharedPreferences("tv_dash", Context.MODE_PRIVATE);
    }

    /** Pinned app package names in display order, or null before the first run. */
    List<String> favorites() {
        String raw = sp.getString("favorites", null);
        if (raw == null) return null;
        List<String> out = new ArrayList<>();
        for (String s : raw.split("\n")) if (!s.isEmpty()) out.add(s);
        return out;
    }

    void setFavorites(List<String> pkgs) {
        StringBuilder b = new StringBuilder();
        for (String p : pkgs) b.append(p).append('\n');
        sp.edit().putString("favorites", b.toString()).apply();
    }

    void resetFavorites() {
        sp.edit().remove("favorites").apply();
    }

    String city() { return sp.getString("city", ""); }
    void setCity(String v) { sp.edit().putString("city", v.trim()).apply(); }

    boolean fahrenheit() { return sp.getBoolean("fahrenheit", true); }
    void setFahrenheit(boolean v) { sp.edit().putBoolean("fahrenheit", v).apply(); }

    boolean clock24() { return sp.getBoolean("clock24", false); }
    void setClock24(boolean v) { sp.edit().putBoolean("clock24", v).apply(); }

    int slideSeconds() { return sp.getInt("slideSeconds", 30); }
    void setSlideSeconds(int v) { sp.edit().putInt("slideSeconds", v).apply(); }

    int saverMinutes() { return sp.getInt("saverMinutes", 5); }
    void setSaverMinutes(int v) { sp.edit().putInt("saverMinutes", v).apply(); }

    /** Custom photo links, one per line. Empty means random scenic photos. */
    List<String> photoUrls() {
        List<String> out = new ArrayList<>();
        for (String s : sp.getString("photoUrls", "").split("\n")) {
            s = s.trim();
            if (s.startsWith("http://") || s.startsWith("https://")) out.add(s);
        }
        return out;
    }

    String photoUrlsRaw() { return sp.getString("photoUrls", ""); }
    void setPhotoUrls(String v) { sp.edit().putString("photoUrls", v).apply(); }

    /** 4-digit code the phone remote must send. Made once, kept until the user asks for a new one. */
    String pin() {
        String pin = sp.getString("pin", null);
        return pin != null ? pin : newPin();
    }

    String newPin() {
        String pin = String.format(java.util.Locale.US, "%04d", new java.security.SecureRandom().nextInt(10000));
        sp.edit().putString("pin", pin).apply();
        return pin;
    }

    boolean showAllApps() { return sp.getBoolean("showAllApps", true); }
    void setShowAllApps(boolean v) { sp.edit().putBoolean("showAllApps", v).apply(); }

    /** Popular streaming apps pinned on first run if they are installed. */
    static final List<String> DEFAULT_FAVORITES = Arrays.asList(
            "com.netflix.ninja",
            "com.amazon.firebat",
            "com.amazon.avod",
            "com.amazon.firetv.youtube",
            "com.google.android.youtube.tv",
            "com.disney.disneyplus",
            "com.hulu.plus",
            "com.wbd.stream",
            "com.hbo.hbonow",
            "com.peacocktv.peacockandroid",
            "com.cbs.ott",
            "tv.pluto.android",
            "com.plexapp.android",
            "com.spotify.tv.android");
}

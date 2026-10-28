package com.tvdash.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

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

    /** Optional key for a future work feed. Empty means the map stays on the built-in sample. Never shown. */
    String workBearer() { return sp.getString("workBearer", ""); }

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

    /** How many symbols the ticker will fetch. Keeps the free quote calls small. */
    static final int MAX_TICKERS = 16;

    /** Stock and ETF symbols, in display order. Empty until the user adds some. */
    List<String> stocks() { return readSymbols("stocks", false); }
    void setStocks(List<String> symbols) { writeSymbols("stocks", symbols, false); }
    void setStocksText(String raw) { setStocks(parseSymbols(raw, false)); }
    String stocksText() { return joinSymbols(stocks()); }

    /** Crypto tickers, in display order. Empty until the user adds some. */
    List<String> crypto() { return readSymbols("crypto", true); }
    void setCrypto(List<String> symbols) { writeSymbols("crypto", symbols, true); }
    void setCryptoText(String raw) { setCrypto(parseSymbols(raw, true)); }
    String cryptoText() { return joinSymbols(crypto()); }

    private static final int MAX_SHOWS = 20;

    /** Shows added from the phone. Empty until the user adds one. No real watch history is stored here. */
    List<Shows.Show> shows() {
        List<Shows.Show> out = new ArrayList<>();
        String raw = sp.getString("shows", "");
        if (raw == null || raw.length() == 0) return out;
        String[] lines = raw.split("\n");
        for (int i = 0; i < lines.length && out.size() < MAX_SHOWS; i++) {
            String[] parts = lines[i].split("\u001f", -1);
            if (parts.length < 1) continue;
            String name = parts[0].trim();
            if (name.length() == 0) continue;
            String poster = parts.length > 1 ? parts[1] : "";
            String network = parts.length > 2 ? parts[2] : "";
            out.add(new Shows.Show(name, poster, network, ""));
        }
        return out;
    }

    void addShow(String name, String poster, String network) {
        if (name == null) return;
        name = name.trim();
        if (name.length() == 0 || name.length() > 80) return;
        if (name.indexOf('\n') >= 0 || name.indexOf('\u001f') >= 0) return;
        if (poster == null) poster = "";
        if (network == null) network = "";
        if (poster.length() > 300) poster = "";
        if (network.length() > 40) network = network.substring(0, 40);
        List<Shows.Show> all = shows();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).name.equalsIgnoreCase(name)) return;
        }
        if (all.size() >= MAX_SHOWS) return;
        String line = name + "\u001f" + poster.replace("\n", "").replace("\u001f", "")
                + "\u001f" + network.replace("\n", "").replace("\u001f", "");
        String raw = sp.getString("shows", "");
        if (raw == null) raw = "";
        sp.edit().putString("shows", raw.length() == 0 ? line : raw + "\n" + line).apply();
    }

    void removeShow(String name) {
        if (name == null) return;
        List<Shows.Show> all = shows();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < all.size(); i++) {
            Shows.Show s = all.get(i);
            if (s.name.equalsIgnoreCase(name.trim())) continue;
            if (b.length() > 0) b.append('\n');
            b.append(s.name).append('\u001f').append(s.poster).append('\u001f').append(s.network);
        }
        sp.edit().putString("shows", b.toString()).apply();
    }

    static String joinSymbols(List<String> symbols) {
        StringBuilder b = new StringBuilder();
        if (symbols != null) {
            for (int i = 0; i < symbols.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(symbols.get(i));
            }
        }
        return b.toString();
    }

    /**
     * Split a typed list into symbols. Stocks allow dots and a leading ^ (BRK.B, ^GSPC).
     * Crypto also allows a CoinGecko id such as avalanche-2. Anything else is dropped.
     */
    static List<String> parseSymbols(String raw, boolean crypto) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split("[,;\\s]+")) {
            if (out.size() >= MAX_TICKERS) break;
            String s = normalizeSymbol(part, crypto);
            if (s != null && !out.contains(s)) out.add(s);
        }
        return out;
    }

    private List<String> readSymbols(String key, boolean crypto) {
        return parseSymbols(sp.getString(key, ""), crypto);
    }

    private void writeSymbols(String key, List<String> symbols, boolean crypto) {
        StringBuilder raw = new StringBuilder();
        if (symbols != null) {
            for (String s : symbols) raw.append(s).append('\n');
        }
        StringBuilder stored = new StringBuilder();
        for (String s : parseSymbols(raw.toString(), crypto)) stored.append(s).append('\n');
        sp.edit().putString(key, stored.toString()).apply();
    }

    private static String normalizeSymbol(String part, boolean crypto) {
        if (part == null) return null;
        String s = part.trim().toUpperCase(Locale.US);
        if (s.startsWith("$")) s = s.substring(1).trim();
        if (!validSymbol(s, crypto)) return null;
        return s;
    }

    private static boolean validSymbol(String s, boolean crypto) {
        int max = crypto ? 40 : 15;
        if (s.isEmpty() || s.length() > max) return false;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            boolean letter = c >= 'A' && c <= 'Z';
            if (letter || digit) any = true;
            if (crypto) {
                if (!(letter || digit || c == '-')) return false;
                if (c == '-' && (i == 0 || i == s.length() - 1)) return false;
            } else {
                if (c == '^') {
                    if (i != 0) return false;
                    continue;
                }
                if (!(letter || digit || c == '.' || c == '-')) return false;
                if ((c == '.' || c == '-') && (i == 0 || i == s.length() - 1)) return false;
            }
        }
        return any;
    }

    /** 4-digit code the phone remote must send. Made once, kept until the user asks for a new one. */
    String pin() {
        String pin = sp.getString("pin", null);
        return pin != null ? pin : newPin();
    }

    /** New PIN, and every phone that already paired is signed out. */
    String newPin() {
        String pin = String.format(Locale.US, "%04d", new java.security.SecureRandom().nextInt(10000));
        sp.edit().putString("pin", pin).remove(DEVICES).apply();
        return pin;
    }

    private static final String DEVICES = "deviceTokens";
    private static final int MAX_DEVICES = 20;

    /** True when this phone's saved token is still allowed. Never expires on its own. */
    synchronized boolean isDevice(String token) {
        if (token == null || token.length() != 32) return false;
        byte[] given;
        try {
            given = token.getBytes("UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return false;
        }
        boolean found = false;
        for (String saved : deviceTokens()) {
            if (saved.length() != token.length()) continue;
            try {
                if (java.security.MessageDigest.isEqual(saved.getBytes("UTF-8"), given)) found = true;
            } catch (java.io.UnsupportedEncodingException ignored) {
            }
        }
        return found;
    }

    /** 128-bit token for one phone. Kept until Sign out phones / New PIN. */
    synchronized String newDevice() {
        byte[] buf = new byte[16];
        new java.security.SecureRandom().nextBytes(buf);
        StringBuilder hex = new StringBuilder(32);
        for (byte b : buf) hex.append(String.format(Locale.US, "%02x", b & 0xFF));
        String token = hex.toString();
        List<String> all = deviceTokens();
        all.add(token);
        while (all.size() > MAX_DEVICES) all.remove(0);
        saveDevices(all);
        return token;
    }

    synchronized void revokeDevice(String token) {
        if (token == null) return;
        List<String> kept = new ArrayList<>();
        for (String saved : deviceTokens()) if (!saved.equals(token)) kept.add(saved);
        saveDevices(kept);
    }

    private List<String> deviceTokens() {
        List<String> out = new ArrayList<>();
        for (String s : sp.getString(DEVICES, "").split("\n")) {
            if (s.length() == 32 && s.matches("[0-9a-f]{32}")) out.add(s);
        }
        return out;
    }

    private void saveDevices(List<String> tokens) {
        StringBuilder b = new StringBuilder();
        for (String t : tokens) b.append(t).append('\n');
        sp.edit().putString(DEVICES, b.toString()).apply();
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

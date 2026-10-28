package com.tvdash.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** City and state positions for the work map. Outlines are bundled. Unknown cities try Open-Meteo, then the state center. */
final class WorkGeo {
    static final class State {
        final String id;
        final double lat;
        final double lon;
        final double[] box;
        final List<float[]> rings;

        State(String id, double lat, double lon, double[] box, List<float[]> rings) {
            this.id = id;
            this.lat = lat;
            this.lon = lon;
            this.box = box;
            this.rings = rings;
        }
    }

    private static final String CITIES =
            "Phoenix|AZ|33.45|-112.07\nMesa|AZ|33.42|-111.83\nTucson|AZ|32.22|-110.97\nFlagstaff|AZ|35.20|-111.65\n"
            + "Denver|CO|39.74|-104.99\nColorado Springs|CO|38.83|-104.82\nFort Collins|CO|40.59|-105.08\nGrand Junction|CO|39.06|-108.55\n"
            + "Minneapolis|MN|44.98|-93.27\nDuluth|MN|46.79|-92.10\nRochester|MN|44.02|-92.47\nSt Cloud|MN|45.56|-94.16\n"
            + "Billings|MT|45.78|-108.50\nMissoula|MT|46.87|-114.00\nBozeman|MT|45.68|-111.04\nGreat Falls|MT|47.50|-111.30\n"
            + "Charlotte|NC|35.23|-80.84\nRaleigh|NC|35.78|-78.64\nAsheville|NC|35.60|-82.55\nWilmington|NC|34.23|-77.94\n"
            + "Albuquerque|NM|35.08|-106.65\nSanta Fe|NM|35.69|-105.94\nLas Cruces|NM|32.32|-106.78\nFarmington|NM|36.73|-108.22\n"
            + "Las Vegas|NV|36.17|-115.14\nReno|NV|39.53|-119.81\nHenderson|NV|36.04|-114.98\nElko|NV|40.83|-115.76\n"
            + "Oklahoma City|OK|35.47|-97.52\nTulsa|OK|36.15|-95.99\nNorman|OK|35.22|-97.44\nLawton|OK|34.61|-98.39\n"
            + "Salt Lake City|UT|40.76|-111.89\nProvo|UT|40.23|-111.66\nOgden|UT|41.22|-111.97\nSt George|UT|37.10|-113.58\n"
            + "Cheyenne|WY|41.14|-104.82\nCasper|WY|42.87|-106.31\nLaramie|WY|41.31|-105.59\nRock Springs|WY|41.59|-109.22\n";

    private static final String STATE_NAMES =
            "AL Alabama,AK Alaska,AZ Arizona,AR Arkansas,CA California,CO Colorado,CT Connecticut,DE Delaware,DC District of Columbia,"
            + "FL Florida,GA Georgia,HI Hawaii,ID Idaho,IL Illinois,IN Indiana,IA Iowa,KS Kansas,KY Kentucky,LA Louisiana,ME Maine,"
            + "MD Maryland,MA Massachusetts,MI Michigan,MN Minnesota,MS Mississippi,MO Missouri,MT Montana,NE Nebraska,NV Nevada,"
            + "NH New Hampshire,NJ New Jersey,NM New Mexico,NY New York,NC North Carolina,ND North Dakota,OH Ohio,OK Oklahoma,"
            + "OR Oregon,PA Pennsylvania,RI Rhode Island,SC South Carolina,SD South Dakota,TN Tennessee,TX Texas,UT Utah,VT Vermont,"
            + "VA Virginia,WA Washington,WV West Virginia,WI Wisconsin,WY Wyoming";

    private static List<State> states;
    private static final Map<String, double[]> cache = new HashMap<String, double[]>();
    private static boolean fileLoaded;

    private WorkGeo() {}

    static synchronized List<State> states(Context context) {
        if (states != null) return states;
        List<State> parsed = new ArrayList<State>();
        try {
            InputStream in = context.getApplicationContext().getAssets().open("us-states.json");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) >= 0) if (n > 0) buf.write(chunk, 0, n);
            in.close();
            JSONArray arr = new JSONArray(buf.toString("UTF-8"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                JSONArray rings = o.getJSONArray("rings");
                List<float[]> shapes = new ArrayList<float[]>();
                for (int r = 0; r < rings.length(); r++) {
                    JSONArray ring = rings.getJSONArray(r);
                    float[] pts = new float[ring.length() * 2];
                    for (int p = 0; p < ring.length(); p++) {
                        JSONArray pair = ring.getJSONArray(p);
                        pts[p * 2] = (float) pair.getDouble(0);
                        pts[p * 2 + 1] = (float) pair.getDouble(1);
                    }
                    shapes.add(pts);
                }
                JSONArray box = o.getJSONArray("box");
                parsed.add(new State(
                        o.getString("id"),
                        o.getDouble("lat"),
                        o.getDouble("lon"),
                        new double[]{box.getDouble(0), box.getDouble(1), box.getDouble(2), box.getDouble(3)},
                        shapes));
            }
        } catch (Exception ignored) {
        }
        states = parsed;
        return states;
    }

    static State find(Context context, String state) {
        String code = stateCode(state);
        if (code.length() == 0) return null;
        List<State> all = states(context);
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(code)) return all.get(i);
        return null;
    }

    /** Fills lat/lon. Returns true when a network lookup was used. */
    static boolean locate(Context context, WorkOrder order, boolean allowNetwork) {
        if (order == null || order.located) return false;
        String code = stateCode(order.state);
        String key = (order.city + "|" + code).toLowerCase(Locale.US);
        double[] known = city(order.city, code);
        if (known == null) known = cached(context, key);
        if (known != null) {
            order.lat = known[0];
            order.lon = known[1];
            order.located = true;
            return false;
        }
        if (allowNetwork && order.city.length() > 0) {
            double[] net = openMeteo(order.city, code);
            if (net != null) {
                order.lat = net[0];
                order.lon = net[1];
                order.located = true;
                remember(context, key, net);
                return true;
            }
        }
        State st = find(context, code);
        if (st != null) {
            order.lat = st.lat;
            order.lon = st.lon;
            order.located = true;
        }
        return false;
    }

    static String stateCode(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() == 0) return "";
        if (s.length() == 2) return s.toUpperCase(Locale.US);
        String lower = s.toLowerCase(Locale.US);
        String[] parts = STATE_NAMES.split(",");
        for (int i = 0; i < parts.length; i++) {
            int space = parts[i].indexOf(' ');
            if (space < 0) continue;
            if (parts[i].substring(space + 1).toLowerCase(Locale.US).equals(lower)) {
                return parts[i].substring(0, space);
            }
        }
        return s.length() <= 3 ? s.toUpperCase(Locale.US) : "";
    }

    static String stateName(String code) {
        String id = stateCode(code);
        String[] parts = STATE_NAMES.split(",");
        for (int i = 0; i < parts.length; i++) {
            int space = parts[i].indexOf(' ');
            if (space > 0 && parts[i].substring(0, space).equals(id)) return parts[i].substring(space + 1);
        }
        return id;
    }

    private static double[] city(String city, String code) {
        if (city == null || city.length() == 0 || code.length() == 0) return null;
        String[] lines = CITIES.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String[] p = lines[i].split("\\|");
            if (p.length < 4) continue;
            if (p[1].equals(code) && p[0].equalsIgnoreCase(city.trim())) {
                try {
                    return new double[]{Double.parseDouble(p[2]), Double.parseDouble(p[3])};
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static synchronized double[] cached(Context context, String key) {
        loadFile(context);
        return cache.get(key);
    }

    private static synchronized void remember(Context context, String key, double[] latLon) {
        cache.put(key, latLon);
        try {
            File file = new File(context.getApplicationContext().getCacheDir(), "work-geo.txt");
            FileOutputStream out = new FileOutputStream(file, true);
            out.write((key + "|" + latLon[0] + "|" + latLon[1] + "\n").getBytes("UTF-8"));
            out.close();
        } catch (Exception ignored) {
        }
    }

    private static void loadFile(Context context) {
        if (fileLoaded) return;
        fileLoaded = true;
        try {
            File file = new File(context.getApplicationContext().getCacheDir(), "work-geo.txt");
            if (!file.exists()) return;
            FileInputStream in = new FileInputStream(file);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int n;
            while ((n = in.read(chunk)) >= 0) if (n > 0) buf.write(chunk, 0, n);
            in.close();
            String[] lines = buf.toString("UTF-8").split("\n");
            for (int i = 0; i < lines.length; i++) {
                String[] p = lines[i].split("\\|");
                if (p.length < 3) continue;
                try {
                    cache.put(p[0], new double[]{Double.parseDouble(p[1]), Double.parseDouble(p[2])});
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static double[] openMeteo(String city, String code) {
        HttpURLConnection conn = null;
        try {
            String url = "https://geocoding-api.open-meteo.com/v1/search?count=5&language=en&format=json&name="
                    + URLEncoder.encode(city, "UTF-8");
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "TVDash/1.5 (Android TV launcher)");
            if (conn.getResponseCode() / 100 != 2) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[2048];
            int n;
            while ((n = in.read(chunk)) >= 0) if (n > 0) buf.write(chunk, 0, n);
            in.close();
            JSONObject root = new JSONObject(buf.toString("UTF-8"));
            JSONArray results = root.optJSONArray("results");
            if (results == null) return null;
            String want = stateName(code).toLowerCase(Locale.US);
            double[] fallback = null;
            for (int i = 0; i < results.length(); i++) {
                JSONObject row = results.getJSONObject(i);
                if (!"US".equalsIgnoreCase(row.optString("country_code", ""))) continue;
                double[] pos = new double[]{row.getDouble("latitude"), row.getDouble("longitude")};
                String admin = row.optString("admin1", "").toLowerCase(Locale.US);
                if (want.length() > 0 && admin.equals(want)) return pos;
                if (fallback == null) fallback = pos;
            }
            return code.length() == 0 ? fallback : null;
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}

package com.tvdash.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Free weather from Open-Meteo (no API key). Location comes from the city setting or the TV's internet address. */
final class Weather {
    static final class Day {
        String name;
        String symbol;
        int high;
        int low;
    }

    String place;
    int temp;
    int feelsLike;
    int humidity;
    int wind;
    String condition;
    String symbol;
    final List<Day> days = new ArrayList<>();

    private static String cachedFor;
    private static double cachedLat, cachedLon;
    private static String cachedPlace;

    /** Blocking; call off the main thread. */
    static Weather fetch(String city, boolean fahrenheit) throws Exception {
        locate(city);
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + cachedLat + "&longitude=" + cachedLon
                + "&current=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m,weather_code"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=4&timezone=auto"
                + (fahrenheit ? "&temperature_unit=fahrenheit&wind_speed_unit=mph" : "&wind_speed_unit=kmh");
        JSONObject json = new JSONObject(get(url));
        JSONObject cur = json.getJSONObject("current");
        Weather w = new Weather();
        w.place = cachedPlace;
        w.temp = (int) Math.round(cur.getDouble("temperature_2m"));
        w.feelsLike = (int) Math.round(cur.getDouble("apparent_temperature"));
        w.humidity = (int) Math.round(cur.getDouble("relative_humidity_2m"));
        w.wind = (int) Math.round(cur.getDouble("wind_speed_10m"));
        int code = cur.getInt("weather_code");
        w.condition = describe(code);
        w.symbol = symbol(code);

        JSONObject daily = json.getJSONObject("daily");
        JSONArray dates = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray highs = daily.getJSONArray("temperature_2m_max");
        JSONArray lows = daily.getJSONArray("temperature_2m_min");
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        SimpleDateFormat out = new SimpleDateFormat("EEE", Locale.getDefault());
        for (int i = 0; i < dates.length(); i++) {
            Day d = new Day();
            Date date = in.parse(dates.getString(i));
            d.name = i == 0 ? "Today" : out.format(date);
            d.symbol = symbol(codes.getInt(i));
            d.high = (int) Math.round(highs.getDouble(i));
            d.low = (int) Math.round(lows.getDouble(i));
            w.days.add(d);
        }
        return w;
    }

    private static synchronized void locate(String city) throws Exception {
        String key = city == null ? "" : city.trim();
        if (key.equals(cachedFor)) return;
        if (key.isEmpty()) {
            locateByIp();
        } else {
            JSONObject json = new JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                    + URLEncoder.encode(key, "UTF-8")));
            JSONArray results = json.optJSONArray("results");
            if (results == null || results.length() == 0) throw new IOException("City not found: " + key);
            JSONObject r = results.getJSONObject(0);
            cachedLat = r.getDouble("latitude");
            cachedLon = r.getDouble("longitude");
            String region = r.optString("admin1", "");
            cachedPlace = r.getString("name") + (region.isEmpty() ? "" : ", " + region);
        }
        cachedFor = key;
    }

    private static void locateByIp() throws Exception {
        try {
            JSONObject j = new JSONObject(get("https://ipapi.co/json/"));
            cachedLat = j.getDouble("latitude");
            cachedLon = j.getDouble("longitude");
            cachedPlace = j.optString("city", "Your area");
        } catch (Exception first) {
            JSONObject j = new JSONObject(get("https://ipwho.is/"));
            if (!j.optBoolean("success", true)) throw first;
            cachedLat = j.getDouble("latitude");
            cachedLon = j.getDouble("longitude");
            cachedPlace = j.optString("city", "Your area");
        }
    }

    static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "TVDash/1.0 (Android TV launcher)");
        try {
            if (c.getResponseCode() / 100 != 2) throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return buf.toString("UTF-8");
        } finally {
            c.disconnect();
        }
    }

    // WMO weather codes: https://open-meteo.com/en/docs
    static String describe(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code == 85 || code == 86) return "Snow showers";
        if (code >= 95) return "Thunderstorms";
        return "—";
    }

    static String symbol(int code) {
        if (code <= 1) return "☀";             // ☀
        if (code <= 3) return "☁";             // ☁
        if (code == 45 || code == 48) return "≡"; // ≡
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return "❄"; // ❄
        if (code >= 95) return "⚡";            // ⚡
        return "☂";                            // ☂
    }
}

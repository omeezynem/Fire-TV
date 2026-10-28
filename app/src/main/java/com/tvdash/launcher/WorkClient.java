package com.tvdash.launcher;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Live work orders from Vigo, or the built-in demo list when no key is saved.
 * The key is sent only as an Authorization header and is never written to a log or a result.
 */
final class WorkClient {
    static final String URL = "https://vigo.fm/api/tv/work-orders";
    private static final long CACHE_MS = 120L * 1000L;

    static final class Result {
        final List<WorkOrder> orders;
        /** Empty when the list is live. "Demo data", "Invalid key", or a short failure line otherwise. */
        final String notice;
        final boolean demo;

        Result(List<WorkOrder> orders, String notice, boolean demo) {
            this.orders = orders == null ? new ArrayList<WorkOrder>() : orders;
            this.notice = notice == null ? "" : notice;
            this.demo = demo;
        }
    }

    private static Result cached;
    private static String cachedKey = "";
    private static long cachedUntil;

    private WorkClient() {}

    /**
     * Blocking. Demo when {@code bearer} is empty. A saved key never falls back to demo.
     * Repeated calls inside two minutes reuse the last answer unless {@code bypassCache} is set.
     */
    static Result load(Context context, String bearer, boolean bypassCache) {
        String key = bearer == null ? "" : bearer.trim();
        if (key.length() == 0) return new Result(sample(), "Demo data", true);
        synchronized (WorkClient.class) {
            long now = System.currentTimeMillis();
            if (!bypassCache && cached != null && key.equals(cachedKey) && now < cachedUntil) return cached;
            Result fresh = fetch(key);
            place(context, fresh.orders);
            cached = fresh;
            cachedKey = key;
            cachedUntil = now + CACHE_MS;
            return fresh;
        }
    }

    private static void place(Context context, List<WorkOrder> orders) {
        if (context == null || orders == null) return;
        int network = 0;
        for (int i = 0; i < orders.size(); i++) {
            boolean used = WorkGeo.locate(context, orders.get(i), network < 12);
            if (used) network++;
        }
    }

    private static Result fetch(String key) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(URL).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + key);
            conn.setRequestProperty("User-Agent", "TVDash/1.5 (Android TV launcher)");
            int code = conn.getResponseCode();
            if (code == 401) return new Result(new ArrayList<WorkOrder>(), "Invalid key", false);
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = read(in);
            if (code >= 200 && code < 300) {
                try {
                    return new Result(WorkOrder.parseList(body), "", false);
                } catch (Exception ignored) {
                    return new Result(new ArrayList<WorkOrder>(), "Vigo sent a list TV Dash couldn't read.", false);
                }
            }
            return new Result(new ArrayList<WorkOrder>(), "Couldn't reach Vigo.", false);
        } catch (Exception ignored) {
            return new Result(new ArrayList<WorkOrder>(), "Couldn't reach Vigo.", false);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String read(InputStream in) throws Exception {
        if (in == null) return "";
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int n;
        int total = 0;
        while ((n = in.read(chunk)) >= 0 && total < 1500000) {
            if (n == 0) continue;
            buf.write(chunk, 0, n);
            total += n;
        }
        in.close();
        return buf.toString("UTF-8");
    }

    /** Obviously fake stores and clients. Dates move with today so overdue and this-week stay meaningful. */
    static List<WorkOrder> sample() {
        List<WorkOrder> all = new ArrayList<WorkOrder>();
        add(all, "DEMO-2401", "Open", true, "On Site", "High", true, "9041", "Mesa Market", "Mesa", "AZ",
                "Northline", "HVAC", "REACTIVE", "Rooftop unit not cooling the sales floor", -1, -3, 0, "Canyon Air", 33.42, -111.83);
        add(all, "DEMO-2402", "Waiting", false, "Scheduling", "Medium", false, "9118", "Peak Pantry", "Phoenix", "AZ",
                "Peak Pantry", "Plumbing", "REACTIVE", "Restroom faucet leaking behind the counter", 4, -1, 2, "Ridge Plumbing", 33.45, -112.07);
        add(all, "DEMO-2403", "Scheduled", false, "Intake", "Low", false, "8802", "Cinder Goods", "Tucson", "AZ",
                "Cinder Goods", "Doors", "RECURRING", "Monthly door closer and hinge check", 9, -12, 8, "Front Range Doors", 32.22, -110.97);
        add(all, "DEMO-2404", "On site", true, "On Site", "Emergency", true, "7740", "Harbor Shelf", "Denver", "CO",
                "Harbor Shelf", "Refrigeration", "REACTIVE", "Walk-in cooler running warm since yesterday", -2, -2, 0, "Highline Mechanical", 39.74, -104.99);
        add(all, "DEMO-2405", "Open", false, "Quote", "Medium", false, "6621", "Blue Sage", "Colorado Springs", "CO",
                "Blue Sage", "Roofing", "REACTIVE", "Ponding on the back roof after rain", 6, -8, 5, "Peak Roofing Co", 38.83, -104.82);
        add(all, "DEMO-2406", "Scheduled", false, "Scheduling", "Low", false, "5510", "Lantern Shops", "Fort Collins", "CO",
                "Lantern Shops", "Electrical", "RECURRING", "Quarterly lighting check for the sales floor", 14, -20, 12, "Front Range Electric", 40.59, -105.08);
        add(all, "DEMO-2407", "Open", false, "After Visit", "High", false, "4488", "Plainfield Market", "Minneapolis", "MN",
                "Plainfield Market", "HVAC", "REACTIVE", "Follow-up on a unit that short-cycled", 1, -6, 1, "Lake Mechanical", 44.98, -93.27);
        add(all, "DEMO-2408", "Waiting", false, "Support", "Medium", true, "4302", "Copper Kettle", "Duluth", "MN",
                "Copper Kettle", "Plumbing", "REACTIVE", "Floor drain slow in the prep area", -4, -15, 3, "Ridge Plumbing", 46.79, -92.10);
        add(all, "DEMO-2409", "Scheduled", false, "Closeout", "Low", false, "4190", "Northline", "Rochester", "MN",
                "Northline", "Janitorial", "RECURRING", "Weekly floor finish in the entry", 3, -4, 3, "North Clean Co", 44.02, -92.47);
        add(all, "DEMO-2410", "Open", false, "On Site", "High", true, "3004", "Red Mesa Retail", "Billings", "MT",
                "Red Mesa Retail", "Electrical", "REACTIVE", "Parking lot lights out on the west row", -1, -1, 0, "Front Range Electric", 45.78, -108.50);
        add(all, "DEMO-2411", "Quote", false, "Quote", "Medium", false, "2881", "Highline Foods", "Missoula", "MT",
                "Highline Foods", "Refrigeration", "REACTIVE", "Display case not holding temperature", 7, -9, 6, "Highline Mechanical", 46.87, -114.00);
        add(all, "DEMO-2412", "Scheduled", false, "Scheduling", "Low", false, "2766", "Cinder Goods", "Bozeman", "MT",
                "Cinder Goods", "Landscaping", "RECURRING", "Seasonal bed cleanup along the front walk", 11, -18, 10, "Big Sky Grounds", 45.68, -111.04);
        add(all, "DEMO-2413", "On site", true, "On Site", "Emergency", true, "1902", "Peak Pantry", "Charlotte", "NC",
                "Peak Pantry", "Plumbing", "REACTIVE", "Water heater leaking in the back room", -3, -1, 0, "Ridge Plumbing", 35.23, -80.84);
        add(all, "DEMO-2414", "Open", false, "A.F.", "Medium", false, "1844", "Blue Sage", "Raleigh", "NC",
                "Blue Sage", "HVAC", "REACTIVE", "Office suite has no heat in the morning", 2, -5, 2, "Canyon Air", 35.78, -78.64);
        add(all, "DEMO-2415", "Waiting", false, "Intake", "Low", false, "1770", "Lantern Shops", "Asheville", "NC",
                "Lantern Shops", "Glass", "RECURRING", "Replace a cracked sidelight at the entry", 8, -11, 9, "Clearview Glass", 35.60, -82.55);
        add(all, "DEMO-2416", "Open", false, "On Site", "High", false, "1608", "Harbor Shelf", "Albuquerque", "NM",
                "Harbor Shelf", "Roofing", "REACTIVE", "Active leak above the stock aisle", -2, -2, 1, "Peak Roofing Co", 35.08, -106.65);
        add(all, "DEMO-2417", "Scheduled", false, "Scheduling", "Medium", false, "1540", "Copper Kettle", "Santa Fe", "NM",
                "Copper Kettle", "Electrical", "REACTIVE", "Breaker trips when the oven line starts", 5, -7, 4, "Mesa Current", 35.69, -105.94);
        add(all, "DEMO-2418", "Open", false, "Support", "Low", false, "1499", "Plainfield Market", "Las Cruces", "NM",
                "Plainfield Market", "Pest", "RECURRING", "Monthly perimeter pest service", 2, -22, 2, "Desert Guard", 32.32, -106.78);
        add(all, "DEMO-2419", "On site", true, "On Site", "High", true, "2207", "Northline", "Las Vegas", "NV",
                "Northline", "HVAC", "REACTIVE", "Sales floor above the cooling set point", 0, -1, 0, "Canyon Air", 36.17, -115.14);
        add(all, "DEMO-2420", "Quote", false, "Quote", "Medium", false, "2144", "Red Mesa Retail", "Reno", "NV",
                "Red Mesa Retail", "Flooring", "REACTIVE", "Torn vinyl at the produce case", 10, -13, 11, "Basin Floors", 39.53, -119.81);
        add(all, "DEMO-2421", "Scheduled", false, "After Visit", "Low", false, "2091", "Highline Foods", "Henderson", "NV",
                "Highline Foods", "Refrigeration", "RECURRING", "Filter change on the produce cases", 6, -6, 5, "Highline Mechanical", 36.04, -114.98);
        add(all, "DEMO-2422", "Open", false, "Scheduling", "Emergency", true, "3305", "Cinder Goods", "Oklahoma City", "OK",
                "Cinder Goods", "Plumbing", "REACTIVE", "No hot water at the hand sinks", -1, -1, 1, "Ridge Plumbing", 35.47, -97.52);
        add(all, "DEMO-2423", "Waiting", false, "Closeout", "Medium", false, "3288", "Blue Sage", "Tulsa", "OK",
                "Blue Sage", "Doors", "REACTIVE", "Receiving door will not latch", 3, -16, 7, "Front Range Doors", 36.15, -95.99);
        add(all, "DEMO-2424", "Scheduled", false, "Intake", "Low", false, "3210", "Lantern Shops", "Norman", "OK",
                "Lantern Shops", "Painting", "RECURRING", "Touch up scuffs in the vestibule", 15, -25, 14, "Plain Paint Co", 35.22, -97.44);
        add(all, "DEMO-2425", "On site", true, "On Site", "High", false, "5102", "Peak Pantry", "Salt Lake City", "UT",
                "Peak Pantry", "Electrical", "REACTIVE", "Emergency lights failed the monthly test", 1, -4, 0, "Mesa Current", 40.76, -111.89);
        add(all, "DEMO-2426", "Open", false, "A.F.", "Medium", false, "5066", "Harbor Shelf", "Provo", "UT",
                "Harbor Shelf", "HVAC", "REACTIVE", "Thermostat blank in the office hall", 4, -10, 4, "Canyon Air", 40.23, -111.66);
        add(all, "DEMO-2427", "Scheduled", false, "Support", "Low", false, "5018", "Copper Kettle", "Ogden", "UT",
                "Copper Kettle", "Janitorial", "RECURRING", "Nightly restroom service this week", 2, -3, 2, "North Clean Co", 41.22, -111.97);
        add(all, "DEMO-2428", "Open", false, "On Site", "High", true, "6104", "Red Mesa Retail", "Cheyenne", "WY",
                "Red Mesa Retail", "Roofing", "REACTIVE", "Shingle lift on the windward corner", -5, -6, 1, "Peak Roofing Co", 41.14, -104.82);
        add(all, "DEMO-2429", "Waiting", false, "Scheduling", "Medium", false, "6080", "Plainfield Market", "Casper", "WY",
                "Plainfield Market", "HVAC", "REACTIVE", "Loud fan on the rooftop unit", 8, -14, 6, "Lake Mechanical", 42.87, -106.31);
        add(all, "DEMO-2430", "Scheduled", false, "Closeout", "Low", false, "6022", "Northline", "Laramie", "WY",
                "Northline", "Landscaping", "RECURRING", "Irrigation shutoff before a freeze", 5, -19, 5, "Big Sky Grounds", 41.31, -105.59);
        return all;
    }

    private static void add(List<WorkOrder> all, String wo, String status, boolean onSiteGold, String phase,
                            String priority, boolean hot, String storeNo, String storeName, String city, String state,
                            String client, String trade, String type, String requested, int slaOff, int createdOff,
                            int visitOff, String vendor, double lat, double lon) {
        int bg = Vigo.TEAL;
        int fg = 0xFFFFFFFF;
        if ("Waiting".equals(status)) bg = Vigo.MUTED;
        else if ("Scheduled".equals(status)) { bg = Vigo.NAVY_3; fg = Vigo.AQUA; }
        else if ("Quote".equals(status)) { bg = Vigo.CYAN; fg = Vigo.NAVY; }
        else if ("On site".equals(status)) { bg = Vigo.GOLD; fg = Vigo.NAVY; }
        int changedOff = createdOff >= 0 ? createdOff : Math.min(-1, createdOff / 2);
        WorkOrder order = new WorkOrder(wo, status, bg, fg, true, phase, priority, hot,
                storeNo + "  " + storeName, city, state, client, trade, type, requested,
                WorkOrder.offsetDay(slaOff), WorkOrder.offsetDay(changedOff), WorkOrder.offsetDay(createdOff),
                vendor, WorkOrder.offsetDay(visitOff));
        order.lat = lat;
        order.lon = lon;
        order.located = true;
        all.add(order);
    }
}

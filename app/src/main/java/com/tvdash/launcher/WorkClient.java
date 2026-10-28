package com.tvdash.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * View-only work orders for the TV map. The list is mock data: this public repo has no real
 * jobs, customers, or API key. A bearer token would be sent only if a base URL were configured;
 * none is. The screen never shows money, balances, street addresses, or anyone's name or phone.
 */
final class WorkClient {
    static final int CORAL = 0xFFFF7F6A;

    static final class Job {
        final String id;
        final String trade;
        final String city;
        final String state;
        final String phase;
        final double lat;
        final double lon;
        final boolean hot;
        final boolean overdue;

        Job(String id, String trade, String city, String state, String phase,
                double lat, double lon, boolean hot, boolean overdue) {
            this.id = id;
            this.trade = trade;
            this.city = city;
            this.state = state;
            this.phase = phase;
            this.lat = lat;
            this.lon = lon;
            this.hot = hot;
            this.overdue = overdue;
        }
    }

    private static final String[][] TRADES = {
            {"Landscaping", "22C55E"},
            {"HVAC", "EF4444"},
            {"Plumbing", "3B82F6"},
            {"Electrical", "FBBF24"},
            {"Roofing", "F97316"},
            {"Doors / locks", "A855F7"},
            {"Pest", "14B8A6"},
            {"General maintenance", "94A3B8"},
            {"Janitorial", "EC4899"},
            {"Refrigeration", "22D3EE"},
            {"Painting", "EAB308"},
            {"Flooring", "8B5CF6"},
            {"Glass", "38BDF8"},
            {"Appliances", "FB7185"},
    };

    private WorkClient() {}

    /**
     * Sample jobs for the map. {@code bearer} is the saved key shape a live call would send as
     * {@code Authorization: Bearer}. No base URL is configured, so this never hits the network
     * and never draws the key.
     */
    static List<Job> load(String bearer) {
        return sample();
    }

    static int color(String trade) {
        if (trade == null) return 0xFF737373;
        for (String[] row : TRADES) {
            if (row[0].equalsIgnoreCase(trade)) return (int) (0xFF000000L | Long.parseLong(row[1], 16));
        }
        int h = Math.abs(trade.toLowerCase(Locale.US).hashCode());
        int[] extra = {0xFF84CC16, 0xFFF43F5E, 0xFF6366F1, 0xFFD946EF};
        return extra[h % extra.length];
    }

    static List<String> legend(List<Job> jobs) {
        List<String> out = new ArrayList<>();
        if (jobs == null) return out;
        for (String[] row : TRADES) {
            for (int i = 0; i < jobs.size(); i++) {
                if (row[0].equalsIgnoreCase(jobs.get(i).trade)) {
                    out.add(row[0]);
                    break;
                }
            }
        }
        return out;
    }

    static int openCount(List<Job> jobs) {
        return jobs == null ? 0 : jobs.size();
    }

    static int hotCount(List<Job> jobs) {
        int n = 0;
        if (jobs == null) return 0;
        for (int i = 0; i < jobs.size(); i++) if (jobs.get(i).hot) n++;
        return n;
    }

    static int overdueCount(List<Job> jobs) {
        int n = 0;
        if (jobs == null) return 0;
        for (int i = 0; i < jobs.size(); i++) if (jobs.get(i).overdue) n++;
        return n;
    }

    /** Compact "TX 5 · FL 3" for the states with the most open jobs. */
    static String topStates(List<Job> jobs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        if (jobs != null) {
            for (int i = 0; i < jobs.size(); i++) {
                String st = jobs.get(i).state;
                Integer c = counts.get(st);
                counts.put(st, c == null ? 1 : c + 1);
            }
        }
        String bestA = "";
        String bestB = "";
        String bestC = "";
        int nA = -1, nB = -1, nC = -1;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            int n = e.getValue();
            if (n > nA) {
                nC = nB; bestC = bestB;
                nB = nA; bestB = bestA;
                nA = n; bestA = e.getKey();
            } else if (n > nB) {
                nC = nB; bestC = bestB;
                nB = n; bestB = e.getKey();
            } else if (n > nC) {
                nC = n; bestC = e.getKey();
            }
        }
        StringBuilder b = new StringBuilder();
        if (nA > 0) b.append(bestA).append(' ').append(nA);
        if (nB > 0) b.append("   ").append(bestB).append(' ').append(nB);
        if (nC > 0) b.append("   ").append(bestC).append(' ').append(nC);
        return b.length() == 0 ? "—" : b.toString();
    }

    private static List<Job> sample() {
        List<Job> all = new ArrayList<>();
        all.add(job("WO-2401", "Landscaping", "Phoenix", "AZ", "Scheduled", 33.45, -112.07, true, false));
        all.add(job("WO-2402", "HVAC", "Dallas", "TX", "On site", 32.78, -96.80, true, true));
        all.add(job("WO-2403", "Plumbing", "Houston", "TX", "Waiting", 29.76, -95.37, false, true));
        all.add(job("WO-2404", "Electrical", "Atlanta", "GA", "Scheduled", 33.75, -84.39, false, false));
        all.add(job("WO-2405", "Roofing", "Chicago", "IL", "On site", 41.88, -87.63, true, false));
        all.add(job("WO-2406", "Doors / locks", "Denver", "CO", "Scheduled", 39.74, -104.99, false, false));
        all.add(job("WO-2407", "Pest", "Seattle", "WA", "Scheduled", 47.61, -122.33, false, false));
        all.add(job("WO-2408", "Refrigeration", "Miami", "FL", "On site", 25.76, -80.19, true, true));
        all.add(job("WO-2409", "Janitorial", "Boston", "MA", "Scheduled", 42.36, -71.06, false, false));
        all.add(job("WO-2410", "General maintenance", "Minneapolis", "MN", "Waiting", 44.98, -93.27, false, true));
        all.add(job("WO-2411", "Landscaping", "Las Vegas", "NV", "On site", 36.17, -115.14, true, false));
        all.add(job("WO-2412", "HVAC", "Charlotte", "NC", "Scheduled", 35.23, -80.84, false, false));
        all.add(job("WO-2413", "Plumbing", "Nashville", "TN", "Scheduled", 36.16, -86.78, false, false));
        all.add(job("WO-2414", "Electrical", "Detroit", "MI", "Waiting", 42.33, -83.05, false, true));
        all.add(job("WO-2415", "Roofing", "Portland", "OR", "Scheduled", 45.52, -122.68, false, false));
        all.add(job("WO-2416", "Pest", "San Diego", "CA", "On site", 32.72, -117.16, true, false));
        all.add(job("WO-2417", "Doors / locks", "Kansas City", "MO", "Scheduled", 39.10, -94.58, false, false));
        all.add(job("WO-2418", "Refrigeration", "Cleveland", "OH", "Waiting", 41.50, -81.69, false, false));
        all.add(job("WO-2419", "Landscaping", "Tampa", "FL", "Scheduled", 27.95, -82.46, false, true));
        all.add(job("WO-2420", "Electrical", "Austin", "TX", "On site", 30.27, -97.74, false, false));
        all.add(job("WO-2421", "Painting", "Philadelphia", "PA", "Scheduled", 39.95, -75.17, false, false));
        all.add(job("WO-2422", "Flooring", "Salt Lake City", "UT", "Waiting", 40.76, -111.89, false, false));
        all.add(job("WO-2423", "Glass", "Raleigh", "NC", "Scheduled", 35.78, -78.64, true, false));
        all.add(job("WO-2424", "Appliances", "Sacramento", "CA", "On site", 38.58, -121.49, false, false));
        all.add(job("WO-2425", "Janitorial", "New Orleans", "LA", "Scheduled", 29.95, -90.07, false, false));
        all.add(job("WO-2426", "General maintenance", "Omaha", "NE", "Scheduled", 41.26, -95.94, false, false));
        all.add(job("WO-2427", "HVAC", "Albuquerque", "NM", "On site", 35.08, -106.65, false, true));
        all.add(job("WO-2428", "Plumbing", "Boise", "ID", "Scheduled", 43.62, -116.21, false, false));
        return all;
    }

    private static Job job(String id, String trade, String city, String state, String phase,
            double lat, double lon, boolean hot, boolean overdue) {
        return new Job(id, trade, city, state, phase, lat, lon, hot, overdue);
    }
}

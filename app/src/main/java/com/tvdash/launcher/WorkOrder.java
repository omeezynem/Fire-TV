package com.tvdash.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * One open work order, limited to the fields Work Mode is allowed to show.
 * Money, phones, emails, people's names, notes, street addresses, and codes are never copied in.
 */
final class WorkOrder {
    final String woNumber;
    final String statusName;
    final int statusBg;
    final int statusFg;
    final boolean hasStatusColor;
    final String phase;
    final String priority;
    final boolean hotJob;
    final String store;
    final String city;
    final String state;
    final String client;
    final String trade;
    final String woType;
    final String workRequested;
    final String slaCompletionDate;
    final String statusChangedAt;
    final String createdAt;
    final String vendors;
    final String nextVisitDate;
    double lat;
    double lon;
    boolean located;

    WorkOrder(String woNumber, String statusName, int statusBg, int statusFg, boolean hasStatusColor,
              String phase, String priority, boolean hotJob, String store, String city, String state,
              String client, String trade, String woType, String workRequested, String slaCompletionDate,
              String statusChangedAt, String createdAt, String vendors, String nextVisitDate) {
        this.woNumber = woNumber == null ? "" : woNumber;
        this.statusName = statusName == null ? "" : statusName;
        this.statusBg = statusBg;
        this.statusFg = statusFg;
        this.hasStatusColor = hasStatusColor;
        this.phase = phase == null ? "" : phase;
        this.priority = priority == null ? "" : priority;
        this.hotJob = hotJob;
        this.store = store == null ? "" : store;
        this.city = city == null ? "" : city;
        this.state = state == null ? "" : state;
        this.client = client == null ? "" : client;
        this.trade = trade == null ? "" : trade;
        this.woType = woType == null ? "" : woType;
        this.workRequested = clip(workRequested, 80);
        this.slaCompletionDate = slaCompletionDate == null ? "" : slaCompletionDate;
        this.statusChangedAt = statusChangedAt == null ? "" : statusChangedAt;
        this.createdAt = createdAt == null ? "" : createdAt;
        this.vendors = vendors == null ? "" : vendors;
        this.nextVisitDate = nextVisitDate == null ? "" : nextVisitDate;
    }

    String place() {
        String where = "";
        if (city.length() > 0) where = city;
        if (state.length() > 0) where = where.length() == 0 ? state : where + ", " + state;
        return where;
    }

    boolean overdue() {
        return beforeToday(slaCompletionDate);
    }

    /** Map dots follow the trade. Hot jobs are marked separately with a coral ring. */
    int dotColor() {
        return tradeColor(trade);
    }

    static int tradeColor(String trade) {
        String t = trade == null ? "" : trade.toLowerCase(Locale.US);
        if (t.contains("landscap")) return 0xFF1F9D6A;
        if (t.contains("hvac")) return 0xFFD64545;
        if (t.contains("plumb")) return 0xFF3B82F6;
        if (t.contains("electric")) return 0xFFF2B84B;
        if (t.contains("roof")) return 0xFFF97316;
        if (t.contains("door") || t.contains("lock")) return 0xFFA855F7;
        if (t.contains("pest")) return 0xFF14B8A6;
        if (t.contains("refriger")) return 0xFF43C7D8;
        if (t.contains("janitor")) return 0xFFEC4899;
        if (t.contains("paint")) return 0xFFEAB308;
        if (t.contains("floor")) return 0xFF8B5CF6;
        if (t.contains("glass")) return 0xFF7BE0DC;
        if (t.contains("appliance")) return 0xFFFB7185;
        if (t.contains("general") || t.contains("maint")) return 0xFF94A3B8;
        if (t.length() == 0) return Vigo.MUTED;
        int[] extra = {0xFF84CC16, 0xFF6366F1, 0xFFD946EF, 0xFF0EA5E9, 0xFFF59E0B, 0xFF22D3EE};
        return extra[Math.abs(t.hashCode()) % extra.length];
    }

    static int tradeRank(String trade) {
        String t = trade == null ? "" : trade.toLowerCase(Locale.US);
        if (t.contains("landscap")) return 0;
        if (t.contains("hvac")) return 1;
        if (t.contains("plumb")) return 2;
        if (t.contains("electric")) return 3;
        if (t.contains("roof")) return 4;
        if (t.contains("door") || t.contains("lock")) return 5;
        if (t.contains("pest")) return 6;
        if (t.contains("refriger")) return 7;
        if (t.contains("janitor")) return 8;
        if (t.contains("paint")) return 9;
        if (t.contains("floor")) return 10;
        if (t.contains("glass")) return 11;
        if (t.contains("appliance")) return 12;
        if (t.contains("general") || t.contains("maint")) return 13;
        return 50;
    }

    static int priorityColor(String priority) {
        String p = priority == null ? "" : priority.toLowerCase(Locale.US);
        if (p.contains("emerg") || p.contains("crit") || p.contains("urgent")) return Vigo.RED;
        if (p.contains("high")) return Vigo.CORAL;
        if (p.contains("med")) return Vigo.GOLD;
        if (p.contains("low")) return Vigo.GREEN;
        return 0;
    }

    static int priorityRank(String priority) {
        int color = priorityColor(priority);
        if (color == Vigo.RED) return 0;
        if (color == Vigo.CORAL) return 1;
        if (color == Vigo.GOLD) return 2;
        if (color == Vigo.GREEN) return 3;
        return 4;
    }

    static List<WorkOrder> parseList(String body) throws org.json.JSONException {
        if (body == null) throw new org.json.JSONException("empty");
        String trimmed = body.trim();
        JSONArray arr;
        if (trimmed.startsWith("[")) {
            arr = new JSONArray(trimmed);
        } else {
            JSONObject wrap = new JSONObject(trimmed);
            arr = wrap.optJSONArray("workOrders");
            if (arr == null) arr = wrap.optJSONArray("orders");
            if (arr == null) arr = wrap.optJSONArray("data");
            if (arr == null) throw new org.json.JSONException("list");
        }
        List<WorkOrder> out = new ArrayList<WorkOrder>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.optJSONObject(i);
            if (row == null) continue;
            WorkOrder order = fromJson(row);
            if (order.woNumber.length() > 0) out.add(order);
        }
        return out;
    }

    static WorkOrder fromJson(JSONObject o) {
        String statusName = "";
        int bg = Vigo.TEAL;
        int fg = 0xFFFFFFFF;
        boolean hasColor = false;
        Object status = o.opt("status");
        if (status instanceof JSONObject) {
            JSONObject s = (JSONObject) status;
            statusName = text(s.opt("name"));
            String bgRaw = text(s.opt("bgColor"));
            String fgRaw = text(s.opt("fgColor"));
            if (bgRaw.length() > 0) {
                bg = parseColor(bgRaw, Vigo.TEAL);
                hasColor = true;
            }
            if (fgRaw.length() > 0) fg = parseColor(fgRaw, 0xFFFFFFFF);
        } else {
            statusName = text(status);
        }

        String city = text(o.opt("city"));
        String state = text(o.opt("state"));
        String store = "";
        Object storeVal = o.opt("store");
        if (storeVal instanceof JSONObject) {
            JSONObject s = (JSONObject) storeVal;
            String number = text(s.opt("number"));
            String shortName = text(s.opt("shortName"));
            if (shortName.length() == 0) shortName = text(s.opt("name"));
            if (number.length() > 0 && shortName.length() > 0) store = number + "  " + shortName;
            else store = number.length() > 0 ? number : shortName;
            if (city.length() == 0) city = text(s.opt("city"));
            if (state.length() == 0) state = text(s.opt("state"));
        } else {
            store = text(storeVal);
        }

        String client = "";
        Object clientVal = o.opt("client");
        if (clientVal instanceof JSONObject) {
            JSONObject c = (JSONObject) clientVal;
            client = text(c.opt("shortName"));
            if (client.length() == 0) client = text(c.opt("name"));
        } else {
            client = text(clientVal);
        }

        return new WorkOrder(
                text(o.opt("woNumber")),
                statusName,
                bg,
                fg,
                hasColor,
                text(o.opt("phase")),
                text(o.opt("priority")),
                flag(o.opt("hotJob")),
                store,
                city,
                state,
                client,
                text(o.opt("trade")),
                text(o.opt("woType")),
                text(o.opt("workRequested")),
                when(o.opt("slaCompletionDate")),
                when(o.opt("statusChangedAt")),
                when(o.opt("createdAt")),
                vendors(o.has("vendors") ? o.opt("vendors") : o.opt("vendor")),
                when(o.opt("nextVisitDate")));
    }

    static String day(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() >= 10 && s.charAt(4) == '-' && s.charAt(7) == '-') return s.substring(0, 10);
        return "";
    }

    static String pretty(String raw) {
        String d = day(raw);
        if (d.length() != 10) return "";
        try {
            Date dt = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d);
            if (dt == null) return d;
            return new SimpleDateFormat("MMM d", Locale.US).format(dt);
        } catch (ParseException e) {
            return d;
        }
    }

    static boolean beforeToday(String raw) {
        String d = day(raw);
        if (d.length() != 10) return false;
        return d.compareTo(today()) < 0;
    }

    static boolean thisWeek(String raw) {
        String d = day(raw);
        if (d.length() != 10) return false;
        Calendar cal = Calendar.getInstance();
        String start = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
        cal.add(Calendar.DATE, 6);
        String end = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
        return d.compareTo(start) >= 0 && d.compareTo(end) <= 0;
    }

    /** Days since a date, or -1 when the value is missing. */
    static int ageDays(String raw) {
        String d = day(raw);
        if (d.length() != 10) return -1;
        try {
            Date then = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(d);
            if (then == null) return -1;
            long days = (System.currentTimeMillis() - then.getTime()) / 86400000L;
            if (days < 0) return 0;
            if (days > 20000) return -1;
            return (int) days;
        } catch (ParseException e) {
            return -1;
        }
    }

    static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    static String offsetDay(int days) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, days);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.getTime());
    }

    private static String clip(String raw, int max) {
        if (raw == null) return "";
        String s = raw.replace('\n', ' ').replace('\r', ' ').trim();
        if (s.length() > max) return s.substring(0, max);
        return s;
    }

    private static String text(Object v) {
        if (v == null || v == JSONObject.NULL) return "";
        return String.valueOf(v).trim();
    }

    private static boolean flag(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim();
            return "true".equalsIgnoreCase(s) || "yes".equalsIgnoreCase(s) || "1".equals(s);
        }
        return false;
    }

    private static String when(Object v) {
        if (v == null || v == JSONObject.NULL) return "";
        if (v instanceof Number) {
            long ms = ((Number) v).longValue();
            if (ms > 0 && ms < 100000000000L) ms *= 1000L;
            if (ms <= 0) return "";
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date(ms));
        }
        return text(v);
    }

    private static String vendors(Object v) {
        if (v == null || v == JSONObject.NULL) return "";
        if (v instanceof String) return clip((String) v, 80);
        if (v instanceof JSONObject) return clip(company((JSONObject) v), 80);
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < arr.length() && b.length() < 80; i++) {
                Object item = arr.opt(i);
                String name = "";
                if (item instanceof String) name = ((String) item).trim();
                else if (item instanceof JSONObject) name = company((JSONObject) item);
                if (name.length() == 0) continue;
                if (b.length() > 0) b.append(", ");
                b.append(name);
            }
            return clip(b.toString(), 80);
        }
        return "";
    }

    /** Company label only. Contact fields on a vendor object are ignored. */
    private static String company(JSONObject o) {
        String s = text(o.opt("companyName"));
        if (s.length() == 0) s = text(o.opt("company"));
        if (s.length() == 0) s = text(o.opt("shortName"));
        if (s.length() == 0 && !o.has("email") && !o.has("phone") && !o.has("contact")) {
            s = text(o.opt("name"));
        }
        return s;
    }

    static int parseColor(String raw, int fallback) {
        if (raw == null) return fallback;
        String s = raw.trim();
        if (s.startsWith("#")) s = s.substring(1);
        try {
            if (s.length() == 6) return (int) (0xFF000000L | Long.parseLong(s, 16));
            if (s.length() == 8) return (int) Long.parseLong(s, 16);
        } catch (NumberFormatException ignored) {
        }
        return fallback;
    }
}

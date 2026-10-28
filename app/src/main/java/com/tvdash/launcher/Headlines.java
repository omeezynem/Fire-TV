package com.tvdash.launcher;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Recent headlines for the symbols the user saved. Empty lists stay empty. Failures keep the last good set. */
final class Headlines {
    static final class Item {
        final String symbol;
        final String title;
        final String source;
        final long published;

        Item(String symbol, String title, String source, long published) {
            this.symbol = symbol;
            this.title = title;
            this.source = source;
            this.published = published;
        }
    }

    private static final int MAX_SYMBOLS = 8;
    private static final int MAX_ITEMS = 12;
    private static List<Item> last = new ArrayList<Item>();

    private Headlines() {}

    /** Blocking. Never throws. */
    static synchronized List<Item> fetch(List<String> stocks, List<String> crypto) {
        List<String[]> wanted = new ArrayList<String[]>();
        if (stocks != null) {
            for (int i = 0; i < stocks.size() && wanted.size() < MAX_SYMBOLS; i++) {
                wanted.add(new String[]{stocks.get(i), Markets.yahooSymbol(stocks.get(i))});
            }
        }
        if (crypto != null) {
            for (int i = 0; i < crypto.size() && wanted.size() < MAX_SYMBOLS; i++) {
                String show = crypto.get(i);
                String yahoo = show.toUpperCase(Locale.US).replace('.', '-');
                if (!yahoo.endsWith("-USD")) yahoo = yahoo + "-USD";
                wanted.add(new String[]{show, yahoo});
            }
        }
        if (wanted.isEmpty()) {
            last = new ArrayList<Item>();
            return last;
        }
        List<Item> found = new ArrayList<Item>();
        boolean any = false;
        for (int i = 0; i < wanted.size(); i++) {
            try {
                String body = Weather.get("https://feeds.finance.yahoo.com/rss/2.0/headline?s="
                        + java.net.URLEncoder.encode(wanted.get(i)[1], "UTF-8")
                        + "&region=US&lang=en-US");
                List<Item> parsed = parse(wanted.get(i)[0], body);
                if (!parsed.isEmpty()) any = true;
                for (int j = 0; j < parsed.size() && j < 2; j++) found.add(parsed.get(j));
            } catch (Exception ignored) {
            }
        }
        if (!any) return new ArrayList<Item>(last);
        Collections.sort(found, new Comparator<Item>() {
            @Override public int compare(Item a, Item b) {
                return a.published > b.published ? -1 : a.published < b.published ? 1 : 0;
            }
        });
        List<Item> deduped = new ArrayList<Item>();
        for (int i = 0; i < found.size() && deduped.size() < MAX_ITEMS; i++) {
            Item item = found.get(i);
            boolean seen = false;
            String key = item.title.toLowerCase(Locale.US);
            for (int j = 0; j < deduped.size(); j++) {
                if (deduped.get(j).title.toLowerCase(Locale.US).equals(key)) seen = true;
            }
            if (!seen) deduped.add(item);
        }
        last = deduped;
        return new ArrayList<Item>(last);
    }

    static String age(long published) {
        if (published <= 0) return "";
        long mins = Math.max(0, (System.currentTimeMillis() - published) / 60000L);
        if (mins < 60) return mins + "m";
        if (mins < 60 * 24) return (mins / 60) + "h";
        return (mins / 1440) + "d";
    }

    private static List<Item> parse(String symbol, String xml) {
        List<Item> out = new ArrayList<Item>();
        if (xml == null) return out;
        int from = 0;
        while (out.size() < 4) {
            int start = xml.indexOf("<item>", from);
            if (start < 0) break;
            int end = xml.indexOf("</item>", start);
            if (end < 0) break;
            String item = xml.substring(start, end);
            String title = text(item, "title");
            if (title.length() > 0 && !"Yahoo! Finance".equals(title)) {
                long when = time(text(item, "pubDate"));
                out.add(new Item(symbol, title, "Yahoo", when));
            }
            from = end + 7;
        }
        return out;
    }

    private static String text(String item, String tag) {
        String open = "<" + tag + ">";
        int a = item.indexOf(open);
        if (a < 0) return "";
        a += open.length();
        int b = item.indexOf("</" + tag + ">", a);
        if (b < 0) return "";
        String raw = item.substring(a, b).trim();
        if (raw.startsWith("<![CDATA[")) raw = raw.substring(9);
        if (raw.endsWith("]]>")) raw = raw.substring(0, raw.length() - 3);
        return decode(raw.trim());
    }

    private static String decode(String s) {
        String prev;
        do {
            prev = s;
            s = s.replace("&amp;", "&");
        } while (!s.equals(prev));
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'");
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            int a = s.indexOf("&#", i);
            if (a < 0) {
                out.append(s.substring(i));
                break;
            }
            out.append(s.substring(i, a));
            int semi = s.indexOf(';', a);
            if (semi < 0 || semi - a > 8) {
                out.append("&#");
                i = a + 2;
                continue;
            }
            String num = s.substring(a + 2, semi);
            try {
                int cp = (num.startsWith("x") || num.startsWith("X"))
                        ? Integer.parseInt(num.substring(1), 16)
                        : Integer.parseInt(num);
                if (cp > 0 && cp < 65536) out.append((char) cp);
                i = semi + 1;
            } catch (NumberFormatException e) {
                out.append("&#");
                i = a + 2;
            }
        }
        return out.toString();
    }

    private static long time(String pub) {
        if (pub == null || pub.length() == 0) return 0;
        String[] patterns = {"EEE, dd MMM yyyy HH:mm:ss Z", "EEE, dd MMM yyyy HH:mm:ss z"};
        for (int i = 0; i < patterns.length; i++) {
            try {
                Date d = new SimpleDateFormat(patterns[i], Locale.US).parse(pub);
                if (d != null) return d.getTime();
            } catch (ParseException ignored) {
            }
        }
        return 0;
    }
}

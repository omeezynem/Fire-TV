package com.tvdash.launcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Live prices for the on-screen ticker. Stocks and ETFs come from Yahoo Finance (no key).
 * Crypto comes from CoinGecko (no key). Each quote is only a symbol, a price, and today's
 * move. Quantities, balances, and holdings are never requested or kept.
 *
 * <p>Stock change is {@code regularMarketPrice} against {@code chartPreviousClose} on a 1-day
 * range. A longer range makes {@code chartPreviousClose} mean something else, so the spark
 * call and the chart fallback both ask for one day. The spark call batches every symbol;
 * the chart endpoint is the fallback when spark omits one.
 */
final class Markets {
    static final class Quote {
        final String symbol;
        final boolean hasPrice;
        final boolean hasChange;
        final double price;
        /** Percent move for the day (stocks) or 24 hours (crypto). Positive means up. */
        final double changePct;

        Quote(String symbol, boolean hasPrice, boolean hasChange, double price, double changePct) {
            this.symbol = symbol;
            this.hasPrice = hasPrice;
            this.hasChange = hasChange;
            this.price = price;
            this.changePct = changePct;
        }

        static Quote pending(String symbol) {
            return new Quote(symbol, false, false, 0, 0);
        }
    }

    /** 1 up, -1 down, 0 flat. Matches the percent after it is rounded to two decimals. */
    static int direction(Quote q) {
        if (q == null || !q.hasPrice || !q.hasChange) return 0;
        if (q.changePct >= 0.005) return 1;
        if (q.changePct <= -0.005) return -1;
        return 0;
    }

    static String arrow(Quote q) {
        int d = direction(q);
        if (d > 0) return "\u25B2"; // ▲
        if (d < 0) return "\u25BC"; // ▼
        return "\u2013";
    }

    static String formatPrice(double p) {
        double a = Math.abs(p);
        if (a >= 1000) return String.format(Locale.getDefault(), "%,.2f", p);
        if (a >= 1) return String.format(Locale.getDefault(), "%.2f", p);
        if (a >= 0.01) return String.format(Locale.getDefault(), "%.4f", p);
        if (a == 0) return String.format(Locale.getDefault(), "%.2f", 0.0);
        String s = String.format(Locale.US, "%.8f", p);
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '0') end--;
        if (s.charAt(end - 1) == '.') end--;
        return s.substring(0, end);
    }

    static String formatChange(double pct) {
        double a = Math.abs(pct);
        String num = String.format(Locale.getDefault(), a >= 100 ? "%.1f" : "%.2f", a);
        if (pct >= 0.005) return "+" + num + "%";
        if (pct <= -0.005) return "\u2212" + num + "%";
        return String.format(Locale.getDefault(), "%.2f%%", 0.0);
    }

    private static final Object LOCK = new Object();
    private static final Map<String, Quote> cache = new HashMap<>();
    /** CoinGecko id for a ticker, or "" when a search proved there is no match. */
    private static final Map<String, String> resolvedIds = new HashMap<>();

    /** Common tickers to CoinGecko ids. Not a watchlist; the on-screen list starts empty. */
    private static final Map<String, String> COIN_IDS = new HashMap<>();

    static {
        coin("BTC", "bitcoin");
        coin("ETH", "ethereum");
        coin("XRP", "ripple");
        coin("DOGE", "dogecoin");
        coin("SOL", "solana");
        coin("ADA", "cardano");
        coin("DOT", "polkadot");
        coin("AVAX", "avalanche-2");
        coin("LINK", "chainlink");
        coin("MATIC", "matic-network");
        coin("POL", "polygon-ecosystem-token");
        coin("LTC", "litecoin");
        coin("BCH", "bitcoin-cash");
        coin("UNI", "uniswap");
        coin("ATOM", "cosmos");
        coin("XLM", "stellar");
        coin("SHIB", "shiba-inu");
        coin("TRX", "tron");
        coin("ETC", "ethereum-classic");
        coin("BNB", "binancecoin");
        coin("USDC", "usd-coin");
        coin("USDT", "tether");
        coin("TON", "the-open-network");
        coin("NEAR", "near");
        coin("APT", "aptos");
        coin("ARB", "arbitrum");
        coin("OP", "optimism");
        coin("SUI", "sui");
        coin("PEPE", "pepe");
        coin("FIL", "filecoin");
        coin("ICP", "internet-computer");
        coin("HBAR", "hedera-hashgraph");
        coin("VET", "vechain");
        coin("ALGO", "algorand");
        coin("AAVE", "aave");
        coin("CRO", "crypto-com-chain");
        coin("DAI", "dai");
        coin("XMR", "monero");
        coin("ZEC", "zcash");
        coin("INJ", "injective-protocol");
        coin("TIA", "celestia");
        coin("SEI", "sei-network");
        coin("FET", "fetch-ai");
        coin("RENDER", "render-token");
        coin("WIF", "dogwifcoin");
        coin("BONK", "bonk");
        coin("TAO", "bittensor");
        coin("WLD", "worldcoin-wld");
        coin("JUP", "jupiter-exchange-solana");
        coin("HYPE", "hyperliquid");
    }

    private static void coin(String symbol, String id) {
        COIN_IDS.put(symbol, id);
    }

    /** Last good prices, or a placeholder per symbol when nothing has loaded yet. No network. */
    static List<Quote> remembered(List<String> stocks, List<String> crypto) {
        List<Quote> out = new ArrayList<>();
        synchronized (LOCK) {
            if (stocks != null) {
                for (String s : stocks) out.add(rememberLocked("s:" + s, s));
            }
            if (crypto != null) {
                for (String s : crypto) out.add(rememberLocked("c:" + s, displayCrypto(s)));
            }
        }
        return out;
    }

    /**
     * Blocking. Call off the main thread. Never throws: a network failure keeps the last
     * good price for that symbol.
     */
    static List<Quote> fetch(List<String> stocks, List<String> crypto) {
        if (stocks == null) stocks = new ArrayList<>();
        if (crypto == null) crypto = new ArrayList<>();
        Loaded loaded = new Loaded();
        try {
            if (!stocks.isEmpty()) loadStocks(stocks, loaded);
        } catch (RuntimeException ignored) {
        }
        try {
            if (!crypto.isEmpty()) loadCrypto(crypto, loaded);
        } catch (RuntimeException ignored) {
        }
        synchronized (LOCK) {
            List<Quote> out = new ArrayList<>();
            for (String s : stocks) out.add(take(loaded, "s:" + s, s));
            for (String s : crypto) out.add(take(loaded, "c:" + s, displayCrypto(s)));
            return out;
        }
    }

    private static Quote rememberLocked(String key, String shown) {
        Quote q = cache.get(key);
        return q != null ? q : Quote.pending(shown);
    }

    private static Quote take(Loaded loaded, String key, String shown) {
        Quote fresh = loaded.quotes.get(key);
        if (fresh != null) {
            cache.put(key, fresh);
            return fresh;
        }
        if (loaded.misses.contains(key)) {
            cache.remove(key);
            return Quote.pending(shown);
        }
        return rememberLocked(key, shown);
    }

    /** What to draw for a crypto entry. Known ids display as their ticker (bitcoin → BTC). */
    static String displayCrypto(String raw) {
        if (raw == null) return "";
        String key = raw.toUpperCase(Locale.US);
        if (COIN_IDS.containsKey(key)) return key;
        String id = key.toLowerCase(Locale.US);
        for (Map.Entry<String, String> e : COIN_IDS.entrySet()) {
            if (e.getValue().equals(id)) return e.getKey();
        }
        return key;
    }

    // ---------------------------------------------------------------- stocks

    /** US class shares are BRK.B in plain text and BRK-B on Yahoo. Exchange codes keep the dot. */
    static String yahooSymbol(String symbol) {
        String s = symbol == null ? "" : symbol.toUpperCase(Locale.US).trim();
        int dot = s.lastIndexOf('.');
        if (dot > 0 && s.length() - dot == 2) {
            boolean letters = true;
            for (int i = 0; i < dot; i++) {
                char c = s.charAt(i);
                if (c < 'A' || c > 'Z') {
                    letters = false;
                    break;
                }
            }
            char cls = s.charAt(dot + 1);
            if (letters && cls >= 'A' && cls <= 'C') s = s.substring(0, dot) + "-" + cls;
        }
        return s;
    }

    private static void loadStocks(List<String> stocks, Loaded loaded) {
        Map<String, String> yahooToRaw = new LinkedHashMap<>();
        Map<String, String> rawToYahoo = new HashMap<>();
        for (String raw : stocks) {
            String y = yahooSymbol(raw).toUpperCase(Locale.US);
            rawToYahoo.put(raw, y);
            if (!yahooToRaw.containsKey(y)) yahooToRaw.put(y, raw);
        }
        Map<String, Quote> byYahoo = new HashMap<>();
        HttpResult spark = yahoo(sparkPath(yahooToRaw.keySet()));
        if (spark != null && spark.code == 200) {
            Map<String, Quote> parsed = parseSpark(spark.body);
            if (parsed != null) byYahoo = parsed;
        }
        // A 429 means Yahoo is throttling this TV. Don't turn that into one request per symbol.
        int networkFails = spark != null && spark.code == 429 ? 8 : 0;
        for (String raw : stocks) {
            String y = rawToYahoo.get(raw);
            Quote q = byYahoo.get(y);
            if (q == null && networkFails < 2) {
                One one = chartOne(y);
                if (one.network) networkFails++;
                else networkFails = 0;
                if (one.miss) loaded.misses.add("s:" + raw);
                q = one.quote;
            }
            if (q != null) loaded.quotes.put("s:" + raw, new Quote(raw, true, q.hasChange, q.price, q.changePct));
        }
    }

    private static String sparkPath(Set<String> yahooSymbols) {
        StringBuilder symbols = new StringBuilder();
        for (String y : yahooSymbols) {
            if (symbols.length() > 0) symbols.append(',');
            symbols.append(encode(y));
        }
        return "/v7/finance/spark?range=1d&interval=1d&symbols=" + symbols;
    }

    private static Map<String, Quote> parseSpark(String body) {
        try {
            JSONObject spark = new JSONObject(body).optJSONObject("spark");
            if (spark == null) return null;
            JSONArray result = spark.optJSONArray("result");
            if (result == null) return null;
            Map<String, Quote> out = new HashMap<>();
            for (int i = 0; i < result.length(); i++) {
                JSONObject item = result.optJSONObject(i);
                if (item == null) continue;
                String symbol = item.optString("symbol", "").toUpperCase(Locale.US);
                JSONArray response = item.optJSONArray("response");
                if (symbol.isEmpty() || response == null || response.length() == 0) continue;
                JSONObject first = response.optJSONObject(0);
                if (first == null) continue;
                Quote q = fromMeta(first.optJSONObject("meta"), symbol);
                if (q != null) out.put(symbol, q);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static One chartOne(String yahoo) {
        HttpResult res = yahoo("/v8/finance/chart/" + encode(yahoo) + "?interval=1d&range=1d");
        if (res == null) return One.network();
        if (res.code == 404) return One.miss();
        if (res.code == 429 || res.code / 100 != 2) return One.network();
        try {
            JSONObject chart = new JSONObject(res.body).optJSONObject("chart");
            if (chart == null) return One.network();
            if (!chart.isNull("error")) return One.miss();
            JSONArray result = chart.optJSONArray("result");
            if (result == null || result.length() == 0) return One.miss();
            JSONObject meta = result.getJSONObject(0).optJSONObject("meta");
            Quote q = fromMeta(meta, yahoo);
            return q == null ? One.miss() : One.ok(q);
        } catch (Exception e) {
            return One.network();
        }
    }

    /** Price and today's move from a Yahoo meta object. */
    private static Quote fromMeta(JSONObject meta, String display) {
        try {
            if (meta == null || meta.isNull("regularMarketPrice")) return null;
            double price = meta.getDouble("regularMarketPrice");
            if (Double.isNaN(price) || Double.isInfinite(price) || price < 0) return null;
            boolean hasChange = false;
            double pct = 0;
            if (!meta.isNull("chartPreviousClose")) {
                double prev = meta.getDouble("chartPreviousClose");
                if (prev > 0 && !Double.isInfinite(prev)) {
                    pct = (price - prev) / prev * 100.0;
                    hasChange = !Double.isNaN(pct) && !Double.isInfinite(pct);
                }
            }
            if (!hasChange && !meta.isNull("regularMarketChangePercent")) {
                pct = meta.getDouble("regularMarketChangePercent");
                hasChange = !Double.isNaN(pct) && !Double.isInfinite(pct);
            }
            return new Quote(display, true, hasChange, price, hasChange ? pct : 0);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- crypto

    private static void loadCrypto(List<String> crypto, Loaded loaded) {
        List<String> ids = new ArrayList<>();
        List<String> raws = new ArrayList<>();
        for (String raw : crypto) {
            String id = coinId(raw, false);
            if (id == null) continue;
            ids.add(id);
            raws.add(raw);
        }
        Set<String> priced = new HashSet<>();
        if (!ids.isEmpty() && !fetchPrices(ids, raws, loaded, priced)) return;

        List<String> againIds = new ArrayList<>();
        List<String> againRaws = new ArrayList<>();
        for (int i = 0; i < raws.size(); i++) {
            String raw = raws.get(i);
            if (priced.contains(raw)) continue;
            if (COIN_IDS.containsKey(raw.toUpperCase(Locale.US))) continue;
            String id = coinId(raw, true);
            if (id == null) continue;
            againIds.add(id);
            againRaws.add(raw);
        }
        // Symbols that were not even looked up (no id yet) still need a search.
        for (String raw : crypto) {
            if (priced.contains(raw) || againRaws.contains(raw)) continue;
            if (COIN_IDS.containsKey(raw.toUpperCase(Locale.US))) continue;
            String id = coinId(raw, true);
            if (id == null) continue;
            againIds.add(id);
            againRaws.add(raw);
        }
        if (!againIds.isEmpty()) fetchPrices(againIds, againRaws, loaded, priced);
    }

    /** @return false when the network failed and cached prices should be left alone */
    private static boolean fetchPrices(List<String> ids, List<String> raws, Loaded loaded, Set<String> priced) {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) joined.append(',');
            joined.append(ids.get(i));
        }
        HttpResult res = httpGet("https://api.coingecko.com/api/v3/simple/price?vs_currencies=usd"
                + "&include_24hr_change=true&ids=" + joined);
        if (res == null || res.code != 200) return false;
        try {
            JSONObject json = new JSONObject(res.body);
            for (int i = 0; i < ids.size(); i++) {
                JSONObject row = json.optJSONObject(ids.get(i));
                if (row == null || row.isNull("usd")) continue;
                double price = row.getDouble("usd");
                if (Double.isNaN(price) || Double.isInfinite(price) || price < 0) continue;
                boolean hasChange = !row.isNull("usd_24h_change");
                double pct = 0;
                if (hasChange) {
                    pct = row.getDouble("usd_24h_change");
                    if (Double.isNaN(pct) || Double.isInfinite(pct)) hasChange = false;
                }
                String raw = raws.get(i);
                loaded.quotes.put("c:" + raw, new Quote(displayCrypto(raw), true, hasChange, price, hasChange ? pct : 0));
                priced.add(raw);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @param search when true, ask CoinGecko if the direct id missed. A failed search is remembered
     *               so the ticker doesn't look the same coin up on every refresh.
     */
    private static String coinId(String raw, boolean search) {
        String key = raw.toUpperCase(Locale.US);
        String known = COIN_IDS.get(key);
        if (known != null) return known;
        synchronized (LOCK) {
            String cached = resolvedIds.get(key);
            if (cached != null) return cached.isEmpty() ? null : cached;
        }
        if (!search) {
            if (key.indexOf('-') >= 0 || key.length() >= 6) {
                String id = key.toLowerCase(Locale.US);
                if (safeId(id)) return id;
            }
            return null;
        }
        String found = searchCoin(key);
        if (found == null) return null; // network failure: don't cache
        synchronized (LOCK) {
            resolvedIds.put(key, found);
        }
        return found.isEmpty() ? null : found;
    }

    private static String searchCoin(String symbol) {
        HttpResult res = httpGet("https://api.coingecko.com/api/v3/search?query=" + encode(symbol));
        if (res == null || res.code != 200) return null;
        try {
            JSONArray coins = new JSONObject(res.body).optJSONArray("coins");
            if (coins == null) return "";
            String want = symbol.toLowerCase(Locale.US);
            String best = null;
            int bestRank = Integer.MAX_VALUE;
            for (int i = 0; i < coins.length(); i++) {
                JSONObject c = coins.optJSONObject(i);
                if (c == null) continue;
                if (!want.equals(c.optString("symbol", "").toLowerCase(Locale.US))) continue;
                if (c.isNull("id")) continue;
                int rank = Integer.MAX_VALUE;
                if (!c.isNull("market_cap_rank")) rank = c.optInt("market_cap_rank", Integer.MAX_VALUE);
                if (rank <= 0) rank = Integer.MAX_VALUE;
                if (best == null || rank < bestRank) {
                    bestRank = rank;
                    best = c.getString("id");
                }
            }
            if (best == null || !safeId(best)) return "";
            return best;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean safeId(String id) {
        if (id == null || id.isEmpty() || id.length() > 80) return false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-';
            if (!ok) return false;
        }
        return id.charAt(0) != '-' && id.charAt(id.length() - 1) != '-';
    }

    // ---------------------------------------------------------------- http

    private static final class Loaded {
        final Map<String, Quote> quotes = new HashMap<>();
        final Set<String> misses = new HashSet<>();
    }

    private static final class One {
        final Quote quote;
        final boolean miss;
        final boolean network;

        private One(Quote quote, boolean miss, boolean network) {
            this.quote = quote;
            this.miss = miss;
            this.network = network;
        }

        static One ok(Quote q) { return new One(q, false, false); }
        static One miss() { return new One(null, true, false); }
        static One network() { return new One(null, false, true); }
    }

    private static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    /** Yahoo on query1, then query2. A browser user agent is rate-limited; the app name is not. */
    private static HttpResult yahoo(String pathAndQuery) {
        HttpResult first = httpGet("https://query1.finance.yahoo.com" + pathAndQuery);
        if (first != null && (first.code == 200 || first.code == 404)) return first;
        HttpResult second = httpGet("https://query2.finance.yahoo.com" + pathAndQuery);
        return second != null ? second : first;
    }

    private static HttpResult httpGet(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty("User-Agent", "TVDash/1.2 (Android TV launcher)");
            int code = c.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            return new HttpResult(code, in == null ? "" : read(in));
        } catch (IOException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        int n;
        while ((n = in.read(chunk)) > 0) {
            buf.write(chunk, 0, n);
            if (buf.size() > 1024 * 1024) break;
        }
        return buf.toString("UTF-8");
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }
}

package com.tvdash.launcher;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP server. Serves the phone remote page and a JSON API the page uses to control the TV live.
 * Listens on every interface, so a phone can reach it on the home network or over a VPN such as Tailscale.
 * The first successful PIN mints a device token. After that the phone sends the token, which stays valid
 * until Sign out phones. Wrong PINs from phones that are not paired still lock out for a few minutes.
 */
final class TvServer {
    static final int FIRST_PORT = 8080;
    private static final int MAX_BODY = 25 * 1024 * 1024;
    private static final int MAX_FAILURES = 5;
    private static final long LOCKOUT_MS = 5 * 60 * 1000;

    private final Context app;
    private final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Map<String, byte[]> iconCache = Collections.synchronizedMap(new HashMap<String, byte[]>());

    private volatile ServerSocket socket;
    private volatile int port;
    private int failures;
    private long lockedUntil;

    TvServer(Context context) {
        app = context.getApplicationContext();
        prefs = new Prefs(app);
    }

    static volatile TvServer running;

    int port() {
        return port;
    }

    /** e.g. http://192.168.1.50:8080, or null when the TV has no network address yet. LAN is preferred. */
    String address() {
        List<String> urls = urls();
        return urls.isEmpty() ? null : urls.get(0);
    }

    /** Every address the phone can open, LAN first, then a Tailscale 100.x address when the TV has one. */
    List<String> urls() {
        List<String> out = new ArrayList<>();
        if (port == 0) return out;
        for (String ip : localIps()) out.add("http://" + ip + ":" + port);
        return out;
    }

    void start() {
        Thread t = new Thread(() -> {
            for (int p = FIRST_PORT; p < FIRST_PORT + 10 && socket == null; p++) {
                ServerSocket s = null;
                try {
                    s = new ServerSocket();
                    // Bind the wildcard address so VPN interfaces (Tailscale 100.x) are included.
                    s.setReuseAddress(true);
                    s.bind(new InetSocketAddress(p));
                    socket = s;
                    port = p;
                } catch (IOException ignored) {
                    if (s != null) try {
                        s.close();
                    } catch (IOException close) {
                    }
                }
            }
            ServerSocket s = socket;
            if (s == null) return;
            running = this;
            while (!s.isClosed()) {
                try {
                    final Socket client = s.accept();
                    pool.execute(() -> handle(client));
                } catch (IOException e) {
                    if (s.isClosed()) break;
                }
            }
        }, "tv-dash-server");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        if (running == this) running = null;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    /** IPv4 addresses worth showing. Home LAN first, then Tailscale (100.64.0.0/10), which is not site-local. */
    static List<String> localIps() {
        List<String> lan = new ArrayList<>();
        List<String> vpn = new ArrayList<>();
        List<String> other = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String host = a.getHostAddress();
                    if (host == null || lan.contains(host) || vpn.contains(host) || other.contains(host)) continue;
                    if (a.isSiteLocalAddress()) lan.add(host);
                    else if (isTailscale((Inet4Address) a)) vpn.add(host);
                    else other.add(host);
                }
            }
        } catch (Exception ignored) {
        }
        List<String> all = new ArrayList<>();
        all.addAll(lan);
        all.addAll(vpn);
        if (all.isEmpty()) all.addAll(other);
        return all;
    }

    /** Tailscale's IPv4 range is the carrier-grade NAT block 100.64.0.0/10. */
    static boolean isTailscale(Inet4Address a) {
        byte[] b = a.getAddress();
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return first == 100 && second >= 64 && second <= 127;
    }

    // ---------------------------------------------------------------- HTTP plumbing

    private static final class Request {
        String method;
        String path;
        final Map<String, String> query = new HashMap<>();
        final Map<String, String> headers = new HashMap<>();
        byte[] body = new byte[0];

        JSONObject json() throws Exception {
            return body.length == 0 ? new JSONObject() : new JSONObject(new String(body, StandardCharsets.UTF_8));
        }
    }

    private static final class Response {
        final int status;
        final String type;
        final byte[] body;

        Response(int status, String type, byte[] body) {
            this.status = status;
            this.type = type;
            this.body = body;
        }

        static Response json(int status, JSONObject o) {
            return new Response(status, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8));
        }

        static Response ok() {
            try {
                return json(200, new JSONObject().put("ok", true));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        static Response error(int status, String message) {
            try {
                return json(status, new JSONObject().put("ok", false).put("error", message));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private void handle(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            Response res;
            Request req = null;
            try {
                req = read(in);
                res = req == null ? Response.error(400, "Bad request") : route(req);
            } catch (Exception e) {
                res = Response.error(500, String.valueOf(e.getMessage()));
            }
            write(s.getOutputStream(), res, req != null && "HEAD".equals(req.method));
        } catch (IOException ignored) {
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') line.write(b);
            if (line.size() > 8192) throw new IOException("Header too long");
        }
        if (b == -1 && line.size() == 0) return null;
        return line.toString("UTF-8");
    }

    private static Request read(InputStream in) throws IOException {
        String first = readLine(in);
        if (first == null) return null;
        String[] parts = first.split(" ");
        if (parts.length < 2) return null;
        Request r = new Request();
        r.method = parts[0];
        String target = parts[1];
        int q = target.indexOf('?');
        r.path = q < 0 ? target : target.substring(0, q);
        if (q >= 0) {
            for (String pair : target.substring(q + 1).split("&")) {
                int eq = pair.indexOf('=');
                if (eq > 0) r.query.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                        URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            }
        }
        String line;
        int count = 0;
        while ((line = readLine(in)) != null && !line.isEmpty()) {
            int colon = line.indexOf(':');
            if (colon > 0) r.headers.put(line.substring(0, colon).trim().toLowerCase(Locale.ROOT), line.substring(colon + 1).trim());
            if (++count > 100) return null;
        }
        String len = r.headers.get("content-length");
        if (len != null) {
            int n = Integer.parseInt(len.trim());
            if (n < 0 || n > MAX_BODY) return null;
            byte[] body = new byte[n];
            int off = 0;
            while (off < n) {
                int got = in.read(body, off, n - off);
                if (got < 0) throw new IOException("Body cut short");
                off += got;
            }
            r.body = body;
        }
        return r;
    }

    private static void write(OutputStream out, Response res, boolean headOnly) throws IOException {
        String head = "HTTP/1.1 " + res.status + " " + reason(res.status) + "\r\n"
                + "Content-Type: " + res.type + "\r\n"
                + "Content-Length: " + res.body.length + "\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        if (!headOnly) out.write(res.body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 429: return "Too Many Requests";
            default: return "Error";
        }
    }

    // ---------------------------------------------------------------- routes

    private Response route(Request r) throws Exception {
        String p = r.path;
        if (p.equals("/") || p.equals("/index.html")) return asset("remote.html", "text/html; charset=utf-8");
        if (p.equals("/manifest.webmanifest")) return manifest();
        if (p.equals("/app-icon.png")) return appIcon();
        if (p.equals("/api/ping")) return Response.json(200, new JSONObject().put("ok", true).put("name", "TV Dash"));
        if (!p.startsWith("/api/")) return Response.error(404, "Not found");

        Response denied = authorize(r);
        if (denied != null) return denied;

        boolean post = r.method.equals("POST");
        switch (p) {
            case "/api/state": return state();
            case "/api/icon": return icon(r.query.get("pkg"));
        }
        if (!post) return Response.error(404, "Not found");
        switch (p) {
            case "/api/pair": return pair(r);
            case "/api/signout": return signOut(r);
            case "/api/launch": return launch(r.json().optString("pkg"));
            case "/api/home": return home();
            case "/api/screensaver": return screensaver(r.json().optBoolean("on", true));
            case "/api/photo/next": return nextPhoto();
            case "/api/photo": return photo(r.body);
            case "/api/photos/clear": return clearPhotos();
            case "/api/message": return message(r.json());
            case "/api/settings": return settings(r.json());
            case "/api/favorites": return favorites(r.json());
            default: return Response.error(404, "Not found");
        }
    }

    /**
     * A paired phone sends its device token and is let in from any address. A PIN is only for the first
     * pairing. Wrong PINs still count toward the lockout. A dead token does not, so signed-out phones
     * cannot lock the TV.
     */
    private synchronized Response authorize(Request r) {
        String device = headerOrQuery(r, "x-device", "device");
        if (prefs.isDevice(device)) return null;

        long now = System.currentTimeMillis();
        if (now < lockedUntil) return Response.error(429, "Too many wrong PINs. Try again in a few minutes.");

        String pin = headerOrQuery(r, "x-pin", "pin");
        if (pin != null && prefs.pin().equals(pin)) {
            failures = 0;
            return null;
        }
        if (device != null && pin == null) return Response.error(401, "This phone was signed out");
        if (++failures >= MAX_FAILURES) {
            failures = 0;
            lockedUntil = now + LOCKOUT_MS;
        }
        return Response.error(401, "Wrong PIN");
    }

    private static String headerOrQuery(Request r, String header, String query) {
        String v = r.headers.get(header);
        if (v == null || v.isEmpty()) v = r.query.get(query);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    /** PIN just succeeded. Mint a token this phone can keep using, including from another address. */
    private Response pair(Request r) throws Exception {
        String pin = headerOrQuery(r, "x-pin", "pin");
        if (pin == null || !prefs.pin().equals(pin)) return Response.error(401, "Enter the PIN from the TV to sign in");
        return Response.json(200, new JSONObject().put("ok", true).put("device", prefs.newDevice()));
    }

    private Response signOut(Request r) {
        prefs.revokeDevice(headerOrQuery(r, "x-device", "device"));
        return Response.ok();
    }

    private Response asset(String name, String type) throws IOException {
        try (InputStream in = app.getAssets().open(name)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) > 0) buf.write(chunk, 0, n);
            return new Response(200, type, buf.toByteArray());
        }
    }

    private Response manifest() throws Exception {
        JSONObject m = new JSONObject()
                .put("name", "TV Dash Remote")
                .put("short_name", "TV Dash")
                .put("start_url", "/")
                .put("display", "standalone")
                .put("background_color", "#0B1426")
                .put("theme_color", "#0B1426")
                .put("icons", new JSONArray().put(new JSONObject()
                        .put("src", "/app-icon.png").put("sizes", "192x192").put("type", "image/png")));
        return new Response(200, "application/manifest+json", m.toString().getBytes(StandardCharsets.UTF_8));
    }

    private Response appIcon() {
        int id = app.getResources().getIdentifier("ic_launcher", "drawable", app.getPackageName());
        Drawable d = id == 0 ? app.getApplicationInfo().loadIcon(app.getPackageManager()) : app.getDrawable(id);
        return new Response(200, "image/png", png(d, 192, 192));
    }

    private Response state() throws Exception {
        JSONObject s = new JSONObject();
        boolean h24 = prefs.clock24();
        s.put("name", "TV Dash");
        s.put("time", new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.getDefault()).format(new Date()));
        s.put("weather", Hub.weatherLine);
        s.put("onScreen", Hub.screen != null);
        s.put("saverOn", Hub.saverOn);
        s.put("photos", Photos.list(app).size());
        JSONArray addresses = new JSONArray();
        for (String url : urls()) addresses.put(url);
        s.put("addresses", addresses);
        s.put("settings", new JSONObject()
                .put("city", prefs.city())
                .put("fahrenheit", prefs.fahrenheit())
                .put("clock24", h24)
                .put("slideSeconds", prefs.slideSeconds())
                .put("saverMinutes", prefs.saverMinutes())
                .put("showAllApps", prefs.showAllApps())
                .put("stocks", stringArray(prefs.stocks()))
                .put("crypto", stringArray(prefs.crypto())));
        JSONArray favs = new JSONArray();
        List<String> favList = prefs.favorites();
        if (favList != null) for (String f : favList) favs.put(f);
        s.put("favorites", favs);
        JSONArray apps = new JSONArray();
        for (AppEntry e : AppEntry.loadAll(app.getPackageManager(), app.getPackageName())) {
            apps.put(new JSONObject().put("pkg", e.pkg).put("label", e.label));
        }
        s.put("apps", apps);
        return Response.json(200, s);
    }

    private Response icon(String pkg) {
        if (pkg == null) return Response.error(400, "pkg missing");
        byte[] cached = iconCache.get(pkg);
        if (cached == null) {
            try {
                Drawable d = app.getPackageManager().getApplicationIcon(pkg);
                cached = png(d, 96, 96);
                iconCache.put(pkg, cached);
            } catch (PackageManager.NameNotFoundException e) {
                return Response.error(404, "No such app");
            }
        }
        return new Response(200, "image/png", cached);
    }

    private static byte[] png(Drawable d, int w, int h) {
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.PNG, 100, out);
        bm.recycle();
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- actions

    private Response launch(String pkg) {
        Intent intent = null;
        for (AppEntry e : AppEntry.loadAll(app.getPackageManager(), app.getPackageName())) {
            if (e.pkg.equals(pkg)) intent = e.launch;
        }
        if (intent == null) return Response.error(404, "That app isn't installed on the TV");
        return start(intent);
    }

    private Response home() {
        return start(new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    /**
     * Android 10+ blocks apps in the background from opening screens unless they may draw over other apps.
     * That's fine while TV Dash is on screen; otherwise the TV may ignore the request.
     */
    private Response start(final Intent intent) {
        final boolean[] ok = {true};
        runOnMain(() -> {
            try {
                app.startActivity(intent);
            } catch (ActivityNotFoundException | SecurityException e) {
                ok[0] = false;
            }
        });
        if (!ok[0]) return Response.error(500, "The TV couldn't open that");
        return Response.ok();
    }

    private Response screensaver(final boolean on) {
        final Hub.Screen screen = Hub.screen;
        if (screen == null) return Response.error(409, "Open TV Dash on the TV first");
        main.post(() -> screen.remoteScreensaver(on));
        return Response.ok();
    }

    private Response nextPhoto() {
        final Hub.Screen screen = Hub.screen;
        if (screen == null) return Response.error(409, "Open TV Dash on the TV first");
        main.post(screen::remoteNextPhoto);
        return Response.ok();
    }

    private Response photo(byte[] body) throws Exception {
        if (body.length < 100) return Response.error(400, "No photo received");
        boolean jpeg = (body[0] & 0xFF) == 0xFF && (body[1] & 0xFF) == 0xD8;
        if (!jpeg) return Response.error(400, "Send the photo as a JPEG");
        final File f = Photos.save(app, body);
        main.post(() -> {
            Hub.Screen screen = Hub.screen;
            if (screen != null) screen.remotePhotosChanged(f);
        });
        return Response.json(200, new JSONObject().put("ok", true).put("photos", Photos.list(app).size()));
    }

    private Response clearPhotos() {
        Photos.clear(app);
        main.post(() -> {
            Hub.Screen screen = Hub.screen;
            if (screen != null) screen.remotePhotosChanged(null);
        });
        return Response.ok();
    }

    private Response message(JSONObject j) {
        final String text = j.optString("text", "").trim();
        final int seconds = Math.max(3, Math.min(600, j.optInt("seconds", 15)));
        if (text.isEmpty()) return Response.error(400, "Message is empty");
        final String clipped = text.length() > 280 ? text.substring(0, 280) : text;
        main.post(() -> {
            Hub.Screen screen = Hub.screen;
            if (screen != null) screen.remoteMessage(clipped, seconds);
            else Toast.makeText(app, clipped, Toast.LENGTH_LONG).show();
        });
        return Response.ok();
    }

    private Response settings(JSONObject j) throws Exception {
        List<String> stocks = null;
        List<String> crypto = null;
        try {
            if (j.has("stocks")) stocks = symbolList(j, "stocks", false);
            if (j.has("crypto")) crypto = symbolList(j, "crypto", true);
        } catch (org.json.JSONException bad) {
            return Response.error(400, "stocks and crypto must be lists of symbols");
        }
        if (j.has("city")) prefs.setCity(j.getString("city"));
        if (j.has("fahrenheit")) prefs.setFahrenheit(j.getBoolean("fahrenheit"));
        if (j.has("clock24")) prefs.setClock24(j.getBoolean("clock24"));
        if (j.has("slideSeconds")) prefs.setSlideSeconds(pick(Prefs.SLIDE_SECONDS, j.getInt("slideSeconds")));
        if (j.has("saverMinutes")) prefs.setSaverMinutes(pick(Prefs.SAVER_MINUTES, j.getInt("saverMinutes")));
        if (j.has("showAllApps")) prefs.setShowAllApps(j.getBoolean("showAllApps"));
        if (stocks != null) prefs.setStocks(stocks);
        if (crypto != null) prefs.setCrypto(crypto);
        main.post(() -> {
            Hub.Screen screen = Hub.screen;
            if (screen != null) screen.remoteSettingsChanged();
        });
        return Response.ok();
    }

    /** Symbol lists from the phone. Arrays are the normal form; a comma-separated string is accepted too. */
    private static List<String> symbolList(JSONObject j, String key, boolean crypto) throws org.json.JSONException {
        Object v = j.get(key);
        if (v instanceof String) return Prefs.parseSymbols((String) v, crypto);
        if (!(v instanceof JSONArray)) throw new org.json.JSONException(key);
        JSONArray a = (JSONArray) v;
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < a.length(); i++) {
            if (a.isNull(i)) continue;
            raw.append(a.opt(i)).append('\n');
        }
        return Prefs.parseSymbols(raw.toString(), crypto);
    }

    private static JSONArray stringArray(List<String> values) {
        JSONArray a = new JSONArray();
        if (values != null) for (String s : values) a.put(s);
        return a;
    }

    /** Snap to the nearest allowed choice so the phone can't set odd values. */
    private static int pick(int[] allowed, int v) {
        int best = allowed[0];
        for (int a : allowed) if (Math.abs(a - v) < Math.abs(best - v)) best = a;
        return best;
    }

    private Response favorites(JSONObject j) throws Exception {
        JSONArray arr = j.getJSONArray("pkgs");
        java.util.ArrayList<String> list = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length() && i < 40; i++) {
            String p = arr.getString(i);
            if (!list.contains(p)) list.add(p);
        }
        prefs.setFavorites(list);
        main.post(() -> {
            Hub.Screen screen = Hub.screen;
            if (screen != null) screen.remoteFavoritesChanged();
        });
        return Response.ok();
    }

    /** Runs on the main thread and waits briefly for it, so the caller can report the result. */
    private void runOnMain(final Runnable r) {
        final Object lock = new Object();
        final boolean[] done = {false};
        main.post(() -> {
            try {
                r.run();
            } finally {
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            long until = System.currentTimeMillis() + 3000;
            while (!done[0]) {
                long left = until - System.currentTimeMillis();
                if (left <= 0) break;
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}

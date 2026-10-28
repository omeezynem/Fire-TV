package com.tvdash.launcher;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows the user saved, plus a best-effort read of Android TV's Watch Next list.
 * Fire TV apps usually do not publish Continue Watching there, so the row you can count on
 * is the one added from the phone. Opening a tile searches that show inside the matching app.
 */
final class Shows {
    static final class Show {
        final String name;
        final String poster;
        final String network;
        final String intentUri;

        Show(String name, String poster, String network, String intentUri) {
            this.name = name == null ? "" : name;
            this.poster = poster == null ? "" : poster;
            this.network = network == null ? "" : network;
            this.intentUri = intentUri == null ? "" : intentUri;
        }
    }

    private Shows() {}

    /** Phone search. Blocking. Returns a short list or empty on failure. */
    static List<Show> search(String query) {
        List<Show> out = new ArrayList<Show>();
        if (query == null) return out;
        String q = query.trim();
        if (q.length() < 2 || q.length() > 60) return out;
        try {
            String body = Weather.get("https://api.tvmaze.com/search/shows?q="
                    + java.net.URLEncoder.encode(q, "UTF-8"));
            JSONArray arr = new JSONArray(body);
            for (int i = 0; i < arr.length() && out.size() < 8; i++) {
                JSONObject show = arr.getJSONObject(i).optJSONObject("show");
                if (show == null) continue;
                String name = show.optString("name", "").trim();
                if (name.length() == 0) continue;
                String poster = "";
                JSONObject image = show.optJSONObject("image");
                if (image != null) poster = image.optString("medium", image.optString("original", ""));
                String network = "";
                JSONObject web = show.optJSONObject("webChannel");
                JSONObject net = show.optJSONObject("network");
                if (web != null) network = web.optString("name", "");
                if (network.length() == 0 && net != null) network = net.optString("name", "");
                out.add(new Show(name, poster, network, ""));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Other apps' Continue Watching, when the device actually exposes it. Empty on Fire TV most of the time. */
    static List<Show> watchNext(Context context) {
        List<Show> out = new ArrayList<Show>();
        if (context == null || Build.VERSION.SDK_INT < 26) return out;
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    Uri.parse("content://android.media.tv/watch_next_program"),
                    new String[]{"title", "poster_art_uri", "intent_uri", "package_name"},
                    null, null, null);
            if (cursor == null) return out;
            int title = cursor.getColumnIndex("title");
            int poster = cursor.getColumnIndex("poster_art_uri");
            int intent = cursor.getColumnIndex("intent_uri");
            int pkg = cursor.getColumnIndex("package_name");
            while (cursor.moveToNext() && out.size() < 12) {
                String name = title >= 0 ? cursor.getString(title) : "";
                if (name == null || name.trim().length() == 0) continue;
                String art = poster >= 0 ? cursor.getString(poster) : "";
                String uri = intent >= 0 ? cursor.getString(intent) : "";
                String owner = pkg >= 0 ? cursor.getString(pkg) : "";
                out.add(new Show(name.trim(), art == null ? "" : art, owner == null ? "" : owner, uri == null ? "" : uri));
            }
        } catch (Throwable ignored) {
            return new ArrayList<Show>();
        } finally {
            if (cursor != null) cursor.close();
        }
        return out;
    }

    /** Opens the show's own app at a search, or the saved watch-next intent. No account data is read. */
    static void open(Context context, Show show) {
        if (context == null || show == null || show.name.length() == 0) return;
        if (show.intentUri.length() > 0) {
            try {
                Intent direct = new Intent(Intent.ACTION_VIEW, Uri.parse(show.intentUri));
                direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(direct);
                return;
            } catch (ActivityNotFoundException ignored) {
            } catch (RuntimeException ignored) {
            }
        }
        String url = searchUrl(show.network, show.name);
        String[] packages = packagesFor(show.network);
        for (int i = 0; i < packages.length; i++) {
            if (!installed(context, packages[i])) continue;
            try {
                Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                view.setPackage(packages[i]);
                view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(view);
                return;
            } catch (ActivityNotFoundException ignored) {
            } catch (RuntimeException ignored) {
            }
            Intent launch = context.getPackageManager().getLaunchIntentForPackage(packages[i]);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(launch);
                    Toast.makeText(context, "Opened the app. Search for " + show.name, Toast.LENGTH_LONG).show();
                    return;
                } catch (RuntimeException ignored) {
                }
            }
        }
        try {
            Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(view);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, "No app on the TV opened a search for " + show.name, Toast.LENGTH_LONG).show();
        } catch (RuntimeException e) {
            Toast.makeText(context, "Couldn't open " + show.name, Toast.LENGTH_SHORT).show();
        }
    }

    private static boolean installed(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static String[] packagesFor(String network) {
        String n = network == null ? "" : network.toLowerCase(java.util.Locale.US);
        if (n.contains("netflix")) return new String[]{"com.netflix.ninja"};
        if (n.contains("prime") || n.contains("amazon")) {
            return new String[]{"com.amazon.avod.thirdpartyclient", "com.amazon.amazonvideo.livingroom", "com.amazon.firebat", "com.amazon.avod"};
        }
        if (n.contains("hulu")) return new String[]{"com.hulu.plus", "com.hulu.livingroomplus"};
        if (n.contains("disney")) return new String[]{"com.disney.disneyplus", "com.disneyplus.disneyplus"};
        if (n.contains("youtube")) {
            return new String[]{"com.amazon.firetv.youtube", "com.google.android.youtube.tv", "com.google.android.youtube"};
        }
        if (n.contains("hbo") || n.equals("max") || n.contains("max ")) {
            return new String[]{"com.wbd.stream", "com.hbo.hbonow"};
        }
        if (n.contains("peacock")) return new String[]{"com.peacocktv.peacockandroid"};
        if (n.contains("paramount") || n.contains("cbs")) return new String[]{"com.cbs.ott"};
        if (n.contains("apple")) return new String[]{"com.apple.atve.androidtv.appletv"};
        return new String[]{
                "com.amazon.firetv.youtube", "com.google.android.youtube.tv", "com.google.android.youtube"
        };
    }

    /** Poster art for a tile. Null on any failure. */
    static Bitmap poster(String url) {
        if (url == null || !url.startsWith("http")) return null;
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(6000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "TVDash/1.4 (Android TV launcher)");
            if (c.getResponseCode() / 100 != 2) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n;
            int total = 0;
            while ((n = in.read(tmp)) >= 0 && total < 1500000) {
                if (n == 0) continue;
                buf.write(tmp, 0, n);
                total += n;
            }
            in.close();
            byte[] data = buf.toByteArray();
            if (data.length == 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 2;
            return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    static String searchUrl(String network, String name) {
        String q = Uri.encode(name == null ? "" : name);
        String n = network == null ? "" : network.toLowerCase(java.util.Locale.US);
        if (n.contains("netflix")) return "https://www.netflix.com/search?q=" + q;
        if (n.contains("disney")) return "https://www.disneyplus.com/search?q=" + q;
        if (n.contains("hulu")) return "https://www.hulu.com/search?q=" + q;
        if (n.contains("prime") || n.contains("amazon")) return "https://app.primevideo.com/search?phrase=" + q;
        if (n.contains("hbo") || n.contains("max")) return "https://play.max.com/search?q=" + q;
        return "https://www.youtube.com/results?search_query=" + q;
    }
}

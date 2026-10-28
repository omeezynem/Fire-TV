package com.tvdash.launcher;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Photos sent from the phone, kept in the app's private storage. */
final class Photos {
    static final int MAX_PHOTOS = 60;

    private Photos() {}

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), "photos");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** Oldest first. */
    static List<File> list(Context c) {
        File[] files = dir(c).listFiles((d, name) -> name.endsWith(".jpg"));
        if (files == null) return new ArrayList<>();
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        return new ArrayList<>(Arrays.asList(files));
    }

    static synchronized File save(Context c, byte[] jpeg) throws IOException {
        File f = new File(dir(c), System.currentTimeMillis() + ".jpg");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(jpeg);
        }
        List<File> all = list(c);
        for (int i = 0; i < all.size() - MAX_PHOTOS; i++) all.get(i).delete();
        return f;
    }

    static synchronized void clear(Context c) {
        for (File f : list(c)) f.delete();
    }

    /** Slideshow sources: phone photos, then the user's photo links. Empty means random scenic photos. */
    static List<String> slideshowSources(Context c, Prefs prefs) {
        List<String> out = new ArrayList<>();
        for (File f : list(c)) out.add("file://" + f.getAbsolutePath());
        out.addAll(prefs.photoUrls());
        return out;
    }
}

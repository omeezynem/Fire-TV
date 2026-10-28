package com.tvdash.launcher;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One launchable app on the TV. */
final class AppEntry {
    final String pkg;
    final String label;
    final Drawable banner; // wide TV artwork, may be null
    final Drawable icon;
    final Intent launch;

    private AppEntry(String pkg, String label, Drawable banner, Drawable icon, Intent launch) {
        this.pkg = pkg;
        this.label = label;
        this.banner = banner;
        this.icon = icon;
        this.launch = launch;
    }

    /** All launchable apps except this one, one per package, TV entries preferred, sorted by name. */
    static List<AppEntry> loadAll(PackageManager pm, String selfPkg) {
        Map<String, AppEntry> byPkg = new LinkedHashMap<>();
        addAll(pm, selfPkg, Intent.CATEGORY_LEANBACK_LAUNCHER, byPkg);
        addAll(pm, selfPkg, Intent.CATEGORY_LAUNCHER, byPkg);
        List<AppEntry> out = new ArrayList<>(byPkg.values());
        Collections.sort(out, (a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static void addAll(PackageManager pm, String selfPkg, String category, Map<String, AppEntry> byPkg) {
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(category);
        List<ResolveInfo> found;
        try {
            found = pm.queryIntentActivities(query, 0);
        } catch (RuntimeException e) {
            return;
        }
        for (ResolveInfo ri : found) {
            ActivityInfo ai = ri.activityInfo;
            if (ai == null || ai.packageName.equals(selfPkg) || byPkg.containsKey(ai.packageName)) continue;
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            CharSequence label = ri.loadLabel(pm);
            Intent launch = new Intent(Intent.ACTION_MAIN)
                    .addCategory(category)
                    .setComponent(new ComponentName(ai.packageName, ai.name))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            byPkg.put(ai.packageName, new AppEntry(ai.packageName,
                    label == null ? ai.packageName : label.toString(), banner, ri.loadIcon(pm), launch));
        }
    }
}

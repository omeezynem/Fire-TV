package com.tvdash.launcher;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Limited fallback when ADB debugging is off. Back and Home are global actions. The direction pad
 * moves the on-screen focus when the current app exposes one. Many video players do not, so the
 * pad cannot drive those without ADB debugging.
 */
public class RemoteAccessibility extends AccessibilityService {
    private static volatile RemoteAccessibility instance;

    static boolean enabled(Context context) {
        try {
            if (context == null) return false;
            String flat = Settings.Secure.getString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null || flat.isEmpty()) return false;
            String full = new ComponentName(context, RemoteAccessibility.class).flattenToString();
            return flat.contains(full) || flat.contains(context.getPackageName() + "/.RemoteAccessibility");
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** @return true when this service actually handled the button */
    static boolean handle(final String name) {
        final RemoteAccessibility svc = instance;
        if (svc == null) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return svc.perform(name);
        final boolean[] ok = {false};
        final Object lock = new Object();
        final boolean[] done = {false};
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                ok[0] = svc.perform(name);
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            long until = System.currentTimeMillis() + 1200;
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
        return ok[0];
    }

    static boolean type(final String text) {
        final RemoteAccessibility svc = instance;
        if (svc == null || text == null || text.isEmpty()) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return svc.setText(text);
        final boolean[] ok = {false};
        final Object lock = new Object();
        final boolean[] done = {false};
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                ok[0] = svc.setText(text);
                synchronized (lock) {
                    done[0] = true;
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            long until = System.currentTimeMillis() + 1200;
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
        return ok[0];
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        if (instance == this) instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    private boolean perform(String name) {
        try {
            return performUnchecked(name);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean performUnchecked(String name) {
        if ("back".equals(name)) return performGlobalAction(GLOBAL_ACTION_BACK);
        if ("home".equals(name)) return performGlobalAction(GLOBAL_ACTION_HOME);
        if ("select".equals(name)) return clickFocused();
        if ("up".equals(name)) return move(0, -1, View.FOCUS_UP);
        if ("down".equals(name)) return move(0, 1, View.FOCUS_DOWN);
        if ("left".equals(name)) return move(-1, 0, View.FOCUS_LEFT);
        if ("right".equals(name)) return move(1, 0, View.FOCUS_RIGHT);
        if ("settings".equals(name)) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
        return false;
    }

    private boolean setText(String text) {
        try {
            return setTextUnchecked(text);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean setTextUnchecked(String text) {
        AccessibilityNodeInfo node = focused();
        while (node != null && !node.isEditable()) {
            AccessibilityNodeInfo parent = node.getParent();
            node.recycle();
            node = parent;
        }
        if (node == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        node.recycle();
        return ok;
    }

    private boolean clickFocused() {
        AccessibilityNodeInfo node = focused();
        if (node == null) return false;
        AccessibilityNodeInfo cur = node;
        while (cur != null) {
            if (cur.isClickable() && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                if (cur != node) cur.recycle();
                node.recycle();
                return true;
            }
            AccessibilityNodeInfo parent = cur.getParent();
            if (cur != node) cur.recycle();
            cur = parent;
        }
        node.recycle();
        return false;
    }

    private boolean move(int dx, int dy, int viewDir) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo cur = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        if (cur == null) cur = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (cur != null && Build.VERSION.SDK_INT >= 28) {
            AccessibilityNodeInfo next = cur.focusSearch(viewDir);
            if (next != null) {
                boolean ok = focus(next);
                next.recycle();
                cur.recycle();
                root.recycle();
                return ok;
            }
        }
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        collect(root, nodes);
        root.recycle();
        if (cur == null) {
            boolean ok = !nodes.isEmpty() && focus(nodes.get(0));
            recycle(nodes);
            return ok;
        }
        Rect from = new Rect();
        cur.getBoundsInScreen(from);
        cur.recycle();
        AccessibilityNodeInfo best = null;
        int bestScore = Integer.MAX_VALUE;
        int cx = from.centerX();
        int cy = from.centerY();
        for (int i = 0; i < nodes.size(); i++) {
            AccessibilityNodeInfo n = nodes.get(i);
            Rect b = new Rect();
            n.getBoundsInScreen(b);
            int vx = b.centerX() - cx;
            int vy = b.centerY() - cy;
            if (dx != 0 && Integer.signum(vx) != dx) continue;
            if (dy != 0 && Integer.signum(vy) != dy) continue;
            int primary = dx != 0 ? Math.abs(vx) : Math.abs(vy);
            int secondary = dx != 0 ? Math.abs(vy) : Math.abs(vx);
            if (primary < 8) continue;
            if (secondary > primary * 2 + 48) continue;
            int score = primary * 2 + secondary;
            if (score < bestScore) {
                bestScore = score;
                best = n;
            }
        }
        boolean ok = best != null && focus(best);
        recycle(nodes);
        return ok;
    }

    private static boolean focus(AccessibilityNodeInfo n) {
        boolean a = n.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        boolean b = n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        return a || b;
    }

    private AccessibilityNodeInfo focused() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return null;
        AccessibilityNodeInfo cur = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (cur == null) cur = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        root.recycle();
        return cur;
    }

    private void collect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        if (node.isVisibleToUser() && (node.isFocusable() || node.isClickable())) out.add(AccessibilityNodeInfo.obtain(node));
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            collect(child, out);
            child.recycle();
        }
    }

    private static void recycle(List<AccessibilityNodeInfo> nodes) {
        for (int i = 0; i < nodes.size(); i++) nodes.get(i).recycle();
    }
}

package com.tvdash.launcher;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;

import org.json.JSONObject;

/**
 * Sends a phone-remote button to whatever app is on the TV. A normal app cannot inject keys, so
 * the real path is the TV's own ADB service ({@code input keyevent}) after the user turns on
 * debugging and taps Allow. Accessibility is a weaker backup for Back, Home, focus, and text fields.
 */
final class RemoteControl {
    static final String SETUP =
            "To control any app from the phone, turn on ADB debugging.\n\n"
                    + "1. Settings → My Fire TV → About.\n"
                    + "2. Click the device name 7 times, until it says you are a developer.\n"
                    + "3. Go back, then open Developer options.\n"
                    + "4. Turn on ADB debugging.\n"
                    + "5. Open Phone remote again and tap Connect ADB. If the TV asks to allow a connection, tap Allow and check Always allow.\n\n"
                    + "Without that, Settings → Accessibility → TV Dash → On gives Back, Home, and play/pause. "
                    + "The direction pad then only works in apps that show which item is selected.\n\n"
                    + "The microphone and the power button are not available.";

    private RemoteControl() {}

    static JSONObject status(Context context) throws Exception {
        boolean adb = AdbRemote.ready();
        boolean a11y = RemoteAccessibility.enabled(context);
        return new JSONObject()
                .put("adb", adb)
                .put("accessibility", a11y)
                .put("full", adb);
    }

    static String statusLine(Context context) {
        if (AdbRemote.ready()) return "Full remote, any app";
        if (RemoteAccessibility.enabled(context)) return "Limited (accessibility)";
        if (AdbRemote.portOpen()) return "ADB is on. Tap Connect ADB.";
        return "Not set up";
    }

    /** @return null on success, or a sentence the phone can show */
    static String press(Context context, String name) {
        int code = code(name);
        if (code == 0 && !"settings".equals(name)) return "Unknown button";
        Context app = context.getApplicationContext();
        if (AdbRemote.ready() || AdbRemote.portOpen()) {
            String err = "settings".equals(name)
                    ? AdbRemote.shell(app, "am start -a android.settings.SETTINGS")
                    : AdbRemote.keyevent(app, code);
            if (err == null) return null;
            if (AdbRemote.ready() || AdbRemote.portOpen()) return err;
        }
        if ("play".equals(name) || "rewind".equals(name) || "forward".equals(name)) {
            if (media(app, code)) return null;
        }
        if ("settings".equals(name)) {
            try {
                app.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return null;
            } catch (RuntimeException ignored) {
            }
        }
        if (RemoteAccessibility.handle(name)) return null;
        if ("menu".equals(name) || "up".equals(name) || "down".equals(name) || "left".equals(name) || "right".equals(name) || "select".equals(name)) {
            return "That button needs ADB debugging to work in every app. " + shortSetup();
        }
        return shortSetup();
    }

    /** @return null on success */
    static String type(Context context, String text) {
        if (text == null) return "Type something first";
        text = text.replace('\n', ' ').replace('\r', ' ').trim();
        if (text.isEmpty()) return "Type something first";
        if (text.length() > 180) text = text.substring(0, 180);
        Context app = context.getApplicationContext();
        if (AdbRemote.ready() || AdbRemote.portOpen()) {
            String err = AdbRemote.shell(app, textCommand(text));
            if (err == null) return null;
            if (AdbRemote.portOpen()) return err;
        }
        if (RemoteAccessibility.type(text)) return null;
        return "Typing into any app needs ADB debugging. " + shortSetup();
    }

    static String connect(Context context) {
        if (!AdbRemote.portOpen()) return "ADB debugging is still off. " + shortSetup();
        String err = AdbRemote.connectForAllow(context.getApplicationContext());
        // ensureLocked returns null both for success and for "port closed" in one branch.
        // connectForAllow only calls ensure when we already know the port is open, and success is ready().
        if (AdbRemote.ready()) return null;
        return err != null ? err : "Couldn't connect. If the TV asked, tap Allow and try again.";
    }

    private static String shortSetup() {
        return "On the TV: Settings → My Fire TV → Developer options → ADB debugging. Then tap Try connection. If asked, tap Allow.";
    }

    static String textCommand(String text) {
        StringBuilder b = new StringBuilder("input text '");
        int n = 0;
        for (int i = 0; i < text.length() && n < 180; i++) {
            char c = text.charAt(i);
            if (c < 32) continue;
            if (c == '\'') b.append("'\\''");
            else b.append(c);
            n++;
        }
        b.append("'");
        return b.toString();
    }

    private static boolean media(Context context, int code) {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null || code == 0) return false;
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t + 30, KeyEvent.ACTION_UP, code, 0));
        return true;
    }

    static int code(String name) {
        if ("up".equals(name)) return 19;
        if ("down".equals(name)) return 20;
        if ("left".equals(name)) return 21;
        if ("right".equals(name)) return 22;
        if ("select".equals(name)) return 23;
        if ("back".equals(name)) return 4;
        if ("home".equals(name)) return 3;
        if ("menu".equals(name)) return 82;
        if ("settings".equals(name)) return 176;
        if ("play".equals(name)) return 85;
        if ("rewind".equals(name)) return 89;
        if ("forward".equals(name)) return 90;
        if ("del".equals(name)) return 67;
        return 0;
    }
}

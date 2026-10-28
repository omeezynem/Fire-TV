package com.tvdash.launcher;

import java.io.File;

/** Connects the phone-remote server (running in RemoteService) with the dashboard while it is on screen. */
final class Hub {
    /** What the server can ask the dashboard to do. Always called on the main thread. */
    interface Screen {
        void remoteSettingsChanged();
        void remoteFavoritesChanged();
        void remoteMessage(String text, int seconds);
        void remoteScreensaver(boolean on);
        void remoteNextPhoto();
        void remotePhotosChanged(File newest);
    }

    /** Set while the dashboard is the app on screen. */
    static volatile Screen screen;
    static volatile boolean saverOn;
    static volatile String weatherLine = "";

    private Hub() {}
}

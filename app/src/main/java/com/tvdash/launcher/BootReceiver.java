package com.tvdash.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the phone remote when the TV turns on, so it works before TV Dash is opened. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) RemoteService.start(context);
    }
}

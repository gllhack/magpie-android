package com.magpie.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the gateway after a reboot, when autostart is on. */
public final class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        if (MagpiePrefs.autostart(ctx)) {
            MagpieService.start(ctx);
        }
    }
}

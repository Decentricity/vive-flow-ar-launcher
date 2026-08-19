package com.decentricity.arlauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * HUE kiosk on Flow does not auto-start the pinned VRAPP. Schedule on-visor
 * keep-alive and poke MainActivity a few times after boot.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action)
                && !"android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            return;
        }
        final Context app = context.getApplicationContext();
        KeepAliveReceiver.schedule(app);
        final PendingResult pending = goAsync();
        Handler h = new Handler(Looper.getMainLooper());
        final int[] delays = { 8000, 16000, 28000, 45000 };
        for (int i = 0; i < delays.length; i++) {
            final boolean last = i == delays.length - 1;
            h.postDelayed(new Runnable() {
                @Override
                public void run() {
                    KeepAliveReceiver.bringUp(app);
                    if (last) pending.finish();
                }
            }, delays[i]);
        }
    }
}

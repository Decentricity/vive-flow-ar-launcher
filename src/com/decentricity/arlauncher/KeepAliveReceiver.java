package com.decentricity.arlauncher;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * On-visor autostart. Flow kiosk does not launch the pinned app and disables
 * hands, so this process has to bring MainActivity up without a PC.
 */
public class KeepAliveReceiver extends BroadcastReceiver {
    static final String ACTION = "com.decentricity.arlauncher.KEEPALIVE";
    private static final int REQ = 7;
    private static final long FIRST_MS = 8000L;
    private static final long EVERY_MS = 12000L;

    static void schedule(Context context) {
        Context app = context.getApplicationContext();
        AlarmManager alarms = (AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        PendingIntent pi = pending(app);
        alarms.cancel(pi);
        alarms.setRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + FIRST_MS,
                EVERY_MS,
                pi);
    }

    static void bringUp(Context context) {
        if (isForeground()) return;
        Context app = context.getApplicationContext();
        Intent launch = new Intent(app, MainActivity.class);
        launch.setAction(Intent.ACTION_MAIN);
        launch.addCategory("com.htc.intent.category.VRAPP");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            app.startActivity(launch);
        } catch (Exception ignored) {
        }
    }

    static boolean isForeground() {
        ActivityManager.RunningAppProcessInfo info =
                new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(info);
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
    }

    private static PendingIntent pending(Context app) {
        Intent i = new Intent(app, KeepAliveReceiver.class);
        i.setAction(ACTION);
        return PendingIntent.getBroadcast(app, REQ, i, PendingIntent.FLAG_UPDATE_CURRENT);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (ACTION.equals(action)) {
            bringUp(context);
        }
    }
}

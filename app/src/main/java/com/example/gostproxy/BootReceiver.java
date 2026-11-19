package com.example.gostproxy;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    private static final String PREFS_NAME = "gost_settings";
    private static final String PREF_AUTO_START = "auto_start_enabled";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        boolean autoStartEnabled = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_AUTO_START, false);
        if (!autoStartEnabled) return;

        MainActivity.startGostService(context, new Intent(context, ProxyService.class));
    }
}

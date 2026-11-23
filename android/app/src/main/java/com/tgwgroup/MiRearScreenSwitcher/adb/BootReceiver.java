package com.tgwgroup.MiRearScreenSwitcher.adb;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.tgwgroup.MiRearScreenSwitcher.TaskServiceManager;

/**
 * Triggers Zero-Touch Local ADB & MrssShellDaemon recovery after device boot or APK update.
 *
 * Adjustment #2 compliance:
 * - Does NOT use directBootAware="true" or ACTION_LOCKED_BOOT_COMPLETED, ensuring
 *   Credential-Encrypted (CE) filesDir (RSA keys) and SharedPreferences are unlocked.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "MRSSBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) &&
            !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        Log.d(TAG, "🔔 BootReceiver triggered by action: " + action);
        try {
            TaskServiceManager manager = TaskServiceManager.getInstance(context);
            LocalAdbBackend backend = manager.getLocalAdbBackend();
            if (manager.isLocalAdbMode() || backend.hasEverPaired()) {
                backend.scheduleZeroTouchRecovery(action);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error scheduling zero-touch recovery on " + action, t);
        }
    }
}


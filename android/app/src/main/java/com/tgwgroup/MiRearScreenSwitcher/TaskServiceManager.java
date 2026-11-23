package com.tgwgroup.MiRearScreenSwitcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.tgwgroup.MiRearScreenSwitcher.adb.LocalAdbBackend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import rikka.shizuku.Shizuku;

/**
 * Unified Privileged Execution Manager supporting dual backends:
 * 1. "shizuku"   - External Shizuku UserService (TaskService in Shizuku process)
 * 2. "local_adb" - Standalone built-in Local ADB + Resident UID 2000 MrssShellDaemon
 */
public class TaskServiceManager {
    private static final String TAG = "TaskServiceManager";

    public static final String BACKEND_SHIZUKU = "shizuku";
    public static final String BACKEND_LOCAL_ADB = "local_adb";

    public static final String PREFS_NAME = "mrss_settings";
    public static final String FLUTTER_PREFS_NAME = "FlutterSharedPreferences";
    public static final String KEY_EXECUTION_BACKEND = "execution_backend";
    public static final String FLUTTER_KEY_EXECUTION_BACKEND = "flutter.execution_backend";

    public static final String ACTION_BACKEND_CHANGED =
            "com.tgwgroup.MiRearScreenSwitcher.ACTION_BACKEND_CHANGED";

    private static volatile TaskServiceManager instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final SharedPreferences flutterPrefs;
    private final LocalAdbBackend localAdbBackend;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile ITaskService shizukuTaskService;
    private volatile boolean shizukuBindingInProgress = false;

    private final Shizuku.UserServiceArgs shizukuServiceArgs =
            new Shizuku.UserServiceArgs(new ComponentName(
                    "com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("task_service")
                    .debuggable(false)
                    .version(1);

    public interface BackendListener {
        void onTaskServiceUpdated(ITaskService service, String activeBackend, boolean isReady);
    }

    private final List<BackendListener> listeners = new ArrayList<>();

    private final ServiceConnection shizukuConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            shizukuBindingInProgress = false;
            shizukuTaskService = ITaskService.Stub.asInterface(binder);
            Log.d(TAG, "✓ Shared Shizuku TaskService connected");
            new Thread(() -> {
                try {
                    DisplayInfoCache.getInstance().initialize(shizukuTaskService);
                } catch (Throwable ignored) {}
            }).start();
            notifyListeners();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            shizukuBindingInProgress = false;
            shizukuTaskService = null;
            Log.w(TAG, "⚠ Shared Shizuku TaskService disconnected");
            notifyListeners();
        }
    };

    private TaskServiceManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.flutterPrefs = appContext.getSharedPreferences(FLUTTER_PREFS_NAME, Context.MODE_PRIVATE);
        this.localAdbBackend = LocalAdbBackend.getInstance(appContext);

        String canonicalMode = getActiveBackendMode();
        prefs.edit().putString(KEY_EXECUTION_BACKEND, canonicalMode).apply();
        flutterPrefs.edit().putString(FLUTTER_KEY_EXECUTION_BACKEND, canonicalMode).apply();

        this.localAdbBackend.addStatusListener((ready, detail) -> {
            if (ready) {
                new Thread(() -> {
                    try {
                        DisplayInfoCache.getInstance().initialize(localAdbBackend.getTaskService());
                    } catch (Throwable ignored) {}
                }).start();
            }
            mainHandler.post(this::notifyListeners);
        });

        try {
            Shizuku.addBinderReceivedListenerSticky(() -> {
                if (!isLocalAdbMode()) {
                    bindShizukuIfAvailable();
                }
            });
            Shizuku.addBinderDeadListener(() -> {
                shizukuTaskService = null;
                shizukuBindingInProgress = false;
                if (!isLocalAdbMode()) {
                    mainHandler.post(this::notifyListeners);
                }
            });
        } catch (Throwable ignored) {}

        refreshBackendConnection();
    }

    public static TaskServiceManager getInstance(Context context) {
        if (instance == null) {
            synchronized (TaskServiceManager.class) {
                if (instance == null) {
                    instance = new TaskServiceManager(context);
                }
            }
        }
        return instance;
    }

    public String getActiveBackendMode() {
        String mode = prefs.getString(KEY_EXECUTION_BACKEND, null);
        if (mode == null || mode.isEmpty()) {
            mode = flutterPrefs.getString(FLUTTER_KEY_EXECUTION_BACKEND, BACKEND_SHIZUKU);
        }
        if (!BACKEND_LOCAL_ADB.equals(mode) && !BACKEND_SHIZUKU.equals(mode)) {
            return BACKEND_SHIZUKU;
        }
        return mode;
    }

    public boolean isLocalAdbMode() {
        return BACKEND_LOCAL_ADB.equals(getActiveBackendMode());
    }

    public static boolean isLocalAdbMode(Context context) {
        return getInstance(context).isLocalAdbMode();
    }

    public void setActiveBackendMode(String mode) {
        String cleanMode = BACKEND_LOCAL_ADB.equals(mode) ? BACKEND_LOCAL_ADB : BACKEND_SHIZUKU;
        prefs.edit().putString(KEY_EXECUTION_BACKEND, cleanMode).apply();
        flutterPrefs.edit().putString(FLUTTER_KEY_EXECUTION_BACKEND, cleanMode).apply();

        Log.d(TAG, "🔄 Execution backend switched to: " + cleanMode);
        refreshBackendConnection();

        Intent broadcast = new Intent(ACTION_BACKEND_CHANGED);
        broadcast.setPackage(appContext.getPackageName());
        broadcast.putExtra("backend", cleanMode);
        appContext.sendBroadcast(broadcast);

        notifyListeners();
    }

    public void refreshBackendConnection() {
        if (isLocalAdbMode()) {
            if (localAdbBackend.isReadyFast()) {
                mainHandler.post(this::notifyListeners);
            }
            new Thread(() -> {
                boolean ready = localAdbBackend.checkReady();
                if (ready) {
                    try {
                        DisplayInfoCache.getInstance().initialize(localAdbBackend.getTaskService());
                    } catch (Throwable ignored) {}
                }
                mainHandler.post(this::notifyListeners);
            }, "MRSS-RefreshBackend").start();
        } else {
            bindShizukuIfAvailable();
        }
    }

    public void broadcastCurrentStatus() {
        mainHandler.post(this::notifyListeners);
    }

    public void bindShizukuIfAvailable() {
        if (shizukuTaskService != null || shizukuBindingInProgress) {
            return;
        }
        try {
            if (Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                shizukuBindingInProgress = true;
                Shizuku.bindUserService(shizukuServiceArgs, shizukuConnection);
            }
        } catch (Throwable t) {
            shizukuBindingInProgress = false;
            Log.w(TAG, "Failed binding Shizuku TaskService: " + t.getMessage());
        }
    }

    public ITaskService getActiveTaskService() {
        if (isLocalAdbMode()) {
            return localAdbBackend.getTaskService();
        } else {
            if (shizukuTaskService == null) {
                bindShizukuIfAvailable();
            }
            return shizukuTaskService;
        }
    }

    public LocalAdbBackend getLocalAdbBackend() {
        return localAdbBackend;
    }

    /**
     * Non-blocking zero-I/O check indicating whether the active backend is ready right now.
     */
    public boolean isBackendReadyFast() {
        if (isLocalAdbMode()) {
            return localAdbBackend.isReadyFast();
        } else {
            try {
                return shizukuTaskService != null && Shizuku.pingBinder();
            } catch (Throwable t) {
                return false;
            }
        }
    }

    public boolean isCurrentBackendReady() {
        if (isLocalAdbMode()) {
            return localAdbBackend.checkReady();
        } else {
            try {
                boolean running = Shizuku.pingBinder();
                boolean granted = running && (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED);
                if (granted && shizukuTaskService == null) {
                    bindShizukuIfAvailable();
                }
                return running && granted;
            } catch (Throwable t) {
                return false;
            }
        }
    }

    public Map<String, Object> getDetailedBackendState() {
        String mode = getActiveBackendMode();
        if (BACKEND_LOCAL_ADB.equals(mode)) {
            Map<String, Object> map = new HashMap<>(localAdbBackend.getDetailedStateMap());
            map.put("backend", BACKEND_LOCAL_ADB);
            return map;
        } else {
            Map<String, Object> map = new HashMap<>();
            boolean running = false;
            boolean granted = false;
            try {
                running = Shizuku.pingBinder();
                granted = running && (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED);
            } catch (Throwable ignored) {}
            map.put("backend", BACKEND_SHIZUKU);
            map.put("ready", running && granted);
            map.put("stateCode", (running && granted) ? "READY" : "NEEDS_SHIZUKU");
            map.put("shizukuRunning", running);
            map.put("shizukuGranted", granted);
            return map;
        }
    }

    public String getCurrentBackendDiagnosticInfo() {
        if (isLocalAdbMode()) {
            return localAdbBackend.getStatusSummary();
        } else {
            try {
                boolean isRunning = Shizuku.pingBinder();
                boolean hasPermission = isRunning && (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED);
                int uid = isRunning ? Shizuku.getUid() : -1;
                int version = isRunning ? Shizuku.getVersion() : -1;
                return "Backend: Shizuku\n" +
                       "Running: " + isRunning + "\n" +
                       "Permission: " + hasPermission + "\n" +
                       "UID: " + uid + "\n" +
                       "Version: " + version;
            } catch (Exception e) {
                return "Backend: Shizuku\nError: " + e.getMessage();
            }
        }
    }

    public void addListener(BackendListener listener) {
        synchronized (listeners) {
            if (!listeners.contains(listener)) {
                listeners.add(listener);
            }
        }
    }

    public void removeListener(BackendListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private void notifyListeners() {
        ITaskService svc = getActiveTaskService();
        String mode = getActiveBackendMode();
        boolean ready = BACKEND_LOCAL_ADB.equals(mode)
                ? localAdbBackend.isReadyFast()
                : (shizukuTaskService != null);

        List<BackendListener> copy;
        synchronized (listeners) {
            copy = new ArrayList<>(listeners);
        }
        for (BackendListener l : copy) {
            try {
                l.onTaskServiceUpdated(svc, mode, ready);
            } catch (Throwable ignored) {}
        }
    }
}

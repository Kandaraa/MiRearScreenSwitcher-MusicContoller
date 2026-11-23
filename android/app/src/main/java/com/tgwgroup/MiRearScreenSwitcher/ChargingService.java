/*
 * Author: AntiOblivionis
 * QQ: 319641317
 * Github: https://github.com/GoldenglowSusie/
 * Bilibili: 罗德岛T0驭械术师澄闪
 *
 * Chief Tester: 汐木泽
 *
 * Co-developed with AI assistants:
 * - Cursor
 * - Claude-4.5-Sonnet
 * - GPT-5
 * - Gemini-2.5-Pro
 */

package com.tgwgroup.MiRearScreenSwitcher;

import android.app.KeyguardManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * 充电状态监听服务
 * 监听电源连接事件，插电后在背屏显示充电电量动画。
 *
 * HyperOS 4 / Android 17 加固：
 * 1. 移除 am force-stop com.xiaomi.subscreencenter，改用 input -d 1 keyevent 224 温和唤醒 Display 1。
 * 2. 锁屏时显式使用 am start --display 0 + RearScreenChargingActivity 静态 getTaskId() 桥接 +
 *    moveTaskToDisplay(taskId, 1) + keyevent 224，绕过 HyperOS 4 锁屏 Display 1 拦截。
 * 3. 在后台线程执行启动并在失败时重置 RearAnimationManager，避免主线程阻塞与状态卡死。
 * 4. 节流常亮唤醒循环至 2500ms（后台线程），防止洪泛 MrssShellDaemon。
 */
public class ChargingService extends Service {
    private static final String TAG = "ChargingService";
    private static final int NOTIFICATION_ID = 1001;
    private static final long CHARGING_ANIMATION_COOLDOWN_MS = 6000L;
    private static final long WAKEUP_LOOP_INTERVAL_MS = 2500L;

    private SharedPreferences prefs;
    private ITaskService taskService;
    private PowerManager.WakeLock wakeLock;

    private static ChargingService instance;
    private long lastChargingAnimationTime = 0L;

    private boolean chargingAlwaysOnEnabled = false;
    private Handler wakeupHandler;
    private Runnable wakeupRunnable;
    private boolean isWakeupRunning = false;

    private final ExecutorService chargingExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MRSS-ChargingLaunch");
        t.setDaemon(true);
        return t;
    });

    public static ITaskService getTaskService() {
        if (instance != null && instance.taskService != null) {
            return instance.taskService;
        }
        if (instance != null) {
            return TaskServiceManager.getInstance(instance).getActiveTaskService();
        }
        return null;
    }

    private final Shizuku.UserServiceArgs serviceArgs =
            new Shizuku.UserServiceArgs(new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("charging_task_service")
                    .debuggable(false)
                    .version(1);

    private final ServiceConnection taskServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.d(TAG, "✓ TaskService connected");
            taskService = ITaskService.Stub.asInterface(binder);
            try {
                DisplayInfoCache.getInstance().initialize(taskService);
            } catch (Exception e) {
                Log.w(TAG, "初始化显示屏缓存失败: " + e.getMessage());
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.d(TAG, "✗ TaskService disconnected");
            taskService = null;
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (taskService == null) {
                    bindTaskService();
                }
            }, 1000);
        }
    };

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        Log.d(TAG, "Shizuku binder received");
        bindTaskService();
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        Log.d(TAG, "Shizuku binder dead");
        taskService = null;
        new Handler(Looper.getMainLooper()).postDelayed(this::bindTaskService, 1000);
    };

    private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && TaskServiceManager.ACTION_BACKEND_CHANGED.equals(intent.getAction())) {
                Log.d(TAG, "收到执行后端切换广播");
                taskService = null;
                bindTaskService();
                return;
            }
            chargingAlwaysOnEnabled = prefs.getBoolean("charging_always_on_enabled", false);
            Log.d(TAG, "充电动画常亮: " + chargingAlwaysOnEnabled);
        }
    };

    private final BroadcastReceiver resumeChargingReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && "com.tgwgroup.MiRearScreenSwitcher.RESUME_CHARGING_ANIMATION".equals(intent.getAction())) {
                Log.d(TAG, "🔋 收到恢复充电动画广播，准备恢复");
                int batteryLevel = getBatteryLevel(context);
                KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                boolean isLocked = km != null && km.isKeyguardLocked();

                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    try {
                        RearAnimationManager.startAnimation(RearAnimationManager.AnimationType.CHARGING);
                        showChargingOnRearScreen(batteryLevel, isLocked);
                        if (chargingAlwaysOnEnabled) {
                            startWakeupAndUpdateLoop();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "恢复充电动画失败", e);
                    }
                }, 300);
            }
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();

            if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
                boolean enabled = prefs.getBoolean("charging_animation_enabled", true);
                if (!enabled) {
                    return;
                }

                long currentTime = System.currentTimeMillis();
                if (currentTime - lastChargingAnimationTime < CHARGING_ANIMATION_COOLDOWN_MS) {
                    Log.d(TAG, "⏸ Charging animation in cooldown, skipping");
                    return;
                }

                KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                boolean isLocked = km != null && km.isKeyguardLocked();
                int batteryLevel = getBatteryLevel(context);
                Log.d(TAG, "🔌 Power connected, battery: " + batteryLevel + "%, locked=" + isLocked);

                lastChargingAnimationTime = currentTime;

                RearAnimationManager.AnimationType oldAnim =
                        RearAnimationManager.startAnimation(RearAnimationManager.AnimationType.CHARGING);
                if (oldAnim == RearAnimationManager.AnimationType.NOTIFICATION) {
                    RearAnimationManager.sendInterruptBroadcast(ChargingService.this, RearAnimationManager.AnimationType.NOTIFICATION);
                }

                showChargingOnRearScreen(batteryLevel, isLocked);

                if (chargingAlwaysOnEnabled) {
                    startWakeupAndUpdateLoop();
                }
            } else if (Intent.ACTION_POWER_DISCONNECTED.equals(action)) {
                Log.d(TAG, "🔌 Power disconnected, finishing charging animation");
                stopWakeupLoop();
                finishChargingAnimation();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "ChargingService created");
        instance = this;
        prefs = getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_POWER_CONNECTED);
        filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        registerReceiver(batteryReceiver, filter);

        IntentFilter settingsFilter = new IntentFilter("com.tgwgroup.MiRearScreenSwitcher.RELOAD_CHARGING_SETTINGS");
        settingsFilter.addAction(TaskServiceManager.ACTION_BACKEND_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, settingsFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(settingsReceiver, settingsFilter);
        }

        IntentFilter resumeFilter = new IntentFilter("com.tgwgroup.MiRearScreenSwitcher.RESUME_CHARGING_ANIMATION");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(resumeChargingReceiver, resumeFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(resumeChargingReceiver, resumeFilter);
        }

        chargingAlwaysOnEnabled = prefs.getBoolean("charging_always_on_enabled", false);
        wakeupHandler = new Handler(Looper.getMainLooper());
        bindTaskService();
        startForeground(NOTIFICATION_ID, RearScreenKeeperService.createServiceNotification(this));
    }

    private void acquireWakeLock(long timeoutMs) {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MRSS:ChargingWake");
                    wakeLock.setReferenceCounted(false);
                }
                if (!wakeLock.isHeld()) {
                    wakeLock.acquire(timeoutMs);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (taskService == null) {
            bindTaskService();
        }
        return START_STICKY;
    }

    private void bindTaskService() {
        try {
            if (TaskServiceManager.isLocalAdbMode(this)) {
                taskService = TaskServiceManager.getInstance(this).getLocalAdbBackend().getTaskService();
                Log.d(TAG, "✓ TaskService connected (Local ADB)");
                if (TaskServiceManager.getInstance(this).isBackendReadyFast()) {
                    new Thread(() -> {
                        try {
                            DisplayInfoCache.getInstance().initialize(taskService);
                        } catch (Exception ignored) {}
                    }).start();
                }
                return;
            }
            if (!Shizuku.pingBinder()) return;
            if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind TaskService", e);
        }
    }

    private int getBatteryLevel(Context context) {
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        return bm != null ? bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : 0;
    }

    private void finishChargingAnimation() {
        try {
            Intent finishIntent = new Intent("com.tgwgroup.MiRearScreenSwitcher.FINISH_CHARGING_ANIMATION");
            finishIntent.setPackage(getPackageName());
            sendBroadcast(finishIntent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to finish charging animation", e);
        }
    }

    private void showChargingOnRearScreen(final int level, final boolean isLocked) {
        chargingExecutor.execute(() -> showChargingOnRearScreenInternal(level, isLocked));
    }

    private void showChargingOnRearScreenInternal(int level, boolean isLocked) {
        boolean launchSucceeded = false;
        try {
            ITaskService ts = getTaskService();
            if (ts == null) {
                bindTaskService();
                for (int retry = 0; retry < 10 && ts == null; retry++) {
                    Thread.sleep(100);
                    ts = getTaskService();
                }
            }
            if (ts == null) {
                Log.e(TAG, "❌ TaskService不可用，放弃启动充电动画");
                return;
            }

            acquireWakeLock(8000);
            long startTime = System.currentTimeMillis();

            // 检查背屏是否有投送的第三方应用
            String lastTask = SwitchToRearTileService.getLastMovedTask();
            int rearTaskId = -1;
            if (lastTask != null && lastTask.contains(":")) {
                try {
                    String rearForegroundApp = ts.getForegroundAppOnDisplay(1);
                    if (rearForegroundApp != null &&
                            (rearForegroundApp.contains("RearScreenChargingActivity") || rearForegroundApp.equals(lastTask))) {
                        String[] parts = lastTask.split(":");
                        rearTaskId = Integer.parseInt(parts[1]);
                    }
                } catch (Exception ignored) {}
            }

            if (rearTaskId > 0) {
                RearScreenKeeperService.pauseMonitoring();
            }

            // 注意：绝不调用 disableSubScreenLauncher() (am force-stop com.xiaomi.subscreencenter)，
            // 改用 input -d 1 keyevent 224 温和唤醒 Display 1，避免黑屏闪烁与焦点抢占！

            String componentNameStr = getPackageName() + "/" + RearScreenChargingActivity.class.getName();

            if (!isLocked) {
                // 解锁状态：先唤醒 Display 1，直接 --display 1 启动
                ts.executeShellCommand("input -d 1 keyevent 224");
                CountDownLatch latch = RearScreenChargingActivity.prepareForLaunch();
                String directCmd = String.format(
                        "am start --display 1 -n %s --ei batteryLevel %d --ei rearTaskId %d",
                        componentNameStr, level, rearTaskId
                );
                Log.d(TAG, "🚀 解锁状态：直接在 Display 1 启动充电动画");
                ts.executeShellCommand(directCmd);

                try {
                    latch.await(900, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {}

                int launchedTaskId = RearScreenChargingActivity.getLastLaunchedTaskId();
                if (launchedTaskId > 0) {
                    // 若意外落到 Display 0，确保搬运到 Display 1
                    if (!ts.isTaskOnDisplay(launchedTaskId, 1)) {
                        ts.moveTaskToDisplay(launchedTaskId, 1);
                        ts.executeShellCommand("input -d 1 keyevent 224");
                    }
                    launchSucceeded = true;
                    Log.d(TAG, "✅ 解锁充电动画已启动 (taskId=" + launchedTaskId + ", 耗时" +
                            (System.currentTimeMillis() - startTime) + "ms)");
                    return;
                }
            }

            // 锁屏状态（或解锁直启未完成）：显式 --display 0 + NO_ANIMATION 启动占位Activity + 等待Display 0过渡稳定后搬运至 Display 1
            Log.d(TAG, "🔒 锁屏/兜底策略：显式 --display 0 (-f 0x10010000) 启动充电占位Activity，随后搬运至 Display 1");
            CountDownLatch latch = RearScreenChargingActivity.prepareForLaunch();
            String mainCmd = String.format(
                    "am start --display 0 -f 0x10010000 -n %s --ei batteryLevel %d --ei rearTaskId %d",
                    componentNameStr, level, rearTaskId
            );
            ts.executeShellCommand(mainCmd);

            int chargingTaskId = -1;
            try {
                latch.await(1500, TimeUnit.MILLISECONDS);
                chargingTaskId = RearScreenChargingActivity.getLastLaunchedTaskId();
            } catch (InterruptedException ignored) {}

            // 兜底：若静态桥接未拿到 taskId，回退 am stack list 查询
            if (chargingTaskId <= 0) {
                for (int attempt = 0; attempt < 15 && chargingTaskId <= 0; attempt++) {
                    Thread.sleep(40);
                    String result = ts.executeShellCommandWithResult("am stack list | grep RearScreenChargingActivity");
                    if (result != null && !result.trim().isEmpty()) {
                        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("taskId=(\\d+)").matcher(result);
                        if (matcher.find()) {
                            chargingTaskId = Integer.parseInt(matcher.group(1));
                            break;
                        }
                    }
                }
            }

            if (chargingTaskId > 0) {
                // USB插电会唤醒Display 0，等待450ms确保HyperOS Display 0 OPEN过渡完全结束后再搬运至Display 1，
                // 防止finishTransition把Surface回弹到stackId:0
                Log.d(TAG, "⏳ 等待450ms让Display 0过渡动画稳定后再搬运 taskId=" + chargingTaskId + " 至 Display 1");
                Thread.sleep(450);
                ts.moveTaskToDisplay(chargingTaskId, 1);
                Thread.sleep(40);
                ts.executeShellCommand("input -d 1 keyevent 224");
                launchSucceeded = true;
                Log.d(TAG, "✅ 充电动画已移动至 Display 1 并唤醒背屏 (taskId=" + chargingTaskId +
                        ", 总耗时" + (System.currentTimeMillis() - startTime) + "ms)");
            } else {
                Log.e(TAG, "❌ 未能获取 RearScreenChargingActivity 的 taskId");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ 启动充电动画失败", e);
        } finally {
            if (!launchSucceeded) {
                RearAnimationManager.resetAnimationOnFailure(RearAnimationManager.AnimationType.CHARGING);
            }
            releaseWakeLock();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopForeground(true);
        instance = null;

        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
        } catch (Exception ignored) {}
        try { unregisterReceiver(batteryReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(settingsReceiver); } catch (Exception ignored) {}
        try { unregisterReceiver(resumeChargingReceiver); } catch (Exception ignored) {}

        stopWakeupLoop();

        try {
            if (taskService != null) {
                if (!TaskServiceManager.isLocalAdbMode(this)) {
                    Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
                }
                taskService = null;
            }
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startWakeupAndUpdateLoop() {
        if (isWakeupRunning) return;
        isWakeupRunning = true;

        wakeupRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isWakeupRunning) return;
                boolean enabled = prefs.getBoolean("charging_always_on_enabled", false);
                if (!enabled) {
                    stopWakeupLoop();
                    return;
                }

                chargingExecutor.execute(() -> {
                    try {
                        ITaskService ts = getTaskService();
                        if (ts != null) {
                            ts.executeShellCommand("input -d 1 keyevent 224");
                        }
                    } catch (Throwable ignored) {}
                });

                try {
                    int batteryLevel = getBatteryLevel(getApplicationContext());
                    RearScreenChargingActivity.updateBatteryLevelStatic(batteryLevel);
                } catch (Exception ignored) {}

                if (isWakeupRunning && wakeupHandler != null) {
                    wakeupHandler.postDelayed(this, WAKEUP_LOOP_INTERVAL_MS);
                }
            }
        };

        wakeupHandler.post(wakeupRunnable);
        Log.d(TAG, "✓ Throttled charging wakeup loop started (interval=" + WAKEUP_LOOP_INTERVAL_MS + "ms)");
    }

    private void stopWakeupLoop() {
        isWakeupRunning = false;
        if (wakeupHandler != null && wakeupRunnable != null) {
            wakeupHandler.removeCallbacks(wakeupRunnable);
        }
    }
}

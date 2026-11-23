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

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;

/**
 * 背屏充电动画Activity
 * 显示全屏液体填充和电量百分比，8秒后自动关闭（常亮模式除外）。
 *
 * HyperOS 4 / Android 17 加固：
 * 1. 当在 Display 0 以透明占位符启动并移动至 Display 1 时，在 onConfigurationChanged / onResume 中
 *    检测抵达 displayId == 1 且 !contentInitialized，完整初始化布局、DPI、安全区和液体动画。
 * 2. 通过静态 CountDownLatch + getTaskId() 桥接毫秒级回传 taskId 给 ChargingService。
 * 3. 无投送 app 时通过 finishAndRemoveTask() 无缝露出下方存活的 SubScreenLauncher，避免黑屏闪烁。
 */
public class RearScreenChargingActivity extends Activity {
    private static final String TAG = "RearScreenChargingActivity";

    private int rearTaskId = -1;
    private int currentBatteryLevel = 0;
    private boolean contentInitialized = false;
    private boolean receiverRegistered = false;
    private ValueAnimator liquidAnimator;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable autoFinishRunnable;

    // 静态实例与 taskId 桥接
    private static volatile RearScreenChargingActivity currentInstance = null;
    private static volatile int lastLaunchedTaskId = -1;
    private static volatile CountDownLatch taskLaunchLatch = null;

    public static CountDownLatch prepareForLaunch() {
        lastLaunchedTaskId = -1;
        CountDownLatch latch = new CountDownLatch(1);
        taskLaunchLatch = latch;
        return latch;
    }

    public static int getLastLaunchedTaskId() {
        return lastLaunchedTaskId;
    }

    private static void publishTaskId(int taskId) {
        if (taskId > 0) {
            lastLaunchedTaskId = taskId;
        }
        CountDownLatch latch = taskLaunchLatch;
        if (latch != null) {
            latch.countDown();
        }
    }

    public static void updateBatteryLevelStatic(final int newLevel) {
        final RearScreenChargingActivity inst = currentInstance;
        if (inst != null && !inst.isFinishing() && !inst.isDestroyed()) {
            inst.runOnUiThread(() -> inst.updateBatteryLevel(newLevel));
        }
    }

    private final BroadcastReceiver finishReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if ("com.tgwgroup.MiRearScreenSwitcher.FINISH_CHARGING_ANIMATION".equals(action)) {
                Log.d(TAG, "🔌 收到拔电广播，立即销毁");
                finishCleanly();
            } else if ("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_CHARGING_ANIMATION".equals(action)) {
                Log.d(TAG, "🔄 收到打断广播（新动画来了），立即销毁但不恢复Launcher");
                finishCleanly();
            } else if ("com.tgwgroup.MiRearScreenSwitcher.UPDATE_CHARGING_BATTERY".equals(action)) {
                int newLevel = intent.getIntExtra("batteryLevel", -1);
                if (newLevel >= 0) {
                    updateBatteryLevel(newLevel);
                }
            }
        }
    };

    public RearScreenChargingActivity() {
        super();
        long time = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🟢 构造函数被调用", time, time));
    }

    private int getCurrentDisplayId() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (getDisplay() != null) {
                    return getDisplay().getDisplayId();
                }
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        long onCreateStartTime = System.currentTimeMillis();
        super.onCreate(savedInstanceState);

        int taskId = getTaskId();
        int displayId = getCurrentDisplayId();
        publishTaskId(taskId);

        currentBatteryLevel = getIntent().getIntExtra("batteryLevel", 0);
        rearTaskId = getIntent().getIntExtra("rearTaskId", -1);

        Log.d(TAG, String.format("[%tT.%tL] 🟡 onCreate开始 displayId=%d, taskId=%d, battery=%d%%, rearTaskId=%d",
                onCreateStartTime, onCreateStartTime, displayId, taskId, currentBatteryLevel, rearTaskId));

        currentInstance = this;
        registerFinishReceiverIfNeeded();

        // 保持锁屏可见（不点亮主屏）
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }

        if (displayId == 0) {
            // 抑制 Display 0 占位打开动画，避免 HyperOS 过渡 leash 与 moveTaskToDisplay 竞争
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
            } else {
                overridePendingTransition(0, 0);
            }
            Log.d(TAG, String.format("[%tT.%tL] 💤 在主屏启动(占位 taskId=%d)，已抑制过渡动画，等待移动到背屏后初始化",
                    onCreateStartTime, onCreateStartTime, taskId));
            contentInitialized = false;
            return;
        }

        initializeChargingUiOnRearDisplay(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        setIntent(intent);
        publishTaskId(getTaskId());

        currentBatteryLevel = intent.getIntExtra("batteryLevel", currentBatteryLevel);
        rearTaskId = intent.getIntExtra("rearTaskId", rearTaskId);

        if (getCurrentDisplayId() == 1) {
            if (!contentInitialized) {
                initializeChargingUiOnRearDisplay(intent);
            } else {
                updateBatteryLevel(currentBatteryLevel);
                scheduleAutoFinishIfNeeded();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        long resumeTime = System.currentTimeMillis();
        int displayId = getCurrentDisplayId();
        publishTaskId(getTaskId());

        Log.d(TAG, String.format("[%tT.%tL] 🟢 onResume displayId=%d, contentInitialized=%s",
                resumeTime, resumeTime, displayId, contentInitialized));

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }

        if (displayId == 1 && !contentInitialized) {
            Log.d(TAG, "🔄 onResume检测到充电占位Activity抵达背屏，立即初始化完整充电动画");
            initializeChargingUiOnRearDisplay(getIntent());
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int displayId = getCurrentDisplayId();
        Log.d(TAG, "⚙️ 配置变化: displayId=" + displayId + ", densityDpi=" + newConfig.densityDpi +
                ", contentInitialized=" + contentInitialized);

        if (displayId == 1 && !contentInitialized) {
            Log.d(TAG, "🔄 onConfigurationChanged检测到充电占位Activity抵达背屏，立即初始化完整充电动画");
            initializeChargingUiOnRearDisplay(getIntent());
        }
    }

    /**
     * 在抵达背屏 (displayId == 1) 后完整初始化充电动画布局、DPI、安全区与液体动画。
     */
    private void initializeChargingUiOnRearDisplay(Intent intent) {
        if (contentInitialized) return;
        contentInitialized = true;

        long startTime = System.currentTimeMillis();
        if (intent != null) {
            currentBatteryLevel = intent.getIntExtra("batteryLevel", currentBatteryLevel);
            rearTaskId = intent.getIntExtra("rearTaskId", rearTaskId);
        }

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        );

        forceRearScreenDensityBeforeInflate();
        setContentView(R.layout.activity_rear_screen_charging);

        LightningShapeView fullScreenLiquid = findViewById(R.id.full_screen_liquid);
        TextView batteryText = findViewById(R.id.battery_text);
        View chargingContainer = findViewById(R.id.charging_container);

        if (chargingContainer != null) {
            chargingContainer.setVisibility(View.VISIBLE);
        }

        if (fullScreenLiquid != null) {
            fullScreenLiquid.setFullScreenMode(true);
            startFullScreenLiquidAnimation(fullScreenLiquid, currentBatteryLevel);
        }

        if (batteryText != null) {
            applySafeAreaToText(batteryText);
            batteryText.setText(currentBatteryLevel + "%");
            startCenterTextAnimation(batteryText);
        }

        scheduleAutoFinishIfNeeded();
        Log.d(TAG, String.format("✅ 背屏充电动画初始化完成: battery=%d%%, rearTaskId=%d (耗时%dms)",
                currentBatteryLevel, rearTaskId, System.currentTimeMillis() - startTime));
    }

    private void scheduleAutoFinishIfNeeded() {
        if (autoFinishRunnable != null) {
            mainHandler.removeCallbacks(autoFinishRunnable);
            autoFinishRunnable = null;
        }
        boolean chargingAlwaysOn = getSharedPreferences("mrss_settings", MODE_PRIVATE)
                .getBoolean("charging_always_on_enabled", false);

        if (chargingAlwaysOn) {
            Log.d(TAG, "💡 充电常亮模式，不自动关闭");
        } else {
            Log.d(TAG, "🎬 充电动画已启动，8秒后自动关闭");
            autoFinishRunnable = this::finishCleanly;
            mainHandler.postDelayed(autoFinishRunnable, 8000L);
        }
    }

    private void registerFinishReceiverIfNeeded() {
        if (receiverRegistered) return;
        try {
            IntentFilter finishFilter = new IntentFilter();
            finishFilter.addAction("com.tgwgroup.MiRearScreenSwitcher.FINISH_CHARGING_ANIMATION");
            finishFilter.addAction("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_CHARGING_ANIMATION");
            finishFilter.addAction("com.tgwgroup.MiRearScreenSwitcher.UPDATE_CHARGING_BATTERY");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(finishReceiver, finishFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(finishReceiver, finishFilter);
            }
            receiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register finish receiver: " + t.getMessage());
        }
    }

    private void finishCleanly() {
        if (isFinishing() || isDestroyed()) return;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            } else {
                finish();
            }
            overridePendingTransition(0, 0);
        } catch (Throwable t) {
            finish();
        }
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onDestroy() {
        long destroyTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🔴 onDestroy被调用", destroyTime, destroyTime));

        if (autoFinishRunnable != null) {
            mainHandler.removeCallbacks(autoFinishRunnable);
            autoFinishRunnable = null;
        }
        if (liquidAnimator != null) {
            try {
                liquidAnimator.cancel();
            } catch (Throwable ignored) {}
            liquidAnimator = null;
        }

        if (receiverRegistered) {
            try {
                unregisterReceiver(finishReceiver);
            } catch (Exception ignored) {}
            receiverRegistered = false;
        }

        super.onDestroy();

        if (this != currentInstance) {
            Log.w(TAG, "⚠️ 这是旧实例，跳过恢复操作");
            return;
        }
        currentInstance = null;

        boolean shouldRestore = RearAnimationManager.endAnimation(RearAnimationManager.AnimationType.CHARGING);
        if (!shouldRestore) {
            Log.d(TAG, "🔄 充电动画被打断，跳过恢复");
            return;
        }

        int currentDisplayId = getCurrentDisplayId();
        if (currentDisplayId == 1) {
            final int finalTaskId = rearTaskId;
            if (finalTaskId > 0) {
                new Thread(() -> {
                    try {
                        Thread.sleep(50);
                        Log.d(TAG, "⚡ 恢复投送app (taskId=" + finalTaskId + ")");
                        restoreProjectedApp(finalTaskId);
                    } catch (Exception e) {
                        Log.e(TAG, "Error in restore thread", e);
                    }
                }).start();
            } else {
                // SubScreenLauncher 未被 force-stop，finishAndRemoveTask() 已无缝露出下方桌面
                Log.d(TAG, "✓ 充电动画结束，无缝返回下方 SubScreenLauncher");
            }
        }
    }

    private void restoreProjectedApp(int taskId) {
        try {
            ITaskService taskService = ChargingService.getTaskService();
            if (taskService != null) {
                taskService.moveTaskToDisplay(taskId, 1);
                Thread.sleep(150);
                restartKeeperService(taskId);
                Log.d(TAG, "✅ Projected app restored (taskId=" + taskId + ")");
            } else {
                MainActivity mainActivity = MainActivity.getCurrentInstance();
                if (mainActivity != null) {
                    mainActivity.executeShellCommand("service call activity_task 50 i32 " + taskId + " i32 1");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore projected app", e);
            RearScreenKeeperService.resumeMonitoring();
            restoreOfficialLauncher();
        }
    }

    private void restartKeeperService(int taskId) {
        try {
            String lastTask = SwitchToRearTileService.getLastMovedTask();
            if (lastTask != null) {
                Intent serviceIntent = new Intent(this, RearScreenKeeperService.class);
                serviceIntent.putExtra("lastMovedTask", lastTask);
                try {
                    android.content.SharedPreferences prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
                    boolean keepScreenOnEnabled = prefs.getBoolean("flutter.keep_screen_on_enabled", true);
                    serviceIntent.putExtra("keepScreenOnEnabled", keepScreenOnEnabled);
                } catch (Exception e) {
                    serviceIntent.putExtra("keepScreenOnEnabled", true);
                }
                startService(serviceIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restart RearScreenKeeperService", e);
        }
    }

    private void restoreOfficialLauncher() {
        try {
            ITaskService taskService = ChargingService.getTaskService();
            if (taskService != null) {
                taskService.enableSubScreenLauncher();
            } else {
                MainActivity mainActivity = MainActivity.getCurrentInstance();
                if (mainActivity != null) {
                    mainActivity.executeShellCommand(
                            "am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher"
                    );
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to restore official launcher", e);
        }
    }

    private void forceRearScreenDensityBeforeInflate() {
        try {
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            int rearScreenDpi = (info != null) ? info.densityDpi : 0;

            if (rearScreenDpi <= 0) {
                ITaskService ts = ChargingService.getTaskService();
                if (ts == null) ts = NotificationService.getTaskService();
                if (ts != null) {
                    try {
                        DisplayInfoCache.getInstance().initialize(ts);
                        info = DisplayInfoCache.getInstance().getCachedInfo();
                        rearScreenDpi = (info != null) ? info.densityDpi : 0;
                    } catch (Exception ignored) {}
                }
            }
            if (rearScreenDpi <= 0) {
                return;
            }

            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            metrics.densityDpi = rearScreenDpi;
            metrics.density = rearScreenDpi / 160f;
            metrics.scaledDensity = metrics.density;

            Configuration config = new Configuration(getResources().getConfiguration());
            config.densityDpi = rearScreenDpi;
            getResources().updateConfiguration(config, metrics);
        } catch (Exception e) {
            Log.e(TAG, "❌ inflate前应用DPI失败", e);
        }
    }

    private void startFullScreenLiquidAnimation(LightningShapeView liquidView, int targetLevel) {
        if (liquidAnimator != null) {
            liquidAnimator.cancel();
        }
        float targetFillLevel = Math.max(0f, Math.min(1f, targetLevel / 100f));
        liquidAnimator = ValueAnimator.ofFloat(0f, targetFillLevel);
        liquidAnimator.setDuration(2000);
        liquidAnimator.setInterpolator(new DecelerateInterpolator(2.5f));
        liquidAnimator.addUpdateListener(animation -> {
            float animatedValue = (float) animation.getAnimatedValue();
            liquidView.setFillLevel(animatedValue);
        });
        liquidAnimator.start();
        Log.d(TAG, String.format("🌊 全屏液体填充动画已启动: 0%% → %d%%", targetLevel));
    }

    private void startCenterTextAnimation(TextView textView) {
        textView.setAlpha(0f);
        textView.setScaleX(0.8f);
        textView.setScaleY(0.8f);

        textView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(800)
                .setStartDelay(600)
                .setInterpolator(new DecelerateInterpolator(2.0f))
                .start();
    }

    private void updateBatteryLevel(int newLevel) {
        try {
            currentBatteryLevel = newLevel;
            if (!contentInitialized) return;
            LightningShapeView liquidView = findViewById(R.id.full_screen_liquid);
            TextView batteryText = findViewById(R.id.battery_text);
            if (liquidView != null && batteryText != null) {
                liquidView.setFillLevel(Math.max(0f, Math.min(1f, newLevel / 100f)));
                batteryText.setText(newLevel + "%");
            }
        } catch (Exception e) {
            Log.w(TAG, "更新电量失败: " + e.getMessage());
        }
    }

    private void applySafeAreaToText(TextView textView) {
        try {
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            if (info == null || !info.hasCutout()) {
                return;
            }
            if (textView.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams) {
                android.widget.FrameLayout.LayoutParams params =
                        (android.widget.FrameLayout.LayoutParams) textView.getLayoutParams();
                params.leftMargin = info.cutout.left;
                params.topMargin = info.cutout.top;
                params.rightMargin = info.cutout.right;
                params.bottomMargin = info.cutout.bottom;
                textView.setLayoutParams(params);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ 应用安全区域失败", e);
        }
    }
}

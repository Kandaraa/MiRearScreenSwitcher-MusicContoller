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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Surface;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 背屏通知显示Activity
 * 显示应用图标、名称和通知内容，带非线性动画效果。
 *
 * HyperOS 4 / Android 17 加固：
 * 1. 支持 Display 0 占位 -> moveTaskToDisplay(taskId, 1) -> Display 1 完整初始化与动画播放（覆盖 onConfigurationChanged 与 onResume）。
 * 2. 通过静态 CountDownLatch + getTaskId() 桥接将 taskId 毫秒级回传给 NotificationService，消除脆弱轮询。
 * 3. 支持 onNewIntent 与 updateActiveOnRearDisplay 原地刷新，避免连续通知触发反复销毁重建。
 * 4. 节流 KEYCODE_WAKEUP (keyevent 224) 至启动 1 次 + 每 2500ms 1 次（后台线程），依赖 FLAG_KEEP_SCREEN_ON 保活。
 * 5. 不再强杀/重启 com.xiaomi.mirror/.SubscreenLauncher，结束时 finishAndRemoveTask() 无缝回到下方存活的 SubScreenLauncher。
 */
public class RearScreenNotificationActivity extends Activity {
    private static final String TAG = "RearScreenNotificationActivity";
    private static final long WAKEUP_INTERVAL_MS = 2500L;

    // 静态实例与 taskId 桥接
    private static volatile RearScreenNotificationActivity currentInstance = null;
    private static volatile int lastLaunchedTaskId = -1;
    private static volatile CountDownLatch taskLaunchLatch = null;

    private static final ExecutorService shellWakeExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MRSS-NotifRearWake");
        t.setDaemon(true);
        return t;
    });

    private String packageName;
    private boolean layoutInflated = false;
    private boolean contentInitialized = false;
    private boolean receiverRegistered = false;
    private int animationGeneration = 0;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable autoFinishRunnable;
    private Runnable phase2Runnable;
    private Runnable wakeupRunnable;
    private boolean isWakeupRunning = false;

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

    public static boolean isActiveOnRearDisplay() {
        RearScreenNotificationActivity inst = currentInstance;
        return inst != null && !inst.isFinishing() && !inst.isDestroyed() && inst.getCurrentDisplayId() == 1;
    }

    /**
     * 若通知Activity已在背屏显示，则直接原地更新内容并重置关闭计时，避免杀掉重建导致闪烁。
     */
    public static boolean updateActiveOnRearDisplay(
            final String pkg,
            final String title,
            final String text,
            final long when,
            final boolean darkMode) {
        final RearScreenNotificationActivity inst = currentInstance;
        if (inst == null || inst.isFinishing() || inst.isDestroyed() || inst.getCurrentDisplayId() != 1) {
            return false;
        }
        inst.runOnUiThread(() -> {
            if (inst.isFinishing() || inst.isDestroyed()) return;
            Intent synthetic = new Intent();
            synthetic.putExtra("packageName", pkg);
            synthetic.putExtra("title", title);
            synthetic.putExtra("text", text);
            synthetic.putExtra("when", when);
            synthetic.putExtra("darkMode", darkMode);
            inst.setIntent(synthetic);
            inst.initializeOrUpdateOnRearDisplay(synthetic, inst.contentInitialized);
        });
        return true;
    }

    // 广播接收器：接收打断命令
    private final BroadcastReceiver interruptReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_NOTIFICATION_ANIMATION".equals(intent.getAction())) {
                Log.d(TAG, "🔄 收到打断广播（新动画来了），立即销毁");
                finishCleanly();
            }
        }
    };

    public RearScreenNotificationActivity() {
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

        Log.d(TAG, String.format("[%tT.%tL] 🟡 onCreate开始 displayId=%d, taskId=%d",
                onCreateStartTime, onCreateStartTime, displayId, taskId));

        currentInstance = this;
        registerInterruptReceiverIfNeeded();

        // 保持常亮 + 锁屏显示（不点亮主屏），并允许背景全屏延伸至摄像头挖孔区
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }
        applyFullBleedCutoutWindowMode();

        if (displayId == 0) {
            // 抑制 Display 0 占位打开动画，并保持透明占位符直到抵达 Display 1
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(Activity.OVERRIDE_TRANSITION_OPEN, 0, 0);
            } else {
                overridePendingTransition(0, 0);
            }
            Log.d(TAG, String.format("[%tT.%tL] 💤 在主屏启动(透明占位 taskId=%d)，已抑制过渡动画，等待移动到背屏",
                    onCreateStartTime, onCreateStartTime, taskId));
            contentInitialized = false;
            return;
        }

        ensureNotificationLayoutInflated();
        initializeOrUpdateOnRearDisplay(getIntent(), false);
        long onCreateEndTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] ✓ onCreate完成 (总耗时%dms)",
                onCreateEndTime, onCreateEndTime, onCreateEndTime - onCreateStartTime));
    }

    private void applyFullBleedCutoutWindowMode() {
        try {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            getWindow().setAttributes(lp);
        } catch (Throwable t) {
            Log.w(TAG, "Failed setting cutout window mode: " + t.getMessage());
        }
    }

    private void ensureNotificationLayoutInflated() {
        if (layoutInflated) return;
        applyFullBleedCutoutWindowMode();
        forceRearScreenDensityBeforeInflate();
        setContentView(R.layout.activity_rear_screen_notification);
        getWindow().setBackgroundDrawableResource(R.drawable.bg_gradient_rear_screen);
        layoutInflated = true;

        // 监听 WindowInsets，确保从 Display 0 搬运到 Display 1 后也能自动刷新 Phase 2 避让区
        View decor = getWindow().getDecorView();
        if (decor != null) {
            decor.setOnApplyWindowInsetsListener((v, insets) -> {
                applyPhase2CameraSafeArea(insets);
                return insets;
            });
            decor.requestApplyInsets();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent == null) return;
        setIntent(intent);
        publishTaskId(getTaskId());

        int displayId = getCurrentDisplayId();
        Log.d(TAG, "🔁 onNewIntent displayId=" + displayId + ", contentInitialized=" + contentInitialized);
        if (displayId == 1) {
            initializeOrUpdateOnRearDisplay(intent, contentInitialized);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        long resumeTime = System.currentTimeMillis();
        int currentDisplayId = getCurrentDisplayId();
        publishTaskId(getTaskId());
        Log.d(TAG, String.format("[%tT.%tL] 🟢 onResume displayId=%d, contentInitialized=%s",
                resumeTime, resumeTime, currentDisplayId, contentInitialized));

        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }

        if (currentDisplayId == 1 && !contentInitialized) {
            Log.d(TAG, "🔄 onResume检测到从主屏移动到背屏，立即初始化内容与动画");
            applyRearScreenDensity();
            initializeOrUpdateOnRearDisplay(getIntent(), false);
        } else if (currentDisplayId == 1 && !isWakeupRunning) {
            startThrottledWakeupLoop();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        long configTime = System.currentTimeMillis();
        int currentDisplayId = getCurrentDisplayId();
        Log.d(TAG, String.format("[%tT.%tL] ⚙️ 配置变化: displayId=%d, densityDpi=%d, contentInitialized=%s",
                configTime, configTime, currentDisplayId, newConfig.densityDpi, contentInitialized));

        if (currentDisplayId == 1 && !contentInitialized) {
            Log.d(TAG, "🔄 onConfigurationChanged检测到抵达背屏，立即初始化内容与动画");
            applyRearScreenDensity();
            initializeOrUpdateOnRearDisplay(getIntent(), false);
        } else if (currentDisplayId == 1 && layoutInflated) {
            applyPhase2CameraSafeArea(null);
        }
    }

    /**
     * 在背屏 (displayId == 1) 上初始化或原地刷新通知 UI 与动画。
     */
    private void initializeOrUpdateOnRearDisplay(Intent intent, boolean isUpdateInPlace) {
        if (intent == null) {
            intent = getIntent();
        }
        if (intent == null) {
            return;
        }

        ensureNotificationLayoutInflated();

        String newPkg = intent.getStringExtra("packageName");
        String title = intent.getStringExtra("title");
        String text = intent.getStringExtra("text");
        boolean darkMode = intent.getBooleanExtra("darkMode", false);

        if (newPkg != null && !newPkg.isEmpty()) {
            this.packageName = newPkg;
        }
        if (title == null) title = "";
        if (text == null) text = "";

        View container = findViewById(R.id.notification_container);
        ImageView appIconCenter = findViewById(R.id.app_icon_center);
        ImageView appIconSmall = findViewById(R.id.app_icon_small);
        TextView appNameText = findViewById(R.id.app_name);
        TextView notificationTitle = findViewById(R.id.notification_title);
        TextView notificationContent = findViewById(R.id.notification_content);
        View appNameContainer = findViewById(R.id.app_name_container);
        View contentContainer = findViewById(R.id.notification_content_container);

        if (container == null || appIconCenter == null || appNameContainer == null || contentContainer == null) {
            Log.e(TAG, "❌ 通知视图未找到，无法初始化");
            return;
        }

        // 应用暗夜模式或常规模式
        if (darkMode) {
            applyDarkMode();
        } else {
            applyRegularLayout();
        }

        // 应用 Phase 2 摄像头避让偏移（仅作用于 notification_wrapper，不影响全屏渐变底与 Phase 1 居中大图标）
        applyPhase2CameraSafeArea(null);
        container.setVisibility(View.VISIBLE);
        contentInitialized = true;

        // 加载应用图标与名称
        String appName = (packageName != null) ? packageName : "";
        try {
            if (packageName != null && !packageName.isEmpty()) {
                PackageManager pm = getPackageManager();
                android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
                CharSequence label = pm.getApplicationLabel(appInfo);
                if (label != null) {
                    appName = label.toString();
                }
                Drawable icon = pm.getApplicationIcon(packageName);
                appIconCenter.setImageDrawable(icon);
                if (appIconSmall != null) {
                    appIconSmall.setImageDrawable(icon);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load app info for " + packageName, e);
        }

        if (appNameText != null) {
            appNameText.setText(appName);
        }

        // 避免标题与正文同时为空导致 Phase 2 呈现空白
        String displayTitle = title.trim();
        String displayText = text.trim();
        if (displayTitle.isEmpty() && displayText.isEmpty()) {
            displayText = getString(R.string.new_message_placeholder);
        }

        if (notificationTitle != null) {
            if (!displayTitle.isEmpty()) {
                notificationTitle.setText(displayTitle);
                notificationTitle.setVisibility(View.VISIBLE);
            } else {
                notificationTitle.setText("");
                notificationTitle.setVisibility(View.GONE);
            }
        }

        if (notificationContent != null) {
            if (!displayText.isEmpty()) {
                notificationContent.setText(displayText);
                notificationContent.setVisibility(View.VISIBLE);
                if (displayTitle.isEmpty()) {
                    notificationContent.setPadding(
                            notificationContent.getPaddingLeft(),
                            0,
                            notificationContent.getPaddingRight(),
                            notificationContent.getPaddingBottom()
                    );
                }
            } else {
                notificationContent.setText("");
                notificationContent.setVisibility(View.GONE);
            }
        }

        Log.d(TAG, String.format("📱 背屏通知%s: %s | title=[%s] | text=[%s]",
                isUpdateInPlace ? "(原地更新)" : "(首次展示)", appName, displayTitle, displayText));

        // 设置点击跳转
        container.setOnClickListener(v -> handleNotificationClick());

        // 启动或更新动画
        if (isUpdateInPlace) {
            showPhase2Directly(appIconCenter, appNameContainer, contentContainer);
        } else {
            startNotificationAnimation(appIconCenter, appNameContainer, contentContainer);
        }

        // 重置自动关闭定时器
        scheduleAutoFinish();

        // 启动节流唤醒循环
        startThrottledWakeupLoop();
    }

    private void scheduleAutoFinish() {
        if (autoFinishRunnable != null) {
            mainHandler.removeCallbacks(autoFinishRunnable);
        }
        int duration = getSharedPreferences("mrss_settings", MODE_PRIVATE).getInt("notification_duration", 10);
        if (duration < 3) duration = 3;
        autoFinishRunnable = this::finishCleanly;
        mainHandler.postDelayed(autoFinishRunnable, duration * 1000L);
    }

    private void cancelRunningAnimations(ImageView iconCenter, View appNameContainer, View contentContainer) {
        animationGeneration++;
        if (phase2Runnable != null) {
            mainHandler.removeCallbacks(phase2Runnable);
            phase2Runnable = null;
        }
        iconCenter.animate().cancel();
        appNameContainer.animate().cancel();
        contentContainer.animate().cancel();
    }

    /**
     * 原地更新时直接展示 Phase 2 内容，带轻量淡入，避免再次播放大图标遮挡消息。
     */
    private void showPhase2Directly(ImageView iconCenter, View appNameContainer, View contentContainer) {
        cancelRunningAnimations(iconCenter, appNameContainer, contentContainer);
        iconCenter.setVisibility(View.GONE);
        iconCenter.setAlpha(0f);
        iconCenter.setLayerType(View.LAYER_TYPE_NONE, null);

        appNameContainer.setVisibility(View.VISIBLE);
        appNameContainer.setScaleX(1f);
        appNameContainer.setScaleY(1f);
        appNameContainer.setAlpha(1f);

        contentContainer.setVisibility(View.VISIBLE);
        contentContainer.setTranslationY(12f);
        contentContainer.setAlpha(0.35f);
        contentContainer.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(220)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .start();
    }

    /**
     * 执行完整通知动画：
     * Phase 1: 中心大图标缩放展示 (0-800ms)
     * Phase 2: 中心图标淡出，应用名与通知内容容器淡入滑入 (800-1250ms)
     */
    private void startNotificationAnimation(ImageView iconCenter, View appNameContainer, View contentContainer) {
        cancelRunningAnimations(iconCenter, appNameContainer, contentContainer);
        final int gen = animationGeneration;

        // 明确重置初始可见性与变换，防止残留动画状态导致 Phase 2 不可见
        iconCenter.setVisibility(View.VISIBLE);
        iconCenter.setAlpha(1f);
        iconCenter.setScaleX(1f);
        iconCenter.setScaleY(1f);

        appNameContainer.setVisibility(View.VISIBLE);
        appNameContainer.setAlpha(0f);
        appNameContainer.setScaleX(0.9f);
        appNameContainer.setScaleY(0.9f);

        contentContainer.setVisibility(View.VISIBLE);
        contentContainer.setAlpha(0f);
        contentContainer.setTranslationY(30f);

        iconCenter.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        appNameContainer.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        contentContainer.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        // 阶段1: 中心图标放大 (0-300ms)
        iconCenter.animate()
                .scaleX(1.3f)
                .scaleY(1.3f)
                .setDuration(300)
                .setInterpolator(new AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    if (gen != animationGeneration || isFinishing() || isDestroyed()) {
                        return;
                    }
                    // 阶段1.5: 停留展示 (300-800ms)
                    phase2Runnable = () -> {
                        if (gen != animationGeneration || isFinishing() || isDestroyed()) {
                            return;
                        }
                        // 阶段2: 中心图标缩小淡出 (800-1000ms)
                        iconCenter.animate()
                                .scaleX(0.5f)
                                .scaleY(0.5f)
                                .alpha(0f)
                                .setDuration(200)
                                .setInterpolator(new AccelerateDecelerateInterpolator())
                                .withEndAction(() -> {
                                    if (gen != animationGeneration) return;
                                    iconCenter.setVisibility(View.GONE);
                                    iconCenter.setLayerType(View.LAYER_TYPE_NONE, null);
                                })
                                .start();

                        // 阶段3: 应用名容器淡入缩放
                        appNameContainer.setVisibility(View.VISIBLE);
                        appNameContainer.animate()
                                .alpha(1f)
                                .scaleX(1f)
                                .scaleY(1f)
                                .setDuration(250)
                                .setStartDelay(50)
                                .setInterpolator(new AccelerateDecelerateInterpolator())
                                .withEndAction(() -> {
                                    appNameContainer.setAlpha(1f);
                                    appNameContainer.setLayerType(View.LAYER_TYPE_NONE, null);
                                })
                                .start();

                        // 阶段4: 通知内容容器从下方滑入
                        contentContainer.setVisibility(View.VISIBLE);
                        contentContainer.animate()
                                .alpha(1f)
                                .translationY(0f)
                                .setDuration(300)
                                .setStartDelay(150)
                                .setInterpolator(new AccelerateDecelerateInterpolator())
                                .withEndAction(() -> {
                                    contentContainer.setAlpha(1f);
                                    contentContainer.setTranslationY(0f);
                                    contentContainer.setLayerType(View.LAYER_TYPE_NONE, null);
                                })
                                .start();
                    };
                    mainHandler.postDelayed(phase2Runnable, 500L);
                })
                .start();
    }

    private void handleNotificationClick() {
        long clickTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 👆 收到点击，准备跳转 package=%s", clickTime, clickTime, packageName));
        if (packageName == null || packageName.isEmpty()) {
            finishCleanly();
            return;
        }
        try {
            Intent launchIntent = getPackageManager().getLaunchIntentForPackage(packageName);
            if (launchIntent == null) {
                finishCleanly();
                return;
            }
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);

            boolean started = false;
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    android.app.ActivityOptions opts = android.app.ActivityOptions.makeBasic();
                    java.lang.reflect.Method m = android.app.ActivityOptions.class.getMethod("setLaunchDisplayId", int.class);
                    m.invoke(opts, 0);
                    startActivity(launchIntent, opts.toBundle());
                    started = true;
                }
            } catch (Throwable t) {
                Log.w(TAG, "ActivityOptions主屏启动失败，回退shell: " + t.getMessage());
            }

            if (!started) {
                final String targetPkg = packageName;
                final android.content.ComponentName comp = launchIntent.getComponent();
                shellWakeExecutor.execute(() -> {
                    try {
                        String component = (comp != null) ? comp.flattenToShortString() : null;
                        if (component == null) {
                            Intent resolve = new Intent(Intent.ACTION_MAIN);
                            resolve.addCategory(Intent.CATEGORY_LAUNCHER);
                            resolve.setPackage(targetPkg);
                            android.content.pm.ResolveInfo ri = getPackageManager().resolveActivity(resolve, 0);
                            if (ri != null && ri.activityInfo != null) {
                                component = ri.activityInfo.packageName + "/" + ri.activityInfo.name;
                            }
                        }
                        if (component != null) {
                            ITaskService ts = NotificationService.getTaskService();
                            if (ts != null) {
                                ts.executeShellCommand("am start --display 0 -n " + component);
                            }
                        }
                    } catch (Throwable ignored) {}
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ 点击跳转失败", e);
        } finally {
            finishCleanly();
        }
    }

    private void registerInterruptReceiverIfNeeded() {
        if (receiverRegistered) return;
        try {
            IntentFilter interruptFilter = new IntentFilter("com.tgwgroup.MiRearScreenSwitcher.INTERRUPT_NOTIFICATION_ANIMATION");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(interruptReceiver, interruptFilter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(interruptReceiver, interruptFilter);
            }
            receiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register interrupt receiver: " + t.getMessage());
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
    protected void onDestroy() {
        long destroyTime = System.currentTimeMillis();
        Log.d(TAG, String.format("[%tT.%tL] 🔴 onDestroy被调用", destroyTime, destroyTime));

        animationGeneration++;
        if (autoFinishRunnable != null) {
            mainHandler.removeCallbacks(autoFinishRunnable);
            autoFinishRunnable = null;
        }
        if (phase2Runnable != null) {
            mainHandler.removeCallbacks(phase2Runnable);
            phase2Runnable = null;
        }
        stopWakeupLoop();

        if (receiverRegistered) {
            try {
                unregisterReceiver(interruptReceiver);
            } catch (Exception ignored) {}
            receiverRegistered = false;
        }

        super.onDestroy();

        if (this != currentInstance) {
            Log.w(TAG, "⚠️ 这是旧实例，跳过恢复操作");
            return;
        }
        currentInstance = null;

        boolean shouldRestore = RearAnimationManager.endAnimation(RearAnimationManager.AnimationType.NOTIFICATION);
        if (!shouldRestore) {
            Log.d(TAG, "🔄 通知动画被打断，跳过恢复");
            return;
        }

        // 若此前打断了常亮充电动画，则发广播恢复充电动画
        if (RearAnimationManager.shouldResumeChargingAnimation()) {
            Log.d(TAG, "🔋 检测到充电动画常亮模式，发送恢复广播");
            Intent resumeIntent = new Intent("com.tgwgroup.MiRearScreenSwitcher.RESUME_CHARGING_ANIMATION");
            resumeIntent.setPackage(getPackageName());
            sendBroadcast(resumeIntent);
            RearAnimationManager.clearChargingAlwaysOnFlag();
            return;
        }

        // SubScreenLauncher 未被 force-stop，finishAndRemoveTask() 已无缝露出下方桌面，不再调用无效的 com.xiaomi.mirror/.SubscreenLauncher
        Log.d(TAG, "✓ 通知结束，无缝返回下方 SubScreenLauncher");
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    /**
     * 在inflate布局之前强制使用背屏DPI
     */
    private void forceRearScreenDensityBeforeInflate() {
        try {
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            int rearScreenDpi = (info != null) ? info.densityDpi : 0;

            if (rearScreenDpi <= 0) {
                ITaskService ts = NotificationService.getTaskService();
                if (ts == null) ts = ChargingService.getTaskService();
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

    private void applyRearScreenDensity() {
        try {
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            int rearScreenDpi = (info != null) ? info.densityDpi : 0;
            if (rearScreenDpi <= 0) return;

            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            if (metrics.densityDpi != rearScreenDpi) {
                metrics.densityDpi = rearScreenDpi;
                metrics.density = rearScreenDpi / 160f;
                metrics.scaledDensity = metrics.density;
                Configuration config = new Configuration(getResources().getConfiguration());
                config.densityDpi = rearScreenDpi;
                getResources().updateConfiguration(config, metrics);
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ 应用背屏DPI失败", e);
        }
    }

    /**
     * 仅对 Phase 2 通知详情容器 (notification_wrapper) 应用背屏摄像头挖孔避让偏移：
     * - 全屏渐变背景与 Phase 1 居中大图标 (app_icon_center) 保持全屏无偏移。
     * - 优先读取真实 DisplayCutout（WindowInsets / Display.getCutout() / DisplayInfoCache）。
     * - HyperOS 4 兜底：若在 Display 1 上报的 cutout 均为 0，按 Xiaomi 17 Pro 硬件几何计算
     *   cameraInsetPx = max(296, round(displayWidthPx * 0.328f)) + 20px 呼吸间距，并适配屏幕旋转方向。
     */
    private void applyPhase2CameraSafeArea(WindowInsets latestInsets) {
        try {
            View wrapper = findViewById(R.id.notification_wrapper);
            if (wrapper == null || !(wrapper.getLayoutParams() instanceof android.view.ViewGroup.MarginLayoutParams)) {
                return;
            }

            int safeLeft = 0;
            int safeTop = 0;
            int safeRight = 0;
            int safeBottom = 0;

            // 1. 检查 WindowInsets.getDisplayCutout()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                WindowInsets insetsToUse = latestInsets;
                if (insetsToUse == null && getWindow() != null && getWindow().getDecorView() != null) {
                    insetsToUse = getWindow().getDecorView().getRootWindowInsets();
                }
                if (insetsToUse != null) {
                    DisplayCutout cutout = insetsToUse.getDisplayCutout();
                    if (cutout != null) {
                        safeLeft = Math.max(safeLeft, cutout.getSafeInsetLeft());
                        safeTop = Math.max(safeTop, cutout.getSafeInsetTop());
                        safeRight = Math.max(safeRight, cutout.getSafeInsetRight());
                        safeBottom = Math.max(safeBottom, cutout.getSafeInsetBottom());
                    }
                }
            }

            // 2. 检查 Display.getCutout() (API 29+)
            Display currentDisplay = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    currentDisplay = getDisplay();
                } catch (Throwable ignored) {}
            }
            if (currentDisplay != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    DisplayCutout dispCutout = currentDisplay.getCutout();
                    if (dispCutout != null) {
                        safeLeft = Math.max(safeLeft, dispCutout.getSafeInsetLeft());
                        safeTop = Math.max(safeTop, dispCutout.getSafeInsetTop());
                        safeRight = Math.max(safeRight, dispCutout.getSafeInsetRight());
                        safeBottom = Math.max(safeBottom, dispCutout.getSafeInsetBottom());
                    }
                } catch (Throwable ignored) {}
            }

            // 3. 检查 DisplayInfoCache 缓存的背屏 Cutout
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            if (info != null && info.hasCutout()) {
                safeLeft = Math.max(safeLeft, info.cutout.left);
                safeTop = Math.max(safeTop, info.cutout.top);
                safeRight = Math.max(safeRight, info.cutout.right);
                safeBottom = Math.max(safeBottom, info.cutout.bottom);
            }

            int displayId = getCurrentDisplayId();
            int displayWidthPx = resolveRearDisplayWidthPx(currentDisplay, info);
            int rotation = (currentDisplay != null) ? currentDisplay.getRotation() : Surface.ROTATION_0;
            boolean usedHyperOsFallback = false;

            // 4. HyperOS 4 兜底：在 Display 1 上若系统上报 cutout 全为 0，按 Xiaomi 17 Pro 摄像头区域计算安全偏移
            if (displayId == 1 && safeLeft == 0 && safeRight == 0 && safeTop == 0 && safeBottom == 0) {
                int cameraInsetPx = Math.max(296, Math.round(displayWidthPx * 0.328f));
                usedHyperOsFallback = true;
                if (rotation == Surface.ROTATION_180) {
                    safeRight = cameraInsetPx;
                } else if (rotation == Surface.ROTATION_90) {
                    safeTop = cameraInsetPx;
                } else if (rotation == Surface.ROTATION_270) {
                    safeBottom = cameraInsetPx;
                } else {
                    safeLeft = cameraInsetPx;
                }
            }

            final int breathingGapPx = 20;
            final int outerEdgeMarginPx = 24;

            int marginLeft = (safeLeft > 0) ? (safeLeft + breathingGapPx) : outerEdgeMarginPx;
            int marginRight = (safeRight > 0) ? (safeRight + breathingGapPx) : outerEdgeMarginPx;
            int marginTop = (safeTop > 0) ? (safeTop + 12) : 12;
            int marginBottom = (safeBottom > 0) ? (safeBottom + 12) : 12;

            android.view.ViewGroup.MarginLayoutParams params =
                    (android.view.ViewGroup.MarginLayoutParams) wrapper.getLayoutParams();
            if (params.leftMargin != marginLeft ||
                    params.topMargin != marginTop ||
                    params.rightMargin != marginRight ||
                    params.bottomMargin != marginBottom) {
                params.leftMargin = marginLeft;
                params.topMargin = marginTop;
                params.rightMargin = marginRight;
                params.bottomMargin = marginBottom;
                wrapper.setLayoutParams(params);
                wrapper.requestLayout();
                Log.d(TAG, String.format(
                        "📐 Phase 2 摄像头避让已应用 (displayId=%d, width=%dpx, rot=%d, fallback=%s): margins=[L=%d, T=%d, R=%d, B=%d]",
                        displayId, displayWidthPx, rotation, usedHyperOsFallback,
                        marginLeft, marginTop, marginRight, marginBottom));
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ 应用 Phase 2 摄像头安全区域失败", e);
        }
    }

    private int resolveRearDisplayWidthPx(Display currentDisplay, RearDisplayHelper.RearDisplayInfo info) {
        int widthPx = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                WindowManager wm = getWindowManager();
                if (wm != null) {
                    widthPx = wm.getCurrentWindowMetrics().getBounds().width();
                }
            } catch (Throwable ignored) {}
        }
        if (widthPx <= 0 && currentDisplay != null) {
            try {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                currentDisplay.getRealMetrics(dm);
                widthPx = dm.widthPixels;
            } catch (Throwable ignored) {}
        }
        if (widthPx <= 0 && info != null && info.width > 0) {
            widthPx = info.width;
        }
        if (widthPx <= 0) {
            widthPx = 904;
        }
        return widthPx;
    }

    private void applyDarkMode() {
        try {
            View darkBackground = findViewById(R.id.dark_mode_background);
            if (darkBackground != null) {
                darkBackground.setVisibility(View.VISIBLE);
            }

            View contentContainer = findViewById(R.id.notification_content_container);
            if (contentContainer != null) {
                contentContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                contentContainer.setPadding(0, 0, 0, 0);
            }

            View appNameContainer = findViewById(R.id.app_name_container);
            if (appNameContainer != null && appNameContainer.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams) {
                android.widget.LinearLayout.LayoutParams containerParams =
                        (android.widget.LinearLayout.LayoutParams) appNameContainer.getLayoutParams();
                containerParams.leftMargin = 0;
                appNameContainer.setLayoutParams(containerParams);
            }

            TextView appName = findViewById(R.id.app_name);
            if (appName != null) {
                appName.setTextColor(android.graphics.Color.WHITE);
            }

            TextView notificationTitle = findViewById(R.id.notification_title);
            if (notificationTitle != null) {
                notificationTitle.setTextColor(android.graphics.Color.WHITE);
                notificationTitle.setMaxLines(2);
                if (notificationTitle.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams) {
                    android.widget.LinearLayout.LayoutParams titleParams =
                            (android.widget.LinearLayout.LayoutParams) notificationTitle.getLayoutParams();
                    titleParams.topMargin = 8;
                    notificationTitle.setLayoutParams(titleParams);
                }
            }

            TextView notificationContent = findViewById(R.id.notification_content);
            if (notificationContent != null) {
                notificationContent.setTextColor(android.graphics.Color.WHITE);
                notificationContent.setMaxLines(6);
                if (notificationContent.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams) {
                    android.widget.LinearLayout.LayoutParams contentParams =
                            (android.widget.LinearLayout.LayoutParams) notificationContent.getLayoutParams();
                    contentParams.topMargin = 8;
                    notificationContent.setLayoutParams(contentParams);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ 应用暗夜模式失败", e);
        }
    }

    private void applyRegularLayout() {
        try {
            View darkBackground = findViewById(R.id.dark_mode_background);
            if (darkBackground != null) {
                darkBackground.setVisibility(View.GONE);
            }

            View contentContainer = findViewById(R.id.notification_content_container);
            if (contentContainer != null) {
                contentContainer.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                contentContainer.setPadding(0, 0, 0, 0);
            }

            View appNameContainer = findViewById(R.id.app_name_container);
            if (appNameContainer != null && appNameContainer.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams) {
                android.widget.LinearLayout.LayoutParams containerParams =
                        (android.widget.LinearLayout.LayoutParams) appNameContainer.getLayoutParams();
                containerParams.leftMargin = 0;
                appNameContainer.setLayoutParams(containerParams);
            }

            TextView appName = findViewById(R.id.app_name);
            if (appName != null) {
                appName.setTextColor(android.graphics.Color.WHITE);
                appName.setShadowLayer(3, 0, 1, android.graphics.Color.parseColor("#40000000"));
            }

            TextView notificationTitle = findViewById(R.id.notification_title);
            if (notificationTitle != null) {
                notificationTitle.setTextColor(android.graphics.Color.WHITE);
                notificationTitle.setShadowLayer(3, 0, 1, android.graphics.Color.parseColor("#40000000"));
                notificationTitle.setMaxLines(2);
                if (notificationTitle.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams) {
                    android.widget.LinearLayout.LayoutParams titleParams =
                            (android.widget.LinearLayout.LayoutParams) notificationTitle.getLayoutParams();
                    titleParams.topMargin = 8;
                    notificationTitle.setLayoutParams(titleParams);
                }
            }

            TextView notificationContent = findViewById(R.id.notification_content);
            if (notificationContent != null) {
                notificationContent.setTextColor(android.graphics.Color.WHITE);
                notificationContent.setShadowLayer(3, 0, 1, android.graphics.Color.parseColor("#40000000"));
                notificationContent.setMaxLines(6);
                if (notificationContent.getLayoutParams() instanceof android.widget.LinearLayout.LayoutParams) {
                    android.widget.LinearLayout.LayoutParams contentParams =
                            (android.widget.LinearLayout.LayoutParams) notificationContent.getLayoutParams();
                    contentParams.topMargin = 8;
                    notificationContent.setLayoutParams(contentParams);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ 应用常规布局调整失败", e);
        }
    }

    /**
     * 节流唤醒循环：启动时立即发送 1 次 input -d 1 keyevent 224，此后每 2500ms 发送 1 次（后台线程），
     * 期间依赖 WindowManager FLAG_KEEP_SCREEN_ON 保持背屏点亮，避免 100ms 洪泛 MrssShellDaemon。
     */
    private void startThrottledWakeupLoop() {
        if (isWakeupRunning) {
            return;
        }
        isWakeupRunning = true;

        wakeupRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isWakeupRunning || isFinishing() || isDestroyed()) {
                    stopWakeupLoop();
                    return;
                }

                shellWakeExecutor.execute(() -> {
                    try {
                        ITaskService ts = NotificationService.getTaskService();
                        if (ts != null) {
                            ts.executeShellCommand("input -d 1 keyevent 224");
                        }
                    } catch (Throwable t) {
                        Log.w(TAG, "发送背屏wakeup失败: " + t.getMessage());
                    }
                });

                if (isWakeupRunning) {
                    mainHandler.postDelayed(this, WAKEUP_INTERVAL_MS);
                }
            }
        };

        mainHandler.post(wakeupRunnable);
        Log.d(TAG, "✓ Throttled wakeup loop started (interval=" + WAKEUP_INTERVAL_MS + "ms)");
    }

    private void stopWakeupLoop() {
        isWakeupRunning = false;
        if (wakeupRunnable != null) {
            mainHandler.removeCallbacks(wakeupRunnable);
            wakeupRunnable = null;
        }
    }
}

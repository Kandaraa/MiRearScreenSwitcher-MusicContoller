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
import android.app.Notification;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcelable;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * 通知监听服务
 * 监听系统通知，将选中应用的通知显示到背屏。
 *
 * HyperOS 4 / Android 17 加固：
 * 1. 使用 extras.getCharSequence() + EXTRA_MESSAGES / EXTRA_BIG_TEXT / EXTRA_TEXT / EXTRA_TEXT_LINES
 *    安全提取正文与标题，彻底消除 SpannableString 导致的 W/Bundle 空内容。
 * 2. 跳过 FLAG_GROUP_SUMMARY 分组摘要，并对连续通知事件做 400ms 防抖合并，防止通知风暴打断动画。
 * 3. 若通知Activity已在背屏存活，直接原地刷新（updateActiveOnRearDisplay），避免闪烁与重复启动。
 * 4. 锁屏时跳过被 HyperOS 4 拦截的 --display 1，直接显式 am start --display 0 + getTaskId() 桥接 +
 *    moveTaskToDisplay(taskId, 1) + input -d 1 keyevent 224，且绝不 am force-stop SubScreenLauncher。
 * 5. 启动失败时在 finally 中重置 RearAnimationManager，防止状态卡死。
 */
public class NotificationService extends NotificationListenerService {
    private static final String TAG = "NotificationService";
    private static final int NOTIFICATION_ID = 1001;
    private static final long NOTIFICATION_DEBOUNCE_MS = 400L;

    private Set<String> selectedApps = new HashSet<>();
    private boolean privacyHideTitle = false;
    private boolean privacyHideContent = false;
    private boolean followDndMode = true;
    private boolean onlyWhenLocked = false;
    private boolean notificationDarkMode = false;
    private boolean serviceEnabled = false;
    private boolean smartMediaEnabled = false;
    private volatile boolean mediaPillEnabled = true;
    private volatile float mediaPillScale = RearSurfacePillRenderer.DEFAULT_SCALE;
    private volatile int mediaPillColor = RearSurfacePillRenderer.DEFAULT_TINT_COLOR;
    private ITaskService taskService;
    private SharedPreferences prefs;
    private PowerManager.WakeLock wakeLock;

    // 通知防抖与串行启动线程
    private final Handler notificationDebounceHandler = new Handler(Looper.getMainLooper());
    private Runnable pendingNotificationRunnable = null;
    private final ExecutorService notificationLaunchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MRSS-NotifLaunch");
        t.setDaemon(true);
        return t;
    });

    // 主屏接近传感器相关
    private SensorManager sensorManager;
    private Sensor mainProximitySensor;
    private boolean isMainScreenCovered = false;

    // 媒体播放检测与原生背屏下拉控制面板相关 (RearMediaActivity + RearPullDownWatcher)
    private MediaSessionManager mediaSessionManager;
    private DisplayManager rearDisplayManager;
    private MediaController activeMediaController = null;
    private RearPullDownWatcher rearPullDownWatcher;
    private volatile RearMediaShadeController.MediaState latestMediaState = null;
    private final Handler mediaMainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mediaExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MRSS-MediaWorker");
        t.setDaemon(true);
        return t;
    });
    private ComponentName componentName;
    private final Map<MediaSession.Token, MediaControllerEntry> registeredMediaControllers = new HashMap<>();
    private final Map<String, MediaSession.Token> lastSeenTokenByPackage = new HashMap<>();
    private final Map<MediaSession.Token, Integer> lastPlaybackStateByToken = new HashMap<>();
    private List<MediaController> latestMediaControllers = new ArrayList<>();
    private volatile String dismissedMediaPackage = null;
    private volatile boolean pendingAutoLaunchAfterAnimation = false;
    private volatile boolean mediaActivityLaunchInFlight = false;
    private volatile boolean wasMediaVisibleBeforeLock = false;
    private volatile boolean wasInAodBeforeLock = false;
    private Runnable pendingLockRestoreRunnable = null;
    private volatile boolean surfacePillVisible = false;
    private volatile boolean lastSurfacePillPlaying = false;
    private volatile int lastSurfacePillX = Integer.MIN_VALUE;
    private volatile int lastSurfacePillY = Integer.MIN_VALUE;
    private volatile float lastSurfacePillScale = -1f;
    private volatile int lastSurfacePillColor = 0;
    private volatile int lastSurfacePillRotation = -1;
    private String cachedArtworkKey = null;
    private Bitmap cachedArtworkBitmap = null;
    private int cachedArtworkAccentColor = 0xFF4ADE80;
    private final Map<String, CachedAppInfo> appInfoCache = new ConcurrentHashMap<>();
    private long lastWakeDisplayCommandMs = 0L;
    private Runnable pendingMediaRemovalRefreshRunnable = null;

    private static final class MediaControllerEntry {
        final MediaController controller;
        final MediaController.Callback callback;

        MediaControllerEntry(MediaController controller, MediaController.Callback callback) {
            this.controller = controller;
            this.callback = callback;
        }
    }

    private static final class CachedAppInfo {
        final String label;
        final Bitmap icon;

        CachedAppInfo(String label, Bitmap icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private static NotificationService instance;
    private static volatile long lastScreenOffElapsedMs = 0L;

    public static NotificationService getInstance() {
        return instance;
    }

    public static boolean wasScreenTurnedOffRecently(long windowMs) {
        long lastOff = lastScreenOffElapsedMs;
        return lastOff > 0L && (SystemClock.elapsedRealtime() - lastOff) <= windowMs;
    }

    public static ITaskService getTaskService() {
        if (instance != null && instance.taskService != null) {
            return instance.taskService;
        }
        if (instance != null) {
            return TaskServiceManager.getInstance(instance).getActiveTaskService();
        }
        return null;
    }

    private final DisplayManager.DisplayListener rearDisplayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {}

        @Override
        public void onDisplayRemoved(int displayId) {}

        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != 1 || !smartMediaEnabled || activeMediaController == null) {
                return;
            }
            try {
                Display rear = (rearDisplayManager != null) ? rearDisplayManager.getDisplay(1) : null;
                if (rear != null && rear.isValid()) {
                    int rot = rear.getRotation();
                    if (lastSurfacePillRotation != -1 && rot != lastSurfacePillRotation) {
                        if (surfacePillVisible) {
                            syncRearSurfacePillAsync(true);
                        } else {
                            syncWatcherHitBox(false, resolveRearPillGeometry(
                                    RearSurfacePillRenderer.clampScale(mediaPillScale)));
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
    };

    private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && TaskServiceManager.ACTION_BACKEND_CHANGED.equals(intent.getAction())) {
                Log.d(TAG, "🔄 收到执行后端切换广播");
                taskService = null;
                bindTaskService();
                if (rearPullDownWatcher != null) {
                    rearPullDownWatcher.stop();
                    mediaMainHandler.postDelayed(NotificationService.this::syncRearPullDownWatcherState, 300L);
                }
                return;
            }
            if (intent != null && "com.tgwgroup.MiRearScreenSwitcher.RELOAD_NOTIFICATION_SETTINGS".equals(intent.getAction())) {
                Log.d(TAG, "🔄 收到重新加载设置的广播");
                loadNotificationServiceSettings();
                loadSettings();
            }
        }
    };

    private final BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                lastScreenOffElapsedMs = SystemClock.elapsedRealtime();
                if (!smartMediaEnabled) return;
                if (RearMediaActivity.isActiveOnRearDisplay()) {
                    wasMediaVisibleBeforeLock = true;
                    wasInAodBeforeLock = RearMediaActivity.isCurrentlyInAod();
                    Log.d(TAG, "🔒 ACTION_SCREEN_OFF while RearMediaActivity active (wasInAod=" + wasInAodBeforeLock + ")");
                }
                schedulePostLockMediaRestoreIfNeeded();
            }
        }
    };

    private final Shizuku.UserServiceArgs serviceArgs =
            new Shizuku.UserServiceArgs(new ComponentName("com.tgwgroup.MiRearScreenSwitcher", TaskService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("notification_task_service")
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

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "🟢 NotificationService created");
        instance = this;
        prefs = getSharedPreferences("mrss_settings", Context.MODE_PRIVATE);

        IntentFilter filter = new IntentFilter("com.tgwgroup.MiRearScreenSwitcher.RELOAD_NOTIFICATION_SETTINGS");
        filter.addAction(TaskServiceManager.ACTION_BACKEND_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(settingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(settingsReceiver, filter);
        }

        IntentFilter screenOffFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenOffReceiver, screenOffFilter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(screenOffReceiver, screenOffFilter);
        }

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        bindTaskService();

        loadNotificationServiceSettings();
        initMainProximitySensor();
        startForeground(NOTIFICATION_ID, RearScreenKeeperService.createServiceNotification(this));

        componentName = new ComponentName(this, NotificationService.class);
        rearPullDownWatcher = new RearPullDownWatcher(this, this::onRearPullDownDetected, this::isRearDisplayOn);
        rearDisplayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (rearDisplayManager != null) {
            try {
                rearDisplayManager.registerDisplayListener(rearDisplayListener, mediaMainHandler);
            } catch (Throwable ignored) {}
        }
        RearAnimationManager.addAnimationStateListener(mediaAnimationListener);
        mediaSessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        try {
            if (mediaSessionManager != null) {
                mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, componentName);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "缺少监听MediaSession的权限", e);
        }

        loadSettings();
        if (smartMediaEnabled && mediaSessionManager != null) {
            try {
                List<MediaController> activeSessions = mediaSessionManager.getActiveSessions(componentName);
                sessionsChangedListener.onActiveSessionsChanged(activeSessions);
            } catch (SecurityException e) {
                Log.e(TAG, "缺少监听MediaSession的权限", e);
            }
        }
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
                        } catch (Exception e) {
                            Log.w(TAG, "初始化显示屏缓存失败: " + e.getMessage());
                        }
                    }).start();
                }
                return;
            }
            if (taskService != null) return;
            if (!Shizuku.pingBinder()) return;
            Shizuku.bindUserService(serviceArgs, taskServiceConnection);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind TaskService", e);
        }
    }

    private void loadNotificationServiceSettings() {
        try {
            SharedPreferences flutterPrefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE);
            serviceEnabled = flutterPrefs.getBoolean("flutter.notification_service_enabled", false);
        } catch (Exception e) {
            Log.e(TAG, "✗ 加载通知服务设置失败", e);
            serviceEnabled = false;
        }
    }

    private void loadSettings() {
        if (prefs == null) return;
        try {
            boolean oldSmartMediaEnabled = smartMediaEnabled;
            boolean oldPillEnabled = mediaPillEnabled;
            float oldPillScale = mediaPillScale;
            int oldPillColor = mediaPillColor;

            selectedApps = prefs.getStringSet("notification_selected_apps", new HashSet<>());
            privacyHideTitle = prefs.getBoolean("notification_privacy_hide_title", false);
            privacyHideContent = prefs.getBoolean("notification_privacy_hide_content", false);
            followDndMode = prefs.getBoolean("notification_follow_dnd_mode", true);
            onlyWhenLocked = prefs.getBoolean("notification_only_when_locked", false);
            notificationDarkMode = prefs.getBoolean("notification_dark_mode", false);
            smartMediaEnabled = prefs.getBoolean("smart_media_enabled", false);
            mediaPillEnabled = prefs.getBoolean("media_pill_enabled", true);
            mediaPillScale = RearSurfacePillRenderer.clampScale(
                    prefs.getFloat("media_pill_scale", RearSurfacePillRenderer.DEFAULT_SCALE));
            mediaPillColor = prefs.getInt("media_pill_color", RearSurfacePillRenderer.DEFAULT_TINT_COLOR);

            boolean pillPrefsChanged = (oldPillEnabled != mediaPillEnabled)
                    || (Math.abs(oldPillScale - mediaPillScale) > 0.001f)
                    || (oldPillColor != mediaPillColor);

            if (!oldSmartMediaEnabled && smartMediaEnabled) {
                dismissedMediaPackage = null;
                pendingAutoLaunchAfterAnimation = false;
                if (mediaSessionManager != null && componentName != null) {
                    try {
                        sessionsChangedListener.onActiveSessionsChanged(
                                mediaSessionManager.getActiveSessions(componentName));
                    } catch (SecurityException e) {
                        Log.e(TAG, "Failed pushing initial media sessions", e);
                    }
                }
            } else if (oldSmartMediaEnabled && !smartMediaEnabled) {
                dismissedMediaPackage = null;
                pendingAutoLaunchAfterAnimation = false;
                hideMediaWidget();
            } else if (smartMediaEnabled && pillPrefsChanged) {
                if (!mediaPillEnabled) {
                    hideRearSurfacePillAsync();
                } else {
                    syncRearSurfacePillAsync(true);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "加载设置失败", e);
            selectedApps = new HashSet<>();
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        super.onNotificationPosted(sbn);
        if (sbn == null) return;

        loadNotificationServiceSettings();
        if (!serviceEnabled) {
            return;
        }

        try {
            String packageName = sbn.getPackageName();
            Notification notification = sbn.getNotification();
            if (packageName == null || notification == null) {
                return;
            }

            // 忽略常驻通知
            if ((notification.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                return;
            }

            // 忽略分组摘要通知（避免与真实子消息同时触发双重通知风暴）
            if ((notification.flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
                Log.d(TAG, "⏭️ 忽略分组摘要通知 (FLAG_GROUP_SUMMARY): " + packageName);
                return;
            }

            // 忽略自己的通知
            if (packageName.equals(getPackageName())) {
                return;
            }

            loadSettings();
            if (!serviceEnabled) {
                return;
            }

            if (followDndMode) {
                try {
                    NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                    if (nm != null && nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL) {
                        Log.d(TAG, "⏭️ 系统勿扰模式已开启，跳过通知动画");
                        return;
                    }
                } catch (Exception ignored) {}
            }

            if (onlyWhenLocked && !isMainScreenCovered) {
                Log.d(TAG, "⏭️ 主屏未被遮盖，仅倒扣手机通知模式已开启，跳过");
                return;
            }

            if (!selectedApps.contains(packageName)) {
                return;
            }

            // 安全提取标题与正文（支持 SpannableString / MessagingStyle / BigText / InboxLines）
            String[] extracted = extractNotificationTitleAndText(notification);
            String title = extracted[0];
            String text = extracted[1];
            long when = notification.when;

            if (privacyHideTitle) {
                title = getString(R.string.privacy_mode_enabled);
            }
            if (privacyHideContent) {
                text = getString(R.string.new_message_placeholder);
            }

            Log.d(TAG, String.format("📢 选中应用通知(%s) key=%s | title=[%s] | text=[%s]",
                    packageName, sbn.getKey(), title, text));

            // 400ms 防抖合并：Gmail / Messenger 2ms 内连发多条更新时仅处理最终完整的一条
            final String finalPackageName = packageName;
            final String finalTitle = title;
            final String finalText = text;
            final long finalWhen = when;

            if (pendingNotificationRunnable != null) {
                notificationDebounceHandler.removeCallbacks(pendingNotificationRunnable);
            }
            pendingNotificationRunnable = () -> {
                pendingNotificationRunnable = null;
                dispatchCoalescedNotification(finalPackageName, finalTitle, finalText, finalWhen);
            };
            notificationDebounceHandler.postDelayed(pendingNotificationRunnable, NOTIFICATION_DEBOUNCE_MS);

        } catch (Exception e) {
            Log.e(TAG, "❌ 处理通知时出错", e);
        }
    }

    /**
     * 安全提取通知标题和正文：
     * 使用 getCharSequence() 避免 SpannableString 触发 W/Bundle 类型异常，
     * 并依次支持 MessagingStyle (EXTRA_MESSAGES)、EXTRA_BIG_TEXT、EXTRA_TEXT、EXTRA_TEXT_LINES、EXTRA_SUB_TEXT 与 tickerText。
     */
    private static String[] extractNotificationTitleAndText(Notification notification) {
        String title = "";
        String text = "";
        Bundle extras = notification.extras;

        if (extras != null) {
            // 1. 优先提取标题（对话标题 > 大标题 > 常规标题）
            title = firstNonEmpty(
                    getCharSequenceSafe(extras, Notification.EXTRA_CONVERSATION_TITLE),
                    getCharSequenceSafe(extras, Notification.EXTRA_TITLE_BIG),
                    getCharSequenceSafe(extras, Notification.EXTRA_TITLE)
            );

            // 2. 尝试解析 MessagingStyle (Messenger / WhatsApp / Google Messages 等)
            String msgSender = "";
            String msgBody = "";
            try {
                Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
                if (messages != null && messages.length > 0) {
                    for (int i = messages.length - 1; i >= 0; i--) {
                        if (messages[i] instanceof Bundle) {
                            Bundle msgBundle = (Bundle) messages[i];
                            String candidateText = getCharSequenceSafe(msgBundle, "text");
                            if (!candidateText.isEmpty()) {
                                msgBody = candidateText;
                                msgSender = getCharSequenceSafe(msgBundle, "sender");
                                if (msgSender.isEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                    try {
                                        android.app.Person person = msgBundle.getParcelable("sender_person");
                                        if (person != null && person.getName() != null) {
                                            msgSender = person.getName().toString().trim();
                                        }
                                    } catch (Throwable ignored) {}
                                }
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // 3. 若非 MessagingStyle 或无消息体，依次检查 EXTRA_BIG_TEXT / EXTRA_TEXT / EXTRA_TEXT_LINES / EXTRA_SUB_TEXT
            String bigText = getCharSequenceSafe(extras, Notification.EXTRA_BIG_TEXT);
            String plainText = getCharSequenceSafe(extras, Notification.EXTRA_TEXT);
            String linesText = extractTextLinesSafe(extras);
            String subText = getCharSequenceSafe(extras, Notification.EXTRA_SUB_TEXT);

            text = firstNonEmpty(msgBody, bigText, plainText, linesText, subText);

            if (title.isEmpty() && !msgSender.isEmpty()) {
                title = msgSender;
            } else if (!msgSender.isEmpty() && !title.equals(msgSender) && !text.startsWith(msgSender)) {
                text = msgSender + ": " + text;
            }
        }

        // 4. 兜底使用 tickerText
        if (text.isEmpty() && notification.tickerText != null) {
            String ticker = notification.tickerText.toString().trim();
            if (!ticker.isEmpty() && !ticker.equals(title)) {
                text = ticker;
            }
        }

        return new String[]{title, text};
    }

    private static String getCharSequenceSafe(Bundle bundle, String key) {
        if (bundle == null || key == null) return "";
        try {
            CharSequence cs = bundle.getCharSequence(key);
            if (cs != null) {
                return cs.toString().trim();
            }
            Object raw = bundle.get(key);
            if (raw != null) {
                return raw.toString().trim();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static String extractTextLinesSafe(Bundle extras) {
        try {
            CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null && lines.length > 0) {
                StringBuilder sb = new StringBuilder();
                int start = Math.max(0, lines.length - 3);
                for (int i = start; i < lines.length; i++) {
                    if (lines[i] == null) continue;
                    String line = lines[i].toString().trim();
                    if (!line.isEmpty()) {
                        if (sb.length() > 0) sb.append('\n');
                        sb.append(line);
                    }
                }
                return sb.toString();
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private static String firstNonEmpty(String... values) {
        for (String v : values) {
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return "";
    }

    private void dispatchCoalescedNotification(String packageName, String title, String text, long when) {
        notificationDarkMode = prefs.getBoolean("notification_dark_mode", false);

        // 若通知Activity已在背屏运行，直接原地更新内容，避免打断销毁重建导致闪烁
        if (RearScreenNotificationActivity.isActiveOnRearDisplay()) {
            Log.d(TAG, "🔄 通知Activity已在背屏存活，原地更新内容");
            boolean updated = RearScreenNotificationActivity.updateActiveOnRearDisplay(
                    packageName, title, text, when, notificationDarkMode);
            if (updated) {
                notificationLaunchExecutor.execute(() -> {
                    try {
                        ITaskService ts = getTaskService();
                        if (ts != null) {
                            ts.executeShellCommand("input -d 1 keyevent 224");
                        }
                    } catch (Throwable ignored) {}
                });
                return;
            }
        }

        // 通知动画管理器：标记开始通知动画
        RearAnimationManager.AnimationType oldAnim =
                RearAnimationManager.startAnimation(RearAnimationManager.AnimationType.NOTIFICATION);

        if (oldAnim == RearAnimationManager.AnimationType.CHARGING) {
            Log.d(TAG, "🔄 检测到充电动画正在播放，发送打断广播");
            boolean chargingAlwaysOn = prefs.getBoolean("charging_always_on_enabled", false);
            RearAnimationManager.markInterruptedChargingAsAlwaysOn(chargingAlwaysOn);
            RearAnimationManager.sendInterruptBroadcast(this, RearAnimationManager.AnimationType.CHARGING);
        }

        showNotificationOnRearScreen(packageName, title, text, when);
    }

    private void showNotificationOnRearScreen(String packageName, String title, String text, long when) {
        if (taskService == null) {
            bindTaskService();
            notificationDebounceHandler.postDelayed(() ->
                    notificationLaunchExecutor.execute(() ->
                            showNotificationOnRearScreenDirect(packageName, title, text, when)), 400L);
        } else {
            notificationLaunchExecutor.execute(() ->
                    showNotificationOnRearScreenDirect(packageName, title, text, when));
        }
    }

    private void showNotificationOnRearScreenDirect(String packageName, String title, String text, long when) {
        boolean launchSucceeded = false;
        try {
            ITaskService ts = getTaskService();
            if (ts == null) {
                Log.e(TAG, "❌ TaskService不可用，放弃显示通知");
                return;
            }

            if (!com.tgwgroup.MiRearScreenSwitcher.adb.ShellCommandSanitizer.isValidPackageName(packageName)) {
                Log.e(TAG, "❌ 拒绝非法通知包名: " + packageName);
                return;
            }

            acquireWakeLock(6000);
            try {
                RearScreenKeeperService.pauseMonitoring();
            } catch (Throwable ignored) {}

            // 注意：绝不调用 disableSubScreenLauncher() (am force-stop com.xiaomi.subscreencenter)，
            // 否则会导致背屏 1s 黑屏闪烁和 SubScreenLauncher 冷启动抢焦点！

            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            boolean isLocked = km != null && km.isKeyguardLocked();

            notificationDarkMode = prefs.getBoolean("notification_dark_mode", false);
            String componentNameStr = getPackageName() + "/" + RearScreenNotificationActivity.class.getName();
            String safePkg = com.tgwgroup.MiRearScreenSwitcher.adb.ShellCommandSanitizer.escapeShellSingleQuoted(packageName);
            String safeTitle = com.tgwgroup.MiRearScreenSwitcher.adb.ShellCommandSanitizer.escapeShellSingleQuoted(title);
            String safeText = com.tgwgroup.MiRearScreenSwitcher.adb.ShellCommandSanitizer.escapeShellSingleQuoted(text);

            if (!isLocked) {
                // 解锁状态：先温和唤醒 Display 1，再直接 --display 1 启动
                ts.executeShellCommand("input -d 1 keyevent 224");
                CountDownLatch latch = RearScreenNotificationActivity.prepareForLaunch();
                String directCmd = String.format(
                        "am start --display 1 -n %s --es packageName %s --es title %s --es text %s --el when %d --ez darkMode %b",
                        componentNameStr, safePkg, safeTitle, safeText, when, notificationDarkMode
                );
                Log.d(TAG, "🚀 解锁状态：直接在 Display 1 启动通知Activity");
                ts.executeShellCommand(directCmd);

                try {
                    latch.await(900, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {}

                if (RearScreenNotificationActivity.isActiveOnRearDisplay()) {
                    launchSucceeded = true;
                    Log.d(TAG, "✅ 通知动画已在 Display 1 启动");
                    return;
                }

                // 若意外落到了 Display 0，直接搬运到 Display 1
                int launchedTaskId = RearScreenNotificationActivity.getLastLaunchedTaskId();
                if (launchedTaskId > 0) {
                    Log.d(TAG, "🔄 解锁启动落到 Display 0 (taskId=" + launchedTaskId + ")，移动到 Display 1");
                    ts.moveTaskToDisplay(launchedTaskId, 1);
                    ts.executeShellCommand("input -d 1 keyevent 224");
                    launchSucceeded = true;
                    return;
                }
            }

            // 锁屏状态（或解锁直接启动失败时的兜底）：
            // HyperOS 4 锁屏时拦截 --display 1 (result code=102)，因此直接显式 --display 0 + NO_ANIMATION 启动 + 搬运至 Display 1
            Log.d(TAG, "🔒 锁屏/兜底策略：显式 --display 0 (-f 0x10010000) 启动通知占位Activity，随后搬运至 Display 1");
            CountDownLatch latch = RearScreenNotificationActivity.prepareForLaunch();
            String startOnMainCmd = String.format(
                    "am start --display 0 -f 0x10010000 -n %s --es packageName %s --es title %s --es text %s --el when %d --ez darkMode %b",
                    componentNameStr, safePkg, safeTitle, safeText, when, notificationDarkMode
            );
            ts.executeShellCommand(startOnMainCmd);

            int notifTaskId = -1;
            try {
                latch.await(1500, TimeUnit.MILLISECONDS);
                notifTaskId = RearScreenNotificationActivity.getLastLaunchedTaskId();
            } catch (InterruptedException ignored) {}

            // 兜底：若静态桥接未拿到 taskId，回退 am stack list 查一次
            if (notifTaskId <= 0) {
                for (int attempt = 0; attempt < 15 && notifTaskId <= 0; attempt++) {
                    Thread.sleep(40);
                    String stackOut = ts.executeShellCommandWithResult("am stack list | grep RearScreenNotificationActivity");
                    if (stackOut != null && !stackOut.trim().isEmpty()) {
                        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("taskId=(\\d+)").matcher(stackOut);
                        if (matcher.find()) {
                            notifTaskId = Integer.parseInt(matcher.group(1));
                            break;
                        }
                    }
                }
            }

            if (notifTaskId > 0) {
                // 若锁屏时Display 0恰好处于亮屏交互状态，等待450ms让Display 0过渡稳定再搬运；息屏/AOD时保持快速搬运
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && pm.isInteractive()) {
                    Log.d(TAG, "⏳ Display 0 当前亮屏(interactive)，等待450ms让Display 0过渡稳定后再搬运 taskId=" + notifTaskId);
                    Thread.sleep(450);
                }
                Log.d(TAG, "🎯 获取到通知 taskId=" + notifTaskId + " -> 移动到 Display 1 并唤醒背屏");
                ts.moveTaskToDisplay(notifTaskId, 1);
                Thread.sleep(40);
                ts.executeShellCommand("input -d 1 keyevent 224");
                launchSucceeded = true;
                Log.d(TAG, "✅ 锁屏通知已成功移动并唤醒 Display 1");
            } else {
                Log.e(TAG, "❌ 未能获取 RearScreenNotificationActivity 的 taskId，放弃本次启动");
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ 显示背屏通知失败", e);
        } finally {
            if (!launchSucceeded) {
                RearAnimationManager.resetAnimationOnFailure(RearAnimationManager.AnimationType.NOTIFICATION);
            }
            releaseWakeLock();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        super.onNotificationRemoved(sbn);
        if (!smartMediaEnabled || sbn == null) return;

        try {
            Notification notification = sbn.getNotification();
            if (notification != null && notification.extras != null) {
                String template = getCharSequenceSafe(notification.extras, Notification.EXTRA_TEMPLATE);
                if (template.contains("MediaStyle")) {
                    String removedPkg = sbn.getPackageName();
                    Log.d(TAG, "🎵 Media notification removed: " + removedPkg);
                    if (removedPkg != null && removedPkg.equals(dismissedMediaPackage)) {
                        dismissedMediaPackage = null;
                        Log.d(TAG, "🧹 Cleared dismissedMediaPackage on MediaStyle notification removal: " + removedPkg);
                    }
                    if (pendingMediaRemovalRefreshRunnable != null) {
                        mediaMainHandler.removeCallbacks(pendingMediaRemovalRefreshRunnable);
                    }
                    pendingMediaRemovalRefreshRunnable = () -> {
                        pendingMediaRemovalRefreshRunnable = null;
                        if (!smartMediaEnabled || mediaSessionManager == null || componentName == null) return;
                        try {
                            List<MediaController> currentSessions =
                                    mediaSessionManager.getActiveSessions(componentName);
                            sessionsChangedListener.onActiveSessionsChanged(currentSessions);
                        } catch (SecurityException ignored) {}
                    };
                    mediaMainHandler.postDelayed(pendingMediaRemovalRefreshRunnable, 150L);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "❌ Error handling removed notification: ", e);
        }
    }

    private void acquireWakeLock(long timeoutMs) {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                if (wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MRSS:NotificationWake");
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

    private void initMainProximitySensor() {
        try {
            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            if (sensorManager != null) {
                List<Sensor> allSensors = sensorManager.getSensorList(Sensor.TYPE_ALL);
                Sensor wakeupSensor = null;
                Sensor nonWakeupSensor = null;
                for (Sensor sensor : allSensors) {
                    String name = sensor.getName();
                    if (name.contains("Proximity") && !name.contains("Back")) {
                        if (name.contains("Wakeup")) {
                            wakeupSensor = sensor;
                        } else {
                            nonWakeupSensor = sensor;
                        }
                    }
                }
                mainProximitySensor = (wakeupSensor != null) ? wakeupSensor : nonWakeupSensor;
                if (mainProximitySensor == null) {
                    mainProximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
                }
                if (mainProximitySensor != null) {
                    sensorManager.registerListener(
                            proximitySensorListener,
                            mainProximitySensor,
                            SensorManager.SENSOR_DELAY_NORMAL
                    );
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "✗ Error initializing main proximity sensor", e);
        }
    }

    private void unregisterMainProximitySensor() {
        try {
            if (sensorManager != null && proximitySensorListener != null) {
                sensorManager.unregisterListener(proximitySensorListener);
            }
        } catch (Exception ignored) {}
    }

    private final SensorEventListener proximitySensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor == mainProximitySensor) {
                float distance = event.values[0];
                float maxRange = mainProximitySensor.getMaximumRange();
                isMainScreenCovered = (distance < maxRange * 0.2f);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private final RearAnimationManager.OnAnimationStateListener mediaAnimationListener =
            new RearAnimationManager.OnAnimationStateListener() {
                @Override
                public void onAnimationStarted(RearAnimationManager.AnimationType type) {
                    // Immediately hide the SurfaceControl Media Pull Pill during notification/charging animations.
                    hideRearSurfacePillAsync();
                }

                @Override
                public void onAnimationEnded(RearAnimationManager.AnimationType type) {
                    if (pendingAutoLaunchAfterAnimation) {
                        pendingAutoLaunchAfterAnimation = false;
                        mediaMainHandler.post(() -> {
                            if (!smartMediaEnabled || activeMediaController == null) {
                                hideRearSurfacePillAsync();
                                return;
                            }
                            if (RearAnimationManager.isAnimationPlaying()) return;
                            PlaybackState pb = activeMediaController.getPlaybackState();
                            if (pb != null && pb.getState() == PlaybackState.STATE_PLAYING) {
                                dispatchMediaStateToActivity(activeMediaController, true);
                            } else {
                                syncRearSurfacePillAsync(true);
                            }
                        });
                    } else {
                        mediaMainHandler.post(() -> syncRearSurfacePillAsync(true));
                    }
                }
            };

    public RearMediaShadeController.MediaState getLatestMediaState() {
        return latestMediaState;
    }

    public void onUserDismissedMedia(String packageName) {
        dismissedMediaPackage = packageName;
        pendingAutoLaunchAfterAnimation = false;
        wasMediaVisibleBeforeLock = false;
        wasInAodBeforeLock = false;
        if (pendingLockRestoreRunnable != null) {
            mediaMainHandler.removeCallbacks(pendingLockRestoreRunnable);
            pendingLockRestoreRunnable = null;
        }
        Log.d(TAG, "👆 User dismissed RearMediaActivity for package: " + packageName);
        mediaMainHandler.post(this::syncRearPullDownWatcherState);
    }

    public void onRearMediaHiddenByLock(boolean wasInAod) {
        wasMediaVisibleBeforeLock = true;
        wasInAodBeforeLock = wasInAod;
        Log.d(TAG, "🔒 RearMediaActivity hidden by lock transition (wasInAod=" + wasInAod + ") -> scheduling restore");
        mediaMainHandler.post(this::schedulePostLockMediaRestoreIfNeeded);
    }

    private void schedulePostLockMediaRestoreIfNeeded() {
        if (!smartMediaEnabled || activeMediaController == null) {
            wasMediaVisibleBeforeLock = false;
            wasInAodBeforeLock = false;
            syncRearPullDownWatcherState();
            return;
        }
        String pkg = activeMediaController.getPackageName();
        if (!wasMediaVisibleBeforeLock || (pkg != null && pkg.equals(dismissedMediaPackage))) {
            wasMediaVisibleBeforeLock = false;
            wasInAodBeforeLock = false;
            syncRearPullDownWatcherState();
            return;
        }

        if (pendingLockRestoreRunnable != null) {
            mediaMainHandler.removeCallbacks(pendingLockRestoreRunnable);
        }
        pendingLockRestoreRunnable = () -> {
            pendingLockRestoreRunnable = null;
            final boolean restoreInAod = wasInAodBeforeLock;
            final boolean shouldRestore = wasMediaVisibleBeforeLock;
            wasMediaVisibleBeforeLock = false;
            wasInAodBeforeLock = false;

            if (!shouldRestore || !smartMediaEnabled || activeMediaController == null) {
                syncRearPullDownWatcherState();
                return;
            }
            String currentPkg = activeMediaController.getPackageName();
            if (currentPkg != null && currentPkg.equals(dismissedMediaPackage)) {
                syncRearPullDownWatcherState();
                return;
            }
            if (RearMediaActivity.isActiveOnRearDisplay() || RearAnimationManager.isAnimationPlaying()) {
                return;
            }
            mediaExecutor.execute(() -> {
                if (!smartMediaEnabled || activeMediaController == null) return;
                if (RearMediaActivity.isActiveOnRearDisplay() || RearAnimationManager.isAnimationPlaying()) return;
                if (isUserProjectedAppOnRearDisplayOnWorker()) {
                    mediaMainHandler.post(this::syncRearPullDownWatcherState);
                    return;
                }
                Log.d(TAG, "🔒 Restoring sticky RearMediaActivity after lock (startInAod=" + restoreInAod + ")");
                launchRearMediaActivityOnWorker(false, restoreInAod, true);
            });
        };
        mediaMainHandler.postDelayed(pendingLockRestoreRunnable, 450L);
    }

    public void onRearMediaActivityStateChanged(boolean openOnRearDisplay) {
        mediaMainHandler.post(() -> {
            if (openOnRearDisplay) {
                hideRearSurfacePillAsync();
                if (rearPullDownWatcher != null) {
                    rearPullDownWatcher.stop();
                }
            } else {
                syncRearPullDownWatcherState();
                // Allow HyperOS ~250ms to finish removing the closed task from Display 1's stack
                // so isSubScreenLauncherForegroundOnDisplay1OnWorker() sees com.xiaomi.subscreencenter.
                mediaMainHandler.postDelayed(() -> {
                    if (smartMediaEnabled && activeMediaController != null && !RearMediaActivity.isActiveOnRearDisplay()) {
                        syncRearSurfacePillAsync(false);
                    }
                }, 250L);
            }
        });
    }

    /**
     * Strict watcher & SurfaceControl pill lifecycle:
     * Active ONLY when Smart Media is enabled, at least one active media session exists,
     * AND RearMediaActivity is currently closed/collapsed.
     */
    private void syncRearPullDownWatcherState() {
        syncRearSurfacePillAsync(false);
        if (rearPullDownWatcher == null) return;
        boolean shouldWatch = smartMediaEnabled
                && activeMediaController != null
                && !RearMediaActivity.isActiveOnRearDisplay();
        if (shouldWatch) {
            rearPullDownWatcher.start();
        } else {
            rearPullDownWatcher.stop();
        }
    }

    private static final class RearPillGeometry {
        final int x;
        final int y;
        final int width;
        final int height;
        final int displayW;
        final int displayH;
        final int rotation;

        RearPillGeometry(int x, int y, int width, int height, int displayW, int displayH, int rotation) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.displayW = displayW;
            this.displayH = displayH;
            this.rotation = rotation;
        }
    }

    private boolean isRearDisplayOn() {
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display rear = (dm != null) ? dm.getDisplay(1) : null;
            return rear != null && rear.getState() == Display.STATE_ON;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private RearPillGeometry resolveRearPillGeometry(float scale) {
        final int pillW = RearSurfacePillRenderer.computeScaledWidth(scale);
        final int pillH = RearSurfacePillRenderer.computeScaledHeight(scale);
        int displayW = 904;
        int displayH = 572;
        int rotation = Surface.ROTATION_0;
        int x = 296 + Math.max(0, (608 - pillW) / 2);
        int y = 10;
        try {
            DisplayManager dm = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display rear = (dm != null) ? dm.getDisplay(1) : null;
            if (rear != null && rear.isValid()) {
                rotation = rear.getRotation();
                DisplayMetrics metrics = new DisplayMetrics();
                rear.getRealMetrics(metrics);
                displayW = metrics.widthPixels > 0 ? metrics.widthPixels : 904;
                displayH = metrics.heightPixels > 0 ? metrics.heightPixels : 572;
                int cutout = (displayW == 904) ? 296 : Math.max(296, Math.round(displayW * 0.328f));
                int safeWidth = Math.max(pillW, displayW - cutout);
                if (rotation == Surface.ROTATION_180) {
                    x = Math.max(0, (safeWidth - pillW) / 2);
                } else {
                    x = cutout + Math.max(0, (safeWidth - pillW) / 2);
                }
            }
        } catch (Throwable ignored) {}
        return new RearPillGeometry(x, y, pillW, pillH, displayW, displayH, rotation);
    }

    private void syncWatcherHitBox(boolean pillVisible, RearPillGeometry geom) {
        RearPullDownWatcher watcher = rearPullDownWatcher;
        if (watcher == null || geom == null) return;
        watcher.updatePillHitBox(
                pillVisible,
                pillVisible ? geom.x : 0,
                pillVisible ? geom.y : 0,
                pillVisible ? geom.width : 0,
                pillVisible ? geom.height : 0,
                geom.displayW,
                geom.displayH,
                geom.rotation);
    }

    private void syncRearSurfacePillAsync(final boolean forceUpdate) {
        mediaExecutor.execute(() -> syncRearSurfacePillOnWorker(forceUpdate));
    }

    private void syncRearSurfacePillOnWorker(boolean forceUpdate) {
        boolean basicEligible = smartMediaEnabled
                && mediaPillEnabled
                && activeMediaController != null
                && !RearMediaActivity.isActiveOnRearDisplay()
                && !RearAnimationManager.isAnimationPlaying()
                && !mediaActivityLaunchInFlight;

        if (!basicEligible || !isSubScreenLauncherForegroundOnDisplay1OnWorker()) {
            hideRearSurfacePillOnWorker();
            return;
        }

        boolean isPlaying = false;
        RearMediaShadeController.MediaState st = latestMediaState;
        if (st != null) {
            isPlaying = st.isPlaying;
        } else {
            try {
                MediaController mc = activeMediaController;
                PlaybackState pb = (mc != null) ? mc.getPlaybackState() : null;
                isPlaying = (pb != null && pb.getState() == PlaybackState.STATE_PLAYING);
            } catch (Throwable ignored) {}
        }

        final float scale = RearSurfacePillRenderer.clampScale(mediaPillScale);
        final int tintColor = mediaPillColor;
        RearPillGeometry geom = resolveRearPillGeometry(scale);

        if (!forceUpdate && surfacePillVisible
                && isPlaying == lastSurfacePillPlaying
                && geom.x == lastSurfacePillX
                && geom.y == lastSurfacePillY
                && geom.rotation == lastSurfacePillRotation
                && Math.abs(scale - lastSurfacePillScale) <= 0.001f
                && tintColor == lastSurfacePillColor) {
            syncWatcherHitBox(true, geom);
            return;
        }

        try {
            ITaskService ts = getTaskService();
            if (ts != null) {
                ts.showRearMediaPillStyled(isPlaying, geom.x, geom.y, scale, tintColor);
                surfacePillVisible = true;
                lastSurfacePillPlaying = isPlaying;
                lastSurfacePillX = geom.x;
                lastSurfacePillY = geom.y;
                lastSurfacePillScale = scale;
                lastSurfacePillColor = tintColor;
                lastSurfacePillRotation = geom.rotation;
                syncWatcherHitBox(true, geom);
            }
        } catch (Throwable t) {
            Log.w(TAG, "showRearMediaPillStyled failed: " + t.getMessage());
        }
    }

    private void hideRearSurfacePillAsync() {
        mediaExecutor.execute(this::hideRearSurfacePillOnWorker);
    }

    private void hideRearSurfacePillOnWorker() {
        surfacePillVisible = false;
        lastSurfacePillX = Integer.MIN_VALUE;
        lastSurfacePillY = Integer.MIN_VALUE;
        lastSurfacePillScale = -1f;
        lastSurfacePillColor = 0;
        lastSurfacePillRotation = -1;
        syncWatcherHitBox(false, resolveRearPillGeometry(RearSurfacePillRenderer.clampScale(mediaPillScale)));
        try {
            ITaskService ts = getTaskService();
            if (ts != null) {
                ts.hideRearMediaPill();
            }
        } catch (Throwable ignored) {}
    }

    private boolean isSubScreenLauncherForegroundOnDisplay1OnWorker() {
        if (RearScreenKeeperService.isRunning()) {
            return false;
        }
        try {
            ITaskService ts = getTaskService();
            if (ts == null) return false;
            String fg = ts.getForegroundAppOnDisplay(1);
            if (fg == null || fg.trim().isEmpty()) return false;
            String pkg = fg.contains(":") ? fg.substring(0, fg.indexOf(':')) : fg.trim();
            return "com.xiaomi.subscreencenter".equals(pkg);
        } catch (Throwable t) {
            return false;
        }
    }

    private void onRearPullDownDetected() {
        if (!smartMediaEnabled) return;
        mediaExecutor.execute(() -> {
            if (!smartMediaEnabled || activeMediaController == null) return;
            if (RearMediaActivity.isActiveOnRearDisplay()) {
                if (rearPullDownWatcher != null) rearPullDownWatcher.stop();
                return;
            }
            if (RearAnimationManager.isAnimationPlaying()) {
                Log.d(TAG, "⏭️ Ignoring rear pull-down: RearAnimationManager animation is playing");
                return;
            }
            if (!isSubScreenLauncherForegroundOnDisplay1OnWorker()) {
                Log.d(TAG, "⏭️ Ignoring rear pull-down: SubScreenLauncher is not foreground on Display 1");
                if (surfacePillVisible) {
                    hideRearSurfacePillOnWorker();
                }
                return;
            }
            dismissedMediaPackage = null;
            pendingAutoLaunchAfterAnimation = false;
            wasMediaVisibleBeforeLock = false;
            wasInAodBeforeLock = false;
            Log.d(TAG, "⬇️ Rear pull-down confirmed on SubScreenLauncher -> launching RearMediaActivity");
            launchRearMediaActivityOnWorker(true, false, false);
        });
    }

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    latestMediaControllers = (controllers != null)
                            ? new ArrayList<>(controllers)
                            : new ArrayList<>();

                    if (!smartMediaEnabled) {
                        unregisterAllMediaCallbacks();
                        activeMediaController = null;
                        latestMediaState = null;
                        hideRearSurfacePillAsync();
                        if (rearPullDownWatcher != null) {
                            rearPullDownWatcher.stop();
                        }
                        RearMediaActivity.finishFromService();
                        return;
                    }

                    // Diff active sessions by MediaSession.Token so we only register/unregister deltas
                    Set<MediaSession.Token> currentTokens = new HashSet<>();
                    for (MediaController mc : latestMediaControllers) {
                        if (mc != null && mc.getSessionToken() != null) {
                            currentTokens.add(mc.getSessionToken());
                        }
                    }

                    Iterator<Map.Entry<MediaSession.Token, MediaControllerEntry>> it =
                            registeredMediaControllers.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<MediaSession.Token, MediaControllerEntry> entry = it.next();
                        if (!currentTokens.contains(entry.getKey())) {
                            try {
                                entry.getValue().controller.unregisterCallback(entry.getValue().callback);
                            } catch (Exception ignored) {}
                            lastPlaybackStateByToken.remove(entry.getKey());
                            it.remove();
                        }
                    }

                    for (final MediaController controller : latestMediaControllers) {
                        if (controller == null) continue;
                        final MediaSession.Token token = controller.getSessionToken();
                        if (token == null || registeredMediaControllers.containsKey(token)) continue;

                        String ctrlPkg = controller.getPackageName();
                        if (ctrlPkg != null && !ctrlPkg.isEmpty()) {
                            MediaSession.Token prevToken = lastSeenTokenByPackage.put(ctrlPkg, token);
                            if (prevToken != null && !token.equals(prevToken) && ctrlPkg.equals(dismissedMediaPackage)) {
                                dismissedMediaPackage = null;
                                Log.d(TAG, "🧹 New MediaSession.Token created for " + ctrlPkg + " -> cleared dismissedMediaPackage");
                            }
                        }

                        PlaybackState initialPb = controller.getPlaybackState();
                        lastPlaybackStateByToken.put(token,
                                initialPb != null ? initialPb.getState() : PlaybackState.STATE_NONE);

                        MediaController.Callback cb = new MediaController.Callback() {
                            @Override
                            public void onPlaybackStateChanged(PlaybackState state) {
                                super.onPlaybackStateChanged(state);
                                mediaMainHandler.post(() -> {
                                    int newState = (state != null) ? state.getState() : PlaybackState.STATE_NONE;
                                    Integer prevStateObj = lastPlaybackStateByToken.put(token, newState);
                                    int prevState = (prevStateObj != null) ? prevStateObj : PlaybackState.STATE_NONE;
                                    if (newState == PlaybackState.STATE_PLAYING
                                            && (prevState == PlaybackState.STATE_NONE
                                            || prevState == PlaybackState.STATE_STOPPED)) {
                                        String pkg = controller.getPackageName();
                                        if (pkg != null && pkg.equals(dismissedMediaPackage)) {
                                            dismissedMediaPackage = null;
                                            Log.d(TAG, "🧹 State NONE/STOPPED -> PLAYING for " + pkg + " -> cleared dismissedMediaPackage");
                                        }
                                    }
                                    reevaluateActiveMediaSession();
                                });
                            }

                            @Override
                            public void onMetadataChanged(MediaMetadata metadata) {
                                super.onMetadataChanged(metadata);
                                mediaMainHandler.post(() -> {
                                    if (!smartMediaEnabled) return;
                                    if (activeMediaController != null
                                            && token.equals(activeMediaController.getSessionToken())) {
                                        dispatchMediaStateToActivity(activeMediaController, false);
                                    } else {
                                        reevaluateActiveMediaSession();
                                    }
                                });
                            }

                            @Override
                            public void onSessionDestroyed() {
                                super.onSessionDestroyed();
                                mediaMainHandler.post(() -> {
                                    MediaControllerEntry removed = registeredMediaControllers.remove(token);
                                    if (removed != null) {
                                        try {
                                            removed.controller.unregisterCallback(removed.callback);
                                        } catch (Exception ignored) {}
                                    }
                                    latestMediaControllers.removeIf(mc ->
                                            mc == null || token.equals(mc.getSessionToken()));
                                    reevaluateActiveMediaSession();
                                });
                            }
                        };

                        try {
                            controller.registerCallback(cb, mediaMainHandler);
                            registeredMediaControllers.put(token, new MediaControllerEntry(controller, cb));
                        } catch (Exception e) {
                            Log.w(TAG, "Failed registering MediaController callback: " + e.getMessage());
                        }
                    }

                    reevaluateActiveMediaSession();
                }
            };

    private String lastMediaTitle = "";
    private int lastPlaybackState = -1;

    private void unregisterAllMediaCallbacks() {
        for (MediaControllerEntry entry : registeredMediaControllers.values()) {
            try {
                entry.controller.unregisterCallback(entry.callback);
            } catch (Exception ignored) {}
        }
        registeredMediaControllers.clear();
    }

    private void reevaluateActiveMediaSession() {
        if (!smartMediaEnabled) {
            hideRearSurfacePillAsync();
            if (rearPullDownWatcher != null) rearPullDownWatcher.stop();
            RearMediaActivity.finishFromService();
            return;
        }

        if (latestMediaControllers == null || latestMediaControllers.isEmpty()) {
            activeMediaController = null;
            latestMediaState = null;
            dismissedMediaPackage = null;
            pendingAutoLaunchAfterAnimation = false;
            lastMediaTitle = "";
            lastPlaybackState = -1;
            hideRearSurfacePillAsync();
            if (rearPullDownWatcher != null) rearPullDownWatcher.stop();
            RearMediaActivity.finishFromService();
            return;
        }

        MediaController playingController = null;
        MediaController bufferingController = null;
        MediaController pausedController = null;

        for (MediaController mc : latestMediaControllers) {
            if (mc == null) continue;
            PlaybackState pb = mc.getPlaybackState();
            if (pb == null) continue;
            int st = pb.getState();
            if (st == PlaybackState.STATE_PLAYING) {
                playingController = mc;
                break;
            } else if (st == PlaybackState.STATE_BUFFERING && bufferingController == null) {
                bufferingController = mc;
            } else if (st == PlaybackState.STATE_PAUSED && pausedController == null) {
                pausedController = mc;
            }
        }

        MediaController targetController = playingController;
        if (targetController == null) targetController = bufferingController;
        if (targetController == null) targetController = pausedController;
        if (targetController == null) targetController = latestMediaControllers.get(0);

        activeMediaController = targetController;
        if (activeMediaController == null) {
            latestMediaState = null;
            if (rearPullDownWatcher != null) rearPullDownWatcher.stop();
            RearMediaActivity.finishFromService();
            return;
        }

        boolean isActivelyPlaying = (playingController != null);
        String pkg = activeMediaController.getPackageName();
        if (isActivelyPlaying && dismissedMediaPackage != null && !dismissedMediaPackage.equals(pkg)) {
            dismissedMediaPackage = null;
        }

        dispatchMediaStateToActivity(activeMediaController, isActivelyPlaying);
    }

    private void dispatchMediaStateToActivity(final MediaController controller, final boolean considerAutoLaunch) {
        if (controller == null || !smartMediaEnabled) return;

        final PlaybackState pbState = controller.getPlaybackState();
        final MediaMetadata metadata = controller.getMetadata();
        final String pkgName = controller.getPackageName() != null ? controller.getPackageName() : "";

        final int currentState = (pbState != null) ? pbState.getState() : PlaybackState.STATE_NONE;
        final boolean isPlaying = (currentState == PlaybackState.STATE_PLAYING);
        final long posMs = (pbState != null) ? Math.max(0L, pbState.getPosition()) : 0L;
        final long lastUpdateElapsed = (pbState != null) ? pbState.getLastPositionUpdateTime() : 0L;
        final float playbackSpeed = (pbState != null && pbState.getPlaybackSpeed() > 0f)
                ? pbState.getPlaybackSpeed() : 1.0f;
        final long supportedActions = (pbState != null) ? pbState.getActions() : 0L;

        String rawTitle = (metadata != null) ? metadata.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
        if (rawTitle == null || rawTitle.trim().isEmpty()) {
            rawTitle = (metadata != null) ? metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) : null;
        }
        final String title = (rawTitle != null && !rawTitle.trim().isEmpty()) ? rawTitle : "Unknown";

        String rawArtist = (metadata != null) ? metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
        if (rawArtist == null || rawArtist.trim().isEmpty()) {
            rawArtist = (metadata != null) ? metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) : null;
        }
        if (rawArtist == null || rawArtist.trim().isEmpty()) {
            rawArtist = (metadata != null) ? metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) : null;
        }
        final String artist = rawArtist != null ? rawArtist : "";

        long rawDuration = 0L;
        if (metadata != null && metadata.containsKey(MediaMetadata.METADATA_KEY_DURATION)) {
            rawDuration = Math.max(0L, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        }
        final long durationMs = rawDuration;

        final boolean stateOrTitleChanged = !title.equals(lastMediaTitle) || currentState != lastPlaybackState;
        lastMediaTitle = title;
        lastPlaybackState = currentState;

        final boolean alreadyActiveOnRear = RearMediaActivity.isActiveOnRearDisplay();
        final boolean blockedByAnimation = RearAnimationManager.isAnimationPlaying();

        if (considerAutoLaunch && isPlaying && !alreadyActiveOnRear && !pkgName.equals(dismissedMediaPackage)
                && blockedByAnimation) {
            pendingAutoLaunchAfterAnimation = true;
        }

        final boolean wantsAutoLaunch = considerAutoLaunch
                && isPlaying
                && !alreadyActiveOnRear
                && !pkgName.equals(dismissedMediaPackage)
                && !blockedByAnimation
                && !RearScreenKeeperService.isRunning();

        mediaExecutor.execute(() -> {
            Bitmap artwork = resolveScaledArtworkOnWorker(metadata, pkgName, title, artist);
            int accentColor = cachedArtworkAccentColor;
            CachedAppInfo appInfo = resolveAppInfoOnWorker(pkgName);

            final RearMediaShadeController.MediaState mediaState = new RearMediaShadeController.MediaState(
                    pkgName,
                    title,
                    artist,
                    appInfo.label,
                    artwork,
                    appInfo.icon,
                    isPlaying,
                    posMs,
                    durationMs,
                    playbackSpeed,
                    lastUpdateElapsed,
                    supportedActions,
                    accentColor);
            latestMediaState = mediaState;

            if (RearMediaActivity.updateMediaStateIfActive(mediaState)) {
                if (stateOrTitleChanged) {
                    wakeRearDisplayOnWorker();
                }
                mediaMainHandler.post(this::syncRearPullDownWatcherState);
                return;
            }

            if (wantsAutoLaunch && !isUserProjectedAppOnRearDisplayOnWorker()) {
                launchRearMediaActivityOnWorker(false, false, false);
            } else {
                mediaMainHandler.post(this::syncRearPullDownWatcherState);
            }
        });
    }

    /**
     * Proven HyperOS 4 Display 1 launch pipeline for RearMediaActivity (runs strictly on mediaExecutor).
     * Never calls disableSubScreenLauncher() / am force-stop com.xiaomi.subscreencenter.
     */
    private void launchRearMediaActivityOnWorker(
            boolean fromUserPullDown,
            boolean startInAod,
            boolean forceLockedPath) {
        if (!smartMediaEnabled) return;
        if (RearMediaActivity.isActiveOnRearDisplay()) {
            RearMediaShadeController.MediaState state = latestMediaState;
            if (state != null) {
                RearMediaActivity.updateMediaStateIfActive(state);
            }
            mediaMainHandler.post(this::syncRearPullDownWatcherState);
            return;
        }
        if (RearAnimationManager.isAnimationPlaying() && !fromUserPullDown) {
            pendingAutoLaunchAfterAnimation = true;
            mediaMainHandler.post(this::syncRearPullDownWatcherState);
            return;
        }
        if (mediaActivityLaunchInFlight) {
            return;
        }

        mediaActivityLaunchInFlight = true;
        try {
            if (taskService == null) {
                bindTaskService();
            }
            ITaskService ts = getTaskService();
            if (ts == null) {
                Log.w(TAG, "⚠️ TaskService unavailable, cannot launch RearMediaActivity");
                return;
            }

            // Hide SurfaceControl pill and stop pull-down watcher before launching RearMediaActivity
            hideRearSurfacePillOnWorker();
            if (rearPullDownWatcher != null) {
                rearPullDownWatcher.stop();
            }

            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            boolean isLocked = forceLockedPath || (km != null && km.isKeyguardLocked());
            String compName = getPackageName() + "/.RearMediaActivity";
            String aodExtra = startInAod ? " --ez " + RearMediaActivity.EXTRA_START_IN_AOD + " true" : "";
            RearMediaActivity.setPendingStartInAod(startInAod);

            if (!isLocked) {
                Log.d(TAG, "🚀 Unlocked: launching native RearMediaActivity directly on Display 1 (startInAod=" + startInAod + ")");
                ts.executeShellCommand("input -d 1 keyevent 224");
                RearMediaActivity.prepareForLaunch();
                ts.executeShellCommand(
                        "am start --display 1 -f 0x10010000 -n " + compName + aodExtra);
                int taskId = RearMediaActivity.awaitLaunchedTaskId(1000L);
                if (!RearMediaActivity.isActiveOnRearDisplay() && taskId > 0) {
                    Log.d(TAG, "🔄 Unlocked launch landed on Display 0 (taskId=" + taskId + "), moving to Display 1");
                    ts.moveTaskToDisplay(taskId, 1);
                    ts.executeShellCommand("input -d 1 keyevent 224");
                }
            } else {
                Log.d(TAG, "🔒 Locked: launching RearMediaActivity on Display 0 placeholder -> moveTaskToDisplay(1) (startInAod=" + startInAod + ")");
                acquireWakeLock(4000L);
                try {
                    RearMediaActivity.prepareForLaunch();
                    ts.executeShellCommand(
                            "am start --display 0 -f 0x10010000 -n " + compName + aodExtra);
                    int taskId = RearMediaActivity.awaitLaunchedTaskId(1200L);
                    if (taskId <= 0) {
                        taskId = findMediaActivityTaskIdFallback(ts);
                    }
                    if (taskId > 0) {
                        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                        boolean frontAwake = pm != null && pm.isInteractive();
                        if (frontAwake && !forceLockedPath) {
                            Log.d(TAG, "⏳ Front lockscreen is interactive -> waiting 450ms for Display 0 transition to settle");
                            try {
                                Thread.sleep(450L);
                            } catch (InterruptedException ignored) {}
                        }
                        ts.moveTaskToDisplay(taskId, 1);
                        ts.executeShellCommand("input -d 1 keyevent 224");
                        Log.d(TAG, "✅ Moved RearMediaActivity (taskId=" + taskId + ") to Display 1");
                    } else {
                        Log.w(TAG, "⚠️ Could not resolve RearMediaActivity taskId during locked handoff");
                    }
                } finally {
                    releaseWakeLock();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "❌ Failed launching RearMediaActivity", t);
        } finally {
            mediaActivityLaunchInFlight = false;
            mediaMainHandler.post(this::syncRearPullDownWatcherState);
        }
    }

    private int findMediaActivityTaskIdFallback(ITaskService ts) {
        try {
            String output = ts.executeShellCommandWithResult("am stack list");
            if (output == null || output.isEmpty()) return -1;
            String[] lines = output.split("\n");
            for (String line : lines) {
                if (line.contains("taskId=") && line.contains("RearMediaActivity")) {
                    int start = line.indexOf("taskId=") + 7;
                    int end = line.indexOf(':', start);
                    if (end > start) {
                        return Integer.parseInt(line.substring(start, end).trim());
                    }
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private Bitmap resolveScaledArtworkOnWorker(MediaMetadata metadata, String pkgName, String title, String artist) {
        String key = pkgName + "|" + title + "|" + artist;
        if (key.equals(cachedArtworkKey) && cachedArtworkBitmap != null && !cachedArtworkBitmap.isRecycled()) {
            return cachedArtworkBitmap;
        }

        Bitmap raw = null;
        if (metadata != null) {
            raw = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (raw == null) raw = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (raw == null) raw = metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (raw == null) {
                String uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI);
                if (uriStr == null || uriStr.isEmpty()) {
                    uriStr = metadata.getString(MediaMetadata.METADATA_KEY_ART_URI);
                }
                if (uriStr != null && !uriStr.isEmpty()) {
                    raw = decodeBitmapFromUriOnWorker(uriStr, 512);
                }
            }
        }

        if (raw == null) {
            cachedArtworkKey = key;
            cachedArtworkBitmap = null;
            cachedArtworkAccentColor = 0xFF4ADE80;
            return null;
        }

        Bitmap scaled = scaleDownBitmap(raw, 512);
        cachedArtworkKey = key;
        cachedArtworkBitmap = scaled;
        cachedArtworkAccentColor = RearMediaShadeController.extractAccentColor(scaled);
        return scaled;
    }

    private Bitmap decodeBitmapFromUriOnWorker(String uriStr, int maxEdgePx) {
        try {
            Uri uri = Uri.parse(uriStr);
            String scheme = uri.getScheme();
            if (!"content".equals(scheme) && !"file".equals(scheme) && !"android.resource".equals(scheme)) {
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = getContentResolver().openInputStream(uri)) {
                if (is == null) return null;
                BitmapFactory.decodeStream(is, null, bounds);
            }
            int sampleSize = 1;
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            while (maxDim / sampleSize > maxEdgePx * 2) {
                sampleSize *= 2;
            }
            BitmapFactory.Options decodeOpts = new BitmapFactory.Options();
            decodeOpts.inSampleSize = sampleSize;
            try (InputStream is2 = getContentResolver().openInputStream(uri)) {
                if (is2 == null) return null;
                return BitmapFactory.decodeStream(is2, null, decodeOpts);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed decoding artwork URI: " + t.getMessage());
            return null;
        }
    }

    private static Bitmap scaleDownBitmap(Bitmap src, int maxEdgePx) {
        if (src == null || src.isRecycled()) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return null;
        int longest = Math.max(w, h);
        if (longest <= maxEdgePx) {
            return src;
        }
        float ratio = (float) maxEdgePx / (float) longest;
        int targetW = Math.max(1, Math.round(w * ratio));
        int targetH = Math.max(1, Math.round(h * ratio));
        return Bitmap.createScaledBitmap(src, targetW, targetH, true);
    }

    private CachedAppInfo resolveAppInfoOnWorker(String pkgName) {
        if (pkgName == null || pkgName.isEmpty()) {
            return new CachedAppInfo("Media", null);
        }
        CachedAppInfo cached = appInfoCache.get(pkgName);
        if (cached != null) {
            return cached;
        }

        String appName = "Media";
        Bitmap appIcon = null;
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkgName, 0);
            if (ai != null) {
                CharSequence label = pm.getApplicationLabel(ai);
                if (label != null && label.length() > 0) {
                    appName = label.toString();
                }
            }
            Drawable icon = pm.getApplicationIcon(pkgName);
            if (icon instanceof BitmapDrawable) {
                Bitmap raw = ((BitmapDrawable) icon).getBitmap();
                if (raw != null && !raw.isRecycled()) {
                    appIcon = Bitmap.createScaledBitmap(raw, 96, 96, true);
                }
            } else if (icon != null) {
                Bitmap rendered = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(rendered);
                icon.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
                icon.draw(canvas);
                appIcon = rendered;
            }
        } catch (Throwable ignored) {}

        CachedAppInfo info = new CachedAppInfo(appName, appIcon);
        appInfoCache.put(pkgName, info);
        return info;
    }

    private boolean isUserProjectedAppOnRearDisplayOnWorker() {
        if (RearScreenKeeperService.isRunning()) {
            return true;
        }
        try {
            ITaskService ts = getTaskService();
            if (ts == null) return false;
            String fg = ts.getForegroundAppOnDisplay(1);
            if (fg == null || fg.trim().isEmpty()) return false;
            String pkg = fg.contains(":") ? fg.substring(0, fg.indexOf(':')) : fg.trim();
            if (pkg.isEmpty()) return false;
            if ("com.xiaomi.subscreencenter".equals(pkg)
                    || "com.android.systemui".equals(pkg)
                    || getPackageName().equals(pkg)) {
                return false;
            }
            Log.d(TAG, "⏭️ User-projected app active on Display 1 (" + pkg + "), deferring RearMediaActivity");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    public void wakeRearDisplayAsync() {
        mediaExecutor.execute(this::wakeRearDisplayOnWorker);
    }

    private void wakeRearDisplayOnWorker() {
        long now = SystemClock.elapsedRealtime();
        if (now - lastWakeDisplayCommandMs < 800L) return;
        lastWakeDisplayCommandMs = now;
        try {
            ITaskService ts = getTaskService();
            if (ts != null) {
                ts.executeShellCommand("input -d 1 keyevent 224");
            }
        } catch (Throwable ignored) {}
    }

    public void pushCurrentMediaStateToFlutter() {
        if (activeMediaController != null && smartMediaEnabled) {
            dispatchMediaStateToActivity(activeMediaController, false);
        }
    }

    public void toggleMediaPlayback() {
        if (activeMediaController != null) {
            try {
                PlaybackState pbState = activeMediaController.getPlaybackState();
                if (pbState != null && pbState.getState() == PlaybackState.STATE_PLAYING) {
                    activeMediaController.getTransportControls().pause();
                } else {
                    activeMediaController.getTransportControls().play();
                }
            } catch (Exception e) {
                Log.w(TAG, "toggleMediaPlayback failed: " + e.getMessage());
            }
        }
    }

    public void skipNextMedia() {
        if (activeMediaController != null) {
            try {
                activeMediaController.getTransportControls().skipToNext();
            } catch (Exception e) {
                Log.w(TAG, "skipNextMedia failed: " + e.getMessage());
            }
        }
    }

    public void skipPrevMedia() {
        if (activeMediaController != null) {
            try {
                activeMediaController.getTransportControls().skipToPrevious();
            } catch (Exception e) {
                Log.w(TAG, "skipPrevMedia failed: " + e.getMessage());
            }
        }
    }

    public void seekToMediaPosition(long positionMs) {
        if (activeMediaController != null) {
            try {
                activeMediaController.getTransportControls().seekTo(Math.max(0L, positionMs));
            } catch (Exception e) {
                Log.w(TAG, "seekToMediaPosition failed: " + e.getMessage());
            }
        }
    }

    public void hideMediaWidget() {
        if (pendingMediaRemovalRefreshRunnable != null) {
            mediaMainHandler.removeCallbacks(pendingMediaRemovalRefreshRunnable);
            pendingMediaRemovalRefreshRunnable = null;
        }
        hideRearSurfacePillAsync();
        if (rearPullDownWatcher != null) {
            rearPullDownWatcher.stop();
        }
        RearMediaActivity.finishFromService();
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        loadSettings();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (pendingNotificationRunnable != null) {
            notificationDebounceHandler.removeCallbacks(pendingNotificationRunnable);
            pendingNotificationRunnable = null;
        }
        try {
            unregisterReceiver(settingsReceiver);
        } catch (Exception ignored) {}
        try {
            unregisterReceiver(screenOffReceiver);
        } catch (Exception ignored) {}
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener);
            Shizuku.removeBinderDeadListener(binderDeadListener);
        } catch (Exception ignored) {}
        try {
            if (taskService != null) {
                if (!TaskServiceManager.isLocalAdbMode(this)) {
                    Shizuku.unbindUserService(serviceArgs, taskServiceConnection, true);
                }
                taskService = null;
            }
        } catch (Exception ignored) {}
        unregisterMainProximitySensor();
        try {
            if (rearDisplayManager != null) {
                rearDisplayManager.unregisterDisplayListener(rearDisplayListener);
                rearDisplayManager = null;
            }
        } catch (Exception ignored) {}
        try {
            RearAnimationManager.removeAnimationStateListener(mediaAnimationListener);
            if (mediaSessionManager != null) {
                mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener);
            }
            unregisterAllMediaCallbacks();
            if (pendingMediaRemovalRefreshRunnable != null) {
                mediaMainHandler.removeCallbacks(pendingMediaRemovalRefreshRunnable);
                pendingMediaRemovalRefreshRunnable = null;
            }
            if (pendingLockRestoreRunnable != null) {
                mediaMainHandler.removeCallbacks(pendingLockRestoreRunnable);
                pendingLockRestoreRunnable = null;
            }
            lastPlaybackStateByToken.clear();
            lastSeenTokenByPackage.clear();
            if (rearPullDownWatcher != null) {
                rearPullDownWatcher.destroy();
                rearPullDownWatcher = null;
            }
            RearMediaActivity.finishFromService();
            try {
                mediaExecutor.submit(this::hideRearSurfacePillOnWorker).get(300, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {}
            mediaExecutor.shutdownNow();
            notificationLaunchExecutor.shutdownNow();
        } catch (Exception ignored) {}
        instance = null;
        stopForeground(true);
    }
}

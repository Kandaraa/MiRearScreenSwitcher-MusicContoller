package com.tgwgroup.MiRearScreenSwitcher;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Pure native Android Activity for the Smart Rear Screen Media Player on Display 1.
 *
 * Replaces the legacy uncached FlutterActivity (zero FlutterEngine startup overhead, zero white flash)
 * and avoids TYPE_APPLICATION_OVERLAY (blocked on Display 1 for non-system apps in HyperOS 4).
 *
 * Supports:
 * - Direct Display 1 launch when unlocked (slide-down reveal animation over translucent SubScreenLauncher)
 * - Proven Display 0 invisible placeholder -> moveTaskToDisplay(taskId, 1) -> Display 1 UI initialization
 *   when locked (without waking the front screen)
 * - Live 1:1 swipe-up-to-close revealing SubScreenLauncher cleanly underneath
 * - Sticky pure-black OLED-safe AOD (0.04f brightness, 60s burn-in shift, 5-min paused safety timeout)
 * - Seamless coexistence when RearScreenNotificationActivity / RearScreenChargingActivity appear on top
 */
public class RearMediaActivity extends Activity {
    private static final String TAG = "RearMediaActivity";
    private static final int FALLBACK_REAR_DPI = 440;

    public static final String EXTRA_START_IN_AOD = "startInAod";

    private static volatile WeakReference<RearMediaActivity> activeInstance = null;
    private static volatile boolean isResumedOnRearDisplay = false;
    private static volatile boolean pendingStartInAod = false;
    private static volatile int lastLaunchedTaskId = -1;
    private static volatile CountDownLatch taskLaunchLatch = null;

    private RearMediaShadeController presenter;
    private boolean uiInitialized = false;
    private boolean finishingCleanly = false;

    // =========================================================================
    // Static Launch Bridge & Service Control API
    // =========================================================================

    public static void prepareForLaunch() {
        lastLaunchedTaskId = -1;
        taskLaunchLatch = new CountDownLatch(1);
    }

    public static int awaitLaunchedTaskId(long timeoutMs) {
        CountDownLatch latch = taskLaunchLatch;
        if (latch != null) {
            try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}
        }
        return lastLaunchedTaskId;
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

    public static void setPendingStartInAod(boolean startInAod) {
        pendingStartInAod = startInAod;
    }

    public static boolean isCurrentlyInAod() {
        WeakReference<RearMediaActivity> ref = activeInstance;
        RearMediaActivity inst = (ref != null) ? ref.get() : null;
        return inst != null && !inst.isFinishing() && !inst.isDestroyed()
                && inst.presenter != null && inst.presenter.isInAodMode();
    }

    public static boolean isActiveOnRearDisplay() {
        WeakReference<RearMediaActivity> ref = activeInstance;
        RearMediaActivity inst = (ref != null) ? ref.get() : null;
        return inst != null && !inst.isFinishing() && !inst.isDestroyed() && isResumedOnRearDisplay;
    }

    public static boolean updateMediaStateIfActive(final RearMediaShadeController.MediaState state) {
        WeakReference<RearMediaActivity> ref = activeInstance;
        final RearMediaActivity inst = (ref != null) ? ref.get() : null;
        if (inst == null || inst.isFinishing() || inst.isDestroyed() || !isResumedOnRearDisplay) {
            return false;
        }
        inst.runOnUiThread(() -> {
            if (inst.isFinishing() || inst.isDestroyed() || !isResumedOnRearDisplay) return;
            if (!inst.uiInitialized) {
                inst.initializeOnRearDisplay();
            }
            if (inst.presenter != null && state != null) {
                inst.presenter.bindMediaState(state, true);
            }
        });
        return true;
    }

    public static void updateAlbumBlurIfActive(final float blurRadius) {
        WeakReference<RearMediaActivity> ref = activeInstance;
        final RearMediaActivity inst = (ref != null) ? ref.get() : null;
        if (inst == null || inst.isFinishing() || inst.isDestroyed()) {
            return;
        }
        inst.runOnUiThread(() -> {
            if (inst.isFinishing() || inst.isDestroyed()) return;
            if (inst.presenter != null) {
                inst.presenter.setAlbumBlurRadius(blurRadius);
            }
        });
    }

    public static void finishFromService() {
        WeakReference<RearMediaActivity> ref = activeInstance;
        final RearMediaActivity inst = (ref != null) ? ref.get() : null;
        if (inst == null || inst.isFinishing() || inst.isDestroyed()) {
            return;
        }
        inst.runOnUiThread(inst::finishCleanly);
    }

    // =========================================================================
    // Activity Lifecycle
    // =========================================================================

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
        super.onCreate(savedInstanceState);

        int taskId = getTaskId();
        int displayId = getCurrentDisplayId();
        publishTaskId(taskId);
        activeInstance = new WeakReference<>(this);

        Log.d(TAG, "🟢 onCreate displayId=" + displayId + ", taskId=" + taskId);

        // Allow displaying over lockscreen without EVER waking the main display (no FLAG_TURN_SCREEN_ON)
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
        }

        if (displayId == 0) {
            // Locked Display 0 -> Display 1 handoff placeholder:
            // Suppress open transition, stay completely invisible, and do NOT acquire FLAG_KEEP_SCREEN_ON yet.
            suppressActivityTransition(true);
            View placeholder = new View(this);
            placeholder.setVisibility(View.INVISIBLE);
            setContentView(placeholder);
            uiInitialized = false;
            Log.d(TAG, "💤 Display 0 transparent handoff placeholder ready (taskId=" + taskId + ")");
            return;
        }

        initializeOnRearDisplay();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null) {
            setIntent(intent);
        }
        publishTaskId(getTaskId());
        if (getCurrentDisplayId() == 1) {
            if (!uiInitialized) {
                initializeOnRearDisplay();
            } else {
                refreshFromNotificationService();
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        int displayId = getCurrentDisplayId();
        Log.d(TAG, "⚙️ onConfigurationChanged displayId=" + displayId
                + ", dpi=" + newConfig.densityDpi + ", uiInitialized=" + uiInitialized);
        if (displayId == 1 && !uiInitialized) {
            initializeOnRearDisplay();
        } else if (displayId == 1 && presenter != null) {
            presenter.refreshGeometry(null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        int displayId = getCurrentDisplayId();
        publishTaskId(getTaskId());

        if (displayId == 1) {
            isResumedOnRearDisplay = true;
            if (!uiInitialized) {
                initializeOnRearDisplay();
            } else {
                applyRearWindowFlags();
                NotificationService svc = NotificationService.getInstance();
                RearMediaShadeController.MediaState latest = (svc != null) ? svc.getLatestMediaState() : null;
                boolean startInAod = consumeStartInAodFlag();
                if (presenter != null) {
                    if (startInAod) {
                        if (latest != null) presenter.bindMediaState(latest, false);
                        presenter.showAodImmediate();
                    } else {
                        presenter.onHostResumed(latest);
                    }
                }
                if (svc != null) {
                    svc.onRearMediaActivityStateChanged(true);
                }
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Do NOT finish() on pause so RearScreenNotificationActivity / RearScreenChargingActivity
        // can appear cleanly on top and return seamlessly back to RearMediaActivity when they finish.
        if (presenter != null) {
            presenter.onHostPaused();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        boolean wasResumed = isResumedOnRearDisplay;
        boolean wasInAod = (presenter != null && presenter.isInAodMode());
        isResumedOnRearDisplay = false;

        if (getCurrentDisplayId() != 1) {
            return;
        }

        NotificationService svc = NotificationService.getInstance();
        if (!isFinishing() && !RearAnimationManager.isAnimationPlaying()) {
            KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
            boolean isLocked = (km != null && km.isKeyguardLocked());
            boolean screenOffJustFired = NotificationService.wasScreenTurnedOffRecently(1500L);

            if (isLocked && screenOffJustFired) {
                // HyperOS pushed SubScreenLauncher TO_FRONT immediately after ACTION_SCREEN_OFF.
                // Record AOD state for lock-screen restore and finish the hidden stale task cleanly
                // so the locked Display 0 -> Display 1 relaunch starts as an invisible placeholder.
                if (wasResumed && svc != null) {
                    svc.onRearMediaHiddenByLock(wasInAod);
                }
                Log.d(TAG, "🔒 RearMediaActivity stopped on lock transition (wasInAod=" + wasInAod + ") -> clearing stale task for lock restore");
                finishCleanly();
            } else {
                // User exited via Xiaomi bottom Home gesture (SubScreenGestureHome) or Back gesture
                // (either unlocked, or already locked without a recent ACTION_SCREEN_OFF).
                String currentPkg = "";
                if (svc != null) {
                    RearMediaShadeController.MediaState latest = svc.getLatestMediaState();
                    if (latest != null && latest.packageName != null) {
                        currentPkg = latest.packageName;
                    }
                    svc.onUserDismissedMedia(currentPkg);
                }
                Log.d(TAG, "🏠 RearMediaActivity stopped by user Home/Back gesture (isLocked=" + isLocked
                        + ", screenOffJustFired=" + screenOffJustFired + ") -> finishing task & arming pull-down watcher");
                finishCleanly();
            }
        } else if (svc != null) {
            svc.onRearMediaActivityStateChanged(false);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        WeakReference<RearMediaActivity> ref = activeInstance;
        if (ref != null && ref.get() == this) {
            isResumedOnRearDisplay = false;
            activeInstance = null;
        }
        if (presenter != null) {
            presenter.destroy();
            presenter = null;
        }
        NotificationService svc = NotificationService.getInstance();
        if (svc != null) {
            svc.onRearMediaActivityStateChanged(false);
        }
        Log.d(TAG, "🔴 RearMediaActivity destroyed");
    }

    private boolean consumeStartInAodFlag() {
        boolean fromStatic = pendingStartInAod;
        pendingStartInAod = false;
        boolean fromIntent = false;
        Intent intent = getIntent();
        if (intent != null && intent.getBooleanExtra(EXTRA_START_IN_AOD, false)) {
            fromIntent = true;
            intent.removeExtra(EXTRA_START_IN_AOD);
        }
        return fromStatic || fromIntent;
    }

    // =========================================================================
    // Display 1 Native UI Initialization
    // =========================================================================

    private void initializeOnRearDisplay() {
        if (uiInitialized || isFinishing() || isDestroyed()) return;
        uiInitialized = true;
        isResumedOnRearDisplay = true;

        applyRearScreenDensity();
        applyRearWindowFlags();

        presenter = new RearMediaShadeController(this, shadeHost);
        setContentView(presenter.getRootView());

        View decor = getWindow().getDecorView();
        if (decor != null) {
            decor.setOnApplyWindowInsetsListener((v, insets) -> {
                if (presenter != null) {
                    presenter.refreshGeometry(insets);
                }
                return insets;
            });
            decor.requestApplyInsets();
        }

        NotificationService svc = NotificationService.getInstance();
        if (svc != null) {
            RearMediaShadeController.MediaState latest = svc.getLatestMediaState();
            if (latest != null) {
                presenter.bindMediaState(latest, false);
            }
            svc.onRearMediaActivityStateChanged(true);
        }

        boolean startInAod = consumeStartInAodFlag();
        if (startInAod) {
            presenter.showAodImmediate();
            Log.d(TAG, "✅ RearMediaActivity initialized straight into OLED AOD on Display 1");
        } else {
            presenter.playSlideDownReveal();
            Log.d(TAG, "✅ RearMediaActivity initialized natively on Display 1");
        }
    }

    private void refreshFromNotificationService() {
        NotificationService svc = NotificationService.getInstance();
        if (svc == null || presenter == null) return;
        RearMediaShadeController.MediaState latest = svc.getLatestMediaState();
        if (latest != null) {
            presenter.bindMediaState(latest, true);
        }
    }

    private void applyRearWindowFlags() {
        try {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                            | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true);
            }
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            }
            getWindow().setAttributes(lp);
        } catch (Throwable t) {
            Log.w(TAG, "Failed applying Display 1 window flags: " + t.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void applyRearScreenDensity() {
        try {
            RearDisplayHelper.RearDisplayInfo info = DisplayInfoCache.getInstance().getCachedInfo();
            int rearDpi = (info != null && info.densityDpi > 0) ? info.densityDpi : FALLBACK_REAR_DPI;

            DisplayMetrics metrics = getResources().getDisplayMetrics();
            if (metrics.densityDpi != rearDpi) {
                metrics.densityDpi = rearDpi;
                metrics.density = rearDpi / 160f;
                metrics.scaledDensity = metrics.density;
                Configuration config = new Configuration(getResources().getConfiguration());
                config.densityDpi = rearDpi;
                getResources().updateConfiguration(config, metrics);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed applying rear screen DPI: " + t.getMessage());
        }
    }

    @SuppressWarnings("deprecation")
    private void suppressActivityTransition(boolean isOpen) {
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                overrideActivityTransition(
                        isOpen ? Activity.OVERRIDE_TRANSITION_OPEN : Activity.OVERRIDE_TRANSITION_CLOSE,
                        0, 0);
            } else {
                overridePendingTransition(0, 0);
            }
        } catch (Throwable ignored) {}
    }

    private void finishCleanly() {
        if (finishingCleanly || isFinishing() || isDestroyed()) return;
        finishingCleanly = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask();
        } else {
            finish();
        }
        suppressActivityTransition(false);
    }

    private final RearMediaShadeController.Host shadeHost = new RearMediaShadeController.Host() {
        @Override
        public Activity getActivity() {
            return RearMediaActivity.this;
        }

        @Override
        public void onRequestFinishDismissed(String packageName) {
            NotificationService svc = NotificationService.getInstance();
            if (svc != null) {
                svc.onUserDismissedMedia(packageName);
            }
            finishCleanly();
        }

        @Override
        public void onRequestFinishAutoTimeout() {
            finishCleanly();
        }

        @Override
        public void onPlayPause() {
            NotificationService svc = NotificationService.getInstance();
            if (svc != null) svc.toggleMediaPlayback();
        }

        @Override
        public void onSkipNext() {
            NotificationService svc = NotificationService.getInstance();
            if (svc != null) svc.skipNextMedia();
        }

        @Override
        public void onSkipPrevious() {
            NotificationService svc = NotificationService.getInstance();
            if (svc != null) svc.skipPrevMedia();
        }

        @Override
        public void onSeekTo(long positionMs) {
            NotificationService svc = NotificationService.getInstance();
            if (svc != null) svc.seekToMediaPosition(positionMs);
        }

        @Override
        public void onWakeRearDisplayRequested() {
            NotificationService svc = NotificationService.getInstance();
            if (svc != null) svc.wakeRearDisplayAsync();
        }
    };
}

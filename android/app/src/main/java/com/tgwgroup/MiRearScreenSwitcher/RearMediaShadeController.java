package com.tgwgroup.MiRearScreenSwitcher;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Pure native UI & sticky OLED-safe AOD presenter bound directly to RearMediaActivity on Display 1.
 *
 * Uses zero TYPE_APPLICATION_OVERLAY windows (HyperOS 4 blocks type=2038 for non-system apps on Display 1).
 * Renders inside RearMediaActivity's translucent full-screen window so slide-down open and live 1:1
 * swipe-up dismiss reveal SubScreenLauncher cleanly underneath.
 */
public class RearMediaShadeController {
    private static final String TAG = "RearMediaShadeCtrl";

    // Hardware geometry defaults for Xiaomi 17 Pro Display 1 (904x572, 296px left cutout, 97px corner radius)
    private static final int DEFAULT_DISPLAY_WIDTH = 904;
    private static final int DEFAULT_DISPLAY_HEIGHT = 572;
    private static final int DEFAULT_CAMERA_CUTOUT_PX = 296;
    private static final int CUTOUT_BREATHING_MARGIN_PX = 20;
    private static final int OUTER_CORNER_SAFE_MARGIN_PX = 42;
    private static final int TOP_SAFE_PADDING_PX = 28;
    private static final int BOTTOM_SAFE_PADDING_PX = 20;

    // Gesture & animation thresholds
    private static final float COMMIT_FRACTION = 0.22f;
    private static final float COMMIT_VELOCITY_PX_PER_SEC = 600f;
    private static final long SHADE_OPEN_DURATION_MS = 360L;
    private static final long SHADE_DISMISS_DURATION_MS = 300L;
    private static final long SHADE_CANCEL_DURATION_MS = 240L;
    private static final long ARTWORK_CROSSFADE_MS = 300L;
    private static final long AOD_FADE_IN_MS = 500L;
    private static final long AOD_WAKE_MS = 250L;
    private static final float AOD_SCREEN_BRIGHTNESS = 0.04f;

    // Timers
    private static final long ACTIVE_IDLE_TIMEOUT_MS = 10_000L;
    private static final long AOD_TRACK_CHANGE_WAKE_MS = 6_000L;
    private static final long PAUSED_AOD_SAFETY_TIMEOUT_MS = 300_000L;
    private static final long PROGRESS_TICK_MS = 500L;
    private static final long AOD_BURN_IN_TICK_MS = 60_000L;

    private static final int DEFAULT_ACCENT_COLOR = 0xFF4ADE80;
    public static final String PREFS_NAME = "mrss_settings";
    public static final String PREF_MEDIA_ALBUM_BLUR = "media_album_blur";
    public static final float MIN_ALBUM_BLUR = 0f;
    public static final float MAX_ALBUM_BLUR = 50f;
    public static final float DEFAULT_ALBUM_BLUR = 20f;
    private static final PathInterpolator HYPEROS_EASE = new PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f);

    // Deterministic burn-in shift patterns (pixels: X in [-8..+8], Y in [-6..+6])
    private static final int[] AOD_SHIFT_X = {0, 5, 8, 4, -3, -8, -5, 2, 7, -6};
    private static final int[] AOD_SHIFT_Y = {0, -4, 3, 6, 4, -2, -6, 2, -5, 5};

    public enum Mode {
        ACTIVE,
        AOD
    }

    public interface Host {
        Activity getActivity();
        void onRequestFinishDismissed(String packageName);
        void onRequestFinishAutoTimeout();
        void onPlayPause();
        void onSkipNext();
        void onSkipPrevious();
        void onSeekTo(long positionMs);
        void onWakeRearDisplayRequested();
    }

    public static final class MediaState {
        public final String packageName;
        public final String title;
        public final String artist;
        public final String appName;
        public final Bitmap albumArt;
        public final Bitmap appIcon;
        public final boolean isPlaying;
        public final long positionMs;
        public final long durationMs;
        public final float playbackSpeed;
        public final long positionUpdateElapsedMs;
        public final long supportedActions;
        public final int accentColor;

        public MediaState(
                String packageName,
                String title,
                String artist,
                String appName,
                Bitmap albumArt,
                Bitmap appIcon,
                boolean isPlaying,
                long positionMs,
                long durationMs,
                float playbackSpeed,
                long positionUpdateElapsedMs,
                long supportedActions,
                int accentColor) {
            this.packageName = packageName != null ? packageName : "";
            this.title = (title != null && !title.trim().isEmpty()) ? title : "Unknown";
            this.artist = artist != null ? artist : "";
            this.appName = (appName != null && !appName.trim().isEmpty()) ? appName : "Media";
            this.albumArt = albumArt;
            this.appIcon = appIcon;
            this.isPlaying = isPlaying;
            this.positionMs = Math.max(0L, positionMs);
            this.durationMs = Math.max(0L, durationMs);
            this.playbackSpeed = playbackSpeed > 0f ? playbackSpeed : 1.0f;
            this.positionUpdateElapsedMs = positionUpdateElapsedMs;
            this.supportedActions = supportedActions;
            this.accentColor = accentColor != 0 ? accentColor : DEFAULT_ACCENT_COLOR;
        }

        public long computeCurrentPositionMs() {
            long pos = positionMs;
            if (isPlaying && positionUpdateElapsedMs > 0L) {
                long delta = Math.max(0L, SystemClock.elapsedRealtime() - positionUpdateElapsedMs);
                pos = positionMs + (long) (delta * playbackSpeed);
            }
            if (durationMs > 0L) {
                pos = Math.min(pos, durationMs);
            }
            return Math.max(0L, pos);
        }
    }

    private final Activity activity;
    private final Host host;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat clockFormat = new SimpleDateFormat("HH:mm", Locale.getDefault());

    // Display 1 geometry
    private int displayWidth = DEFAULT_DISPLAY_WIDTH;
    private int displayHeight = DEFAULT_DISPLAY_HEIGHT;
    private int cameraInsetPx = DEFAULT_CAMERA_CUTOUT_PX;
    private boolean cameraOnLeft = true;
    private float density = 2.0f;
    private int touchSlop = 16;

    // State
    private Mode mode = Mode.ACTIVE;
    private MediaState currentMedia;
    private String lastRenderedTitle = "";
    private Bitmap lastRenderedArtwork = null;
    private boolean hostPaused = false;
    private boolean destroyed = false;
    private boolean animating = false;
    private int aodShiftIndex = 0;
    private float albumBlurRadius = DEFAULT_ALBUM_BLUR;

    // Views
    private ShadeContainerView rootView;
    private FrameLayout shadeContentRoot;
    private FrameLayout activeLayer;
    private ImageView bgArtA;
    private ImageView bgArtB;
    private boolean usingArtA = true;
    private LinearLayout safeContentContainer;
    private ImageView appIconView;
    private TextView appNameView;
    private TextView activeClockView;
    private TextView titleView;
    private TextView artistView;
    private MediaScrubBar scrubBar;
    private TextView elapsedTimeView;
    private TextView totalTimeView;
    private ImageView prevButton;
    private ImageView playPauseButton;
    private ImageView nextButton;

    // AOD views
    private FrameLayout aodLayer;
    private LinearLayout aodContentGroup;
    private TextView aodClockView;
    private TextView aodTitleView;
    private ImageView aodStatusIcon;
    private TextView aodArtistView;
    private View aodBottomHandle;

    // Timers
    private final Runnable activeIdleToAodRunnable = this::transitionToAod;
    private final Runnable pausedSafetyDismissRunnable = this::handlePausedSafetyTimeout;

    private void handlePausedSafetyTimeout() {
        if (destroyed || hostPaused) return;
        if (currentMedia != null && !currentMedia.isPlaying) {
            Log.d(TAG, "⏱️ Paused for 5 minutes -> auto-dismissing RearMediaActivity");
            host.onRequestFinishAutoTimeout();
        }
    }
    private final Runnable progressTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed || hostPaused || mode != Mode.ACTIVE || currentMedia == null) return;
            updateProgressUiOnly();
            updateClockTexts();
            if (currentMedia.isPlaying) {
                mainHandler.postDelayed(this, PROGRESS_TICK_MS);
            }
        }
    };
    private final Runnable aodBurnInTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed || hostPaused || mode != Mode.AOD) return;
            updateClockTexts();
            applyNextAodBurnInShift();
            mainHandler.postDelayed(this, AOD_BURN_IN_TICK_MS);
        }
    };

    public RearMediaShadeController(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        this.touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        loadAlbumBlurFromPrefs();
        refreshGeometry(null);
        buildViews();
    }

    public static float clampAlbumBlur(float blurRadius) {
        if (Float.isNaN(blurRadius) || Float.isInfinite(blurRadius)) {
            return DEFAULT_ALBUM_BLUR;
        }
        return Math.max(MIN_ALBUM_BLUR, Math.min(MAX_ALBUM_BLUR, blurRadius));
    }

    private void loadAlbumBlurFromPrefs() {
        try {
            android.content.SharedPreferences prefs =
                    activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            albumBlurRadius = clampAlbumBlur(prefs.getFloat(PREF_MEDIA_ALBUM_BLUR, DEFAULT_ALBUM_BLUR));
        } catch (Throwable ignored) {
            albumBlurRadius = DEFAULT_ALBUM_BLUR;
        }
    }

    public void setAlbumBlurRadius(float blurRadius) {
        float clamped = clampAlbumBlur(blurRadius);
        if (Math.abs(clamped - albumBlurRadius) < 0.05f) {
            return;
        }
        albumBlurRadius = clamped;
        applyBlurEffect(bgArtA);
        applyBlurEffect(bgArtB);
    }

    public View getRootView() {
        return rootView;
    }

    public boolean isInAodMode() {
        return mode == Mode.AOD;
    }

    /**
     * Immediately enters pure-black OLED AOD mode without flashing active brightness.
     * Used when restoring RearMediaActivity after the phone locks while already in AOD.
     */
    public void showAodImmediate() {
        if (destroyed || shadeContentRoot == null) return;
        cancelAllTimers();
        mode = Mode.AOD;
        animating = false;
        shadeContentRoot.animate().cancel();
        shadeContentRoot.setTranslationY(0f);

        if (activeLayer != null) {
            activeLayer.animate().cancel();
            activeLayer.setAlpha(0f);
            activeLayer.setVisibility(View.GONE);
        }
        if (aodLayer != null) {
            aodLayer.animate().cancel();
            aodLayer.setVisibility(View.VISIBLE);
            aodLayer.setAlpha(1f);
        }

        updateClockTexts();
        applyWindowBrightness(AOD_SCREEN_BRIGHTNESS);
        if (!hostPaused) {
            mainHandler.postDelayed(aodBurnInTickRunnable, AOD_BURN_IN_TICK_MS);
            updatePausedSafetyTimer();
        }
    }

    /**
     * Lightweight accent color extraction from an already-scaled Bitmap (safe on worker thread).
     */
    public static int extractAccentColor(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return DEFAULT_ACCENT_COLOR;
        }
        try {
            Bitmap sample = Bitmap.createScaledBitmap(bitmap, 8, 8, true);
            float[] hsv = new float[3];
            int bestColor = DEFAULT_ACCENT_COLOR;
            float bestScore = -1f;

            for (int y = 0; y < sample.getHeight(); y++) {
                for (int x = 0; x < sample.getWidth(); x++) {
                    int pixel = sample.getPixel(x, y);
                    if (Color.alpha(pixel) < 128) continue;
                    Color.colorToHSV(pixel, hsv);
                    float sat = hsv[1];
                    float val = hsv[2];
                    if (sat < 0.25f || val < 0.25f) continue;
                    float score = sat * 0.7f + val * 0.3f;
                    if (score > bestScore) {
                        bestScore = score;
                        float[] boosted = new float[]{hsv[0], Math.min(1f, sat * 1.1f), Math.max(0.75f, val)};
                        bestColor = Color.HSVToColor(boosted);
                    }
                }
            }
            if (sample != bitmap) {
                sample.recycle();
            }
            return bestColor;
        } catch (Throwable t) {
            return DEFAULT_ACCENT_COLOR;
        }
    }

    // =========================================================================
    // Lifecycle & Public Binding API (Main Thread)
    // =========================================================================

    public void playSlideDownReveal() {
        if (destroyed || shadeContentRoot == null) return;
        mode = Mode.ACTIVE;
        setActiveVisualsImmediate();
        applyWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);

        animating = true;
        shadeContentRoot.animate().cancel();
        shadeContentRoot.setTranslationY(-displayHeight);
        shadeContentRoot.animate()
                .translationY(0f)
                .setDuration(SHADE_OPEN_DURATION_MS)
                .setInterpolator(HYPEROS_EASE)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        shadeContentRoot.animate().setListener(null);
                        animating = false;
                        if (!destroyed && !hostPaused) {
                            restartProgressTickerIfNeeded();
                            resetActiveIdleTimer(ACTIVE_IDLE_TIMEOUT_MS);
                            updatePausedSafetyTimer();
                        }
                    }
                })
                .start();
    }

    public void bindMediaState(MediaState newState, boolean crossfadeArt) {
        if (destroyed || newState == null) return;
        boolean titleChanged = !TextUtils.isEmpty(lastRenderedTitle)
                && !TextUtils.equals(lastRenderedTitle, newState.title);
        this.currentMedia = newState;

        appNameView.setText(newState.appName);
        if (newState.appIcon != null && !newState.appIcon.isRecycled()) {
            appIconView.setImageBitmap(newState.appIcon);
            appIconView.setVisibility(View.VISIBLE);
        } else {
            appIconView.setVisibility(View.GONE);
        }

        titleView.setText(newState.title);
        if (newState.artist.isEmpty()) {
            artistView.setVisibility(View.GONE);
        } else {
            artistView.setVisibility(View.VISIBLE);
            artistView.setText(newState.artist);
        }

        playPauseButton.setImageResource(newState.isPlaying ? R.drawable.ic_media_pause : R.drawable.ic_media_play);

        long actions = newState.supportedActions;
        boolean canPrev = (actions == 0L) || ((actions & PlaybackState.ACTION_SKIP_TO_PREVIOUS) != 0L);
        boolean canNext = (actions == 0L) || ((actions & PlaybackState.ACTION_SKIP_TO_NEXT) != 0L);
        boolean canSeek = (newState.durationMs > 0L)
                && (actions == 0L || ((actions & PlaybackState.ACTION_SEEK_TO) != 0L));

        prevButton.setEnabled(canPrev);
        prevButton.setAlpha(canPrev ? 1.0f : 0.30f);
        nextButton.setEnabled(canNext);
        nextButton.setAlpha(canNext ? 1.0f : 0.30f);

        scrubBar.setSeekEnabled(canSeek);
        scrubBar.setAccentColor(newState.accentColor);

        aodTitleView.setText(newState.title);
        aodArtistView.setText(newState.artist.isEmpty() ? newState.appName : newState.artist);
        aodStatusIcon.setImageResource(newState.isPlaying ? R.drawable.ic_media_pause : R.drawable.ic_media_play);

        updateClockTexts();
        updateProgressUiOnly();
        updateArtworkBackground(newState.albumArt, crossfadeArt);

        if (!hostPaused) {
            if (mode == Mode.AOD && titleChanged) {
                Log.d(TAG, "🎵 Track changed in AOD -> waking to ACTIVE for 6s: " + newState.title);
                wakeFromAodToActive(AOD_TRACK_CHANGE_WAKE_MS);
            } else if (mode == Mode.ACTIVE) {
                restartProgressTickerIfNeeded();
                updatePausedSafetyTimer();
            } else {
                updatePausedSafetyTimer();
            }
        }

        lastRenderedTitle = newState.title;
    }

    public void onHostPaused() {
        hostPaused = true;
        mainHandler.removeCallbacks(progressTickRunnable);
        mainHandler.removeCallbacks(activeIdleToAodRunnable);
        mainHandler.removeCallbacks(aodBurnInTickRunnable);
    }

    public void onHostResumed(MediaState latestState) {
        if (destroyed) return;
        hostPaused = false;
        loadAlbumBlurFromPrefs();
        applyBlurEffect(bgArtA);
        applyBlurEffect(bgArtB);
        refreshGeometry(null);
        if (latestState != null) {
            bindMediaState(latestState, false);
        }
        if (mode == Mode.AOD) {
            applyWindowBrightness(AOD_SCREEN_BRIGHTNESS);
            updateClockTexts();
            mainHandler.removeCallbacks(aodBurnInTickRunnable);
            mainHandler.postDelayed(aodBurnInTickRunnable, AOD_BURN_IN_TICK_MS);
        } else {
            applyWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
            restartProgressTickerIfNeeded();
            resetActiveIdleTimer(ACTIVE_IDLE_TIMEOUT_MS);
        }
        updatePausedSafetyTimer();
    }

    public void destroy() {
        destroyed = true;
        cancelAllTimers();
        if (shadeContentRoot != null) {
            shadeContentRoot.animate().cancel();
        }
        lastRenderedArtwork = null;
        currentMedia = null;
    }

    // =========================================================================
    // Geometry & Cutout Safe Zone
    // =========================================================================

    @SuppressWarnings("deprecation")
    public void refreshGeometry(WindowInsets insets) {
        Display display = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                display = activity.getDisplay();
            } catch (Throwable ignored) {}
        }
        if (display == null) {
            try {
                display = activity.getWindowManager().getDefaultDisplay();
            } catch (Throwable ignored) {}
        }

        DisplayMetrics dm = new DisplayMetrics();
        if (display != null) {
            try {
                display.getRealMetrics(dm);
            } catch (Throwable ignored) {}
        }
        if (dm.widthPixels <= 0 || dm.heightPixels <= 0) {
            dm = activity.getResources().getDisplayMetrics();
        }

        int w = dm.widthPixels > 0 ? dm.widthPixels : DEFAULT_DISPLAY_WIDTH;
        int h = dm.heightPixels > 0 ? dm.heightPixels : DEFAULT_DISPLAY_HEIGHT;
        density = dm.density > 0f ? dm.density : 2.0f;

        int rotation = Surface.ROTATION_0;
        int displayId = 1;
        if (display != null) {
            try {
                rotation = display.getRotation();
                displayId = display.getDisplayId();
            } catch (Throwable ignored) {}
        }

        int detectedLeft = 0;
        int detectedRight = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && insets != null) {
            DisplayCutout cutout = insets.getDisplayCutout();
            if (cutout != null) {
                detectedLeft = cutout.getSafeInsetLeft();
                detectedRight = cutout.getSafeInsetRight();
            }
        }
        if (detectedLeft == 0 && detectedRight == 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && display != null) {
            try {
                DisplayCutout cutout = display.getCutout();
                if (cutout != null) {
                    detectedLeft = cutout.getSafeInsetLeft();
                    detectedRight = cutout.getSafeInsetRight();
                }
            } catch (Throwable ignored) {}
        }

        displayWidth = w;
        displayHeight = h;

        if (detectedLeft > 0 || detectedRight > 0) {
            cameraOnLeft = detectedLeft >= detectedRight;
            cameraInsetPx = Math.max(detectedLeft, detectedRight);
        } else if (displayId == 1) {
            // Known HyperOS 4 quirk: Display.getCutout() returns null/0 for third-party Activities on Display 1.
            cameraInsetPx = (w == DEFAULT_DISPLAY_WIDTH)
                    ? DEFAULT_CAMERA_CUTOUT_PX
                    : Math.max(DEFAULT_CAMERA_CUTOUT_PX, Math.round(w * 0.328f));
            cameraOnLeft = (rotation != Surface.ROTATION_180 && rotation != Surface.ROTATION_270);
        } else {
            cameraInsetPx = 0;
            cameraOnLeft = true;
        }

        applySafeZonePaddingToViews();
    }

    private void applySafeZonePaddingToViews() {
        int cameraPad = cameraInsetPx > 0
                ? (cameraInsetPx + CUTOUT_BREATHING_MARGIN_PX)
                : OUTER_CORNER_SAFE_MARGIN_PX;
        int leftPad = cameraOnLeft ? cameraPad : OUTER_CORNER_SAFE_MARGIN_PX;
        int rightPad = cameraOnLeft ? OUTER_CORNER_SAFE_MARGIN_PX : cameraPad;

        if (safeContentContainer != null) {
            safeContentContainer.setPadding(leftPad, TOP_SAFE_PADDING_PX, rightPad, BOTTOM_SAFE_PADDING_PX);
        }
        if (aodContentGroup != null) {
            aodContentGroup.setPadding(leftPad, TOP_SAFE_PADDING_PX, rightPad, 34);
        }
        if (aodBottomHandle != null) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) aodBottomHandle.getLayoutParams();
            if (lp != null) {
                lp.leftMargin = cameraOnLeft ? cameraInsetPx : 0;
                lp.rightMargin = cameraOnLeft ? 0 : cameraInsetPx;
                aodBottomHandle.setLayoutParams(lp);
            }
        }
    }

    // =========================================================================
    // View Hierarchy Construction
    // =========================================================================

    private void buildViews() {
        rootView = new ShadeContainerView(activity);
        rootView.setBackgroundColor(Color.TRANSPARENT);

        shadeContentRoot = new FrameLayout(activity);
        shadeContentRoot.setBackgroundColor(Color.BLACK); // Pure #000000 for OLED AOD
        rootView.addView(shadeContentRoot, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 1. ACTIVE LAYER
        activeLayer = new FrameLayout(activity);
        shadeContentRoot.addView(activeLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View bgFallbackGradient = new View(activity);
        GradientDrawable fallbackGrad = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{0xFF20232E, 0xFF12141B, 0xFF08090D});
        bgFallbackGradient.setBackground(fallbackGrad);
        activeLayer.addView(bgFallbackGradient, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        bgArtA = createBlurredBackgroundImageView();
        bgArtB = createBlurredBackgroundImageView();
        bgArtB.setAlpha(0f);
        activeLayer.addView(bgArtA, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        activeLayer.addView(bgArtB, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View vignetteScrim = new View(activity);
        GradientDrawable scrimDrawable = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x73000000, 0x9E000000, 0xCC000000});
        vignetteScrim.setBackground(scrimDrawable);
        activeLayer.addView(vignetteScrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        safeContentContainer = new LinearLayout(activity);
        safeContentContainer.setOrientation(LinearLayout.VERTICAL);
        activeLayer.addView(safeContentContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Header row: App icon + App name + HH:mm clock
        LinearLayout headerRow = new LinearLayout(activity);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        safeContentContainer.addView(headerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        appIconView = new ImageView(activity);
        appIconView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final float iconRadius = dp(6);
        appIconView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), iconRadius);
            }
        });
        appIconView.setClipToOutline(true);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(22), dp(22));
        iconLp.setMarginEnd(dp(8));
        headerRow.addView(appIconView, iconLp);

        appNameView = new TextView(activity);
        appNameView.setTextColor(0xBFFFFFFF);
        appNameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        appNameView.setSingleLine(true);
        appNameView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams appNameLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        appNameLp.setMarginEnd(dp(8));
        headerRow.addView(appNameView, appNameLp);

        activeClockView = new TextView(activity);
        activeClockView.setTextColor(0xB3FFFFFF);
        activeClockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        activeClockView.setFontFeatureSettings("tnum");
        headerRow.addView(activeClockView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        safeContentContainer.addView(new View(activity),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 0.5f));

        // Track title & artist
        titleView = new TextView(activity);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setLineSpacing(0f, 1.05f);
        titleView.setShadowLayer(4f, 0f, 1.5f, 0x80000000);
        safeContentContainer.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        artistView = new TextView(activity);
        artistView.setTextColor(0xBFFFFFFF);
        artistView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        artistView.setSingleLine(true);
        artistView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams artistLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        artistLp.topMargin = dp(4);
        safeContentContainer.addView(artistView, artistLp);

        safeContentContainer.addView(new View(activity),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        // Interactive scrub bar
        scrubBar = new MediaScrubBar(activity);
        scrubBar.setOnSeekListener(new MediaScrubBar.OnSeekListener() {
            @Override
            public void onScrubStart() {
                resetActiveIdleTimer(ACTIVE_IDLE_TIMEOUT_MS);
            }

            @Override
            public void onScrubMove(long positionMs) {
                resetActiveIdleTimer(ACTIVE_IDLE_TIMEOUT_MS);
                if (elapsedTimeView != null) {
                    elapsedTimeView.setText(formatTimeMs(positionMs));
                }
            }

            @Override
            public void onScrubCommit(long positionMs) {
                resetActiveIdleTimer(ACTIVE_IDLE_TIMEOUT_MS);
                if (elapsedTimeView != null) {
                    elapsedTimeView.setText(formatTimeMs(positionMs));
                }
                host.onSeekTo(positionMs);
            }
        });
        safeContentContainer.addView(scrubBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(24)));

        LinearLayout timeRow = new LinearLayout(activity);
        timeRow.setOrientation(LinearLayout.HORIZONTAL);
        safeContentContainer.addView(timeRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        elapsedTimeView = new TextView(activity);
        elapsedTimeView.setTextColor(0xA6FFFFFF);
        elapsedTimeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        elapsedTimeView.setFontFeatureSettings("tnum");
        timeRow.addView(elapsedTimeView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        totalTimeView = new TextView(activity);
        totalTimeView.setTextColor(0xA6FFFFFF);
        totalTimeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        totalTimeView.setFontFeatureSettings("tnum");
        totalTimeView.setGravity(Gravity.END);
        timeRow.addView(totalTimeView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Transport controls row
        LinearLayout controlsRow = new LinearLayout(activity);
        controlsRow.setOrientation(LinearLayout.HORIZONTAL);
        controlsRow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams controlsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        controlsLp.topMargin = dp(8);
        safeContentContainer.addView(controlsRow, controlsLp);

        prevButton = createTransportButton(dp(42), dp(9), R.drawable.ic_media_prev, false);
        prevButton.setOnClickListener(v -> {
            animateButtonTap(v);
            resetActiveIdleTimer(ACTIVE_IDLE_TIMEOUT_MS);
            host.onSkipPrevious();
        });
        controlsRow.addView(prevButton);

        playPauseButton = createTransportButton(dp(56), dp(14), R.drawable.ic_media_play, true);
        LinearLayout.LayoutParams playLp = (LinearLayout.LayoutParams) playPauseButton.getLayoutParams();
        playLp.setMarginStart(dp(24));
        playLp.setMarginEnd(dp(24));
        playPauseButton.setOnClickListener(v -> {
            animateButtonTap(v);
            resetActiveIdleTimer(ACTIVE_IDLE_TIMEOUT_MS);
            host.onPlayPause();
        });
        controlsRow.addView(playPauseButton);

        nextButton = createTransportButton(dp(42), dp(9), R.drawable.ic_media_next, false);
        nextButton.setOnClickListener(v -> {
            animateButtonTap(v);
            resetActiveIdleTimer(ACTIVE_IDLE_TIMEOUT_MS);
            host.onSkipNext();
        });
        controlsRow.addView(nextButton);

        // Bottom 51x4 px swipe-up handle pill
        View activeBottomHandle = new View(activity);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setShape(GradientDrawable.RECTANGLE);
        handleBg.setCornerRadius(2f);
        handleBg.setColor(0x73FFFFFF);
        activeBottomHandle.setBackground(handleBg);
        LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(51, 4);
        handleLp.gravity = Gravity.CENTER_HORIZONTAL;
        handleLp.topMargin = dp(10);
        safeContentContainer.addView(activeBottomHandle, handleLp);

        // 2. OLED-SAFE AOD LAYER (pure #000000 backdrop + dimmed clock/title)
        aodLayer = new FrameLayout(activity);
        aodLayer.setBackgroundColor(Color.TRANSPARENT);
        aodLayer.setVisibility(View.GONE);
        aodLayer.setAlpha(0f);
        shadeContentRoot.addView(aodLayer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        aodContentGroup = new LinearLayout(activity);
        aodContentGroup.setOrientation(LinearLayout.VERTICAL);
        aodContentGroup.setGravity(Gravity.CENTER_VERTICAL);
        aodLayer.addView(aodContentGroup, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        aodClockView = new TextView(activity);
        aodClockView.setTextColor(0xB3FFFFFF);
        aodClockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40f);
        aodClockView.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        aodClockView.setFontFeatureSettings("tnum");
        aodContentGroup.addView(aodClockView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        aodTitleView = new TextView(activity);
        aodTitleView.setTextColor(0x99FFFFFF);
        aodTitleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        aodTitleView.setTypeface(Typeface.DEFAULT_BOLD);
        aodTitleView.setSingleLine(true);
        aodTitleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams aodTitleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aodTitleLp.topMargin = dp(10);
        aodContentGroup.addView(aodTitleView, aodTitleLp);

        LinearLayout aodSubRow = new LinearLayout(activity);
        aodSubRow.setOrientation(LinearLayout.HORIZONTAL);
        aodSubRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams aodSubLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        aodSubLp.topMargin = dp(4);
        aodContentGroup.addView(aodSubRow, aodSubLp);

        aodStatusIcon = new ImageView(activity);
        aodStatusIcon.setColorFilter(0x88FFFFFF);
        LinearLayout.LayoutParams aodIconLp = new LinearLayout.LayoutParams(dp(14), dp(14));
        aodIconLp.setMarginEnd(dp(6));
        aodSubRow.addView(aodStatusIcon, aodIconLp);

        aodArtistView = new TextView(activity);
        aodArtistView.setTextColor(0x80FFFFFF);
        aodArtistView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        aodArtistView.setSingleLine(true);
        aodArtistView.setEllipsize(TextUtils.TruncateAt.END);
        aodSubRow.addView(aodArtistView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        aodBottomHandle = new View(activity);
        GradientDrawable aodHandleBg = new GradientDrawable();
        aodHandleBg.setShape(GradientDrawable.RECTANGLE);
        aodHandleBg.setCornerRadius(2f);
        aodHandleBg.setColor(0x40FFFFFF);
        aodBottomHandle.setBackground(aodHandleBg);
        FrameLayout.LayoutParams aodHandleLp = new FrameLayout.LayoutParams(51, 4);
        aodHandleLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        aodHandleLp.bottomMargin = 20;
        aodLayer.addView(aodBottomHandle, aodHandleLp);

        applySafeZonePaddingToViews();
    }

    private ImageView createBlurredBackgroundImageView() {
        ImageView iv = new ImageView(activity);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        applyBlurEffect(iv);
        return iv;
    }

    private void applyBlurEffect(ImageView iv) {
        if (iv == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (albumBlurRadius <= 0.1f) {
                iv.setRenderEffect(null);
            } else {
                iv.setRenderEffect(RenderEffect.createBlurEffect(
                        albumBlurRadius, albumBlurRadius, Shader.TileMode.CLAMP));
            }
        }
    }

    private ImageView createTransportButton(int sizePx, int paddingPx, int drawableRes, boolean circularGlass) {
        ImageView btn = new ImageView(activity);
        btn.setImageResource(drawableRes);
        btn.setScaleType(ImageView.ScaleType.FIT_CENTER);
        btn.setPadding(paddingPx, paddingPx, paddingPx, paddingPx);
        btn.setClickable(true);

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        if (circularGlass) {
            circle.setColor(0x36FFFFFF);
            circle.setStroke(1, 0x52FFFFFF);
        } else {
            circle.setColor(0x18FFFFFF);
        }
        btn.setBackground(circle);
        btn.setLayoutParams(new LinearLayout.LayoutParams(sizePx, sizePx));
        return btn;
    }

    private void animateButtonTap(View v) {
        v.animate().cancel();
        v.setScaleX(0.88f);
        v.setScaleY(0.88f);
        v.animate().scaleX(1f).scaleY(1f).setDuration(140L).start();
    }

    private void updateArtworkBackground(Bitmap newArt, boolean animate) {
        if (bgArtA == null || bgArtB == null) return;
        if (newArt == lastRenderedArtwork) return;
        lastRenderedArtwork = newArt;

        if (newArt == null || newArt.isRecycled()) {
            bgArtA.animate().cancel();
            bgArtB.animate().cancel();
            bgArtA.setAlpha(0f);
            bgArtB.setAlpha(0f);
            bgArtA.setImageBitmap(null);
            bgArtB.setImageBitmap(null);
            return;
        }

        ImageView incoming = usingArtA ? bgArtB : bgArtA;
        ImageView outgoing = usingArtA ? bgArtA : bgArtB;
        usingArtA = !usingArtA;

        incoming.setImageBitmap(newArt);
        incoming.animate().cancel();
        outgoing.animate().cancel();

        if (animate) {
            incoming.setAlpha(0f);
            incoming.animate().alpha(1f).setDuration(ARTWORK_CROSSFADE_MS).start();
            outgoing.animate().alpha(0f).setDuration(ARTWORK_CROSSFADE_MS).start();
        } else {
            incoming.setAlpha(1f);
            outgoing.setAlpha(0f);
        }
    }

    private void updateProgressUiOnly() {
        if (currentMedia == null || scrubBar == null) return;
        long duration = currentMedia.durationMs;
        long pos = currentMedia.computeCurrentPositionMs();

        if (!scrubBar.isScrubbing()) {
            scrubBar.setProgress(pos, duration);
            elapsedTimeView.setText(formatTimeMs(pos));
        }
        totalTimeView.setText(duration > 0L ? formatTimeMs(duration) : "--:--");
    }

    private void updateClockTexts() {
        String now = clockFormat.format(new Date());
        if (activeClockView != null) {
            activeClockView.setText(now);
        }
        if (aodClockView != null) {
            aodClockView.setText(now);
        }
    }

    // =========================================================================
    // OLED-Safe AOD Transitions & Timers
    // =========================================================================

    private void transitionToAod() {
        if (destroyed || hostPaused || mode != Mode.ACTIVE || animating) return;

        Log.d(TAG, "🌙 Transitioning RearMediaActivity -> OLED AOD");
        mode = Mode.AOD;
        mainHandler.removeCallbacks(progressTickRunnable);
        mainHandler.removeCallbacks(activeIdleToAodRunnable);

        updateClockTexts();
        aodLayer.animate().cancel();
        activeLayer.animate().cancel();

        aodLayer.setVisibility(View.VISIBLE);
        aodLayer.setAlpha(0f);
        aodLayer.animate().alpha(1f).setDuration(AOD_FADE_IN_MS).setListener(null).start();

        activeLayer.animate()
                .alpha(0f)
                .setDuration(AOD_FADE_IN_MS)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        activeLayer.animate().setListener(null);
                        if (mode == Mode.AOD) {
                            activeLayer.setVisibility(View.GONE);
                        }
                    }
                })
                .start();

        applyWindowBrightness(AOD_SCREEN_BRIGHTNESS);
        applyNextAodBurnInShift();
        mainHandler.removeCallbacks(aodBurnInTickRunnable);
        mainHandler.postDelayed(aodBurnInTickRunnable, AOD_BURN_IN_TICK_MS);
        updatePausedSafetyTimer();
    }

    private void wakeFromAodToActive(long idleTimeoutMs) {
        if (destroyed || hostPaused) return;

        Log.d(TAG, "☀️ Waking RearMediaActivity from OLED AOD -> ACTIVE");
        mode = Mode.ACTIVE;
        mainHandler.removeCallbacks(aodBurnInTickRunnable);

        applyWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE);
        host.onWakeRearDisplayRequested();

        updateClockTexts();
        updateProgressUiOnly();

        activeLayer.animate().cancel();
        aodLayer.animate().cancel();

        activeLayer.setVisibility(View.VISIBLE);
        activeLayer.setAlpha(0f);
        activeLayer.animate().alpha(1f).setDuration(AOD_WAKE_MS).setListener(null).start();

        aodLayer.animate()
                .alpha(0f)
                .setDuration(AOD_WAKE_MS)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        aodLayer.animate().setListener(null);
                        if (mode == Mode.ACTIVE) {
                            aodLayer.setVisibility(View.GONE);
                        }
                    }
                })
                .start();

        restartProgressTickerIfNeeded();
        resetActiveIdleTimer(idleTimeoutMs);
        updatePausedSafetyTimer();
    }

    private void setActiveVisualsImmediate() {
        if (activeLayer != null) {
            activeLayer.animate().cancel();
            activeLayer.setVisibility(View.VISIBLE);
            activeLayer.setAlpha(1f);
        }
        if (aodLayer != null) {
            aodLayer.animate().cancel();
            aodLayer.setVisibility(View.GONE);
            aodLayer.setAlpha(0f);
        }
    }

    private void applyNextAodBurnInShift() {
        if (aodContentGroup == null) return;
        aodShiftIndex = (aodShiftIndex + 1) % AOD_SHIFT_X.length;
        aodContentGroup.animate()
                .translationX(AOD_SHIFT_X[aodShiftIndex])
                .translationY(AOD_SHIFT_Y[aodShiftIndex])
                .setDuration(350L)
                .start();
    }

    private void applyWindowBrightness(float brightness) {
        try {
            Window window = activity.getWindow();
            if (window == null) return;
            WindowManager.LayoutParams lp = window.getAttributes();
            if (lp.screenBrightness != brightness) {
                lp.screenBrightness = brightness;
                window.setAttributes(lp);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed updating Activity screenBrightness: " + t.getMessage());
        }
    }

    private void resetActiveIdleTimer(long delayMs) {
        mainHandler.removeCallbacks(activeIdleToAodRunnable);
        if (!hostPaused && mode == Mode.ACTIVE) {
            mainHandler.postDelayed(activeIdleToAodRunnable, delayMs);
        }
    }

    private void restartProgressTickerIfNeeded() {
        mainHandler.removeCallbacks(progressTickRunnable);
        if (!hostPaused && mode == Mode.ACTIVE && currentMedia != null && currentMedia.isPlaying) {
            mainHandler.postDelayed(progressTickRunnable, PROGRESS_TICK_MS);
        }
    }

    private void updatePausedSafetyTimer() {
        mainHandler.removeCallbacks(pausedSafetyDismissRunnable);
        if (!hostPaused && currentMedia != null && !currentMedia.isPlaying) {
            mainHandler.postDelayed(pausedSafetyDismissRunnable, PAUSED_AOD_SAFETY_TIMEOUT_MS);
        }
    }

    private void cancelAllTimers() {
        mainHandler.removeCallbacks(activeIdleToAodRunnable);
        mainHandler.removeCallbacks(pausedSafetyDismissRunnable);
        mainHandler.removeCallbacks(progressTickRunnable);
        mainHandler.removeCallbacks(aodBurnInTickRunnable);
    }

    private void commitSwipeUpDismiss() {
        if (destroyed || shadeContentRoot == null) return;
        final String dismissedPkg = (currentMedia != null) ? currentMedia.packageName : "";
        cancelAllTimers();
        animating = true;
        shadeContentRoot.animate().cancel();
        shadeContentRoot.animate()
                .translationY(-displayHeight)
                .setDuration(SHADE_DISMISS_DURATION_MS)
                .setInterpolator(HYPEROS_EASE)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        shadeContentRoot.animate().setListener(null);
                        animating = false;
                        host.onRequestFinishDismissed(dismissedPkg);
                    }
                })
                .start();
    }

    // =========================================================================
    // Custom Shade Container (Live 1:1 Swipe-Up Dismiss + AOD Tap-to-Wake)
    // =========================================================================

    private class ShadeContainerView extends FrameLayout {
        private float downRawX;
        private float downRawY;
        private boolean draggingUp;
        private VelocityTracker velocityTracker;

        ShadeContainerView(Context context) {
            super(context);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (destroyed || animating) return true;

            if (mode == Mode.AOD) {
                return true;
            }

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    resetActiveIdleTimer(ACTIVE_IDLE_TIMEOUT_MS);
                    downRawX = ev.getRawX();
                    downRawY = ev.getRawY();
                    draggingUp = false;
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                    }
                    velocityTracker = VelocityTracker.obtain();
                    addRawVelocityEvent(velocityTracker, ev);
                    return false;

                case MotionEvent.ACTION_MOVE:
                    if (velocityTracker != null) {
                        addRawVelocityEvent(velocityTracker, ev);
                    }
                    float dx = ev.getRawX() - downRawX;
                    float dy = ev.getRawY() - downRawY;
                    if (dy < 0f && Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx) * 1.2f) {
                        draggingUp = true;
                        return true;
                    }
                    return false;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }
                    draggingUp = false;
                    return false;

                default:
                    return false;
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (destroyed || animating || shadeContentRoot == null) return true;

            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = ev.getRawX();
                    downRawY = ev.getRawY();
                    draggingUp = false;
                    if (velocityTracker != null) {
                        velocityTracker.recycle();
                    }
                    velocityTracker = VelocityTracker.obtain();
                    addRawVelocityEvent(velocityTracker, ev);
                    if (mode == Mode.ACTIVE) {
                        resetActiveIdleTimer(ACTIVE_IDLE_TIMEOUT_MS);
                    }
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (velocityTracker != null) {
                        addRawVelocityEvent(velocityTracker, ev);
                    }
                    float dx = ev.getRawX() - downRawX;
                    float dy = ev.getRawY() - downRawY;
                    if (!draggingUp && dy < 0f && Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx) * 1.2f) {
                        draggingUp = true;
                    }
                    if (draggingUp) {
                        shadeContentRoot.setTranslationY(clamp(dy, -displayHeight, 0f));
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    float upDx = ev.getRawX() - downRawX;
                    float upDy = ev.getRawY() - downRawY;
                    float velocityY = 0f;
                    if (velocityTracker != null) {
                        addRawVelocityEvent(velocityTracker, ev);
                        velocityTracker.computeCurrentVelocity(1000);
                        velocityY = velocityTracker.getYVelocity();
                        velocityTracker.recycle();
                        velocityTracker = null;
                    }

                    boolean isUp = (ev.getActionMasked() == MotionEvent.ACTION_UP);
                    if (draggingUp) {
                        draggingUp = false;
                        boolean shouldDismiss = isUp
                                && (-upDy > displayHeight * COMMIT_FRACTION || velocityY < -COMMIT_VELOCITY_PX_PER_SEC);
                        if (shouldDismiss) {
                            commitSwipeUpDismiss();
                        } else {
                            animating = true;
                            shadeContentRoot.animate()
                                    .translationY(0f)
                                    .setDuration(SHADE_CANCEL_DURATION_MS)
                                    .setInterpolator(HYPEROS_EASE)
                                    .setListener(new AnimatorListenerAdapter() {
                                        @Override
                                        public void onAnimationEnd(Animator animation) {
                                            shadeContentRoot.animate().setListener(null);
                                            animating = false;
                                        }
                                    })
                                    .start();
                        }
                        return true;
                    }

                    if (mode == Mode.AOD && isUp
                            && Math.abs(upDx) < touchSlop && Math.abs(upDy) < touchSlop) {
                        wakeFromAodToActive(ACTIVE_IDLE_TIMEOUT_MS);
                    } else if (mode == Mode.ACTIVE) {
                        resetActiveIdleTimer(ACTIVE_IDLE_TIMEOUT_MS);
                    }
                    return true;

                default:
                    return true;
            }
        }
    }

    // =========================================================================
    // Custom Interactive Progress Scrubber View (Zero Material Dependencies)
    // =========================================================================

    private static class MediaScrubBar extends View {
        interface OnSeekListener {
            void onScrubStart();
            void onScrubMove(long positionMs);
            void onScrubCommit(long positionMs);
        }

        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final RectF fillRect = new RectF();
        private final float trackHeightPx;
        private final float thumbRadiusNormalPx;
        private final float thumbRadiusActivePx;

        private long positionMs = 0L;
        private long durationMs = 0L;
        private boolean seekEnabled = false;
        private boolean scrubbing = false;
        private OnSeekListener listener;

        MediaScrubBar(Context context) {
            super(context);
            float d = context.getResources().getDisplayMetrics().density;
            trackHeightPx = 4f * d;
            thumbRadiusNormalPx = 5f * d;
            thumbRadiusActivePx = 7f * d;

            trackPaint.setColor(0x40FFFFFF);
            fillPaint.setColor(0xE6FFFFFF);
            thumbPaint.setColor(Color.WHITE);
        }

        void setOnSeekListener(OnSeekListener listener) {
            this.listener = listener;
        }

        void setSeekEnabled(boolean enabled) {
            this.seekEnabled = enabled;
            invalidate();
        }

        void setAccentColor(int color) {
            int blended = Color.argb(
                    235,
                    (Color.red(color) + 255) / 2,
                    (Color.green(color) + 255) / 2,
                    (Color.blue(color) + 255) / 2);
            fillPaint.setColor(blended);
            invalidate();
        }

        void setProgress(long posMs, long durMs) {
            this.positionMs = Math.max(0L, posMs);
            this.durationMs = Math.max(0L, durMs);
            invalidate();
        }

        boolean isScrubbing() {
            return scrubbing;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            float pad = thumbRadiusActivePx;
            float usableW = Math.max(1f, w - 2f * pad);
            float cy = h / 2f;
            float halfTrack = trackHeightPx / 2f;

            trackRect.set(pad, cy - halfTrack, pad + usableW, cy + halfTrack);
            canvas.drawRoundRect(trackRect, halfTrack, halfTrack, trackPaint);

            float fraction = (durationMs > 0L) ? clamp((float) positionMs / (float) durationMs, 0f, 1f) : 0f;
            float fillEndX = pad + usableW * fraction;
            fillRect.set(pad, cy - halfTrack, Math.max(pad + trackHeightPx, fillEndX), cy + halfTrack);
            if (fraction > 0f) {
                canvas.drawRoundRect(fillRect, halfTrack, halfTrack, fillPaint);
            }

            if (seekEnabled && durationMs > 0L) {
                float r = scrubbing ? thumbRadiusActivePx : thumbRadiusNormalPx;
                canvas.drawCircle(fillEndX, cy, r, thumbPaint);
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (!seekEnabled || durationMs <= 0L) return false;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    scrubbing = true;
                    if (getParent() != null) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    updatePositionFromTouchX(event.getX());
                    if (listener != null) {
                        listener.onScrubStart();
                        listener.onScrubMove(positionMs);
                    }
                    invalidate();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (scrubbing) {
                        updatePositionFromTouchX(event.getX());
                        if (listener != null) {
                            listener.onScrubMove(positionMs);
                        }
                        invalidate();
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (scrubbing) {
                        updatePositionFromTouchX(event.getX());
                        scrubbing = false;
                        if (getParent() != null) {
                            getParent().requestDisallowInterceptTouchEvent(false);
                        }
                        if (event.getActionMasked() == MotionEvent.ACTION_UP && listener != null) {
                            listener.onScrubCommit(positionMs);
                        }
                        invalidate();
                    }
                    return true;

                default:
                    return super.onTouchEvent(event);
            }
        }

        private void updatePositionFromTouchX(float x) {
            float pad = thumbRadiusActivePx;
            float usableW = Math.max(1f, getWidth() - 2f * pad);
            float fraction = clamp((x - pad) / usableW, 0f, 1f);
            positionMs = Math.round(fraction * durationMs);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private int dp(int value) {
        return Math.round(value * density);
    }

    private static float clamp(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    private static void addRawVelocityEvent(VelocityTracker tracker, MotionEvent event) {
        MotionEvent copy = MotionEvent.obtain(event);
        copy.setLocation(event.getRawX(), event.getRawY());
        tracker.addMovement(copy);
        copy.recycle();
    }

    private static String formatTimeMs(long ms) {
        long totalSeconds = Math.max(0L, ms / 1000L);
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }
}

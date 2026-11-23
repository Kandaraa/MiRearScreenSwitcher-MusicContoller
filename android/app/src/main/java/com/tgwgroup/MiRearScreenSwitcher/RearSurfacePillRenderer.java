package com.tgwgroup.MiRearScreenSwitcher;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceControl;

import androidx.annotation.Keep;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * UID 2000 (shell) SurfaceFlinger Media Pull Pill renderer for Xiaomi 17 Pro Display 1.
 *
 * Composites a scalable (1.0x..5.0x, base 100x24px) liquid-glass rounded capsule with a
 * directional top-lit rim, top specular glass sheen, animated 3-bar mini equalizer, and grab bar
 * directly onto Display 1's layerStack at z=300000 without creating any WindowManager window:
 * - Bypasses HyperOS 4 WMS non-system overlay restrictions (types 2032/2038).
 * - Zero touch interception, zero ActivityRecordInputSink, zero InputDispatcher ANR.
 * - Strictly uses software Canvas (lockCanvas(null)) so the resident daemon never spawns HWUI.
 * - Pre-renders the static capsule/sheen/stroke/grab-bar once per (scale, tintColor) into a
 *   cached ARGB_8888 Bitmap and reuses Paint/RectF objects so every animation frame is allocation-free.
 * - Automatically pauses rendering while Display 1 is OFF (STATE_OFF) to save battery.
 */
@Keep
public class RearSurfacePillRenderer {
    private static final String LAYER_NAME = "MRSS_Media_Pill";
    public static final int BASE_WIDTH = 100;
    public static final int BASE_HEIGHT = 24;
    public static final float MIN_SCALE = 1.0f;
    public static final float MAX_SCALE = 5.0f;
    public static final float DEFAULT_SCALE = 3.0f;
    public static final int DEFAULT_TINT_COLOR = 0xFFD60A0A;

    // Backwards-compatible default dimensions (3.0x)
    public static final int PILL_WIDTH = Math.round(BASE_WIDTH * DEFAULT_SCALE);
    public static final int PILL_HEIGHT = Math.round(BASE_HEIGHT * DEFAULT_SCALE);

    private static final int PILL_Z_LAYER = 300000;

    // Base (1x) capsule geometry specification
    private static final float BASE_CORNER_RADIUS = 11f;
    private static final float BASE_STROKE_WIDTH_PX = 2.5f;
    private static final float BASE_CAPSULE_INSET = 1.25f;

    // Confirmed default crimson palette & fixed inner colors
    private static final int DEFAULT_FILL_COLOR = 0x3DBF4545;
    private static final int DEFAULT_STROKE_TOP_COLOR = 0xC8D60A0A;
    private static final int DEFAULT_STROKE_BOTTOM_COLOR = 0x34D60A0A;
    private static final int DOT_PLAYING_COLOR = 0xFF05FF44;
    private static final int DOT_PAUSED_COLOR = 0xFFFFEB3B;
    private static final int GRAB_BAR_COLOR = 0xD6FFFFFF;

    // Top specular glass sheen
    private static final int[] SHEEN_COLORS = {0x24FFFFFF, 0x05FFFFFF, 0x2E000000};
    private static final float[] SHEEN_POSITIONS = {0f, 0.45f, 1f};

    // Base (1x) 3-bar mini equalizer geometry & reference heights
    private static final int EQ_BAR_COUNT = 3;
    private static final float BASE_EQ_START_X = 12.0f;
    private static final float BASE_EQ_STRIDE_X = 3.5f;
    private static final float BASE_EQ_BAR_WIDTH = 2.0f;
    private static final float BASE_EQ_CENTER_Y = 12.0f;
    private static final float BASE_EQ_CORNER_RADIUS = 1.0f;
    private static final float[] BASE_EQ_PLAYING_REF_HEIGHTS = {6f, 9f, 5f};
    private static final float[] BASE_EQ_PAUSED_HEIGHTS = {3f, 3f, 3f};

    // Multi-harmonic organic oscillation parameters per bar (incommensurate frequencies)
    private static final float[] BASE_EQ_AMPLITUDE = {2.6f, 3.2f, 2.3f};
    private static final float[] EQ_PRIMARY_FREQ_RAD_S = {5.4f, 6.9f, 4.7f};
    private static final float[] EQ_HARMONIC_FREQ_RAD_S = {2.7f, 3.3f, 2.2f};
    private static final float[] EQ_PHASE_OFFSET = {0.0f, 1.95f, 3.85f};
    private static final float BASE_EQ_MIN_HEIGHT = 2.5f;
    private static final float BASE_EQ_MAX_HEIGHT = 13.0f;

    // Base (1x) grab bar geometry
    private static final float BASE_GRAB_LEFT = 31.5f;
    private static final float BASE_GRAB_TOP = 10.5f;
    private static final float BASE_GRAB_RIGHT = 82.5f;
    private static final float BASE_GRAB_BOTTOM = 13.5f;
    private static final float BASE_GRAB_CORNER_RADIUS = 1.50f;

    // Animation cadence & display power gating
    private static final long EQ_FRAME_INTERVAL_MS = 42L;      // ~24 fps smooth software cadence
    private static final long DISPLAY_STATE_CHECK_MS = 500L;   // Poll Display 1 power state at 2 Hz max
    private static final float HEIGHT_LERP_ALPHA = 0.45f;
    private static final float BASE_PAUSED_SETTLE_EPSILON = 0.12f;

    // Cached reflection handles (resolved once)
    private static volatile boolean reflectionInitialized = false;
    private static Constructor<SurfaceControl.Builder> scBuilderSessionCtor;
    private static Class<?> surfaceSessionClass;
    private static Method scBuilderSetOpaqueMethod;
    private static Method txSetLayerStackMethod;
    private static Method txRemoveMethod;
    private static Object displayManagerGlobalInstance;
    private static Method dmgGetDisplayInfoMethod;
    private static Field displayInfoLayerStackField;
    private static Field displayInfoStateField;

    // Native SurfaceFlinger handles & state
    private SurfaceControl surfaceControl;
    private Surface surface;
    private int lastX = Integer.MIN_VALUE;
    private int lastY = Integer.MIN_VALUE;
    private float currentScale = DEFAULT_SCALE;
    private int currentTintColor = DEFAULT_TINT_COLOR;
    private int currentWidth = PILL_WIDTH;
    private int currentHeight = PILL_HEIGHT;
    private boolean currentPlaying = false;

    // Zero-allocation rendering buffers/objects
    private Bitmap staticBackdropBitmap;
    private float cachedBackdropScale = -1f;
    private int cachedBackdropTintColor = 0;
    private final Paint eqBarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF eqBarRect = new RectF();
    // Stored in 1x normalized units so changing scale never causes bar jumps
    private final float[] currentBarHeights1x = {3f, 3f, 3f};
    private long animOriginElapsedMs = 0L;
    private long lastDisplayStateCheckMs = 0L;
    private boolean cachedRearDisplayOn = true;

    // Lightweight single-thread equalizer animation scheduler
    private ScheduledExecutorService animExecutor;
    private ScheduledFuture<?> animFuture;

    public RearSurfacePillRenderer() {
        eqBarPaint.setStyle(Paint.Style.FILL);
    }

    public static float clampScale(float scale) {
        if (Float.isNaN(scale) || Float.isInfinite(scale)) {
            return DEFAULT_SCALE;
        }
        return clamp(scale, MIN_SCALE, MAX_SCALE);
    }

    public static int computeScaledWidth(float scale) {
        return Math.round(BASE_WIDTH * clampScale(scale));
    }

    public static int computeScaledHeight(float scale) {
        return Math.round(BASE_HEIGHT * clampScale(scale));
    }

    /**
     * Derives the translucent liquid-glass fill color from an overall ARGB tint color.
     * Preserves exact confirmed 0x3DBF4545 when using DEFAULT_TINT_COLOR (0xFFD60A0A).
     */
    public static int deriveGlassFillColor(int tintColor) {
        int rgb = tintColor & 0x00FFFFFF;
        if (rgb == (DEFAULT_TINT_COLOR & 0x00FFFFFF)) {
            return DEFAULT_FILL_COLOR;
        }
        int r = Color.red(tintColor);
        int g = Color.green(tintColor);
        int b = Color.blue(tintColor);
        // Soften slightly toward frosted mid-neutral so bright/saturated hues read as liquid glass
        int fr = Math.min(255, Math.round(r * 0.78f + 115f * 0.22f));
        int fg = Math.min(255, Math.round(g * 0.78f + 115f * 0.22f));
        int fb = Math.min(255, Math.round(b * 0.78f + 115f * 0.22f));
        return Color.argb(0x3D, fr, fg, fb);
    }

    public static int deriveStrokeTopColor(int tintColor) {
        int rgb = tintColor & 0x00FFFFFF;
        if (rgb == (DEFAULT_TINT_COLOR & 0x00FFFFFF)) {
            return DEFAULT_STROKE_TOP_COLOR;
        }
        return (0xC8 << 24) | rgb;
    }

    public static int deriveStrokeBottomColor(int tintColor) {
        int rgb = tintColor & 0x00FFFFFF;
        if (rgb == (DEFAULT_TINT_COLOR & 0x00FFFFFF)) {
            return DEFAULT_STROKE_BOTTOM_COLOR;
        }
        return (0x34 << 24) | rgb;
    }

    /**
     * Backwards-compatible default-style show/update (3.0x, crimson liquid glass).
     */
    public synchronized void showOrUpdate(boolean isPlaying, int x, int y) {
        showOrUpdate(isPlaying, x, y, DEFAULT_SCALE, DEFAULT_TINT_COLOR);
    }

    /**
     * Creates (if needed) or updates the Display 1 Media Pull Pill with custom scale & glass tint color.
     */
    public synchronized void showOrUpdate(boolean isPlaying, int x, int y, float scale, int tintColor) {
        try {
            float safeScale = clampScale(scale);
            int safeTint = (tintColor == 0) ? DEFAULT_TINT_COLOR : (0xFF000000 | (tintColor & 0x00FFFFFF));
            int targetW = computeScaledWidth(safeScale);
            int targetH = computeScaledHeight(safeScale);

            boolean sizeChanged = (targetW != currentWidth || targetH != currentHeight);
            boolean newlyCreated = false;

            currentScale = safeScale;
            currentTintColor = safeTint;
            currentWidth = targetW;
            currentHeight = targetH;

            if (surfaceControl == null || surface == null || !surface.isValid() || sizeChanged) {
                hideInternal();
                createLayerLocked(x, y, targetW, targetH);
                newlyCreated = true;
            } else if (x != lastX || y != lastY) {
                SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
                tx.setPosition(surfaceControl, (float) x, (float) y);
                tx.setVisibility(surfaceControl, true);
                tx.apply();
                lastX = x;
                lastY = y;
            }

            boolean playStateChanged = (newlyCreated || isPlaying != currentPlaying);
            currentPlaying = isPlaying;

            if (newlyCreated) {
                float[] initial = isPlaying ? BASE_EQ_PLAYING_REF_HEIGHTS : BASE_EQ_PAUSED_HEIGHTS;
                System.arraycopy(initial, 0, currentBarHeights1x, 0, EQ_BAR_COUNT);
                animOriginElapsedMs = SystemClock.elapsedRealtime();
            }

            if (surface != null && surface.isValid()) {
                drawPillFrameLocked(currentPlaying, currentBarHeights1x);
            }

            if (isPlaying) {
                startAnimationLoopLocked();
            } else if (playStateChanged) {
                startAnimationLoopLocked();
            } else if (isSettledAtPausedLocked()) {
                stopAnimationLoopLocked();
            }
        } catch (Throwable t) {
            System.err.println("RearSurfacePillRenderer showOrUpdate error: " + t.getMessage());
            hideInternal();
        }
    }

    /**
     * Safely stops animation, removes the SurfaceControl layer from SurfaceFlinger,
     * and releases native Surface/SurfaceControl handles.
     */
    public synchronized void hide() {
        hideInternal();
    }

    /**
     * Full teardown on daemon / TaskService shutdown (also releases backdrop bitmap and scheduler thread).
     */
    public synchronized void shutdown() {
        hideInternal();
        if (animExecutor != null) {
            try {
                animExecutor.shutdownNow();
            } catch (Throwable ignored) {}
            animExecutor = null;
        }
        recycleBackdropLocked();
    }

    // =========================================================================
    // Equalizer Animation Loop (24fps while playing + Display 1 power gating)
    // =========================================================================

    private void startAnimationLoopLocked() {
        if (animFuture != null && !animFuture.isCancelled() && !animFuture.isDone()) {
            return;
        }
        if (animExecutor == null || animExecutor.isShutdown()) {
            animExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "MRSS-PillEqAnim");
                t.setDaemon(true);
                return t;
            });
        }
        lastDisplayStateCheckMs = 0L;
        cachedRearDisplayOn = true;
        animFuture = animExecutor.scheduleAtFixedRate(
                this::onAnimationTick,
                EQ_FRAME_INTERVAL_MS,
                EQ_FRAME_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
    }

    private void stopAnimationLoopLocked() {
        if (animFuture != null) {
            animFuture.cancel(false);
            animFuture = null;
        }
    }

    private synchronized void onAnimationTick() {
        if (surfaceControl == null || surface == null || !surface.isValid()) {
            stopAnimationLoopLocked();
            return;
        }

        long nowMs = SystemClock.elapsedRealtime();
        if (nowMs - lastDisplayStateCheckMs >= DISPLAY_STATE_CHECK_MS) {
            lastDisplayStateCheckMs = nowMs;
            cachedRearDisplayOn = isRearDisplayOn();
        }

        // Power optimization: skip pushing SurfaceFlinger frames while Display 1 is OFF
        if (!cachedRearDisplayOn) {
            if (!currentPlaying) {
                System.arraycopy(BASE_EQ_PAUSED_HEIGHTS, 0, currentBarHeights1x, 0, EQ_BAR_COUNT);
                stopAnimationLoopLocked();
            }
            return;
        }

        if (currentPlaying) {
            float tSec = (nowMs - animOriginElapsedMs) * 0.001f;
            for (int i = 0; i < EQ_BAR_COUNT; i++) {
                float wave = (float) (0.68 * Math.sin(tSec * EQ_PRIMARY_FREQ_RAD_S[i] + EQ_PHASE_OFFSET[i])
                        + 0.32 * Math.sin(tSec * EQ_HARMONIC_FREQ_RAD_S[i] + EQ_PHASE_OFFSET[i] * 1.7f));
                float targetH = clamp(
                        BASE_EQ_PLAYING_REF_HEIGHTS[i] + wave * BASE_EQ_AMPLITUDE[i],
                        BASE_EQ_MIN_HEIGHT,
                        BASE_EQ_MAX_HEIGHT);
                currentBarHeights1x[i] += (targetH - currentBarHeights1x[i]) * HEIGHT_LERP_ALPHA;
            }
            try {
                drawPillFrameLocked(true, currentBarHeights1x);
            } catch (Throwable t) {
                System.err.println("RearSurfacePillRenderer animation frame error: " + t.getMessage());
                hideInternal();
            }
        } else {
            boolean allSettled = true;
            for (int i = 0; i < EQ_BAR_COUNT; i++) {
                float target = BASE_EQ_PAUSED_HEIGHTS[i];
                float diff = target - currentBarHeights1x[i];
                if (Math.abs(diff) <= BASE_PAUSED_SETTLE_EPSILON) {
                    currentBarHeights1x[i] = target;
                } else {
                    currentBarHeights1x[i] += diff * HEIGHT_LERP_ALPHA;
                    allSettled = false;
                }
            }
            try {
                drawPillFrameLocked(false, currentBarHeights1x);
            } catch (Throwable t) {
                hideInternal();
                return;
            }
            if (allSettled) {
                stopAnimationLoopLocked();
            }
        }
    }

    private boolean isSettledAtPausedLocked() {
        for (int i = 0; i < EQ_BAR_COUNT; i++) {
            if (Math.abs(currentBarHeights1x[i] - BASE_EQ_PAUSED_HEIGHTS[i]) > BASE_PAUSED_SETTLE_EPSILON) {
                return false;
            }
        }
        return true;
    }

    // =========================================================================
    // SurfaceControl & Zero-Allocation Software Canvas Rendering
    // =========================================================================

    private void createLayerLocked(int x, int y, int width, int height) throws Exception {
        ensureReflectionInitialized();
        ensureStaticBackdropBitmapLocked();
        int layerStack = resolveDisplay1LayerStack();

        SurfaceControl.Builder builder = createBuilder();
        builder.setName(LAYER_NAME)
                .setBufferSize(width, height)
                .setFormat(PixelFormat.RGBA_8888);

        if (scBuilderSetOpaqueMethod != null) {
            try {
                scBuilderSetOpaqueMethod.invoke(builder, false);
            } catch (Throwable ignored) {}
        }

        SurfaceControl sc = builder.build();

        SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
        if (txSetLayerStackMethod != null) {
            try {
                txSetLayerStackMethod.invoke(tx, sc, layerStack);
            } catch (Throwable t) {
                System.err.println("RearSurfacePillRenderer setLayerStack failed: " + t.getMessage());
            }
        }

        tx.setLayer(sc, PILL_Z_LAYER);
        tx.setPosition(sc, (float) x, (float) y);
        tx.setVisibility(sc, true);
        tx.apply();

        Surface surf = new Surface(sc);

        this.surfaceControl = sc;
        this.surface = surf;
        this.lastX = x;
        this.lastY = y;
    }

    /**
     * Pre-renders the invariant liquid-glass capsule fill, top specular sheen, directional
     * top-lit rim stroke, and grab bar once for the active (currentScale, currentTintColor).
     */
    private void ensureStaticBackdropBitmapLocked() {
        if (staticBackdropBitmap != null && !staticBackdropBitmap.isRecycled()
                && cachedBackdropScale == currentScale
                && cachedBackdropTintColor == currentTintColor) {
            return;
        }
        recycleBackdropLocked();

        float s = currentScale;
        int w = currentWidth;
        int h = currentHeight;
        float inset = BASE_CAPSULE_INSET * s;
        float cornerRadius = BASE_CORNER_RADIUS * s;
        float strokeWidth = BASE_STROKE_WIDTH_PX * s;

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        RectF capsuleRect = new RectF(inset, inset, w - inset, h - inset);

        // 1. Base translucent liquid-glass fill
        Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(deriveGlassFillColor(currentTintColor));
        c.drawRoundRect(capsuleRect, cornerRadius, cornerRadius, fillPaint);

        // 2. Top specular glass sheen
        Paint sheenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        sheenPaint.setStyle(Paint.Style.FILL);
        sheenPaint.setShader(new LinearGradient(
                0f, inset,
                0f, (float) h,
                SHEEN_COLORS,
                SHEEN_POSITIONS,
                Shader.TileMode.CLAMP));
        c.drawRoundRect(capsuleRect, cornerRadius, cornerRadius, sheenPaint);

        // 3. Directional top-lit rim stroke
        if (strokeWidth > 0f) {
            Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(strokeWidth);
            strokePaint.setShader(new LinearGradient(
                    0f, inset,
                    0f, h - inset,
                    deriveStrokeTopColor(currentTintColor),
                    deriveStrokeBottomColor(currentTintColor),
                    Shader.TileMode.CLAMP));
            c.drawRoundRect(capsuleRect, cornerRadius, cornerRadius, strokePaint);
        }

        // 4. Grab bar
        Paint grabPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        grabPaint.setStyle(Paint.Style.FILL);
        grabPaint.setColor(GRAB_BAR_COLOR);
        float grabRadius = BASE_GRAB_CORNER_RADIUS * s;
        c.drawRoundRect(
                new RectF(BASE_GRAB_LEFT * s, BASE_GRAB_TOP * s, BASE_GRAB_RIGHT * s, BASE_GRAB_BOTTOM * s),
                grabRadius,
                grabRadius,
                grabPaint);

        staticBackdropBitmap = bmp;
        cachedBackdropScale = currentScale;
        cachedBackdropTintColor = currentTintColor;
    }

    private void recycleBackdropLocked() {
        if (staticBackdropBitmap != null) {
            try {
                staticBackdropBitmap.recycle();
            } catch (Throwable ignored) {}
            staticBackdropBitmap = null;
        }
        cachedBackdropScale = -1f;
        cachedBackdropTintColor = 0;
    }

    private void drawPillFrameLocked(boolean isPlaying, float[] barHeights1x) {
        if (surface == null || !surface.isValid()) return;
        ensureStaticBackdropBitmapLocked();

        // CRITICAL: Strictly use software lockCanvas(null) — NEVER lockHardwareCanvas() —
        // so the resident UID 2000 daemon never spawns a HWUI RenderThread/GPU context.
        Canvas canvas = surface.lockCanvas(null);
        if (canvas == null) return;
        try {
            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
            canvas.drawBitmap(staticBackdropBitmap, 0f, 0f, null);

            float s = currentScale;
            float centerY = BASE_EQ_CENTER_Y * s;
            float barW = BASE_EQ_BAR_WIDTH * s;
            float barCorner = BASE_EQ_CORNER_RADIUS * s;

            // 3-bar mini equalizer indicator
            eqBarPaint.setColor(isPlaying ? DOT_PLAYING_COLOR : DOT_PAUSED_COLOR);
            for (int i = 0; i < EQ_BAR_COUNT; i++) {
                float left = (BASE_EQ_START_X + i * BASE_EQ_STRIDE_X) * s;
                float halfH = (barHeights1x[i] * s) * 0.5f;
                eqBarRect.set(left, centerY - halfH, left + barW, centerY + halfH);
                canvas.drawRoundRect(eqBarRect, barCorner, barCorner, eqBarPaint);
            }
        } finally {
            surface.unlockCanvasAndPost(canvas);
        }
    }

    private void hideInternal() {
        stopAnimationLoopLocked();

        Surface surf = this.surface;
        this.surface = null;
        if (surf != null) {
            try {
                surf.release();
            } catch (Throwable ignored) {}
        }

        SurfaceControl sc = this.surfaceControl;
        this.surfaceControl = null;
        if (sc != null) {
            try {
                SurfaceControl.Transaction tx = new SurfaceControl.Transaction();
                boolean removed = false;
                if (txRemoveMethod != null) {
                    try {
                        txRemoveMethod.invoke(tx, sc);
                        removed = true;
                    } catch (Throwable ignored) {}
                }
                if (!removed) {
                    tx.reparent(sc, null);
                }
                tx.apply();
            } catch (Throwable ignored) {}
            try {
                sc.release();
            } catch (Throwable ignored) {}
        }

        this.lastX = Integer.MIN_VALUE;
        this.lastY = Integer.MIN_VALUE;
    }

    // =========================================================================
    // Cached Hidden-API Reflection Helpers
    // =========================================================================

    private static void ensureReflectionInitialized() {
        if (reflectionInitialized) return;
        synchronized (RearSurfacePillRenderer.class) {
            if (reflectionInitialized) return;
            try {
                surfaceSessionClass = Class.forName("android.view.SurfaceSession");
                scBuilderSessionCtor = SurfaceControl.Builder.class.getDeclaredConstructor(surfaceSessionClass);
                scBuilderSessionCtor.setAccessible(true);
            } catch (Throwable ignored) {}
            try {
                scBuilderSetOpaqueMethod = SurfaceControl.Builder.class.getMethod("setOpaque", boolean.class);
            } catch (Throwable ignored) {}
            try {
                txSetLayerStackMethod = SurfaceControl.Transaction.class
                        .getMethod("setLayerStack", SurfaceControl.class, int.class);
            } catch (Throwable ignored) {}
            try {
                txRemoveMethod = SurfaceControl.Transaction.class
                        .getMethod("remove", SurfaceControl.class);
            } catch (Throwable ignored) {}
            try {
                Class<?> dmgCls = Class.forName("android.hardware.display.DisplayManagerGlobal");
                displayManagerGlobalInstance = dmgCls.getMethod("getInstance").invoke(null);
                dmgGetDisplayInfoMethod = dmgCls.getMethod("getDisplayInfo", int.class);
                Class<?> diCls = Class.forName("android.view.DisplayInfo");
                displayInfoLayerStackField = diCls.getField("layerStack");
                displayInfoStateField = diCls.getField("state");
            } catch (Throwable ignored) {}
            reflectionInitialized = true;
        }
    }

    private static SurfaceControl.Builder createBuilder() {
        if (surfaceSessionClass != null && scBuilderSessionCtor != null) {
            try {
                Object session = surfaceSessionClass.getDeclaredConstructor().newInstance();
                return scBuilderSessionCtor.newInstance(session);
            } catch (Throwable ignored) {}
        }
        return new SurfaceControl.Builder();
    }

    private static int resolveDisplay1LayerStack() {
        if (displayManagerGlobalInstance != null && dmgGetDisplayInfoMethod != null && displayInfoLayerStackField != null) {
            try {
                Object displayInfo = dmgGetDisplayInfoMethod.invoke(displayManagerGlobalInstance, 1);
                if (displayInfo != null) {
                    return displayInfoLayerStackField.getInt(displayInfo);
                }
            } catch (Throwable ignored) {}
        }
        return 1;
    }

    private static boolean isRearDisplayOn() {
        if (displayManagerGlobalInstance != null && dmgGetDisplayInfoMethod != null && displayInfoStateField != null) {
            try {
                Object displayInfo = dmgGetDisplayInfoMethod.invoke(displayManagerGlobalInstance, 1);
                if (displayInfo != null) {
                    int state = displayInfoStateField.getInt(displayInfo);
                    return state != Display.STATE_OFF;
                }
            } catch (Throwable ignored) {}
        }
        return true;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

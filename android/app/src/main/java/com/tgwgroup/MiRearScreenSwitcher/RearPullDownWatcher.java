package com.tgwgroup.MiRearScreenSwitcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;
import android.view.Surface;

import com.tgwgroup.MiRearScreenSwitcher.adb.LocalAdbBackend;
import com.tgwgroup.MiRearScreenSwitcher.adb.MrssShellDaemon;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight, zero-polling UID 2000 rear pull-down & pill-tap gesture watcher.
 *
 * Primary mode (Local ADB / Xiaomi 17 Pro & compatible HyperOS rear touch devices):
 *   - Dynamically discovers Display 1's touchscreen via `getevent -pl` (matching
 *     `Xiaomi_Touch_Input_1`, or fallback `ABS_MT_POSITION_X` max ~90399 / `ABS_MT_POSITION_Y`
 *     max ~57199) and caches its path + axis maxima.
 *   - Passively streams raw hex `getevent <devicePath>` over a dedicated secondary TCP socket to
 *     MrssShellDaemon (`CMD_STREAM_EXEC`). Zero CPU while idle; child process is killed
 *     immediately on `stop()`.
 *   - Evaluates a dynamic hit-box synced pixel-for-pixel with the active `RearSurfacePillRenderer`
 *     bounds (supporting `1.0x`–`5.0x` scaling, rotation, pill-ON tap & swipe-down, and pill-OFF
 *     top-edge swipe-down), independent of HyperOS's narrow `inHotZone`.
 *
 * Automatic fallback mode:
 *   - If no Display 1 touch device is discovered (other hardware) or in Shizuku mode, falls back
 *     to monitoring HyperOS's `SubScreenCenter_ServiceAssistantGesture` (`mTargetPosition = 0`)
 *     logcat stream so compatibility is never broken.
 *
 * Strictly active ONLY when Smart Media is enabled, a media session exists, and RearMediaActivity
 * is currently collapsed/closed.
 */
public class RearPullDownWatcher {
    private static final String TAG = "RearPullDownWatcher";
    public static final String ACTION_REAR_PULLDOWN =
            "com.tgwgroup.MiRearScreenSwitcher.ACTION_REAR_PULLDOWN";

    private static final String LOGCAT_CMD =
            "exec logcat -v brief -T 1 -s SubScreenCenter_ServiceAssistantGesture:D";
    private static final String LOGCAT_TRIGGER_PATTERN = "mTargetPosition = 0";
    private static final long TRIGGER_DEBOUNCE_MS = 1500L;

    // Linux input_event constants (raw hex getevent format: TTTT CCCC VVVVVVVV)
    private static final int EV_SYN = 0x0000;
    private static final int SYN_REPORT = 0x0000;
    private static final int EV_KEY = 0x0001;
    private static final int BTN_TOUCH = 0x014a;
    private static final int EV_ABS = 0x0003;
    private static final int ABS_MT_SLOT = 0x002f;
    private static final int ABS_MT_POSITION_X = 0x0035;
    private static final int ABS_MT_POSITION_Y = 0x0036;

    // Hardware geometry defaults (Xiaomi 17 Pro Display 1: 904x572, 296px camera cutout)
    private static final int DEFAULT_DISPLAY_W = 904;
    private static final int DEFAULT_DISPLAY_H = 572;
    private static final int DEFAULT_TOUCH_MAX_X = 90399;
    private static final int DEFAULT_TOUCH_MAX_Y = 57199;

    // Dynamic hit-zone & gesture thresholds (logical Display 1 pixels)
    private static final float PILL_PAD_X_PX = 24f;
    private static final float PILL_PAD_BOTTOM_PX = 28f;
    private static final float TOP_BEZEL_WITH_PILL_PX = 56f;
    private static final float TOP_BEZEL_NO_PILL_PX = 64f;
    private static final float SWIPE_DOWN_MIN_DY_PX = 55f;
    private static final float SWIPE_DOWN_SLOPE_RATIO = 1.3f;
    private static final double TAP_MAX_DISTANCE_PX = 28.0;
    private static final long TAP_MAX_DURATION_MS = 400L;

    public interface Listener {
        void onRearPullDownDetected();
    }

    public interface DisplayStateProvider {
        boolean isRearDisplayOn();
    }

    private static final class TouchDeviceInfo {
        final String devicePath;
        final int maxX;
        final int maxY;

        TouchDeviceInfo(String devicePath, int maxX, int maxY) {
            this.devicePath = devicePath;
            this.maxX = maxX > 0 ? maxX : DEFAULT_TOUCH_MAX_X;
            this.maxY = maxY > 0 ? maxY : DEFAULT_TOUCH_MAX_Y;
        }
    }

    private static final class HitBoxState {
        static final HitBoxState DEFAULT = new HitBoxState(
                false, 0, 0, 0, 0, DEFAULT_DISPLAY_W, DEFAULT_DISPLAY_H, Surface.ROTATION_0);

        final boolean pillVisible;
        final int x;
        final int y;
        final int width;
        final int height;
        final int displayW;
        final int displayH;
        final int rotation;

        HitBoxState(
                boolean pillVisible,
                int x,
                int y,
                int width,
                int height,
                int displayW,
                int displayH,
                int rotation) {
            this.pillVisible = pillVisible;
            this.x = x;
            this.y = y;
            this.width = Math.max(0, width);
            this.height = Math.max(0, height);
            this.displayW = displayW > 0 ? displayW : DEFAULT_DISPLAY_W;
            this.displayH = displayH > 0 ? displayH : DEFAULT_DISPLAY_H;
            this.rotation = rotation;
        }
    }

    private final Context appContext;
    private final Listener listener;
    private final DisplayStateProvider displayStateProvider;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final Object lock = new Object();

    private volatile Socket streamSocket = null;
    private volatile Thread streamThread = null;
    private volatile boolean shizukuWatcherStarted = false;
    private volatile boolean receiverRegistered = false;
    private volatile long lastTriggerElapsedMs = 0L;

    // Atomic immutable snapshot updated live from NotificationService without restarting stream
    private volatile HitBoxState hitBox = HitBoxState.DEFAULT;

    // Cached dynamic discovery result (populated once `getevent -pl` returns valid output)
    private volatile boolean touchDiscoveryCompleted = false;
    private volatile TouchDeviceInfo cachedTouchDevice = null;

    private final BroadcastReceiver shizukuPullDownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && ACTION_REAR_PULLDOWN.equals(intent.getAction())) {
                if (active.get()) {
                    dispatchDebouncedTrigger("Shizuku ACTION_REAR_PULLDOWN broadcast");
                }
            }
        }
    };

    public RearPullDownWatcher(Context context, Listener listener) {
        this(context, listener, null);
    }

    public RearPullDownWatcher(
            Context context,
            Listener listener,
            DisplayStateProvider displayStateProvider) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        this.displayStateProvider = displayStateProvider;
    }

    /**
     * Thread-safe live hit-box update called whenever the rear pill is shown, rescaled,
     * rotated, or hidden. Takes effect immediately on the next touch frame without restarting
     * the underlying `getevent` stream.
     */
    public void updatePillHitBox(
            boolean pillVisible,
            int x,
            int y,
            int width,
            int height,
            int displayW,
            int displayH,
            int rotation) {
        this.hitBox = new HitBoxState(pillVisible, x, y, width, height, displayW, displayH, rotation);
    }

    public boolean isRunning() {
        return active.get();
    }

    public void start() {
        synchronized (lock) {
            if (active.get()) {
                return;
            }
            active.set(true);
            ensureReceiverRegisteredLocked();

            if (TaskServiceManager.isLocalAdbMode(appContext)) {
                startDaemonStreamThreadLocked();
            } else {
                startShizukuWatcherAsync();
            }
        }
    }

    public void stop() {
        synchronized (lock) {
            if (!active.getAndSet(false) && streamSocket == null && !shizukuWatcherStarted) {
                return;
            }
            closeStreamSocketLocked();
            Thread t = streamThread;
            streamThread = null;
            if (t != null) {
                t.interrupt();
            }
            if (shizukuWatcherStarted) {
                shizukuWatcherStarted = false;
                stopShizukuWatcherAsync();
            }
            Log.d(TAG, "⏹️ RearPullDownWatcher stopped");
        }
    }

    public void destroy() {
        stop();
        synchronized (lock) {
            if (receiverRegistered) {
                try {
                    appContext.unregisterReceiver(shizukuPullDownReceiver);
                } catch (Throwable ignored) {}
                receiverRegistered = false;
            }
        }
    }

    private void ensureReceiverRegisteredLocked() {
        if (receiverRegistered) return;
        try {
            IntentFilter filter = new IntentFilter(ACTION_REAR_PULLDOWN);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Shizuku UID 2000 sends via `am broadcast`, which crosses UID boundary into app process.
                appContext.registerReceiver(shizukuPullDownReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                appContext.registerReceiver(shizukuPullDownReceiver, filter);
            }
            receiverRegistered = true;
        } catch (Throwable t) {
            Log.w(TAG, "Failed registering pull-down broadcast receiver: " + t.getMessage());
        }
    }

    private boolean isRearDisplayOn() {
        if (displayStateProvider != null) {
            try {
                return displayStateProvider.isRearDisplayOn();
            } catch (Throwable ignored) {}
        }
        try {
            DisplayManager dm = (DisplayManager) appContext.getSystemService(Context.DISPLAY_SERVICE);
            Display rear = (dm != null) ? dm.getDisplay(1) : null;
            return rear != null && rear.getState() == Display.STATE_ON;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // =========================================================================
    // Dynamic Display 1 Touchscreen Discovery (`getevent -pl`)
    // =========================================================================

    private TouchDeviceInfo resolveTouchDeviceIfNeeded() {
        if (touchDiscoveryCompleted) {
            return cachedTouchDevice;
        }
        try {
            ITaskService ts = NotificationService.getTaskService();
            if (ts == null) {
                return null;
            }
            String output = ts.executeShellCommandWithResult("getevent -pl");
            if (output == null || output.trim().isEmpty()) {
                return null;
            }
            TouchDeviceInfo discovered = parseRearTouchDevice(output);
            cachedTouchDevice = discovered;
            touchDiscoveryCompleted = true;
            if (discovered != null) {
                Log.d(TAG, "🎯 Discovered Display 1 touch device: " + discovered.devicePath
                        + " (maxX=" + discovered.maxX + ", maxY=" + discovered.maxY + ")");
            } else {
                Log.w(TAG, "⚠️ No Display 1 touch device found in `getevent -pl`; falling back to logcat watcher");
            }
            return discovered;
        } catch (Throwable t) {
            Log.w(TAG, "Touch device discovery failed: " + t.getMessage());
            return null;
        }
    }

    private static TouchDeviceInfo parseRearTouchDevice(String output) {
        String currentPath = null;
        String currentName = "";
        int currentMaxX = -1;
        int currentMaxY = -1;

        TouchDeviceInfo nameMatch = null;
        TouchDeviceInfo rangeFallback = null;

        String[] lines = output.split("\\r?\\n");
        for (int i = 0; i <= lines.length; i++) {
            String line = (i < lines.length) ? lines[i] : null;
            boolean isNewBlock = (line == null) || line.startsWith("add device ");

            if (isNewBlock && currentPath != null) {
                if (currentName.contains("Xiaomi_Touch_Input_1")) {
                    nameMatch = new TouchDeviceInfo(
                            currentPath,
                            currentMaxX > 0 ? currentMaxX : DEFAULT_TOUCH_MAX_X,
                            currentMaxY > 0 ? currentMaxY : DEFAULT_TOUCH_MAX_Y);
                    break;
                }
                if (rangeFallback == null
                        && currentMaxX >= 89000 && currentMaxX <= 92000
                        && currentMaxY >= 56000 && currentMaxY <= 58500) {
                    rangeFallback = new TouchDeviceInfo(currentPath, currentMaxX, currentMaxY);
                }
            }

            if (line == null) break;

            if (line.startsWith("add device ")) {
                int pathIdx = line.indexOf("/dev/input/");
                currentPath = (pathIdx >= 0) ? line.substring(pathIdx).trim() : null;
                currentName = "";
                currentMaxX = -1;
                currentMaxY = -1;
            } else if (currentPath != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("name:")) {
                    String val = trimmed.substring(5).trim();
                    if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
                        val = val.substring(1, val.length() - 1);
                    }
                    currentName = val;
                } else if (trimmed.contains("ABS_MT_POSITION_X") || trimmed.startsWith("0035 ")) {
                    int m = parseMaxField(trimmed);
                    if (m > 0) currentMaxX = m;
                } else if (trimmed.contains("ABS_MT_POSITION_Y") || trimmed.startsWith("0036 ")) {
                    int m = parseMaxField(trimmed);
                    if (m > 0) currentMaxY = m;
                }
            }
        }

        return (nameMatch != null) ? nameMatch : rangeFallback;
    }

    private static int parseMaxField(String line) {
        int idx = line.indexOf("max ");
        if (idx < 0) return -1;
        int p = idx + 4;
        int len = line.length();
        while (p < len && line.charAt(p) == ' ') p++;
        int start = p;
        while (p < len) {
            char c = line.charAt(p);
            if (c < '0' || c > '9') break;
            p++;
        }
        if (p == start) return -1;
        try {
            return Integer.parseInt(line.substring(start, p));
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    // =========================================================================
    // Passive Zero-CPU Touch Stream & Logcat Fallback (MrssShellDaemon)
    // =========================================================================

    private void startDaemonStreamThreadLocked() {
        Thread t = new Thread(() -> {
            Log.d(TAG, "▶️ RearPullDownWatcher started (MrssShellDaemon CMD_STREAM_EXEC)");
            long backoffMs = 1500L;

            while (active.get() && !Thread.currentThread().isInterrupted()) {
                if (!TaskServiceManager.isLocalAdbMode(appContext)) {
                    startShizukuWatcherAsync();
                    return;
                }

                LocalAdbBackend backend = LocalAdbBackend.getInstance(appContext);
                if (!backend.isReadyFast()) {
                    sleepQuietly(backoffMs);
                    backoffMs = Math.min(backoffMs * 2L, 8000L);
                    continue;
                }

                TouchDeviceInfo touchDevice = resolveTouchDeviceIfNeeded();
                final boolean useTouchStream = (touchDevice != null);
                final String streamCmd = useTouchStream
                        ? ("exec getevent " + touchDevice.devicePath)
                        : LOGCAT_CMD;

                Socket socket = null;
                try {
                    socket = new Socket();
                    synchronized (lock) {
                        if (!active.get()) {
                            closeQuietly(socket);
                            return;
                        }
                        streamSocket = socket;
                    }

                    socket.connect(new InetSocketAddress("127.0.0.1", MrssShellDaemon.DEFAULT_PORT), 1500);
                    socket.setTcpNoDelay(true);
                    socket.setSoTimeout(3000);

                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    DataOutputStream out = new DataOutputStream(socket.getOutputStream());

                    out.writeUTF(backend.getDaemonToken());
                    out.flush();
                    if (!in.readBoolean()) {
                        throw new IllegalStateException("Daemon auth rejected");
                    }

                    out.writeByte(MrssShellDaemon.CMD_STREAM_EXEC);
                    MrssShellDaemon.writeString(out, streamCmd);
                    out.flush();

                    boolean streamStarted = in.readBoolean();
                    if (!streamStarted) {
                        throw new IllegalStateException("Daemon refused CMD_STREAM_EXEC");
                    }

                    // Connected & streaming: disable read timeout so idle stream consumes zero CPU
                    socket.setSoTimeout(0);
                    backoffMs = 1500L;

                    if (useTouchStream) {
                        Log.d(TAG, "✅ RearPullDownWatcher streaming passive touch: " + touchDevice.devicePath);
                        TouchGestureTracker tracker = new TouchGestureTracker(touchDevice);
                        while (active.get() && !socket.isClosed()) {
                            String line = MrssShellDaemon.readString(in);
                            if (line != null && !line.isEmpty()) {
                                tracker.feedRawLine(line);
                            }
                        }
                    } else {
                        Log.d(TAG, "✅ RearPullDownWatcher streaming logcat fallback (SubScreenCenter_ServiceAssistantGesture)");
                        while (active.get() && !socket.isClosed()) {
                            String line = MrssShellDaemon.readString(in);
                            if (line != null && line.contains(LOGCAT_TRIGGER_PATTERN)) {
                                dispatchDebouncedTrigger(line.trim());
                            }
                        }
                    }
                } catch (Throwable err) {
                    if (active.get()) {
                        Log.d(TAG, "RearPullDownWatcher stream ended (" + err.getMessage() + "); retry in " + backoffMs + "ms");
                        sleepQuietly(backoffMs);
                        backoffMs = Math.min(backoffMs * 2L, 8000L);
                    }
                } finally {
                    synchronized (lock) {
                        if (streamSocket == socket) {
                            streamSocket = null;
                        }
                    }
                    closeQuietly(socket);
                }
            }
        }, "MRSS-RearPullDownWatcher");
        t.setDaemon(true);
        streamThread = t;
        t.start();
    }

    /**
     * Zero-allocation per-event touch state machine (strictly runs on the stream thread).
     * Parses raw hex `TTTT CCCC VVVVVVVV` lines from `getevent <devicePath>`.
     */
    private final class TouchGestureTracker {
        private final TouchDeviceInfo device;
        private final int[] parsedTriplet = new int[3];

        private int currentSlot = 0;
        private int slot0RawX = -1;
        private int slot0RawY = -1;

        private boolean touchDown = false;
        private boolean awaitingFirstDownFrame = false;
        private boolean pendingUpFrame = false;
        private boolean gestureTriggeredThisTouch = false;

        private boolean downWhileDisplayOn = false;
        private boolean downInPillBox = false;
        private boolean downInTopBezel = false;
        private float downX = 0f;
        private float downY = 0f;
        private float curX = 0f;
        private float curY = 0f;
        private long downTimeMs = 0L;

        TouchGestureTracker(TouchDeviceInfo device) {
            this.device = device;
        }

        void feedRawLine(String line) {
            if (!parseHexTriplet(line, parsedTriplet)) {
                return;
            }
            int type = parsedTriplet[0];
            int code = parsedTriplet[1];
            int value = parsedTriplet[2];

            if (type == EV_ABS) {
                if (code == ABS_MT_SLOT) {
                    currentSlot = value;
                } else if (currentSlot == 0) {
                    if (code == ABS_MT_POSITION_X) {
                        slot0RawX = value;
                    } else if (code == ABS_MT_POSITION_Y) {
                        slot0RawY = value;
                    }
                }
            } else if (type == EV_KEY && code == BTN_TOUCH) {
                if (value == 1) {
                    touchDown = true;
                    awaitingFirstDownFrame = true;
                    pendingUpFrame = false;
                    gestureTriggeredThisTouch = false;
                } else if (value == 0) {
                    pendingUpFrame = true;
                }
            } else if (type == EV_SYN && code == SYN_REPORT) {
                onSynReport();
            }
        }

        private void onSynReport() {
            if (!touchDown && !pendingUpFrame) {
                return;
            }
            if (slot0RawX < 0 || slot0RawY < 0) {
                if (pendingUpFrame) {
                    touchDown = false;
                    awaitingFirstDownFrame = false;
                    pendingUpFrame = false;
                }
                return;
            }

            final HitBoxState hb = hitBox;
            float px = (slot0RawX * (float) hb.displayW) / (device.maxX + 1f);
            float py = (slot0RawY * (float) hb.displayH) / (device.maxY + 1f);
            if (hb.rotation == Surface.ROTATION_180) {
                px = hb.displayW - px;
                py = hb.displayH - py;
            }
            curX = px;
            curY = py;

            if (awaitingFirstDownFrame) {
                awaitingFirstDownFrame = false;
                downX = curX;
                downY = curY;
                downTimeMs = SystemClock.elapsedRealtime();
                downWhileDisplayOn = isRearDisplayOn();

                int cutout = (hb.displayW == DEFAULT_DISPLAY_W)
                        ? 296
                        : Math.max(296, Math.round(hb.displayW * 0.328f));
                float safeMinX = (hb.rotation == Surface.ROTATION_180) ? 0f : cutout;
                float safeMaxX = (hb.rotation == Surface.ROTATION_180) ? (hb.displayW - cutout) : hb.displayW;

                if (hb.pillVisible) {
                    float pillLeft = Math.max(0f, hb.x - PILL_PAD_X_PX);
                    float pillRight = Math.min(hb.displayW, hb.x + hb.width + PILL_PAD_X_PX);
                    float pillBottom = Math.min(hb.displayH, hb.y + hb.height + PILL_PAD_BOTTOM_PX);
                    downInPillBox = (downX >= pillLeft && downX <= pillRight
                            && downY >= 0f && downY <= pillBottom);
                    downInTopBezel = (downY <= TOP_BEZEL_WITH_PILL_PX
                            && downX >= safeMinX && downX <= safeMaxX);
                } else {
                    downInPillBox = false;
                    downInTopBezel = (downY <= TOP_BEZEL_NO_PILL_PX
                            && downX >= safeMinX && downX <= safeMaxX);
                }
            }

            float dx = curX - downX;
            float dy = curY - downY;

            // Rule 1: Swipe Down (fires immediately before finger lift, both pill ON and pill OFF)
            if (touchDown && downWhileDisplayOn && !gestureTriggeredThisTouch
                    && (downInPillBox || downInTopBezel)) {
                if (dy >= SWIPE_DOWN_MIN_DY_PX && dy > Math.abs(dx) * SWIPE_DOWN_SLOPE_RATIO) {
                    gestureTriggeredThisTouch = true;
                    String tag = downInPillBox ? "SWIPE_DOWN_PILL" : "SWIPE_DOWN_TOP_EDGE";
                    dispatchDebouncedTrigger(formatGestureLog(tag, downX, downY, curX, curY, -1L));
                }
            }

            // Rule 2: Direct Tap on Pill (only when pillVisible == true, evaluated on BTN_TOUCH UP)
            if (pendingUpFrame) {
                pendingUpFrame = false;
                touchDown = false;
                if (hb.pillVisible && downWhileDisplayOn && !gestureTriggeredThisTouch && downInPillBox) {
                    double dist = Math.hypot(dx, dy);
                    long durationMs = SystemClock.elapsedRealtime() - downTimeMs;
                    if (dist <= TAP_MAX_DISTANCE_PX && durationMs <= TAP_MAX_DURATION_MS) {
                        gestureTriggeredThisTouch = true;
                        dispatchDebouncedTrigger(formatGestureLog("TAP_ON_PILL", downX, downY, curX, curY, durationMs));
                    }
                }
            }
        }
    }

    private static String formatGestureLog(
            String kind, float downX, float downY, float curX, float curY, long durationMs) {
        if (durationMs >= 0L) {
            return String.format(Locale.US, "%s (%.0f,%.0f)->(%.0f,%.0f) %dms",
                    kind, downX, downY, curX, curY, durationMs);
        }
        return String.format(Locale.US, "%s (%.0f,%.0f)->(%.0f,%.0f)",
                kind, downX, downY, curX, curY);
    }

    /**
     * Parses raw `getevent` hex lines (`TTTT CCCC VVVVVVVV` or `/dev/input/eventX: TTTT CCCC VVVVVVVV`)
     * into `out[0]=type, out[1]=code, out[2]=value` with zero temporary object allocations.
     */
    private static boolean parseHexTriplet(String line, int[] out) {
        int len = line.length();
        int i = 0;
        int colon = line.indexOf(':');
        if (colon >= 0) {
            i = colon + 1;
        }
        for (int field = 0; field < 3; field++) {
            while (i < len && line.charAt(i) <= ' ') i++;
            int start = i;
            int acc = 0;
            while (i < len) {
                char c = line.charAt(i);
                int digit;
                if (c >= '0' && c <= '9') {
                    digit = c - '0';
                } else if (c >= 'a' && c <= 'f') {
                    digit = 10 + (c - 'a');
                } else if (c >= 'A' && c <= 'F') {
                    digit = 10 + (c - 'A');
                } else if (c <= ' ') {
                    break;
                } else {
                    return false;
                }
                acc = (acc << 4) | digit;
                i++;
            }
            if (i == start) {
                return false;
            }
            out[field] = acc;
        }
        return true;
    }

    // =========================================================================
    // Shizuku Mode Fallback
    // =========================================================================

    private void startShizukuWatcherAsync() {
        Thread t = new Thread(() -> {
            try {
                ITaskService ts = NotificationService.getTaskService();
                if (ts != null && active.get()) {
                    boolean ok = ts.startRearPullDownWatcher();
                    synchronized (lock) {
                        shizukuWatcherStarted = ok && active.get();
                    }
                    Log.d(TAG, "▶️ RearPullDownWatcher started (Shizuku TaskService, ok=" + ok + ")");
                }
            } catch (Throwable e) {
                Log.w(TAG, "Failed starting Shizuku rear pull-down watcher: " + e.getMessage());
            }
        }, "MRSS-StartShizukuWatcher");
        t.setDaemon(true);
        t.start();
    }

    private void stopShizukuWatcherAsync() {
        Thread t = new Thread(() -> {
            try {
                ITaskService ts = NotificationService.getTaskService();
                if (ts != null) {
                    ts.stopRearPullDownWatcher();
                }
            } catch (Throwable ignored) {}
        }, "MRSS-StopShizukuWatcher");
        t.setDaemon(true);
        t.start();
    }

    private void dispatchDebouncedTrigger(String reason) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastTriggerElapsedMs < TRIGGER_DEBOUNCE_MS) {
            Log.d(TAG, "⏳ RearPullDownWatcher debounced match: [" + reason + "]");
            return;
        }
        lastTriggerElapsedMs = now;
        Log.d(TAG, "⬇️ RearPullDownWatcher triggered [" + reason + "] -> invoking pull-down callback");
        if (listener != null) {
            listener.onRearPullDownDetected();
        }
    }

    private void closeStreamSocketLocked() {
        Socket s = streamSocket;
        streamSocket = null;
        closeQuietly(s);
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (Throwable ignored) {}
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.tgwgroup.MiRearScreenSwitcher.adb;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import com.tgwgroup.MiRearScreenSwitcher.MainActivity;
import com.tgwgroup.MiRearScreenSwitcher.TaskServiceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-Disabling AccessibilityService for One-Time On-Device Local ADB Self-Pairing.
 *
 * Hardened for Xiaomi 17 Pro (HyperOS 4 / Android 17 dual-display architecture):
 * 1. Passes knownConnectPort (scraped from the Wireless Debugging main screen) directly into
 *    LocalAdbBackend.pairAndConnect(pairingPort, pairingCode, knownConnectPort) so post-pairing
 *    ADB connection happens immediately without waiting 10s on NsdManager mDNS discovery.
 * 2. Prevents infinite re-pairing loops via pairingSucceededThisSession + a max-2-attempts cap,
 *    disarming and returning to MRSS immediately after pairAndConnect finishes.
 * 3. Eliminates ghost swipes on MRSS / Launcher by checking the active/focused TYPE_APPLICATION
 *    window on Display.DEFAULT_DISPLAY before taking any action, removing cachedSettingsRoot,
 *    never scrolling when settingsTexts is empty, and immediately disarming if the user returns
 *    to MRSS or a launcher >2,500ms after arming.
 */
public class LocalAdbAutoPairAccessibilityService extends AccessibilityService {
    private static final String TAG = "LocalAdbAutoPair";

    private static final String PKG_SETTINGS = "com.android.settings";
    private static final String PKG_SYSTEMUI = "com.android.systemui";
    private static final String PKG_MRSS = "com.tgwgroup.MiRearScreenSwitcher";
    private static final String PKG_MIUI_HOME = "com.miui.home";
    private static final String NSD_PAIRING_TYPE = "_adb-tls-pairing._tcp.";
    private static final long TICKER_INTERVAL_MS = 700L;
    private static final int MAX_PAIRING_ATTEMPTS_PER_SESSION = 2;

    private static final Pattern SIX_DIGIT_CODE_PATTERN = Pattern.compile("^\\d{6}$");
    private static final Pattern SPACED_SIX_DIGIT_CODE_PATTERN = Pattern.compile("^(\\d{3})\\s+(\\d{3})$");
    private static final Pattern EMBEDDED_SIX_DIGIT_PATTERN = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");
    private static final Pattern IP_PORT_PATTERN = Pattern.compile(
            "(?:(?:\\d{1,3}\\.){3}\\d{1,3}|127\\.0\\.0\\.1|localhost)\\s*[:：]\\s*(\\d{4,5})");

    private enum ScreenState {
        NONE,
        DEVELOPER_OPTIONS,
        WIRELESS_DEBUGGING_SCREEN,
        NETWORK_TRUST_DIALOG,
        PAIRING_DIALOG
    }

    /**
     * Encapsulates a single window root and its own isolated text nodes so foreground AlertDialogs
     * are never polluted by background SubSettings nodes.
     */
    private static final class WindowCandidate {
        final AccessibilityNodeInfo root;
        final String packageName;
        final String rootClassName;
        final String title;
        final int windowId;
        final int windowType;
        final int layer;
        final boolean focused;
        final boolean active;
        final boolean fromActiveRoot;
        final List<String> texts;

        WindowCandidate(
                AccessibilityNodeInfo root,
                String packageName,
                String title,
                int windowId,
                int windowType,
                int layer,
                boolean focused,
                boolean active,
                boolean fromActiveRoot) {
            this.root = root;
            this.packageName = packageName != null ? packageName : "";
            this.rootClassName = (root != null && root.getClassName() != null)
                    ? root.getClassName().toString()
                    : "unknown";
            this.title = title != null ? title : "";
            this.windowId = windowId;
            this.windowType = windowType;
            this.layer = layer;
            this.focused = focused;
            this.active = active;
            this.fromActiveRoot = fromActiveRoot;
            this.texts = new ArrayList<>();
            collectAllTexts(root, this.texts);
        }
    }

    /**
     * Result of inspecting Display 0 windows, including whether a non-Settings/non-SystemUI
     * application window is currently focused in the foreground.
     */
    private static final class WindowSnapshot {
        final List<WindowCandidate> candidates;
        final boolean hasForegroundSettingsOrSystemUiAppWindow;
        final boolean userReturnedToMrssOrLauncher;
        final String foregroundAppDescription;

        WindowSnapshot(
                List<WindowCandidate> candidates,
                boolean hasForegroundSettingsOrSystemUiAppWindow,
                boolean userReturnedToMrssOrLauncher,
                String foregroundAppDescription) {
            this.candidates = candidates;
            this.hasForegroundSettingsOrSystemUiAppWindow = hasForegroundSettingsOrSystemUiAppWindow;
            this.userReturnedToMrssOrLauncher = userReturnedToMrssOrLauncher;
            this.foregroundAppDescription = foregroundAppDescription;
        }
    }

    private static volatile long armedTimestampMs = 0L;
    private static final long ARM_SESSION_TTL_MS = 120_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean pairingInFlight = new AtomicBoolean(false);
    private final AtomicBoolean pairingSucceededThisSession = new AtomicBoolean(false);
    private final AtomicInteger pairingAttemptsThisSession = new AtomicInteger(0);
    private final AtomicBoolean tickerRunning = new AtomicBoolean(false);
    private final AtomicInteger mdnsPairingPort = new AtomicInteger(0);
    private final AtomicBoolean nsdListening = new AtomicBoolean(false);
    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener pairingListener;

    private ScreenState currentScreenState = ScreenState.NONE;
    private boolean hasEnteredSettingsThisSession = false;
    private String lastLoggedWindowSummary = "";
    private long lastScrollTimeMs = 0L;
    private int scrollAttempts = 0;
    private String lastScreenTextSignature = "";
    private boolean lastScrollUsedNodeAction = false;

    private long lastPairDialogClickTimeMs = 0L;
    private boolean pairingRowClicked = false;
    private int pairingRowClickAttempts = 0;
    private volatile int knownConnectPort = 0;

    private long lastSwitchToggleTimeMs = 0L;
    private long pairDialogFirstSeenMs = 0L;
    private String lastAttemptedPairSignature = "";

    private final Runnable stepTickerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isSessionArmed()) {
                tickerRunning.set(false);
                Log.d(TAG, "⏹️ Ticker stopped (session no longer armed)");
                return;
            }
            try {
                runStateMachineStep("ticker", null);
            } catch (Throwable t) {
                Log.w(TAG, "Ticker step exception: " + t.getMessage());
            }
            if (isSessionArmed()) {
                mainHandler.postDelayed(this, TICKER_INTERVAL_MS);
            } else {
                tickerRunning.set(false);
            }
        }
    };

    public static void armAutoPairSession(Context context, boolean armed) {
        armedTimestampMs = armed ? System.currentTimeMillis() : 0L;
        Log.d(TAG, (armed ? "🟢 Armed" : "⚪ Disarmed") + " one-time AutoPair session");
        if (armed && context != null) {
            LocalAdbBackend.getInstance(context).startBackgroundMdnsDiscovery();
        }
    }

    public static boolean isSessionArmed() {
        long ts = armedTimestampMs;
        return ts > 0 && (System.currentTimeMillis() - ts) < ARM_SESSION_TTL_MS;
    }

    public static boolean isAccessibilityServiceEnabled(Context context) {
        try {
            String enabledServices = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            );
            if (enabledServices == null || enabledServices.isEmpty()) {
                return false;
            }
            ComponentName target = new ComponentName(context, LocalAdbAutoPairAccessibilityService.class);
            String flat1 = target.flattenToString();
            String flat2 = target.flattenToShortString();
            for (String item : enabledServices.split(":")) {
                if (item.equalsIgnoreCase(flat1) || item.equalsIgnoreCase(flat2)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static void launchDeveloperOptions(Context context) {
        try {
            Log.d(TAG, "🚀 Launching Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS");
            Intent devIntent = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            devIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            context.startActivity(devIntent);
        } catch (Throwable t) {
            Log.e(TAG, "Failed launching Developer Options", t);
        }
    }

    private void ensureTickerStarted() {
        if (isSessionArmed() && tickerRunning.compareAndSet(false, true)) {
            Log.d(TAG, "⏱️ Starting 700ms state machine step ticker");
            mainHandler.postDelayed(stepTickerRunnable, TICKER_INTERVAL_MS);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        Log.d(TAG, "🔗 onServiceConnected (armed=" + isSessionArmed() + ")");
        if (isSessionArmed()) {
            resetSessionCounters();
            startPairingMdnsDiscovery();
            ensureTickerStarted();
            mainHandler.postDelayed(() -> launchDeveloperOptions(this), 250);
        }
    }

    private void resetSessionCounters() {
        currentScreenState = ScreenState.NONE;
        hasEnteredSettingsThisSession = false;
        pairingInFlight.set(false);
        pairingSucceededThisSession.set(false);
        pairingAttemptsThisSession.set(0);
        scrollAttempts = 0;
        lastScrollTimeMs = 0L;
        lastScreenTextSignature = "";
        lastLoggedWindowSummary = "";
        lastScrollUsedNodeAction = false;
        lastPairDialogClickTimeMs = 0L;
        pairingRowClicked = false;
        pairingRowClickAttempts = 0;
        knownConnectPort = 0;
        lastSwitchToggleTimeMs = 0L;
        pairDialogFirstSeenMs = 0L;
        lastAttemptedPairSignature = "";
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isSessionArmed()) {
            return;
        }
        ensureTickerStarted();
        String evtPkg = (event != null && event.getPackageName() != null)
                ? event.getPackageName().toString()
                : "unknown";
        runStateMachineStep("event:" + evtPkg, event);
    }

    private void runStateMachineStep(String source, AccessibilityEvent event) {
        if (!isSessionArmed() || pairingInFlight.get()) {
            return;
        }

        // Reset counters if a new armed session started while the service was already connected
        if (pairingSucceededThisSession.get() && (System.currentTimeMillis() - armedTimestampMs) < 1500L) {
            resetSessionCounters();
        }

        startPairingMdnsDiscovery();

        WindowSnapshot snapshot = collectWindowSnapshot(event, source);
        long elapsedSinceArmed = System.currentTimeMillis() - armedTimestampMs;

        // Fix #4: If the user returned to MRSS or a launcher >2,500ms after arming, immediately disarm and stop ticker
        if (elapsedSinceArmed > 2500L && snapshot.userReturnedToMrssOrLauncher) {
            Log.w(TAG, "🛑 [" + source + "] User left Settings to " + snapshot.foregroundAppDescription +
                    " (" + elapsedSinceArmed + "ms after arming) -> disarming AutoPair session and stopping ticker");
            disarmAndStopTickerOnly();
            return;
        }

        // Fix #4: Never transition state or dispatch gestures/scrolls when the active/focused application
        // window on Display 0 is not com.android.settings or com.android.systemui
        if (!snapshot.hasForegroundSettingsOrSystemUiAppWindow) {
            return;
        }

        List<WindowCandidate> candidates = snapshot.candidates;
        if (candidates.isEmpty()) {
            return;
        }

        StringBuilder pkgs = new StringBuilder();
        boolean hasSettingsWindow = false;
        for (WindowCandidate c : candidates) {
            if (!c.packageName.isEmpty()) {
                if (pkgs.length() > 0) pkgs.append(",");
                pkgs.append(c.packageName);
                if (PKG_SETTINGS.equals(c.packageName) && !c.texts.isEmpty()) {
                    hasSettingsWindow = true;
                    hasEnteredSettingsThisSession = true;
                }
            }
        }

        try {
            // Priority 1: Network trust confirmation dialog ("Allow wireless debugging on this network?")
            if (handleNetworkTrustDialog(candidates, source)) {
                return;
            }

            // Priority 2: Strictly gated 6-digit Pairing Code dialog (inspected per-window, focused/topmost first)
            if (handlePairingCodeDialog(candidates, source)) {
                return;
            }

            // Priority 3: Wireless Debugging main screen (strictly separated from PAIRING_DIALOG)
            if (handleWirelessDebuggingScreen(candidates, source)) {
                return;
            }

            // Priority 4: Developer Options list -> find or scroll to "Wireless debugging" / "无线调试"
            handleDeveloperOptionsScreen(candidates, source, pkgs.toString(), hasSettingsWindow);
        } catch (Throwable t) {
            Log.w(TAG, "[" + source + "] State machine traversal error: " + t.getMessage());
        }
    }

    /**
     * Collects Display 0 window roots as isolated WindowCandidate objects without merging text across
     * windows and without any cachedSettingsRoot fallback. Strictly verifies whether the focused/active
     * TYPE_APPLICATION window on Display.DEFAULT_DISPLAY belongs to com.android.settings or
     * com.android.systemui.
     */
    private WindowSnapshot collectWindowSnapshot(AccessibilityEvent event, String source) {
        List<AccessibilityWindowInfo> display0Windows = new ArrayList<>();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                SparseArray<List<AccessibilityWindowInfo>> allDisplays = getWindowsOnAllDisplays();
                if (allDisplays != null) {
                    List<AccessibilityWindowInfo> d0 = allDisplays.get(Display.DEFAULT_DISPLAY);
                    if (d0 != null) {
                        display0Windows.addAll(d0);
                    }
                }
            } catch (Throwable ignored) {}
        }

        try {
            List<AccessibilityWindowInfo> defaultWindows = getWindows();
            if (defaultWindows != null) {
                for (AccessibilityWindowInfo w : defaultWindows) {
                    if (!display0Windows.contains(w)) {
                        display0Windows.add(w);
                    }
                }
            }
        } catch (Throwable ignored) {}

        AccessibilityNodeInfo activeWindowRoot = null;
        try {
            activeWindowRoot = getRootInActiveWindow();
        } catch (Throwable ignored) {}

        List<WindowCandidate> settingsCandidates = new ArrayList<>();
        List<WindowCandidate> systemUiCandidates = new ArrayList<>();
        boolean activeRootMatchedWindow = false;

        boolean hasSettingsOrSystemUiAppWindow = false;
        boolean hasForeignFocusedAppWindow = false;
        String foreignAppDesc = "";

        StringBuilder winSummary = new StringBuilder();
        for (AccessibilityWindowInfo win : display0Windows) {
            AccessibilityNodeInfo root = null;
            try {
                root = win.getRoot();
            } catch (Throwable ignored) {}
            String rootPkg = (root != null && root.getPackageName() != null)
                    ? root.getPackageName().toString()
                    : "null";
            String titleStr = "";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    CharSequence titleSeq = win.getTitle();
                    if (titleSeq != null) titleStr = titleSeq.toString();
                } catch (Throwable ignored) {}
            }
            int layer = 0;
            boolean focused = false;
            boolean active = false;
            int winType = win.getType();
            try {
                layer = win.getLayer();
                focused = win.isFocused();
                active = win.isActive();
            } catch (Throwable ignored) {}

            if (winSummary.length() > 0) winSummary.append(" | ");
            winSummary.append("win[id=").append(win.getId())
                      .append(",type=").append(winType)
                      .append(",layer=").append(layer)
                      .append(",focused=").append(focused)
                      .append(",active=").append(active)
                      .append(",title=").append(titleStr)
                      .append(",rootPkg=").append(rootPkg)
                      .append(",rootNull=").append(root == null)
                      .append("]");

            if (winType == AccessibilityWindowInfo.TYPE_APPLICATION) {
                if (PKG_SETTINGS.equals(rootPkg) || PKG_SYSTEMUI.equals(rootPkg)) {
                    hasSettingsOrSystemUiAppWindow = true;
                } else if (focused || active) {
                    // Focused application window is neither com.android.settings nor com.android.systemui
                    hasForeignFocusedAppWindow = true;
                    foreignAppDesc = "win[title=" + titleStr + ",pkg=" + rootPkg + "]";
                }
            }

            if (root != null) {
                boolean isSameAsActiveRoot = (activeWindowRoot != null && activeWindowRoot.equals(root));
                if (isSameAsActiveRoot) {
                    activeRootMatchedWindow = true;
                }
                WindowCandidate candidate = new WindowCandidate(
                        root,
                        rootPkg,
                        titleStr,
                        win.getId(),
                        winType,
                        layer,
                        focused,
                        active,
                        isSameAsActiveRoot
                );
                if (PKG_SETTINGS.equals(rootPkg) && !candidate.texts.isEmpty()) {
                    settingsCandidates.add(candidate);
                } else if (PKG_SYSTEMUI.equals(rootPkg) && !candidate.texts.isEmpty()) {
                    systemUiCandidates.add(candidate);
                }
            }
        }

        String summaryStr = winSummary.toString();
        if (!summaryStr.equals(lastLoggedWindowSummary)) {
            lastLoggedWindowSummary = summaryStr;
            Log.d(TAG, "🪟 [" + source + "] Display0 Windows (" + display0Windows.size() + "): " +
                    (summaryStr.isEmpty() ? "none" : summaryStr));
        }

        // Check activeWindowRoot if not already matched
        if (activeWindowRoot != null && activeWindowRoot.getPackageName() != null) {
            String activePkg = activeWindowRoot.getPackageName().toString();
            if (!PKG_SETTINGS.equals(activePkg) && !PKG_SYSTEMUI.equals(activePkg)) {
                hasForeignFocusedAppWindow = true;
                foreignAppDesc = "activeRoot[pkg=" + activePkg + "]";
            } else if (!activeRootMatchedWindow) {
                WindowCandidate activeCandidate = new WindowCandidate(
                        activeWindowRoot,
                        activePkg,
                        "activeWindow",
                        -1,
                        AccessibilityWindowInfo.TYPE_APPLICATION,
                        Integer.MAX_VALUE,
                        true,
                        true,
                        true
                );
                if (!activeCandidate.texts.isEmpty()) {
                    if (PKG_SETTINGS.equals(activePkg)) {
                        settingsCandidates.add(0, activeCandidate);
                        hasSettingsOrSystemUiAppWindow = true;
                    } else if (PKG_SYSTEMUI.equals(activePkg)) {
                        systemUiCandidates.add(0, activeCandidate);
                    }
                }
            }
        }

        // Also inspect event.getSource() climbed to root ONLY if no foreign app window is focused
        if (!hasForeignFocusedAppWindow && event != null) {
            try {
                String evtPkg = (event.getPackageName() != null) ? event.getPackageName().toString() : "";
                if (PKG_MRSS.equals(evtPkg) || PKG_MIUI_HOME.equals(evtPkg)) {
                    hasForeignFocusedAppWindow = true;
                    foreignAppDesc = "event[pkg=" + evtPkg + "]";
                } else {
                    AccessibilityNodeInfo src = event.getSource();
                    if (src != null) {
                        AccessibilityNodeInfo top = climbToRoot(src);
                        String topPkg = (top.getPackageName() != null) ? top.getPackageName().toString() : evtPkg;
                        if (PKG_SETTINGS.equals(topPkg) && !containsRoot(settingsCandidates, top)) {
                            WindowCandidate evtCandidate = new WindowCandidate(
                                    top,
                                    topPkg,
                                    "eventSourceRoot",
                                    -2,
                                    AccessibilityWindowInfo.TYPE_APPLICATION,
                                    Integer.MAX_VALUE - 1,
                                    true,
                                    true,
                                    false
                            );
                            if (!evtCandidate.texts.isEmpty()) {
                                settingsCandidates.add(0, evtCandidate);
                                hasSettingsOrSystemUiAppWindow = true;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Check if MRSS itself is resumed in the foreground or if the user returned to Launcher after entering Settings
        boolean mrssForeground = MainActivity.isMainActivityForeground();
        if (mrssForeground) {
            hasForeignFocusedAppWindow = true;
            foreignAppDesc = "MRSS MainActivity (resumed)";
        } else if (!hasSettingsOrSystemUiAppWindow && hasEnteredSettingsThisSession && settingsCandidates.isEmpty()) {
            hasForeignFocusedAppWindow = true;
            if (foreignAppDesc.isEmpty()) {
                foreignAppDesc = "non-Settings foreground window";
            }
        }

        // Sort com.android.settings windows so the focused / active / highest-layer / dialog window comes FIRST
        Collections.sort(settingsCandidates, (a, b) -> {
            if (a.focused != b.focused) return a.focused ? -1 : 1;
            if (a.fromActiveRoot != b.fromActiveRoot) return a.fromActiveRoot ? -1 : 1;
            if (a.active != b.active) return a.active ? -1 : 1;
            boolean aIsDialog = !hasWirelessDebuggingMainScreenMarkers(a.texts) && hasPairingDialogMarkers(a.texts);
            boolean bIsDialog = !hasWirelessDebuggingMainScreenMarkers(b.texts) && hasPairingDialogMarkers(b.texts);
            if (aIsDialog != bIsDialog) return aIsDialog ? -1 : 1;
            if (a.layer != b.layer) return Integer.compare(b.layer, a.layer);
            return Integer.compare(b.windowId, a.windowId);
        });

        List<WindowCandidate> combined = new ArrayList<>(settingsCandidates.size() + systemUiCandidates.size());
        combined.addAll(settingsCandidates);
        combined.addAll(systemUiCandidates);

        boolean allowAction = hasSettingsOrSystemUiAppWindow && !hasForeignFocusedAppWindow && !mrssForeground;
        boolean leftSettings = mrssForeground || (hasForeignFocusedAppWindow && hasEnteredSettingsThisSession);

        return new WindowSnapshot(combined, allowAction, leftSettings, foreignAppDesc);
    }

    private static boolean containsRoot(List<WindowCandidate> list, AccessibilityNodeInfo target) {
        if (target == null) return false;
        for (WindowCandidate c : list) {
            if (target.equals(c.root)) {
                return true;
            }
        }
        return false;
    }

    private static AccessibilityNodeInfo climbToRoot(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo curr = node;
        int depth = 0;
        while (curr != null && depth < 64) {
            AccessibilityNodeInfo parent = null;
            try {
                parent = curr.getParent();
            } catch (Throwable ignored) {}
            if (parent == null) {
                return curr;
            }
            curr = parent;
            depth++;
        }
        return curr != null ? curr : node;
    }

    private void recordStateTransition(ScreenState newState, WindowCandidate win, String source) {
        if (currentScreenState != newState) {
            Log.d(TAG, "🔄 [" + source + "] State transition: " + currentScreenState + " -> " + newState +
                    " | win[id=" + (win != null ? win.windowId : -1) +
                    ",type=" + (win != null ? win.windowType : -1) +
                    ",layer=" + (win != null ? win.layer : -1) +
                    ",focused=" + (win != null && win.focused) +
                    ",active=" + (win != null && win.active) +
                    ",title=" + (win != null ? win.title : "") +
                    ",rootClass=" + (win != null ? win.rootClassName : "") +
                    ",pairingRowClicked=" + pairingRowClicked +
                    ",knownConnectPort=" + knownConnectPort +
                    "] | sampleTexts=" + formatSampleTexts(win != null ? win.texts : null, 8));
            currentScreenState = newState;
        }
    }

    private static String formatSampleTexts(List<String> texts, int maxItems) {
        if (texts == null || texts.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        int count = Math.min(texts.size(), maxItems);
        for (int i = 0; i < count; i++) {
            if (i > 0) sb.append(", ");
            sb.append('"').append(texts.get(i).replace("\n", " ").trim()).append('"');
        }
        if (texts.size() > maxItems) {
            sb.append(", ...+").append(texts.size() - maxItems).append(" more");
        }
        sb.append(']');
        return sb.toString();
    }

    private static boolean hasWirelessDebuggingMainScreenMarkers(List<String> windowTexts) {
        if (windowTexts == null || windowTexts.isEmpty()) return false;
        for (String raw : windowTexts) {
            if (raw == null) continue;
            String t = raw.trim();
            if (t.isEmpty()) continue;
            String low = t.toLowerCase();
            if (low.contains("pair device with qr code") ||
                t.contains("使用二维码配对设备") ||
                t.contains("使用二維碼配對設備") ||
                t.contains("使用二维码配对") ||
                t.contains("使用二維碼配對") ||
                low.contains("paired devices") ||
                t.contains("已配对的设备") ||
                t.contains("已配對的設備") ||
                low.equals("device name") ||
                t.equals("设备名称") ||
                t.equals("裝置名稱")) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPairingDialogMarkers(List<String> windowTexts) {
        if (windowTexts == null || windowTexts.isEmpty()) return false;
        boolean hasExplicitCodeLabel = false;
        boolean hasSixDigitCode = false;
        boolean hasCancelButton = false;

        for (String raw : windowTexts) {
            if (raw == null) continue;
            String t = raw.trim();
            if (t.isEmpty()) continue;
            String low = t.toLowerCase();

            if (low.contains("wi-fi pairing code") ||
                t.contains("WLAN 配对码") ||
                t.contains("WLAN配对码") ||
                t.contains("WLAN 调试配对码") ||
                t.contains("WLAN调试配对码") ||
                t.contains("无线调试配对码") ||
                t.contains("無線調試配對碼") ||
                t.contains("Wi-Fi 配對碼") ||
                t.contains("Wi-Fi配對碼") ||
                t.contains("WLAN 配對碼") ||
                t.contains("WLAN配對碼")) {
                hasExplicitCodeLabel = true;
            }

            if (SIX_DIGIT_CODE_PATTERN.matcher(t).matches() ||
                SPACED_SIX_DIGIT_CODE_PATTERN.matcher(t).matches()) {
                hasSixDigitCode = true;
            }

            if (low.equals("cancel") || t.equals("取消")) {
                hasCancelButton = true;
            }
        }

        return hasExplicitCodeLabel || (hasSixDigitCode && hasCancelButton);
    }

    /**
     * Step 1: Handles the "Allow wireless debugging on this network?" dialog (in SystemUI or Settings).
     */
    private boolean handleNetworkTrustDialog(List<WindowCandidate> candidates, String source) {
        for (WindowCandidate candidate : candidates) {
            AccessibilityNodeInfo root = candidate.root;
            AccessibilityNodeInfo alwaysAllowNode = findNodeByTextContains(root,
                    "始终允许在此网络", "始終允許在此網絡", "Always allow on this network",
                    "允许在此网络上进行无线调试", "Allow wireless debugging on this network");
            if (alwaysAllowNode == null) {
                continue;
            }

            recordStateTransition(ScreenState.NETWORK_TRUST_DIALOG, candidate, source);
            AccessibilityNodeInfo checkable = findCheckableNode(root);
            if (checkable != null && !checkable.isChecked()) {
                Log.d(TAG, "[" + source + "] Checking 'Always allow on this network' checkbox");
                clickNodeOrParent(checkable);
                return true;
            } else if (alwaysAllowNode.isCheckable() && !alwaysAllowNode.isChecked()) {
                Log.d(TAG, "[" + source + "] Checking 'Always allow on this network' node");
                clickNodeOrParent(alwaysAllowNode);
                return true;
            }

            AccessibilityNodeInfo allowBtn = findNodeByExactOrContainsText(root,
                    "允许", "允許", "Allow", "确定", "確定", "OK");
            if (allowBtn != null) {
                Log.d(TAG, "[" + source + "] Clicking 'Allow/OK' on network trust dialog");
                clickNodeOrParent(allowBtn);
                return true;
            }
        }
        return false;
    }

    /**
     * Step 2: Strictly gated 6-digit Pairing Code dialog detection & extraction.
     */
    private boolean handlePairingCodeDialog(List<WindowCandidate> candidates, String source) {
        WindowCandidate pairingDialogWindow = null;

        for (WindowCandidate candidate : candidates) {
            if (!PKG_SETTINGS.equals(candidate.packageName)) {
                continue;
            }
            if (hasWirelessDebuggingMainScreenMarkers(candidate.texts)) {
                continue;
            }
            if (hasPairingDialogMarkers(candidate.texts)) {
                pairingDialogWindow = candidate;
                break;
            }
        }

        if (pairingDialogWindow == null) {
            pairDialogFirstSeenMs = 0L;
            return false;
        }

        recordStateTransition(ScreenState.PAIRING_DIALOG, pairingDialogWindow, source);

        if (pairDialogFirstSeenMs == 0L) {
            pairDialogFirstSeenMs = System.currentTimeMillis();
        }

        String foundCode = null;
        int dialogPort = 0;

        for (String text : pairingDialogWindow.texts) {
            if (text == null) continue;
            String trimmed = text.trim();
            if (trimmed.isEmpty()) continue;

            Matcher ipMatcher = IP_PORT_PATTERN.matcher(trimmed);
            if (ipMatcher.find()) {
                try {
                    int parsedPort = Integer.parseInt(ipMatcher.group(1));
                    if (parsedPort > 0 && parsedPort <= 65535) {
                        if (parsedPort == knownConnectPort && knownConnectPort > 0) {
                            Log.w(TAG, "⚠️ Ignoring dialog IP:port " + parsedPort +
                                    " because it matches main-screen knownConnectPort");
                        } else {
                            dialogPort = parsedPort;
                        }
                    }
                } catch (NumberFormatException ignored) {}
                continue;
            }

            if (SIX_DIGIT_CODE_PATTERN.matcher(trimmed).matches()) {
                foundCode = trimmed;
            } else if (foundCode == null) {
                Matcher spacedMatcher = SPACED_SIX_DIGIT_CODE_PATTERN.matcher(trimmed);
                if (spacedMatcher.matches()) {
                    foundCode = spacedMatcher.group(1) + spacedMatcher.group(2);
                }
            }
        }

        if (foundCode == null) {
            for (String text : pairingDialogWindow.texts) {
                if (text == null) continue;
                String trimmed = text.trim();
                if (trimmed.isEmpty() || trimmed.contains(":") || trimmed.contains("：") || trimmed.contains(".")) {
                    continue;
                }
                Matcher m = EMBEDDED_SIX_DIGIT_PATTERN.matcher(trimmed);
                if (m.find()) {
                    foundCode = m.group(1);
                    break;
                }
            }
        }

        int mdnsPort = mdnsPairingPort.get();
        int effectivePort = 0;
        if (mdnsPort > 0 && mdnsPort != knownConnectPort) {
            effectivePort = mdnsPort;
        } else if (dialogPort > 0 && dialogPort != knownConnectPort) {
            effectivePort = dialogPort;
        }

        Log.d(TAG, "[" + source + "] STATE=PAIRING_DIALOG winId=" + pairingDialogWindow.windowId +
                " title=\"" + pairingDialogWindow.title + "\"" +
                " code=" + (foundCode != null ? foundCode : "null") +
                " effectivePort=" + effectivePort +
                " (dialogPort=" + dialogPort + ", mDNS=" + mdnsPort + ", knownConnectPort=" + knownConnectPort + ")");

        if (foundCode != null && effectivePort > 0) {
            String sig = effectivePort + ":" + foundCode;
            if (!sig.equals(lastAttemptedPairSignature)) {
                lastAttemptedPairSignature = sig;
                Log.d(TAG, "🎯 Extracted pairing code=" + foundCode + ", port=" + effectivePort +
                        ", knownConnectPortHint=" + knownConnectPort + " -> starting SPAKE2 pairing");
                executeAutomatedPairing(effectivePort, foundCode);
            }
            return true;
        }

        if (System.currentTimeMillis() - pairDialogFirstSeenMs > 2800L && foundCode == null) {
            Log.w(TAG, "⚠️ Pairing dialog open >2.8s but 6-digit code node is empty/redacted -> falling back to Notification RemoteInput");
            activateNotificationPairingFallback();
            return true;
        }

        return true;
    }

    /**
     * Step 3: Strictly separated WIRELESS_DEBUGGING_SCREEN handler.
     */
    private boolean handleWirelessDebuggingScreen(List<WindowCandidate> candidates, String source) {
        // Fix #3: Never click "Pair device with pairing code" again once pairing has succeeded or max attempts reached
        if (pairingSucceededThisSession.get() ||
                pairingAttemptsThisSession.get() >= MAX_PAIRING_ATTEMPTS_PER_SESSION) {
            return true;
        }

        long now = System.currentTimeMillis();

        for (WindowCandidate candidate : candidates) {
            if (!PKG_SETTINGS.equals(candidate.packageName) || candidate.texts.isEmpty()) {
                continue;
            }
            AccessibilityNodeInfo root = candidate.root;
            boolean hasMainMarkers = hasWirelessDebuggingMainScreenMarkers(candidate.texts);

            AccessibilityNodeInfo pairRow = findNodeByTextContains(root,
                    "使用配对码配对设备", "使用配對碼配對設備", "Pair device with pairing code");
            AccessibilityNodeInfo titleNode = findNodeByTextContains(root,
                    "无线调试", "無線調試", "Wireless debugging");
            AccessibilityNodeInfo devOptionsMarker = findNodeByTextContains(root,
                    "USB 调试", "USB調試", "USB 調試", "USB debugging",
                    "系统栈跟踪", "布局边界", "关闭开发者选项");

            if (!hasMainMarkers && pairRow == null && !(titleNode != null && devOptionsMarker == null)) {
                continue;
            }

            pairDialogFirstSeenMs = 0L;
            recordStateTransition(ScreenState.WIRELESS_DEBUGGING_SCREEN, candidate, source);

            // 1. Record any IP:port visible on the main Wireless Debugging screen as knownConnectPort
            int mainScreenPort = extractFirstIpPort(candidate.texts);
            if (mainScreenPort > 0 && mainScreenPort != knownConnectPort) {
                knownConnectPort = mainScreenPort;
                LocalAdbBackend.getInstance(getApplicationContext()).setDiscoveredConnectPort(knownConnectPort);
                Log.d(TAG, "📌 [" + source + "] Recorded main-screen knownConnectPort=" + knownConnectPort +
                        " (will be passed directly to pairAndConnect)");
            }

            // 2. Check if Wireless Debugging master switch is ON or OFF
            AccessibilityNodeInfo toggleSwitch = findCheckableNode(root);
            boolean isWirelessDebuggingOn = (mainScreenPort > 0) ||
                    (pairRow != null && pairRow.isEnabled()) ||
                    (toggleSwitch != null && toggleSwitch.isChecked());

            if (!isWirelessDebuggingOn && toggleSwitch != null && !toggleSwitch.isChecked()) {
                if (now - lastSwitchToggleTimeMs >= 1500L) {
                    lastSwitchToggleTimeMs = now;
                    boolean toggled = clickNodeOrParent(toggleSwitch);
                    if (!toggled && titleNode != null) {
                        toggled = clickNodeOrParent(titleNode);
                    }
                    Log.d(TAG, "🔛 [" + source + "] Toggled Wireless Debugging master switch ON (result=" + toggled + ")");
                }
                return true;
            }

            // 3. Once ON, locate "Pair device with pairing code" and click it (1s debounce + gesture tap fallback)
            if (pairRow != null && pairRow.isEnabled()) {
                if (now - lastPairDialogClickTimeMs >= 1000L) {
                    lastPairDialogClickTimeMs = now;
                    pairingRowClickAttempts++;

                    AccessibilityNodeInfo clickableTarget = findClickableSelfOrParent(pairRow);
                    Rect targetBounds = new Rect();
                    if (clickableTarget != null) {
                        clickableTarget.getBoundsInScreen(targetBounds);
                    }
                    if (targetBounds.isEmpty()) {
                        pairRow.getBoundsInScreen(targetBounds);
                    }

                    boolean clicked = clickNodeOrParent(pairRow);
                    pairingRowClicked = true;
                    Log.d(TAG, "👉 [" + source + "] STATE=WIRELESS_DEBUGGING_SCREEN -> ACTION_CLICK (#" +
                            pairingRowClickAttempts + ") on 'Pair device with pairing code' (result=" + clicked +
                            ", bounds=" + targetBounds.toShortString() +
                            ", knownConnectPort=" + knownConnectPort + ")");

                    if (!clicked || pairingRowClickAttempts >= 2) {
                        boolean tapped = dispatchTapGestureOnDisplay0(targetBounds,
                                "Pair device with pairing code (attempt #" + pairingRowClickAttempts + ")");
                        Log.d(TAG, "👆 [" + source + "] Fallback dispatchGesture tap on 'Pair device with pairing code' center=(" +
                                targetBounds.centerX() + "," + targetBounds.centerY() + ") dispatched=" + tapped);
                    }
                }
                return true;
            }

            if (isWirelessDebuggingOn && pairRow == null && now - lastScrollTimeMs >= 1000L) {
                lastScrollTimeMs = now;
                AccessibilityNodeInfo scrollable = findScrollableNode(root);
                if (scrollable != null) {
                    boolean scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
                    Log.d(TAG, "[" + source + "] STATE=WIRELESS_DEBUGGING_SCREEN -> scrolled for 'Pair device with pairing code' (result=" + scrolled + ")");
                }
            }

            return true;
        }
        return false;
    }

    private static int extractFirstIpPort(List<String> texts) {
        if (texts == null) return 0;
        for (String text : texts) {
            if (text == null) continue;
            Matcher m = IP_PORT_PATTERN.matcher(text.trim());
            if (m.find()) {
                try {
                    int port = Integer.parseInt(m.group(1));
                    if (port > 0 && port <= 65535) {
                        return port;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }

    /**
     * Step 4: On Developer Options screen, finds "Wireless debugging" / "无线调试" or scrolls down.
     * Fix #4: Never classifies or scrolls when settingsTexts is empty (settingsTexts == 0).
     */
    private void handleDeveloperOptionsScreen(
            List<WindowCandidate> candidates,
            String source,
            String pkgs,
            boolean hasSettingsWindow) {
        if (pairingSucceededThisSession.get()) {
            return;
        }

        List<String> settingsTexts = new ArrayList<>();
        WindowCandidate primarySettingsWindow = null;

        for (WindowCandidate candidate : candidates) {
            if (PKG_SETTINGS.equals(candidate.packageName) && !candidate.texts.isEmpty()) {
                if (primarySettingsWindow == null) {
                    primarySettingsWindow = candidate;
                }
                settingsTexts.addAll(candidate.texts);

                AccessibilityNodeInfo wirelessItem = findNodeByTextContains(candidate.root,
                        "无线调试", "無線調試", "Wireless debugging");
                if (wirelessItem != null) {
                    recordStateTransition(ScreenState.DEVELOPER_OPTIONS, candidate, source);
                    boolean clicked = clickNodeOrParent(wirelessItem);
                    Log.d(TAG, "[" + source + "] STATE=DEVELOPER_OPTIONS -> Found 'Wireless debugging'! Clicked=" + clicked);
                    return;
                }
            }
        }

        // Fix #4: Never classify an empty node list (settingsTexts == 0) as DEVELOPER_OPTIONS
        // and never swipe blindly when settingsTexts is empty!
        if (!hasSettingsWindow || primarySettingsWindow == null || settingsTexts.isEmpty()) {
            return;
        }

        recordStateTransition(ScreenState.DEVELOPER_OPTIONS, primarySettingsWindow, source);

        long now = System.currentTimeMillis();
        if (now - lastScrollTimeMs < 650L || scrollAttempts >= 25) {
            return;
        }

        String currentSignature = buildTextSignature(settingsTexts);
        boolean textUnchangedAfterNodeScroll = lastScrollUsedNodeAction &&
                !currentSignature.isEmpty() &&
                currentSignature.equals(lastScreenTextSignature);
        if (!currentSignature.isEmpty()) {
            lastScreenTextSignature = currentSignature;
        }

        AccessibilityNodeInfo scrollable = null;
        if (!textUnchangedAfterNodeScroll) {
            for (WindowCandidate candidate : candidates) {
                if (PKG_SETTINGS.equals(candidate.packageName)) {
                    scrollable = findScrollableNode(candidate.root);
                    if (scrollable != null) break;
                }
            }
        }

        lastScrollTimeMs = now;
        scrollAttempts++;

        if (scrollable != null) {
            boolean scrolled = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            Log.d(TAG, "[" + source + "] STATE=DEVELOPER_OPTIONS (pkgs=" + pkgs + ", settingsTexts=" + settingsTexts.size() +
                    ") -> ACTION_SCROLL_FORWARD (" + scrollAttempts + "/25) on " +
                    scrollable.getClassName() + " result=" + scrolled);
            if (scrolled) {
                lastScrollUsedNodeAction = true;
                return;
            }
        }

        lastScrollUsedNodeAction = false;
        boolean swiped = dispatchSwipeUpGestureOnDisplay0(scrollAttempts);
        Log.d(TAG, "[" + source + "] STATE=DEVELOPER_OPTIONS (pkgs=" + pkgs + ", settingsTexts=" + settingsTexts.size() +
                ", nodeScrollUnchanged=" + textUnchangedAfterNodeScroll +
                ") -> dispatchGesture upward swipe (" + scrollAttempts + "/25) dispatched=" + swiped);
    }

    private boolean dispatchTapGestureOnDisplay0(final Rect bounds, final String targetLabel) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || bounds == null || bounds.isEmpty()) {
            return false;
        }
        try {
            final float tapX = bounds.exactCenterX();
            final float tapY = bounds.exactCenterY();
            if (tapX <= 0f || tapY <= 0f) {
                return false;
            }

            Path tapPath = new Path();
            tapPath.moveTo(tapX, tapY);
            tapPath.lineTo(tapX, tapY);

            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(tapPath, 0L, 60L);
            GestureDescription.Builder builder = new GestureDescription.Builder()
                    .addStroke(stroke);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setDisplayId(Display.DEFAULT_DISPLAY);
            }

            return dispatchGesture(builder.build(), new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    Log.d(TAG, "✅ Gesture tap onCompleted for [" + targetLabel +
                            "] at (" + (int) tapX + "," + (int) tapY + ") on displayId=0");
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    Log.w(TAG, "❌ Gesture tap onCancelled for [" + targetLabel +
                            "] at (" + (int) tapX + "," + (int) tapY + ") on displayId=0");
                }
            }, mainHandler);
        } catch (Throwable t) {
            Log.w(TAG, "dispatchTapGestureOnDisplay0 failed: " + t.getMessage());
            return false;
        }
    }

    private boolean dispatchSwipeUpGestureOnDisplay0(final int attemptNum) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        try {
            int width = 0;
            int height = 0;

            DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display display0 = (displayManager != null)
                    ? displayManager.getDisplay(Display.DEFAULT_DISPLAY)
                    : null;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    Context dispCtx = (display0 != null) ? createDisplayContext(display0) : this;
                    WindowManager wm = (WindowManager) dispCtx.getSystemService(Context.WINDOW_SERVICE);
                    if (wm != null) {
                        Rect bounds = wm.getCurrentWindowMetrics().getBounds();
                        width = bounds.width();
                        height = bounds.height();
                    }
                } catch (Throwable ignored) {}
            }

            if ((width <= 0 || height <= 0) && display0 != null) {
                DisplayMetrics realMetrics = new DisplayMetrics();
                display0.getRealMetrics(realMetrics);
                width = realMetrics.widthPixels;
                height = realMetrics.heightPixels;
            }

            if (width <= 0 || height <= 0) {
                DisplayMetrics fallback = getResources().getDisplayMetrics();
                width = fallback.widthPixels;
                height = fallback.heightPixels;
            }

            final float centerX = width * 0.5f;
            final float startY = height * 0.72f;
            final float endY = height * 0.28f;

            Path swipePath = new Path();
            swipePath.moveTo(centerX, startY);
            swipePath.lineTo(centerX, endY);

            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(swipePath, 0L, 350L);
            GestureDescription.Builder builder = new GestureDescription.Builder()
                    .addStroke(stroke);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                builder.setDisplayId(Display.DEFAULT_DISPLAY);
            }
            GestureDescription gesture = builder.build();

            final int w = width;
            final int h = height;
            return dispatchGesture(gesture, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    Log.d(TAG, "✅ Gesture swipe #" + attemptNum + " onCompleted [displayId=0, metrics=" +
                            w + "x" + h + ", path=(" + (int) centerX + "," + (int) startY + ")->(" +
                            (int) centerX + "," + (int) endY + ")]");
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    Log.w(TAG, "❌ Gesture swipe #" + attemptNum + " onCancelled by system [displayId=0, metrics=" +
                            w + "x" + h + ", path=(" + (int) centerX + "," + (int) startY + ")->(" +
                            (int) centerX + "," + (int) endY + ")]");
                }
            }, mainHandler);
        } catch (Throwable t) {
            Log.w(TAG, "dispatchSwipeUpGestureOnDisplay0 failed: " + t.getMessage());
            return false;
        }
    }

    private static String buildTextSignature(List<String> texts) {
        if (texts == null || texts.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(texts.size(), 15);
        for (int i = 0; i < limit; i++) {
            sb.append(texts.get(i)).append('|');
        }
        return sb.toString();
    }

    /**
     * Fix #1 & Fix #3:
     * - Passes knownConnectPort directly into pairAndConnect(pairingPort, pairingCode, connectPortHint).
     * - Marks pairingSucceededThisSession = true and caps attempts at MAX_PAIRING_ATTEMPTS_PER_SESSION (2)
     *   so "Pair device with pairing code" is never clicked in an infinite loop.
     * - If pairAndConnect returns false, immediately disarms, stops the ticker, calls disableSelf(),
     *   and returns to MRSS with a clear status Toast instead of looping on Settings.
     */
    private void executeAutomatedPairing(final int pairingPort, final String pairingCode) {
        if (pairingSucceededThisSession.get()) {
            return;
        }
        int attemptNum = pairingAttemptsThisSession.incrementAndGet();
        if (attemptNum > MAX_PAIRING_ATTEMPTS_PER_SESSION) {
            Log.w(TAG, "🛑 Reached max pairing attempts (" + MAX_PAIRING_ATTEMPTS_PER_SESSION +
                    ") for this session -> disarming and returning to MRSS");
            finishAndDisableSelf();
            return;
        }
        if (!pairingInFlight.compareAndSet(false, true)) {
            return;
        }

        final int connectPortHint = this.knownConnectPort;
        mainHandler.post(() -> Toast.makeText(
                getApplicationContext(),
                isChinese() ? ("⏳ 正在自动配对 (" + pairingCode + ")...") : ("⏳ Auto-pairing (" + pairingCode + ")..."),
                Toast.LENGTH_SHORT
        ).show());

        new Thread(() -> {
            try {
                Context appCtx = getApplicationContext();
                TaskServiceManager manager = TaskServiceManager.getInstance(appCtx);
                manager.setActiveBackendMode(TaskServiceManager.BACKEND_LOCAL_ADB);

                LocalAdbBackend backend = LocalAdbBackend.getInstance(appCtx);
                boolean ok = backend.pairAndConnect(pairingPort, pairingCode, connectPortHint);
                Log.d(TAG, "🔐 pairAndConnect(pairingPort=" + pairingPort + ", code=" + pairingCode +
                        ", connectPortHint=" + connectPortHint + ") completed -> ok=" + ok);

                // Never re-click "Pair device with pairing code" after pairAndConnect finishes
                pairingSucceededThisSession.set(true);
                manager.refreshBackendConnection();

                if (ok) {
                    mainHandler.post(() -> {
                        Toast.makeText(
                                appCtx,
                                isChinese()
                                        ? "✅ 内置 ADB 自动激活完成！无障碍服务已自动关闭"
                                        : "✅ Local ADB Auto-Activated! Accessibility service disabled.",
                                Toast.LENGTH_LONG
                        ).show();
                        finishAndDisableSelf();
                    });
                } else {
                    mainHandler.post(() -> {
                        Toast.makeText(
                                appCtx,
                                isChinese()
                                        ? "⚠️ 配对已完成但守护进程连接超时，已关闭无障碍并返回应用"
                                        : "⚠️ Pairing finished but daemon connection timed out. Returning to MRSS.",
                                Toast.LENGTH_LONG
                        ).show();
                        finishAndDisableSelf();
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Auto-pairing error", e);
                pairingSucceededThisSession.set(true);
                mainHandler.post(() -> {
                    Toast.makeText(
                            getApplicationContext(),
                            isChinese()
                                    ? ("⚠️ 自动配对异常: " + e.getMessage())
                                    : ("⚠️ Auto-pair error: " + e.getMessage()),
                            Toast.LENGTH_LONG
                    ).show();
                    finishAndDisableSelf();
                });
            }
        }, "MRSS-AutoPairWorker").start();
    }

    private void activateNotificationPairingFallback() {
        armAutoPairSession(this, false);
        mainHandler.removeCallbacks(stepTickerRunnable);
        tickerRunning.set(false);
        try {
            Intent svcIntent = new Intent(this, LocalAdbPairingService.class);
            svcIntent.setAction(LocalAdbPairingService.ACTION_START_PAIRING);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svcIntent);
            } else {
                startService(svcIntent);
            }
            Toast.makeText(
                    this,
                    isChinese()
                            ? "系统隐藏了配对码控件，已为您开启下拉通知栏快捷配对输入"
                            : "System redacted pairing dialog; switched to Notification Pairing banner.",
                    Toast.LENGTH_LONG
            ).show();
        } catch (Throwable ignored) {}
        stopPairingMdnsDiscovery();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            disableSelf();
        }
    }

    private void disarmAndStopTickerOnly() {
        armAutoPairSession(this, false);
        mainHandler.removeCallbacks(stepTickerRunnable);
        tickerRunning.set(false);
        stopPairingMdnsDiscovery();
    }

    private void finishAndDisableSelf() {
        disarmAndStopTickerOnly();
        try {
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                                Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(mainIntent);
        } catch (Throwable ignored) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                disableSelf();
                Log.d(TAG, "🔒 LocalAdbAutoPairAccessibilityService disabled itself via disableSelf()");
            } catch (Throwable ignored) {}
        }
    }

    // =========================================================================
    // Node Tree Helpers & mDNS Pairing Port Discovery
    // =========================================================================

    private static void collectAllTexts(AccessibilityNodeInfo node, List<String> out) {
        if (node == null) return;
        CharSequence t = node.getText();
        if (t != null && t.length() > 0) {
            out.add(t.toString());
        }
        CharSequence cd = node.getContentDescription();
        if (cd != null && cd.length() > 0) {
            out.add(cd.toString());
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            collectAllTexts(node.getChild(i), out);
        }
    }

    private static AccessibilityNodeInfo findNodeByTextContains(AccessibilityNodeInfo node, String... needles) {
        if (node == null) return null;
        CharSequence textSeq = node.getText();
        CharSequence descSeq = node.getContentDescription();
        String text = textSeq != null ? textSeq.toString().toLowerCase() : "";
        String desc = descSeq != null ? descSeq.toString().toLowerCase() : "";

        for (String needle : needles) {
            String lowNeedle = needle.toLowerCase();
            if (text.contains(lowNeedle) || desc.contains(lowNeedle)) {
                return node;
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo found = findNodeByTextContains(node.getChild(i), needles);
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo findNodeByExactOrContainsText(AccessibilityNodeInfo node, String... labels) {
        if (node == null) return null;
        CharSequence textSeq = node.getText();
        String text = textSeq != null ? textSeq.toString().trim() : "";
        if (!text.isEmpty()) {
            for (String label : labels) {
                if (text.equalsIgnoreCase(label)) {
                    return node;
                }
            }
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo found = findNodeByExactOrContainsText(node.getChild(i), labels);
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo findCheckableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isCheckable()) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo found = findCheckableNode(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo findScrollableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) {
            return node;
        }
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo found = findScrollableNode(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private static AccessibilityNodeInfo findClickableSelfOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo curr = node;
        int depth = 0;
        while (curr != null && depth < 6) {
            if (curr.isClickable()) {
                return curr;
            }
            curr = curr.getParent();
            depth++;
        }
        return node;
    }

    private static boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo curr = node;
        int depth = 0;
        while (curr != null && depth < 6) {
            if (curr.isClickable()) {
                return curr.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            curr = curr.getParent();
            depth++;
        }
        return node != null && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private void startPairingMdnsDiscovery() {
        if (!nsdListening.compareAndSet(false, true)) {
            return;
        }
        try {
            nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
            if (nsdManager == null) {
                nsdListening.set(false);
                return;
            }
            pairingListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    nsdListening.set(false);
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                    nsdListening.set(false);
                }

                @Override
                public void onDiscoveryStarted(String serviceType) {}

                @Override
                public void onDiscoveryStopped(String serviceType) {
                    nsdListening.set(false);
                }

                @Override
                public void onServiceFound(NsdServiceInfo serviceInfo) {
                    nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(NsdServiceInfo info, int errorCode) {}

                        @Override
                        public void onServiceResolved(NsdServiceInfo resolvedInfo) {
                            int port = resolvedInfo.getPort();
                            if (port > 0) {
                                mdnsPairingPort.set(port);
                                Log.d(TAG, "📡 AutoPair NSD resolved _adb-tls-pairing._tcp on port " + port);
                            }
                        }
                    });
                }

                @Override
                public void onServiceLost(NsdServiceInfo serviceInfo) {}
            };
            nsdManager.discoverServices(NSD_PAIRING_TYPE, NsdManager.PROTOCOL_DNS_SD, pairingListener);
        } catch (Throwable t) {
            nsdListening.set(false);
        }
    }

    private void stopPairingMdnsDiscovery() {
        if (nsdManager != null && nsdListening.compareAndSet(true, false) && pairingListener != null) {
            try {
                nsdManager.stopServiceDiscovery(pairingListener);
            } catch (Throwable ignored) {}
        }
    }

    private boolean isChinese() {
        try {
            String lang = getResources().getConfiguration().locale.getLanguage();
            return "zh".equalsIgnoreCase(lang);
        } catch (Throwable t) {
            return true;
        }
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(stepTickerRunnable);
        tickerRunning.set(false);
        stopPairingMdnsDiscovery();
        super.onDestroy();
    }
}

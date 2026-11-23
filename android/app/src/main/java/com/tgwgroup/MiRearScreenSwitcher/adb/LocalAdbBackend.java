package com.tgwgroup.MiRearScreenSwitcher.adb;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import com.tgwgroup.MiRearScreenSwitcher.ITaskService;
import com.tgwgroup.MiRearScreenSwitcher.TaskService;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLProtocolException;

/**
 * Core Local ADB privileged execution backend for MRSS.
 *
 * Hardened Architecture:
 * 1. Direct Connect Port Hint Handoff: pairAndConnect(pairingPort, pairingCode, knownConnectPortHint)
 *    connects immediately to 127.0.0.1:knownConnectPortHint right after SPAKE2 pairing succeeds,
 *    bypassing any NsdManager mDNS delay.
 * 2. NsdManager Stop-Before-Start Synchronization: Waits up to 1,000ms on a CountDownLatch for
 *    onDiscoveryStopped / onStopDiscoveryFailed before starting a new DiscoveryListener, and cycles
 *    adb_wifi_enabled (0 -> 500ms -> 1) if already 1 and no mDNS port resolves within 3,000ms.
 * 3. In-Shell MRSS_DAEMON_READY Wait: Spawns MrssShellDaemon with setsid + trap '' HUP + </dev/null
 *    and polls up to 3 seconds inside the outer shell until MRSS_DAEMON_READY appears in
 *    /data/local/tmp/mrss_daemon.log before closing the PTY.
 * 4. Strict Bootstrap Gate: While bootstrapInProgress is true (or MrssShellDaemon is not active),
 *    isReadyFast() returns false and execute() fails fast immediately without touching AdbConnection.
 */
public class LocalAdbBackend implements TaskService.ShellExecutor {
    private static final String TAG = "LocalAdbBackend";
    private static final String PREFS_NAME = "mrss_settings";
    public static final String PREF_LAST_CONNECT_PORT = "local_adb_last_connect_port";
    public static final String PREF_LAST_PAIRING_PORT = "local_adb_last_pairing_port";
    public static final String PREF_DAEMON_TOKEN = "local_adb_daemon_token";
    public static final String PREF_EVER_PAIRED = "local_adb_ever_paired";

    public static final String STATE_READY = "READY";
    public static final String STATE_WAITING_FOR_WIFI = "WAITING_FOR_WIFI";
    public static final String STATE_NEEDS_PAIRING = "NEEDS_PAIRING";
    public static final String STATE_ERROR = "ERROR";

    public static final String PERM_WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS";
    public static final String PERM_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";
    public static final String PERM_USE_LOOPBACK_INTERFACE = "android.permission.USE_LOOPBACK_INTERFACE";
    private static final String SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled";
    public static final String DAEMON_LOG_PATH = "/data/local/tmp/mrss_daemon.log";

    private static final String NSD_CONNECT_TYPE = "_adb-tls-connect._tcp.";
    private static final int CLASSIC_ADB_PORT = 5555;
    private static final long AUTO_CONNECT_COOLDOWN_MS = 10_000L;
    private static final long ZERO_TOUCH_RECOVERY_COOLDOWN_MS = 15_000L;
    private static final long DAEMON_PING_FAIL_CACHE_MS = 2_000L;
    private static final long DAEMON_PING_SUCCESS_CACHE_MS = 2_500L;

    private static volatile LocalAdbBackend instance;

    private final Context appContext;
    private final SharedPreferences prefs;
    private final AdbKeyManager keyManager;
    private final ITaskService localTaskService;

    private final ExecutorService ioPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "MRSS-LocalAdbIO");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService connectExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MRSS-LocalAdbConnect");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService wakeupWorker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MRSS-LocalAdbWakeup");
        t.setDaemon(true);
        return t;
    });

    private final AtomicBoolean wakeupInFlight = new AtomicBoolean(false);
    private final AtomicBoolean connectInProgress = new AtomicBoolean(false);
    private final AtomicBoolean recoveryInProgress = new AtomicBoolean(false);
    private final AtomicBoolean certificateRejectedCircuitBreaker = new AtomicBoolean(false);
    private final AtomicBoolean bootstrapInProgress = new AtomicBoolean(false);

    private volatile long lastAutoConnectAttemptMs = 0L;
    private volatile long lastFailedRecoveryMs = 0L;
    private volatile long lastDaemonPingFailMs = 0L;
    private volatile long lastDaemonPingSuccessMs = 0L;

    private volatile AdbConnection activeAdbConnection;
    private final AtomicInteger discoveredConnectPort = new AtomicInteger(0);
    private final AtomicBoolean nsdDiscovering = new AtomicBoolean(false);
    private final Object nsdLock = new Object();
    private NsdManager.DiscoveryListener connectDiscoveryListener;
    private volatile CountDownLatch freshConnectPortLatch = new CountDownLatch(1);
    private volatile CountDownLatch nsdStopLatch = null;

    private volatile String lastStatusDetail = "Idle";
    private volatile String currentStateCode = STATE_NEEDS_PAIRING;
    private volatile boolean daemonActive = false;
    private volatile Boolean lastBroadcastReady = null;
    private volatile String lastBroadcastStateCode = null;

    public interface StatusListener {
        void onLocalAdbStatusChanged(boolean ready, String detail);
    }

    private final List<StatusListener> statusListeners = new ArrayList<>();

    private LocalAdbBackend(Context context) {
        this.appContext = context.getApplicationContext();
        this.prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        this.keyManager = AdbKeyManager.getInstance(appContext);
        this.localTaskService = new TaskService(this);

        int savedPort = prefs.getInt(PREF_LAST_CONNECT_PORT, 0);
        if (savedPort > 0) {
            discoveredConnectPort.set(savedPort);
        }

        startBackgroundMdnsDiscovery();
        registerNetworkCallback();

        // Check daemon once on startup in background without blocking
        connectExecutor.execute(() -> {
            if (pingDaemonInternal(false)) {
                markDaemonReadyAndBroadcast("Resident Shell Daemon Active (UID 2000)");
            } else if (canAttemptBackgroundAutoConnect()) {
                tryAutoConnectSingleFlight(false, 2000);
            }
        });
    }

    public static LocalAdbBackend getInstance(Context context) {
        if (instance == null) {
            synchronized (LocalAdbBackend.class) {
                if (instance == null) {
                    instance = new LocalAdbBackend(context);
                }
            }
        }
        return instance;
    }

    public ITaskService getTaskService() {
        return localTaskService;
    }

    /**
     * Strictly gated readiness check.
     * While bootstrapInProgress is true (pairing, connecting, spawning MrssShellDaemon, or running
     * post-spawn pm grant), isReadyFast() MUST return false. Only returns true when MrssShellDaemon
     * has completed bootstrap and passed pingDaemonInternal().
     */
    public boolean isReadyFast() {
        if (bootstrapInProgress.get()) {
            return false;
        }
        return daemonActive;
    }

    public boolean isBootstrapInProgress() {
        return bootstrapInProgress.get();
    }

    public void addStatusListener(StatusListener listener) {
        synchronized (statusListeners) {
            if (!statusListeners.contains(listener)) {
                statusListeners.add(listener);
            }
        }
    }

    public void removeStatusListener(StatusListener listener) {
        synchronized (statusListeners) {
            statusListeners.remove(listener);
        }
    }

    /**
     * Immediately clears any negative ping cache, marks the resident daemon active, transitions
     * to STATE_READY, dismisses any lingering LocalAdbPairingService notification, and broadcasts
     * status to TaskServiceManager / MainActivity / Flutter UI.
     */
    private void markDaemonReadyAndBroadcast(String detail) {
        this.lastDaemonPingFailMs = 0L;
        this.lastDaemonPingSuccessMs = System.currentTimeMillis();
        this.daemonActive = true;
        dismissPairingNotificationServiceQuietly();
        notifyStatusChanged(true, STATE_READY, detail != null ? detail : "Resident Shell Daemon Active (UID 2000)");
    }

    private void dismissPairingNotificationServiceQuietly() {
        try {
            Intent stopBroadcast = new Intent(LocalAdbPairingService.ACTION_STOP_PAIRING);
            stopBroadcast.setPackage(appContext.getPackageName());
            appContext.sendBroadcast(stopBroadcast);
        } catch (Throwable ignored) {}
        try {
            Intent svcIntent = new Intent(appContext, LocalAdbPairingService.class);
            appContext.stopService(svcIntent);
        } catch (Throwable ignored) {}
    }

    private void notifyStatusChanged(boolean ready, String stateCode, String detail) {
        this.currentStateCode = stateCode;
        this.lastStatusDetail = detail;

        // Deduplicate identical status broadcasts to prevent UI refresh storms
        Boolean prevReady = this.lastBroadcastReady;
        String prevCode = this.lastBroadcastStateCode;
        if (prevReady != null && prevReady == ready && Objects.equals(prevCode, stateCode)) {
            return;
        }
        this.lastBroadcastReady = ready;
        this.lastBroadcastStateCode = stateCode;

        List<StatusListener> copy;
        synchronized (statusListeners) {
            copy = new ArrayList<>(statusListeners);
        }
        for (StatusListener l : copy) {
            try {
                l.onLocalAdbStatusChanged(ready, detail);
            } catch (Throwable ignored) {}
        }
    }

    public boolean hasEverPaired() {
        return prefs.getBoolean(PREF_EVER_PAIRED, false) && keyManager.hasPersistedKeys();
    }

    public boolean isCertificateCircuitTripped() {
        return certificateRejectedCircuitBreaker.get();
    }

    private boolean canAttemptBackgroundAutoConnect() {
        if (bootstrapInProgress.get()) {
            return false;
        }
        if (!hasEverPaired()) {
            return false;
        }
        if (certificateRejectedCircuitBreaker.get()) {
            return false;
        }
        if (LocalAdbAutoPairAccessibilityService.isSessionArmed()) {
            return false;
        }
        long now = System.currentTimeMillis();
        return (now - lastAutoConnectAttemptMs) >= AUTO_CONNECT_COOLDOWN_MS;
    }

    public boolean hasWriteSecureSettingsPermission() {
        try {
            return appContext.checkSelfPermission(PERM_WRITE_SECURE_SETTINGS)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isWifiConnected() {
        try {
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network[] networks = cm.getAllNetworks();
            if (networks != null) {
                for (Network net : networks) {
                    NetworkCapabilities caps = cm.getNetworkCapabilities(net);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return true;
                    }
                }
            }
            Network active = cm.getActiveNetwork();
            if (active != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(active);
                return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private String getCurrentApkSignature() {
        try {
            String src = appContext.getApplicationInfo().sourceDir;
            long mod = new File(src).lastModified();
            return src + "@" + mod;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    public String getDaemonToken() {
        String token = prefs.getString(PREF_DAEMON_TOKEN, null);
        if (token == null || token.length() < 16) {
            byte[] bytes = new byte[24];
            new SecureRandom().nextBytes(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) {
                sb.append(String.format("%02x", b & 0xFF));
            }
            token = sb.toString();
            prefs.edit().putString(PREF_DAEMON_TOKEN, token).apply();
        }
        return token;
    }

    public int getDiscoveredConnectPort() {
        return discoveredConnectPort.get();
    }

    public void setDiscoveredConnectPort(int port) {
        if (port > 0) {
            discoveredConnectPort.set(port);
            prefs.edit().putInt(PREF_LAST_CONNECT_PORT, port).apply();
            CountDownLatch latch = freshConnectPortLatch;
            if (latch != null) {
                latch.countDown();
            }
        }
    }

    private void clearDiscoveredConnectPort() {
        discoveredConnectPort.set(0);
        prefs.edit().remove(PREF_LAST_CONNECT_PORT).apply();
    }

    private void discardRefusedConnectPort(int refusedPort) {
        if (discoveredConnectPort.compareAndSet(refusedPort, 0)) {
            Log.w(TAG, "🗑️ Discarded refused connect port " + refusedPort + " (waiting for fresh mDNS resolution)");
        }
        if (prefs.getInt(PREF_LAST_CONNECT_PORT, 0) == refusedPort) {
            prefs.edit().remove(PREF_LAST_CONNECT_PORT).apply();
        }
        freshConnectPortLatch = new CountDownLatch(1);
    }

    public Map<String, Object> getDetailedStateMap() {
        boolean bootstrapping = bootstrapInProgress.get();
        boolean daemonOk = !bootstrapping && pingDaemonSafe();
        boolean ready = daemonOk;
        boolean everPaired = hasEverPaired();
        boolean writeSecure = hasWriteSecureSettingsPermission();
        boolean wifi = isWifiConnected();

        String stateCode;
        if (ready) {
            stateCode = STATE_READY;
        } else if (certificateRejectedCircuitBreaker.get() || !everPaired) {
            stateCode = STATE_NEEDS_PAIRING;
        } else if (writeSecure && !wifi) {
            stateCode = STATE_WAITING_FOR_WIFI;
        } else {
            stateCode = currentStateCode != null ? currentStateCode : STATE_NEEDS_PAIRING;
        }

        Map<String, Object> map = new HashMap<>();
        map.put("ready", ready);
        map.put("stateCode", stateCode);
        map.put("daemonActive", daemonOk);
        map.put("everPaired", everPaired);
        map.put("hasWriteSecureSettings", writeSecure);
        map.put("wifiConnected", wifi);
        map.put("detail", lastStatusDetail);
        return map;
    }

    public String getStatusSummary() {
        boolean bootstrapping = bootstrapInProgress.get();
        boolean daemonOk = !bootstrapping && pingDaemonSafe();
        int port = activeAdbConnection != null ? activeAdbConnection.getPort() : discoveredConnectPort.get();
        return "Backend: Local ADB\n" +
               "Ready: " + daemonOk + (bootstrapping ? " (Bootstrapping...)" : "") + "\n" +
               "State: " + currentStateCode + "\n" +
               "Resident Daemon (UID 2000): " + (daemonOk ? "Active (Wi-Fi Independent)" : "Inactive") + "\n" +
               "Paired: " + hasEverPaired() + " (CircuitBreaker=" + certificateRejectedCircuitBreaker.get() + ")\n" +
               "Zero-Touch Recovery (WRITE_SECURE_SETTINGS): " + hasWriteSecureSettingsPermission() + "\n" +
               "Wi-Fi Transport: " + (isWifiConnected() ? "Connected" : "Offline") + "\n" +
               "Discovered TLS Port: " + (port > 0 ? port : "None") + "\n" +
               "Detail: " + lastStatusDetail;
    }

    /**
     * Non-blocking readiness check. Returns true ONLY when MrssShellDaemon is verified active and
     * bootstrap is not in progress.
     */
    public boolean checkReady() {
        if (bootstrapInProgress.get()) {
            return false;
        }
        return runOnIoThread(() -> {
            if (bootstrapInProgress.get()) {
                return false;
            }
            if (pingDaemonInternal(false)) {
                markDaemonReadyAndBroadcast("Resident Shell Daemon Active (UID 2000)");
                return true;
            }
            daemonActive = false;
            if (canAttemptBackgroundAutoConnect()) {
                connectExecutor.execute(() -> {
                    if (!tryZeroTouchRecoveryInternal(8000)) {
                        tryAutoConnectSingleFlight(false, 1800);
                    }
                });
            }
            return false;
        }, false, 800);
    }

    public boolean pingDaemonSafe() {
        if (bootstrapInProgress.get()) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (daemonActive && (now - lastDaemonPingSuccessMs) < DAEMON_PING_SUCCESS_CACHE_MS) {
            return true;
        }
        if (!daemonActive && (now - lastDaemonPingFailMs) < DAEMON_PING_FAIL_CACHE_MS) {
            return false;
        }
        return runOnIoThread(() -> pingDaemonInternal(false), daemonActive, 600);
    }

    private boolean pingDaemonInternal() {
        return pingDaemonInternal(true);
    }

    private boolean pingDaemonInternal(boolean logFailure) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", MrssShellDaemon.DEFAULT_PORT), 250);
            s.setTcpNoDelay(true);
            s.setSoTimeout(800);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());

            out.writeUTF(getDaemonToken());
            out.flush();
            if (!in.readBoolean()) {
                if (logFailure) {
                    Log.w(TAG, "pingDaemonInternal(127.0.0.1:" + MrssShellDaemon.DEFAULT_PORT +
                            ") failed: token authentication rejected by daemon");
                }
                lastDaemonPingFailMs = System.currentTimeMillis();
                daemonActive = false;
                return false;
            }
            out.writeByte(MrssShellDaemon.CMD_GET_INFO);
            out.flush();
            int ver = in.readInt();
            String daemonApkSig = MrssShellDaemon.readString(in);
            if (ver != MrssShellDaemon.PROTOCOL_VERSION) {
                if (logFailure) {
                    Log.w(TAG, "pingDaemonInternal(127.0.0.1:" + MrssShellDaemon.DEFAULT_PORT +
                            ") failed: protocol version mismatch (" + ver + " != " + MrssShellDaemon.PROTOCOL_VERSION + ")");
                }
                lastDaemonPingFailMs = System.currentTimeMillis();
                daemonActive = false;
                return false;
            }

            String expectedSig = getCurrentApkSignature();
            if (daemonApkSig != null && !daemonApkSig.isEmpty() && !expectedSig.equals(daemonApkSig)) {
                Log.d(TAG, "🔄 Detected updated APK signature (" + daemonApkSig + " -> " + expectedSig + "), triggering offline-safe self-upgrade...");
                if (upgradeDaemonInPlace(appContext.getApplicationInfo().sourceDir, expectedSig)) {
                    lastDaemonPingFailMs = 0L;
                    lastDaemonPingSuccessMs = System.currentTimeMillis();
                    daemonActive = true;
                    return true;
                }
            }

            lastDaemonPingFailMs = 0L;
            lastDaemonPingSuccessMs = System.currentTimeMillis();
            daemonActive = true;
            return true;
        } catch (Throwable t) {
            if (logFailure) {
                Log.d(TAG, "pingDaemonInternal(127.0.0.1:" + MrssShellDaemon.DEFAULT_PORT + ") failed: " +
                        t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            lastDaemonPingFailMs = System.currentTimeMillis();
            daemonActive = false;
            return false;
        }
    }

    private boolean upgradeDaemonInPlace(String newApkPath, String newApkSig) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", MrssShellDaemon.DEFAULT_PORT), 300);
            s.setTcpNoDelay(true);
            s.setSoTimeout(1500);
            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());

            out.writeUTF(getDaemonToken());
            out.flush();
            if (!in.readBoolean()) {
                return false;
            }
            out.writeByte(MrssShellDaemon.CMD_UPGRADE);
            MrssShellDaemon.writeString(out, newApkPath);
            MrssShellDaemon.writeString(out, newApkSig);
            out.flush();
            in.readInt();
        } catch (Throwable t) {
            Log.w(TAG, "In-place daemon upgrade request failed: " + t.getMessage());
            return true;
        }

        for (int i = 0; i < 15; i++) {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {}
            try (Socket check = new Socket()) {
                check.connect(new InetSocketAddress("127.0.0.1", MrssShellDaemon.DEFAULT_PORT), 200);
                DataInputStream in = new DataInputStream(check.getInputStream());
                DataOutputStream out = new DataOutputStream(check.getOutputStream());
                out.writeUTF(getDaemonToken());
                out.flush();
                if (in.readBoolean()) {
                    out.writeByte(MrssShellDaemon.CMD_PING);
                    out.flush();
                    if (in.readInt() == MrssShellDaemon.PROTOCOL_VERSION) {
                        Log.d(TAG, "✅ Offline-safe MrssShellDaemon self-upgrade completed!");
                        return true;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    /**
     * Schedules Zero-Touch Reboot / OTA Recovery on the dedicated single-flight connectExecutor.
     */
    public void scheduleZeroTouchRecovery(final String reason) {
        if (LocalAdbAutoPairAccessibilityService.isSessionArmed() || bootstrapInProgress.get()) {
            return;
        }
        connectExecutor.execute(() -> {
            Log.d(TAG, "🚀 Running Zero-Touch Recovery (reason=" + reason + ")");
            tryZeroTouchRecoveryInternal(8000);
        });
    }

    /**
     * Fix #2: Zero-Touch Recovery with Dynamic Port Discovery & Stop-Before-Start NSD Synchronization:
     * 1. Enforces a minimum 15,000ms cooldown after a failed attempt so adb_wifi_enabled never toggle-storms.
     * 2. Immediately clears stale discoveredConnectPort BEFORE setting adb_wifi_enabled=1.
     * 3. Stops any active NsdManager listener (awaiting onDiscoveryStopped up to 1,000ms) and starts a
     *    fresh _adb-tls-connect._tcp. discovery session, waiting via CountDownLatch (up to 8,000ms).
     * 4. If adb_wifi_enabled was already 1 and no _adb-tls-connect._tcp port resolves within 3,000ms,
     *    cycles adb_wifi_enabled (0 -> sleep 500ms -> 1) once so adbd actively re-broadcasts its mDNS record.
     * 5. Keeps adb_wifi_enabled=1 until MrssShellDaemon passes pingDaemon(), then resets adb_wifi_enabled=0.
     */
    private boolean tryZeroTouchRecoveryInternal(int maxWaitMs) {
        if (LocalAdbAutoPairAccessibilityService.isSessionArmed() ||
                certificateRejectedCircuitBreaker.get() ||
                bootstrapInProgress.get()) {
            return false;
        }
        if (pingDaemonInternal(false)) {
            markDaemonReadyAndBroadcast("Resident Shell Daemon Active (UID 2000)");
            return true;
        }
        if (!hasEverPaired() || !hasWriteSecureSettingsPermission()) {
            return false;
        }
        if (!isWifiConnected()) {
            notifyStatusChanged(false, STATE_WAITING_FOR_WIFI,
                    "Waiting for Wi-Fi to auto-recover Local ADB daemon...");
            return false;
        }
        long now = System.currentTimeMillis();
        long elapsedSinceFail = now - lastFailedRecoveryMs;
        if (lastFailedRecoveryMs > 0L && elapsedSinceFail < ZERO_TOUCH_RECOVERY_COOLDOWN_MS) {
            Log.d(TAG, "⏳ Zero-Touch Recovery in 15s cooldown (" +
                    ((ZERO_TOUCH_RECOVERY_COOLDOWN_MS - elapsedSinceFail) / 1000) + "s remaining)");
            return false;
        }
        if (!recoveryInProgress.compareAndSet(false, true)) {
            return false;
        }

        ContentResolver cr = appContext.getContentResolver();
        boolean weEnabledWifiAdb = false;
        boolean cycledAlreadyOnWifiAdb = false;
        boolean recovered = false;
        try {
            // 1. Clear stale cached connect port BEFORE setting adb_wifi_enabled=1
            clearDiscoveredConnectPort();

            // 2. Stop any active NsdManager discovery listener (awaiting onDiscoveryStopped) and start fresh discovery
            CountDownLatch portLatch = restartFreshMdnsConnectDiscovery();

            int currentWifiAdb = 0;
            try {
                currentWifiAdb = Settings.Global.getInt(cr, SETTING_ADB_WIFI_ENABLED, 0);
            } catch (Throwable ignored) {}

            if (currentWifiAdb == 0) {
                Settings.Global.putInt(cr, SETTING_ADB_WIFI_ENABLED, 1);
                weEnabledWifiAdb = true;
                Log.d(TAG, "🔓 Zero-Touch Recovery: cleared stale port & enabled adb_wifi_enabled=1");
            } else {
                Log.d(TAG, "🔄 Zero-Touch Recovery: adb_wifi_enabled already 1; started fresh mDNS discovery");
            }

            long startWaitMs = System.currentTimeMillis();
            long totalWaitMs = Math.max(maxWaitMs, 8000);
            long deadline = startWaitMs + totalWaitMs;

            while (System.currentTimeMillis() < deadline) {
                if (LocalAdbAutoPairAccessibilityService.isSessionArmed() || certificateRejectedCircuitBreaker.get()) {
                    return false;
                }
                if (!bootstrapInProgress.get() && pingDaemonInternal(false)) {
                    markDaemonReadyAndBroadcast("Resident Shell Daemon Active (UID 2000)");
                    recovered = true;
                    return true;
                }

                // If adb_wifi_enabled was already 1 and no mDNS port resolved within 3,000ms,
                // cycle adb_wifi_enabled 0 -> 500ms -> 1 once so adbd re-broadcasts its mDNS record
                if (!weEnabledWifiAdb && !cycledAlreadyOnWifiAdb &&
                        discoveredConnectPort.get() <= 0 &&
                        (System.currentTimeMillis() - startWaitMs) >= 3000L) {
                    cycledAlreadyOnWifiAdb = true;
                    Log.d(TAG, "🔄 Zero-Touch Recovery: adb_wifi_enabled was 1 but no mDNS port resolved in 3000ms -> cycling 0 -> 500ms -> 1");
                    try {
                        Settings.Global.putInt(cr, SETTING_ADB_WIFI_ENABLED, 0);
                        Thread.sleep(500);
                        clearDiscoveredConnectPort();
                        portLatch = restartFreshMdnsConnectDiscovery();
                        Settings.Global.putInt(cr, SETTING_ADB_WIFI_ENABLED, 1);
                        weEnabledWifiAdb = true;
                        deadline = Math.max(deadline, System.currentTimeMillis() + 6000L);
                    } catch (Throwable cycleErr) {
                        Log.w(TAG, "Failed cycling adb_wifi_enabled: " + cycleErr.getMessage());
                    }
                }

                long remainingMs = deadline - System.currentTimeMillis();
                if (remainingMs <= 0) {
                    break;
                }

                int port = discoveredConnectPort.get();
                if (port <= 0) {
                    boolean resolved = portLatch.await(Math.min(remainingMs, 1000L), TimeUnit.MILLISECONDS);
                    port = discoveredConnectPort.get();
                    if (!resolved && port <= 0) {
                        continue;
                    }
                }

                if (port > 0) {
                    Log.d(TAG, "🔌 Zero-Touch Recovery: connecting to freshly discovered TLS port " + port);
                    if (connectToPortInternal(port, 2500, false) && daemonActive) {
                        recovered = true;
                        lastFailedRecoveryMs = 0L;
                        return true;
                    }
                    discardRefusedConnectPort(port);
                    portLatch = freshConnectPortLatch;
                }
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "Zero-Touch Recovery attempt did not complete: " + t.getMessage());
            return false;
        } finally {
            if (!recovered) {
                lastFailedRecoveryMs = System.currentTimeMillis();
                if (weEnabledWifiAdb && !LocalAdbAutoPairAccessibilityService.isSessionArmed()) {
                    disableWirelessDebuggingQuietly();
                }
            }
            recoveryInProgress.set(false);
        }
    }

    public void disableWirelessDebuggingQuietly() {
        try {
            if (hasWriteSecureSettingsPermission()) {
                Settings.Global.putInt(appContext.getContentResolver(), SETTING_ADB_WIFI_ENABLED, 0);
                Log.d(TAG, "🔒 Reset adb_wifi_enabled=0 (MrssShellDaemon verified active)");
                return;
            }
        } catch (Throwable ignored) {}

        try {
            tryExecuteViaDaemon("settings put global adb_wifi_enabled 0", true);
        } catch (Throwable ignored) {}
    }

    /**
     * Explicit user-initiated Auto-Connect button handler.
     */
    public boolean tryAutoConnect(int timeoutMs) {
        return runOnIoThread(() -> {
            certificateRejectedCircuitBreaker.set(false);
            lastFailedRecoveryMs = 0L;
            if (hasEverPaired() && hasWriteSecureSettingsPermission() && tryZeroTouchRecoveryInternal(timeoutMs)) {
                return true;
            }
            return tryAutoConnectSingleFlight(true, timeoutMs);
        }, false, Math.max(timeoutMs, 8000) + 10000);
    }

    private boolean tryAutoConnectSingleFlight(boolean manualUserRequest, int timeoutMs) {
        if (bootstrapInProgress.get()) {
            return false;
        }
        if (pingDaemonInternal(false)) {
            markDaemonReadyAndBroadcast("Resident Shell Daemon Active (UID 2000)");
            return true;
        }

        if (!manualUserRequest && !canAttemptBackgroundAutoConnect()) {
            return false;
        }

        if (!connectInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "⏳ Skipping concurrent auto-connect (single-flight already in progress)");
            return false;
        }

        try {
            lastAutoConnectAttemptMs = System.currentTimeMillis();
            startBackgroundMdnsDiscovery();

            Set<Integer> candidatePorts = new LinkedHashSet<>();
            if (hasEverPaired() || manualUserRequest) {
                int mdnsPort = discoveredConnectPort.get();
                if (mdnsPort > 0) {
                    candidatePorts.add(mdnsPort);
                }
                int savedPort = prefs.getInt(PREF_LAST_CONNECT_PORT, 0);
                if (savedPort > 0 && savedPort != mdnsPort) {
                    candidatePorts.add(savedPort);
                }
            }
            if (manualUserRequest) {
                candidatePorts.add(CLASSIC_ADB_PORT);
            }

            for (int port : candidatePorts) {
                if (!manualUserRequest && (certificateRejectedCircuitBreaker.get() ||
                        LocalAdbAutoPairAccessibilityService.isSessionArmed())) {
                    break;
                }
                if (connectToPortInternal(port, Math.min(timeoutMs, 2500), manualUserRequest)) {
                    return true;
                }
                if (certificateRejectedCircuitBreaker.get()) {
                    break;
                }
            }

            if (hasEverPaired() && hasWriteSecureSettingsPermission() && !isWifiConnected()) {
                notifyStatusChanged(false, STATE_WAITING_FOR_WIFI,
                        "Waiting for Wi-Fi to auto-recover Local ADB daemon...");
            } else {
                notifyStatusChanged(false, STATE_NEEDS_PAIRING, "Pairing or connection required");
            }
            return false;
        } finally {
            connectInProgress.set(false);
        }
    }

    /**
     * Explicit user-initiated connection to a specific port.
     */
    public boolean connectToPort(int port, int timeoutMs) {
        return runOnIoThread(() -> {
            certificateRejectedCircuitBreaker.set(false);
            return connectToPortInternal(port, timeoutMs, true);
        }, false, timeoutMs + 12000);
    }

    /**
     * Connects to adbd on 127.0.0.1:port and runs the complete MrssShellDaemon bootstrap while
     * holding bootstrapInProgress=true. Never transitions to STATE_READY unless MrssShellDaemon
     * passes pingDaemonInternal() (including post-teardown survival).
     */
    private boolean connectToPortInternal(int port, int timeoutMs, boolean manualUserRequest) {
        if (port <= 0 || port > 65535) return false;
        if (!manualUserRequest && certificateRejectedCircuitBreaker.get()) {
            return false;
        }
        if (!bootstrapInProgress.compareAndSet(false, true)) {
            Log.d(TAG, "⏳ connectToPortInternal skipped: bootstrap already in progress");
            return false;
        }

        AdbConnection conn = new AdbConnection("127.0.0.1", port, keyManager);
        try {
            daemonActive = false;
            Log.d(TAG, "🔌 Connecting to Local ADB on 127.0.0.1:" + port + " (timeout=" + timeoutMs + "ms)...");
            conn.connect(timeoutMs);
            Log.d(TAG, "✅ AdbConnection established on 127.0.0.1:" + port + " -> starting daemon bootstrap");
            AdbConnection prev = activeAdbConnection;
            activeAdbConnection = conn;
            if (prev != null && prev != conn) {
                try {
                    prev.close();
                } catch (Throwable ignored) {}
            }
            certificateRejectedCircuitBreaker.set(false);
            setDiscoveredConnectPort(port);
            prefs.edit().putBoolean(PREF_EVER_PAIRED, true).apply();

            boolean daemonReady = bootstrapDaemonUnderLock(conn);
            if (!daemonReady) {
                try {
                    conn.close();
                } catch (Throwable ignored) {}
                if (activeAdbConnection == conn) {
                    activeAdbConnection = null;
                }
                daemonActive = false;
                lastFailedRecoveryMs = System.currentTimeMillis();
                notifyStatusChanged(false, STATE_ERROR,
                        "MrssShellDaemon failed to start; check logcat for " + DAEMON_LOG_PATH);
                return false;
            }
            return true;
        } catch (Exception e) {
            conn.close();
            if (activeAdbConnection == conn) {
                activeAdbConnection = null;
            }
            if (isCertificateRejectedException(e)) {
                Log.w(TAG, "🔒 Certificate rejected on TLS port " + port +
                        " (SSLV3_ALERT_CERTIFICATE_UNKNOWN) -> tripping unpaired circuit breaker");
                certificateRejectedCircuitBreaker.set(true);
                prefs.edit().putBoolean(PREF_EVER_PAIRED, false).apply();
                notifyStatusChanged(false, STATE_NEEDS_PAIRING,
                        "Pairing required (device certificate not yet authorized)");
            } else if (isConnectionRefusedException(e)) {
                Log.w(TAG, "🚫 Port " + port + " refused connection (ECONNREFUSED): " + e.getMessage());
                discardRefusedConnectPort(port);
            } else {
                Log.w(TAG, "Failed connecting to Local ADB port " + port + ": " +
                        e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return false;
        } finally {
            bootstrapInProgress.set(false);
        }
    }

    private static boolean isConnectionRefusedException(Throwable t) {
        Throwable curr = t;
        int depth = 0;
        while (curr != null && depth < 6) {
            if (curr instanceof ConnectException) {
                return true;
            }
            String msg = curr.getMessage();
            if (msg != null) {
                String upper = msg.toUpperCase();
                if (upper.contains("ECONNREFUSED") || upper.contains("CONNECTION REFUSED")) {
                    return true;
                }
            }
            curr = curr.getCause();
            depth++;
        }
        return false;
    }

    private static boolean isCertificateRejectedException(Throwable t) {
        Throwable curr = t;
        int depth = 0;
        while (curr != null && depth < 6) {
            String msg = curr.getMessage();
            if (msg != null) {
                String upper = msg.toUpperCase();
                if (upper.contains("CERTIFICATE_UNKNOWN") ||
                    upper.contains("SSLV3_ALERT") ||
                    upper.contains("ALERT_CERTIFICATE")) {
                    return true;
                }
            }
            if (curr instanceof SSLHandshakeException || curr instanceof SSLProtocolException) {
                return true;
            }
            curr = curr.getCause();
            depth++;
        }
        return false;
    }

    /**
     * Overload without connect port hint.
     */
    public boolean pairAndConnect(int pairingPort, String pairingCode) throws Exception {
        return pairAndConnect(pairingPort, pairingCode, 0);
    }

    /**
     * Fix #1: Pairs with Android 11-17 Wireless Debugging on 127.0.0.1:pairingPort using the 6-digit
     * code, and immediately connects to 127.0.0.1:knownConnectPortHint (if > 0) without waiting on
     * NsdManager mDNS discovery. Only falls back to mDNS discovery if knownConnectPortHint <= 0 or
     * direct connect to knownConnectPortHint fails.
     */
    public boolean pairAndConnect(final int pairingPort, final String pairingCode, final int knownConnectPortHint) throws Exception {
        Future<Boolean> future = ioPool.submit(() -> {
            Log.d(TAG, "🔐 pairAndConnect starting: pairingPort=" + pairingPort +
                    ", knownConnectPortHint=" + knownConnectPortHint);
            if (knownConnectPortHint > 0) {
                setDiscoveredConnectPort(knownConnectPortHint);
            }
            startBackgroundMdnsDiscovery();

            AdbPairingClient pairingClient = new AdbPairingClient(keyManager);
            boolean paired = pairingClient.pair("127.0.0.1", pairingPort, pairingCode);
            if (!paired) {
                Log.e(TAG, "❌ SPAKE2 pairing failed on 127.0.0.1:" + pairingPort);
                return false;
            }

            Log.d(TAG, "✅ SPAKE2 pairing succeeded on 127.0.0.1:" + pairingPort);
            certificateRejectedCircuitBreaker.set(false);
            lastAutoConnectAttemptMs = 0L;
            lastFailedRecoveryMs = 0L;
            prefs.edit()
                    .putInt(PREF_LAST_PAIRING_PORT, pairingPort)
                    .putBoolean(PREF_EVER_PAIRED, true)
                    .apply();

            // Step A: Immediately connect to knownConnectPortHint if provided (skipping 8-10s mDNS wait)
            if (knownConnectPortHint > 0 && knownConnectPortHint != pairingPort) {
                for (int attempt = 1; attempt <= 2; attempt++) {
                    Log.d(TAG, "⚡ Post-pair direct connect (attempt #" + attempt +
                            "/2) to hint port 127.0.0.1:" + knownConnectPortHint);
                    if (connectToPortInternal(knownConnectPortHint, 3000, true)) {
                        Log.d(TAG, "🎉 Post-pair direct connect & daemon bootstrap succeeded on hint port " +
                                knownConnectPortHint);
                        return true;
                    }
                    if (attempt == 1) {
                        // Brief pause in case adbd takes ~150ms after pairing to reload adb_keys
                        Thread.sleep(250);
                    }
                }
                Log.w(TAG, "⚠️ Direct connect to hint port " + knownConnectPortHint +
                        " failed; falling back to mDNS _adb-tls-connect._tcp discovery");
            }

            // Step B: Fallback to mDNS discovery
            Log.d(TAG, "📡 Waiting for mDNS _adb-tls-connect._tcp port resolution...");
            long deadline = System.currentTimeMillis() + 8000L;
            while (System.currentTimeMillis() < deadline) {
                int port = discoveredConnectPort.get();
                if (port <= 0) {
                    CountDownLatch latch = freshConnectPortLatch;
                    if (latch != null) {
                        latch.await(500, TimeUnit.MILLISECONDS);
                    } else {
                        Thread.sleep(250);
                    }
                    port = discoveredConnectPort.get();
                }
                if (port > 0) {
                    Log.d(TAG, "🔌 Post-pair mDNS connect attempt to 127.0.0.1:" + port);
                    if (connectToPortInternal(port, 3000, true)) {
                        Log.d(TAG, "🎉 Post-pair mDNS connect & daemon bootstrap succeeded on port " + port);
                        return true;
                    }
                }
                Thread.sleep(250);
            }

            boolean fallbackPing = !bootstrapInProgress.get() && pingDaemonInternal(true);
            Log.w(TAG, "⚠️ pairAndConnect finished mDNS fallback loop -> daemonAlive=" + fallbackPing);
            return fallbackPing;
        });
        return future.get(35, TimeUnit.SECONDS);
    }

    /**
     * Builds the single-line command that:
     * 1. Detaches MrssShellDaemon into a new session with setsid (or nohup fallback) + trap '' HUP +
     *    </dev/null + >/data/local/tmp/mrss_daemon.log 2>&1 & + exec.
     * 2. Polls inside the outer spawn shell up to 3 seconds (30 * 0.1s) until MRSS_DAEMON_READY
     *    is written to /data/local/tmp/mrss_daemon.log before exiting, preventing the 14ms PTY-close race.
     * 3. Outputs cat /data/local/tmp/mrss_daemon.log as the shell command output.
     */
    public static String buildDetachedDaemonLaunchCommand(
            String apkPath,
            int port,
            String token,
            String apkSig) {
        String cleanApk = (apkPath != null ? apkPath : "").replace("'", "").replace("\"", "");
        String cleanToken = (token != null ? token : "").replace("'", "").replace("\"", "");
        String cleanSig = (apkSig != null ? apkSig : "").replace("'", "").replace("\"", "");
        String subShell = String.format(
                "trap '' HUP; export CLASSPATH='%s'; exec /system/bin/app_process /system/bin --nice-name=mrss_shell_daemon " +
                "com.tgwgroup.MiRearScreenSwitcher.adb.MrssShellDaemon %d '%s' '%s'",
                cleanApk,
                port,
                cleanToken,
                cleanSig
        );
        return "rm -f " + DAEMON_LOG_PATH + "; " +
               "if command -v setsid >/dev/null 2>&1; then " +
               "setsid /system/bin/sh -c \"" + subShell + "\" </dev/null >" + DAEMON_LOG_PATH + " 2>&1 & " +
               "else " +
               "nohup /system/bin/sh -c \"" + subShell + "\" </dev/null >" + DAEMON_LOG_PATH + " 2>&1 & " +
               "fi; " +
               "i=0; while [ $i -lt 30 ]; do grep -q MRSS_DAEMON_READY " + DAEMON_LOG_PATH + " && break; sleep 0.1; i=$((i+1)); done; " +
               "cat " + DAEMON_LOG_PATH + " 2>&1 || true";
    }

    /**
     * Runs with bootstrapInProgress == true so ZERO UI shell calls can contend with AdbConnection.
     */
    private boolean bootstrapDaemonUnderLock(AdbConnection conn) {
        if (conn == null || !conn.isConnected()) return false;
        try {
            if (pingDaemonInternal(false)) {
                Log.d(TAG, "✅ Existing MrssShellDaemon already healthy on 127.0.0.1:" + MrssShellDaemon.DEFAULT_PORT);
                runPostSpawnGrantsAndFinalize(conn);
                return daemonActive;
            }

            // Clean up any stale daemon in a SEPARATE shell call using bracket regex '[m]rss_shell_daemon'
            // so pkill -f never matches its own sh -c command line or the subsequent spawn shell.
            try {
                conn.executeShellCommand("pkill -f '[m]rss_shell_daemon' 2>/dev/null || true", 2000);
                Thread.sleep(200);
            } catch (Exception ignored) {}

            String apkPath = appContext.getApplicationInfo().sourceDir;
            String token = getDaemonToken();
            String apkSig = getCurrentApkSignature();
            String launchCmd = buildDetachedDaemonLaunchCommand(
                    apkPath,
                    MrssShellDaemon.DEFAULT_PORT,
                    token,
                    apkSig
            );

            Log.d(TAG, "🚀 Spawning detached MrssShellDaemon (in-shell wait up to 3s for MRSS_DAEMON_READY)...");
            AdbConnection.ShellOutput spawnOut = conn.executeShellCommand(launchCmd, 8000);
            String spawnLog = (spawnOut != null && spawnOut.stdout != null) ? spawnOut.stdout.trim() : "";
            Log.d(TAG, "📦 Daemon spawn command completed (exit=" +
                    (spawnOut != null ? spawnOut.exitCode : -1) + ", log=" + spawnLog + ")");

            // Poll pingDaemonInternal(true) on 127.0.0.1:43821 every 200ms for up to 5,000ms (25 attempts)
            lastDaemonPingFailMs = 0L;
            boolean verified = false;
            for (int attempt = 1; attempt <= 25; attempt++) {
                if (pingDaemonInternal(true)) {
                    verified = true;
                    Log.d(TAG, "✅ MrssShellDaemon answered CMD_GET_INFO on attempt #" + attempt +
                            " on 127.0.0.1:" + MrssShellDaemon.DEFAULT_PORT);
                    break;
                }
                Thread.sleep(200);
            }

            if (verified) {
                runPostSpawnGrantsAndFinalize(conn);
                return daemonActive;
            }

            // Diagnostics on failure: read /data/local/tmp/mrss_daemon.log over the still-open AdbConnection
            try {
                AdbConnection.ShellOutput logOut = conn.executeShellCommand(
                        "cat " + DAEMON_LOG_PATH + " 2>&1 || echo '(mrss_daemon.log not found)'",
                        3000
                );
                Log.e(TAG, "❌ MrssShellDaemon failed to answer ping after 5000ms! Contents of " +
                        DAEMON_LOG_PATH + ":\n" + (logOut != null ? logOut.stdout : "(null)"));
            } catch (Throwable diagErr) {
                Log.e(TAG, "❌ MrssShellDaemon failed to answer ping after 5000ms and could not read " +
                        DAEMON_LOG_PATH + ": " + diagErr.getMessage());
            }
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "MrssShellDaemon bootstrap error: " + t.getMessage(), t);
            return false;
        }
    }

    /**
     * Runs pm grant WRITE_SECURE_SETTINGS and keep-alive AppOps sequentially through the verified
     * MrssShellDaemon BEFORE reporting STATE_READY, then resets adb_wifi_enabled=0, waits 300ms,
     * verifies survival via pingDaemonInternal(true), closes AdbConnection, and marks READY.
     */
    private void runPostSpawnGrantsAndFinalize(AdbConnection conn) {
        String pkg = appContext.getPackageName();
        String grantCmd =
                "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS ; " +
                "cmd appops set " + pkg + " RUN_IN_BACKGROUND allow ; " +
                "cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow ; " +
                "cmd deviceidle whitelist +" + pkg;

        Result grantRes = tryExecuteViaDaemon(grantCmd, false);
        if (grantRes != null) {
            Log.d(TAG, "🔑 Executed post-spawn self-grants sequentially via MrssShellDaemon (exit=" +
                    grantRes.exitCode + ")");
        } else if (conn != null && conn.isConnected()) {
            try {
                conn.executeShellCommand(grantCmd, 3500);
            } catch (Throwable ignored) {}
        }

        // Reset adb_wifi_enabled=0 now that MrssShellDaemon is verified and self-grants are complete
        disableWirelessDebuggingQuietly();

        // Close AdbConnection cleanly so all subsequent execution goes strictly through MrssShellDaemon
        if (conn != null) {
            try {
                conn.close();
            } catch (Throwable ignored) {}
            if (activeAdbConnection == conn) {
                activeAdbConnection = null;
            }
        }

        try {
            Thread.sleep(300);
        } catch (InterruptedException ignored) {}

        boolean survived = pingDaemonInternal(true);
        Log.d(TAG, "🛡️ Post-teardown pingDaemon() check 300ms after adb_wifi_enabled=0 -> survived=" + survived);
        if (survived) {
            bootstrapInProgress.set(false);
            markDaemonReadyAndBroadcast("Resident Shell Daemon Active (UID 2000)");
        } else {
            daemonActive = false;
            Log.e(TAG, "❌ MrssShellDaemon did not survive adb_wifi_enabled=0 teardown!");
        }
    }

    // =========================================================================
    // TaskService.ShellExecutor implementation (Strictly Fail-Fast, Zero Blocking Reconnects)
    // =========================================================================

    @Override
    public Result execute(final String cmd) throws Exception {
        if (cmd == null) {
            return new Result(-1, "", "Null command");
        }

        if (bootstrapInProgress.get()) {
            throw new IllegalStateException("Local ADB bootstrap in progress (fail-fast)");
        }
        if (!isReadyFast() && (System.currentTimeMillis() - lastDaemonPingFailMs) < DAEMON_PING_FAIL_CACHE_MS) {
            throw new IllegalStateException("Local ADB is not connected (fail-fast)");
        }

        // Coalesce 100ms keep-alive KEYCODE_WAKEUP loops called from Main Looper
        if (Looper.myLooper() == Looper.getMainLooper() &&
            "input -d 1 keyevent KEYCODE_WAKEUP".equals(cmd.trim())) {
            if (!isReadyFast()) {
                return new Result(-1, "", "Not connected");
            }
            if (wakeupInFlight.compareAndSet(false, true)) {
                wakeupWorker.execute(() -> {
                    try {
                        executeInternal(cmd, true);
                    } catch (Throwable ignored) {
                    } finally {
                        wakeupInFlight.set(false);
                    }
                });
            }
            return new Result(0, "", "");
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!isReadyFast()) {
                throw new IllegalStateException("Local ADB is not connected (fail-fast main thread)");
            }
            Future<Result> future = ioPool.submit(() -> executeInternal(cmd, false));
            return future.get(2500, TimeUnit.MILLISECONDS);
        }

        return executeInternal(cmd, false);
    }

    private Result executeInternal(String cmd, boolean fireAndForget) throws Exception {
        if (bootstrapInProgress.get()) {
            throw new IllegalStateException("Local ADB bootstrap in progress (fail-fast)");
        }

        if (daemonActive || (System.currentTimeMillis() - lastDaemonPingFailMs) >= DAEMON_PING_FAIL_CACHE_MS) {
            Result daemonRes = tryExecuteViaDaemon(cmd, fireAndForget);
            if (daemonRes != null) {
                boolean wasInactive = !daemonActive;
                lastDaemonPingFailMs = 0L;
                lastDaemonPingSuccessMs = System.currentTimeMillis();
                daemonActive = true;
                if (wasInactive) {
                    markDaemonReadyAndBroadcast("Resident Shell Daemon Active (UID 2000)");
                }
                return daemonRes;
            }
            daemonActive = false;
            lastDaemonPingFailMs = System.currentTimeMillis();
        }

        throw new IllegalStateException("Local ADB daemon is not active (fail-fast)");
    }

    private Result tryExecuteViaDaemon(String cmd, boolean fireAndForget) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", MrssShellDaemon.DEFAULT_PORT), 200);
            s.setTcpNoDelay(true);
            s.setSoTimeout(5000);

            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());

            out.writeUTF(getDaemonToken());
            out.flush();
            if (!in.readBoolean()) {
                return null;
            }

            if (fireAndForget) {
                out.writeByte(MrssShellDaemon.CMD_EXEC_NOWAIT);
                MrssShellDaemon.writeString(out, cmd);
                out.flush();
                int rc = in.readInt();
                return new Result(rc, "", "");
            } else {
                out.writeByte(MrssShellDaemon.CMD_EXEC);
                MrssShellDaemon.writeString(out, cmd);
                out.flush();
                int exitCode = in.readInt();
                String stdout = MrssShellDaemon.readString(in);
                String stderr = MrssShellDaemon.readString(in);
                return new Result(exitCode, stdout, stderr);
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public boolean showRearMediaPill(final boolean isPlaying, final int x, final int y) {
        if (bootstrapInProgress.get() || !isReadyFast()) {
            return false;
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", MrssShellDaemon.DEFAULT_PORT), 200);
            s.setTcpNoDelay(true);
            s.setSoTimeout(2000);

            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());

            out.writeUTF(getDaemonToken());
            out.flush();
            if (!in.readBoolean()) {
                return false;
            }

            out.writeByte(MrssShellDaemon.CMD_PILL_SHOW);
            out.writeBoolean(isPlaying);
            out.writeInt(x);
            out.writeInt(y);
            out.flush();
            return in.readInt() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public boolean showRearMediaPillStyled(
            final boolean isPlaying,
            final int x,
            final int y,
            final float scale,
            final int tintColor) {
        if (bootstrapInProgress.get() || !isReadyFast()) {
            return false;
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", MrssShellDaemon.DEFAULT_PORT), 200);
            s.setTcpNoDelay(true);
            s.setSoTimeout(2000);

            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());

            out.writeUTF(getDaemonToken());
            out.flush();
            if (!in.readBoolean()) {
                return false;
            }

            out.writeByte(MrssShellDaemon.CMD_PILL_SHOW_STYLED);
            out.writeBoolean(isPlaying);
            out.writeInt(x);
            out.writeInt(y);
            out.writeFloat(scale);
            out.writeInt(tintColor);
            out.flush();
            return in.readInt() == 0;
        } catch (Throwable ignored) {
            return showRearMediaPill(isPlaying, x, y);
        }
    }

    @Override
    public boolean hideRearMediaPill() {
        if (bootstrapInProgress.get() || !isReadyFast()) {
            return false;
        }
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", MrssShellDaemon.DEFAULT_PORT), 200);
            s.setTcpNoDelay(true);
            s.setSoTimeout(2000);

            DataInputStream in = new DataInputStream(s.getInputStream());
            DataOutputStream out = new DataOutputStream(s.getOutputStream());

            out.writeUTF(getDaemonToken());
            out.flush();
            if (!in.readBoolean()) {
                return false;
            }

            out.writeByte(MrssShellDaemon.CMD_PILL_HIDE);
            out.flush();
            return in.readInt() == 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // =========================================================================
    // Fix #2: mDNS Discovery (_adb-tls-connect._tcp) with Stop-Before-Start Synchronization
    // =========================================================================

    /**
     * Stops any existing NsdManager connect discovery listener cleanly, awaits onDiscoveryStopped
     * up to 1,000ms so HyperOS / Android 17 NsdService never overlaps discovery sessions, creates
     * a fresh CountDownLatch, and starts a new discoverServices("_adb-tls-connect._tcp.", ...) session.
     */
    private CountDownLatch restartFreshMdnsConnectDiscovery() {
        stopBackgroundMdnsDiscovery();
        CountDownLatch latch = new CountDownLatch(1);
        this.freshConnectPortLatch = latch;
        startBackgroundMdnsDiscovery();
        return latch;
    }

    public void stopBackgroundMdnsDiscovery() {
        CountDownLatch stopLatch = null;
        synchronized (nsdLock) {
            NsdManager.DiscoveryListener listener = connectDiscoveryListener;
            connectDiscoveryListener = null;
            if (nsdDiscovering.get() && listener != null) {
                stopLatch = new CountDownLatch(1);
                this.nsdStopLatch = stopLatch;
                try {
                    NsdManager nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
                    if (nsdManager != null) {
                        Log.d(TAG, "⏹️ Requesting NsdManager.stopServiceDiscovery(_adb-tls-connect._tcp)...");
                        nsdManager.stopServiceDiscovery(listener);
                    } else {
                        nsdDiscovering.set(false);
                        stopLatch.countDown();
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "stopServiceDiscovery exception: " + t.getMessage());
                    nsdDiscovering.set(false);
                    stopLatch.countDown();
                }
            } else {
                nsdDiscovering.set(false);
            }
        }

        if (stopLatch != null) {
            try {
                boolean stoppedCleanly = stopLatch.await(1000, TimeUnit.MILLISECONDS);
                Log.d(TAG, "⏹️ Await NSD onDiscoveryStopped completed (clean=" + stoppedCleanly + ")");
            } catch (InterruptedException ignored) {}
            nsdDiscovering.set(false);
        }
    }

    public void startBackgroundMdnsDiscovery() {
        synchronized (nsdLock) {
            if (!nsdDiscovering.compareAndSet(false, true)) {
                return;
            }
            try {
                final NsdManager nsdManager = (NsdManager) appContext.getSystemService(Context.NSD_SERVICE);
                if (nsdManager == null) {
                    nsdDiscovering.set(false);
                    return;
                }
                connectDiscoveryListener = new NsdManager.DiscoveryListener() {
                    @Override
                    public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                        nsdDiscovering.set(false);
                        Log.w(TAG, "⚠️ NSD onStartDiscoveryFailed (" + serviceType + ") errorCode=" + errorCode);
                    }

                    @Override
                    public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                        nsdDiscovering.set(false);
                        CountDownLatch sl = nsdStopLatch;
                        if (sl != null) sl.countDown();
                        Log.w(TAG, "⚠️ NSD onStopDiscoveryFailed (" + serviceType + ") errorCode=" + errorCode);
                    }

                    @Override
                    public void onDiscoveryStarted(String serviceType) {
                        Log.d(TAG, "📡 NSD onDiscoveryStarted (" + serviceType + ")");
                    }

                    @Override
                    public void onDiscoveryStopped(String serviceType) {
                        nsdDiscovering.set(false);
                        CountDownLatch sl = nsdStopLatch;
                        if (sl != null) sl.countDown();
                        Log.d(TAG, "⏹️ NSD onDiscoveryStopped (" + serviceType + ")");
                    }

                    @Override
                    public void onServiceFound(NsdServiceInfo serviceInfo) {
                        Log.d(TAG, "📡 NSD onServiceFound: " +
                                (serviceInfo != null ? serviceInfo.getServiceName() : "null"));
                        nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                            @Override
                            public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                                Log.w(TAG, "⚠️ NSD onResolveFailed (" +
                                        (info != null ? info.getServiceName() : "null") + ") errorCode=" + errorCode);
                            }

                            @Override
                            public void onServiceResolved(NsdServiceInfo resolvedInfo) {
                                int port = resolvedInfo.getPort();
                                if (port > 0) {
                                    Log.d(TAG, "📡 Discovered _adb-tls-connect._tcp on port " + port);
                                    setDiscoveredConnectPort(port);
                                    if (!daemonActive && !recoveryInProgress.get() && !bootstrapInProgress.get() && canAttemptBackgroundAutoConnect()) {
                                        connectExecutor.execute(() -> tryAutoConnectSingleFlight(false, 2000));
                                    }
                                }
                            }
                        });
                    }

                    @Override
                    public void onServiceLost(NsdServiceInfo serviceInfo) {}
                };
                nsdManager.discoverServices(NSD_CONNECT_TYPE, NsdManager.PROTOCOL_DNS_SD, connectDiscoveryListener);
            } catch (Throwable t) {
                nsdDiscovering.set(false);
                Log.w(TAG, "Failed starting NSD connect discovery: " + t.getMessage());
            }
        }
    }

    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build();
            cm.registerNetworkCallback(req, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (!canAttemptBackgroundAutoConnect()) {
                        return;
                    }
                    connectExecutor.execute(() -> {
                        if (!bootstrapInProgress.get() && !pingDaemonInternal(false) && canAttemptBackgroundAutoConnect()) {
                            Log.d(TAG, "📶 Wi-Fi transport available; triggering Zero-Touch Local ADB recovery");
                            if (!tryZeroTouchRecoveryInternal(8000)) {
                                tryAutoConnectSingleFlight(false, 2000);
                            }
                        }
                    });
                }
            });
        } catch (Throwable ignored) {}
    }

    private <T> T runOnIoThread(Callable<T> task, T fallback, long timeoutMs) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            try {
                return task.call();
            } catch (Exception e) {
                return fallback;
            }
        }
        try {
            Future<T> f = ioPool.submit(task);
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }
}

package com.tgwgroup.MiRearScreenSwitcher.adb;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import com.tgwgroup.MiRearScreenSwitcher.MainActivity;
import com.tgwgroup.MiRearScreenSwitcher.R;
import com.tgwgroup.MiRearScreenSwitcher.TaskServiceManager;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Interactive Foreground Service for Android 11+ Wireless Debugging Pairing.
 *
 * Why a Notification with RemoteInput is essential on Android / HyperOS:
 * Android Settings immediately closes and invalidates the "Pair device with pairing code" dialog
 * if the Settings app loses foreground focus. By posting an interactive high-priority notification
 * with an inline RemoteInput field while discovering _adb-tls-pairing._tcp via NsdManager (mDNS),
 * the user can stay in Developer Options -> Wireless Debugging, pull down the notification shade,
 * type the 6-digit pairing code (or "port:code"), and complete pairing in one step.
 */
public class LocalAdbPairingService extends Service {
    private static final String TAG = "LocalAdbPairingSvc";
    private static final String CHANNEL_ID = "mrss_local_adb_pairing";
    private static final int NOTIFICATION_ID = 10088;

    public static final String ACTION_START_PAIRING = "com.tgwgroup.MiRearScreenSwitcher.START_ADB_PAIRING";
    public static final String ACTION_STOP_PAIRING = "com.tgwgroup.MiRearScreenSwitcher.STOP_ADB_PAIRING";
    public static final String ACTION_SUBMIT_CODE = "com.tgwgroup.MiRearScreenSwitcher.SUBMIT_ADB_PAIRING_CODE";
    public static final String KEY_PAIRING_CODE_INPUT = "mrss_pairing_code_input";

    private static final String NSD_PAIRING_TYPE = "_adb-tls-pairing._tcp.";
    private static final String NSD_CONNECT_TYPE = "_adb-tls-connect._tcp.";
    private static final long PAIRING_TIMEOUT_MS = 5 * 60 * 1000L; // 5-minute auto-timeout

    private NsdManager nsdManager;
    private NsdManager.DiscoveryListener pairingDiscoveryListener;
    private NsdManager.DiscoveryListener connectDiscoveryListener;
    private final AtomicBoolean pairingDiscovering = new AtomicBoolean(false);
    private final AtomicBoolean connectDiscovering = new AtomicBoolean(false);
    private final AtomicInteger discoveredPairingPort = new AtomicInteger(0);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final Runnable autoTimeoutRunnable = () -> {
        Log.d(TAG, "⏱️ 5-minute pairing timeout reached; stopping mDNS discovery and dismissing notification");
        stopMdnsDiscovery();
        stopSelf();
    };

    private final BroadcastReceiver replyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (ACTION_SUBMIT_CODE.equals(action)) {
                android.os.Bundle results = RemoteInput.getResultsFromIntent(intent);
                if (results != null) {
                    CharSequence inputSeq = results.getCharSequence(KEY_PAIRING_CODE_INPUT);
                    String rawInput = inputSeq != null ? inputSeq.toString().trim() : "";
                    handleUserSubmittedCode(rawInput);
                }
            } else if (ACTION_STOP_PAIRING.equals(action)) {
                stopSelf();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SUBMIT_CODE);
        filter.addAction(ACTION_STOP_PAIRING);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(replyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(replyReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_PAIRING.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, buildPairingNotification(
                isChinese() ? "正在搜索无线调试配对服务..." : "Searching for Wireless Debugging pairing...",
                isChinese()
                        ? "请前往「开发者选项 → 无线调试 → 使用配对码配对设备」，然后在此输入6位配对码（或 端口:配对码）"
                        : "Open Developer Options → Wireless Debugging → Pair with pairing code, then enter the 6-digit code (or port:code) below.",
                true
        ));

        mainHandler.removeCallbacks(autoTimeoutRunnable);
        mainHandler.postDelayed(autoTimeoutRunnable, PAIRING_TIMEOUT_MS);

        new Thread(this::startMdnsDiscovery, "MRSS-PairingNsdInit").start();
        return START_NOT_STICKY;
    }

    private void handleUserSubmittedCode(String rawInput) {
        if (rawInput.isEmpty()) {
            updateNotification(
                    isChinese() ? "❌ 配对码不能为空" : "❌ Pairing code cannot be empty",
                    isChinese() ? "请重新输入6位配对码（或 端口:配对码）" : "Please enter the 6-digit code (or port:code).",
                    true
            );
            return;
        }

        int port = discoveredPairingPort.get();
        String code = rawInput;

        // Allow "port:code" or "port code" syntax if user typed port manually
        if (rawInput.contains(":")) {
            String[] parts = rawInput.split(":", 2);
            try {
                port = Integer.parseInt(parts[0].trim());
                code = parts[1].trim();
            } catch (NumberFormatException ignored) {}
        } else if (rawInput.matches("\\d{4,5}\\s+\\d{6}")) {
            String[] parts = rawInput.trim().split("\\s+", 2);
            try {
                port = Integer.parseInt(parts[0]);
                code = parts[1];
            } catch (NumberFormatException ignored) {}
        }

        if (port <= 0) {
            updateNotification(
                    isChinese() ? "⚠️ 尚未自动检测到配对端口" : "⚠️ Pairing port not auto-detected yet",
                    isChinese()
                            ? "请确保「使用配对码配对」弹窗处于打开状态，或按「端口:配对码」格式输入（例如 39182:123456）"
                            : "Keep the pairing dialog open in Settings, or enter as 'port:code' (e.g. 39182:123456).",
                    true
            );
            return;
        }

        final int targetPort = port;
        final String targetCode = code;
        updateNotification(
                isChinese() ? ("⏳ 正在配对 (127.0.0.1:" + targetPort + ")...") : ("⏳ Pairing with 127.0.0.1:" + targetPort + "..."),
                isChinese() ? "正在执行 TLS 1.3 + SPAKE2 密钥交换..." : "Performing TLS 1.3 + SPAKE2 handshake...",
                false
        );

        new Thread(() -> {
            try {
                LocalAdbBackend backend = LocalAdbBackend.getInstance(getApplicationContext());
                boolean ok = backend.pairAndConnect(targetPort, targetCode);
                if (ok) {
                    TaskServiceManager.getInstance(getApplicationContext()).refreshBackendConnection();
                    mainHandler.post(() -> {
                        updateNotification(
                                isChinese() ? "✅ 内置 ADB 配对并连接成功！" : "✅ Local ADB Paired & Connected!",
                                isChinese() ? "MRSS 特权守护进程已激活，现在可以返回应用。" : "MRSS privileged daemon is active. You can return to the app.",
                                false
                        );
                    });
                    mainHandler.postDelayed(this::stopSelf, 3500);
                } else {
                    mainHandler.post(() -> updateNotification(
                            isChinese() ? "❌ 配对失败" : "❌ Pairing failed",
                            isChinese() ? "请检查配对码是否正确，并在无线调试中重新点击「使用配对码配对」" : "Check the pairing code and reopen 'Pair with pairing code' in Wireless Debugging.",
                            true
                    ));
                }
            } catch (Exception e) {
                Log.e(TAG, "Pairing failed", e);
                mainHandler.post(() -> updateNotification(
                        isChinese() ? ("❌ 配对失败: " + e.getMessage()) : ("❌ Pairing error: " + e.getMessage()),
                        isChinese() ? "请重新打开配对码弹窗并重试（支持 端口:配对码 格式）" : "Reopen the pairing dialog in Settings and try again (supports port:code).",
                        true
                ));
            }
        }, "MRSS-NotifPairing").start();
    }

    private void startMdnsDiscovery() {
        nsdManager = (NsdManager) getSystemService(Context.NSD_SERVICE);
        if (nsdManager == null) return;

        if (pairingDiscovering.compareAndSet(false, true)) {
            pairingDiscoveryListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    pairingDiscovering.set(false);
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                    pairingDiscovering.set(false);
                }

                @Override
                public void onDiscoveryStarted(String serviceType) {}

                @Override
                public void onDiscoveryStopped(String serviceType) {
                    pairingDiscovering.set(false);
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
                                Log.d(TAG, "🎯 Discovered _adb-tls-pairing._tcp on port " + port);
                                discoveredPairingPort.set(port);
                                mainHandler.post(() -> updateNotification(
                                        isChinese()
                                                ? ("🎯 已发现配对端口: " + port)
                                                : ("🎯 Pairing Port Found: " + port),
                                        isChinese()
                                                ? "点击下方「输入配对码」直接输入屏幕上的6位数字即可完成配对！"
                                                : "Tap 'Enter Pairing Code' below and type the 6-digit code shown on screen!",
                                        true
                                ));
                            }
                        }
                    });
                }

                @Override
                public void onServiceLost(NsdServiceInfo serviceInfo) {}
            };
            try {
                nsdManager.discoverServices(NSD_PAIRING_TYPE, NsdManager.PROTOCOL_DNS_SD, pairingDiscoveryListener);
            } catch (Throwable t) {
                pairingDiscovering.set(false);
            }
        }

        if (connectDiscovering.compareAndSet(false, true)) {
            connectDiscoveryListener = new NsdManager.DiscoveryListener() {
                @Override
                public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                    connectDiscovering.set(false);
                }

                @Override
                public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                    connectDiscovering.set(false);
                }

                @Override
                public void onDiscoveryStarted(String serviceType) {}

                @Override
                public void onDiscoveryStopped(String serviceType) {
                    connectDiscovering.set(false);
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
                                LocalAdbBackend.getInstance(getApplicationContext()).setDiscoveredConnectPort(port);
                            }
                        }
                    });
                }

                @Override
                public void onServiceLost(NsdServiceInfo serviceInfo) {}
            };
            try {
                nsdManager.discoverServices(NSD_CONNECT_TYPE, NsdManager.PROTOCOL_DNS_SD, connectDiscoveryListener);
            } catch (Throwable t) {
                connectDiscovering.set(false);
            }
        }
    }

    private void stopMdnsDiscovery() {
        if (nsdManager != null) {
            if (pairingDiscovering.compareAndSet(true, false) && pairingDiscoveryListener != null) {
                try {
                    nsdManager.stopServiceDiscovery(pairingDiscoveryListener);
                } catch (Throwable ignored) {}
            }
            if (connectDiscovering.compareAndSet(true, false) && connectDiscoveryListener != null) {
                try {
                    nsdManager.stopServiceDiscovery(connectDiscoveryListener);
                } catch (Throwable ignored) {}
            }
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    isChinese() ? "MRSS 无线调试配对" : "MRSS Wireless ADB Pairing",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Interactive notification for pairing Local ADB with Wireless Debugging");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private void updateNotification(String title, String content, boolean includeInput) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildPairingNotification(title, content, includeInput));
        }
    }

    private Notification buildPairingNotification(String title, String content, boolean includeInput) {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        PendingIntent contentPendingIntent = PendingIntent.getActivity(
                this, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(content))
                .setContentIntent(contentPendingIntent)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(false);

        if (includeInput) {
            String replyLabel = isChinese()
                    ? "输入6位配对码（或 端口:配对码）"
                    : "Enter 6-digit code (or port:code)";
            RemoteInput remoteInput = new RemoteInput.Builder(KEY_PAIRING_CODE_INPUT)
                    .setLabel(replyLabel)
                    .build();

            Intent submitIntent = new Intent(ACTION_SUBMIT_CODE);
            submitIntent.setPackage(getPackageName());
            int mutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_MUTABLE
                    : 0;
            PendingIntent replyPendingIntent = PendingIntent.getBroadcast(
                    this, 1, submitIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | mutFlag
            );

            NotificationCompat.Action replyAction = new NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_send,
                    isChinese() ? "输入配对码" : "Enter Pairing Code",
                    replyPendingIntent
            ).addRemoteInput(remoteInput).build();

            builder.addAction(replyAction);
        }

        Intent cancelIntent = new Intent(ACTION_STOP_PAIRING);
        cancelIntent.setPackage(getPackageName());
        PendingIntent cancelPendingIntent = PendingIntent.getBroadcast(
                this, 2, cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel,
                isChinese() ? "关闭" : "Cancel", cancelPendingIntent);

        return builder.build();
    }

    private boolean isChinese() {
        String lang = getResources().getConfiguration().locale.getLanguage();
        return "zh".equalsIgnoreCase(lang);
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(autoTimeoutRunnable);
        stopMdnsDiscovery();
        try {
            unregisterReceiver(replyReceiver);
        } catch (Throwable ignored) {}
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}


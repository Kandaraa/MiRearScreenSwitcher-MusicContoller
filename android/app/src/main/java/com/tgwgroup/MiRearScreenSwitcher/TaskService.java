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

import android.os.RemoteException;
import android.util.Log;
import androidx.annotation.Keep;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

/**
 * Privileged ITaskService implementation.
 * - When instantiated via the no-arg constructor by Shizuku, executes commands directly in
 *   the Shizuku UserService process (UID 2000).
 * - When instantiated with a custom ShellExecutor by LocalAdbBackend, routes all privileged
 *   commands through the standalone Local ADB / MrssShellDaemon pipeline (UID 2000).
 */
public class TaskService extends ITaskService.Stub {
    private static final String TAG = "TaskService";

    public interface ShellExecutor {
        final class Result {
            public final int exitCode;
            public final String stdout;
            public final String stderr;

            public Result(int exitCode, String stdout, String stderr) {
                this.exitCode = exitCode;
                this.stdout = stdout != null ? stdout : "";
                this.stderr = stderr != null ? stderr : "";
            }
        }

        Result execute(String cmd) throws Exception;

        default boolean showRearMediaPill(boolean isPlaying, int x, int y) throws Exception {
            return false;
        }

        default boolean showRearMediaPillStyled(boolean isPlaying, int x, int y, float scale, int tintColor) throws Exception {
            return showRearMediaPill(isPlaying, x, y);
        }

        default boolean hideRearMediaPill() throws Exception {
            return false;
        }
    }

    private final ShellExecutor customExecutor;
    private final RearSurfacePillRenderer pillRenderer = new RearSurfacePillRenderer();
    private final Object pullDownWatcherLock = new Object();
    private volatile boolean pullDownWatcherRunning = false;
    private volatile Process pullDownWatcherProcess = null;
    private volatile long lastPullDownBroadcastMs = 0L;

    @Keep
    public TaskService() {
        this.customExecutor = null;
    }

    public TaskService(ShellExecutor customExecutor) {
        this.customExecutor = customExecutor;
    }

    private ShellExecutor.Result runShell(String cmd) throws Exception {
        if (customExecutor != null) {
            return customExecutor.execute(cmd);
        }
        ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
        Process process = pb.start();

        final Process p = process;
        final StringBuilder errBuf = new StringBuilder();
        Thread errThread = new Thread(() -> {
            try (BufferedReader errReader = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8), 4096)) {
                String line;
                while ((line = errReader.readLine()) != null) {
                    errBuf.append(line).append('\n');
                }
            } catch (Throwable ignored) {}
        });
        errThread.start();

        StringBuilder outBuf = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 8192)) {
            String line;
            while ((line = reader.readLine()) != null) {
                outBuf.append(line).append('\n');
            }
        }

        errThread.join(2000);
        int exitCode = process.waitFor();
        return new ShellExecutor.Result(exitCode, outBuf.toString(), errBuf.toString());
    }

    @Override
    public void destroy() {
        try {
            stopRearPullDownWatcher();
        } catch (Throwable ignored) {}
        try {
            pillRenderer.shutdown();
        } catch (Throwable ignored) {}
        if (customExecutor == null) {
            System.exit(0);
        }
    }

    @Override
    public String getCurrentForegroundApp() throws RemoteException {
        try {
            ShellExecutor.Result res = runShell("am stack list");
            BufferedReader reader = new BufferedReader(new StringReader(res.stdout));

            boolean inDisplayZero = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("RootTask")) {
                    inDisplayZero = line.contains("displayId=0");
                    continue;
                }

                if (inDisplayZero && line.contains("taskId=") && line.contains("/")) {
                    int tidStart = line.indexOf("taskId=") + 7;
                    int tidEnd = line.indexOf(':', tidStart);
                    if (tidEnd <= tidStart) continue;
                    String taskId = line.substring(tidStart, tidEnd).trim();

                    int pkgStart = tidEnd + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    if (pkgEnd <= pkgStart) continue;
                    String packageName = line.substring(pkgStart, pkgEnd).trim();

                    if (packageName.contains("launcher") ||
                        packageName.contains("miui.home") ||
                        packageName.equals("com.tgwgroup.MiRearScreenSwitcher")) {
                        continue;
                    }

                    reader.close();
                    return packageName + ":" + taskId;
                }
            }
            reader.close();
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error getting current app", e);
            return null;
        }
    }

    @Override
    public int getTaskIdByPackage(String packageName) throws RemoteException {
        try {
            ShellExecutor.Result res = runShell("am stack list");
            BufferedReader reader = new BufferedReader(new StringReader(res.stdout));

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("taskId=") && line.contains(packageName)) {
                    int start = line.indexOf("taskId=") + 7;
                    int end = line.indexOf(':', start);
                    if (end <= start) continue;
                    String taskId = line.substring(start, end).trim();
                    int tid = Integer.parseInt(taskId);
                    reader.close();
                    return tid;
                }
            }
            reader.close();
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "Error getting taskId", e);
            return -1;
        }
    }

    @Override
    public boolean moveTaskToDisplay(int taskId, int displayId) throws RemoteException {
        try {
            String packageName = getPackageNameFromTaskId(taskId);
            String cmd = "service call activity_task 50 i32 " + taskId + " i32 " + displayId;

            ShellExecutor.Result res = runShell(cmd);
            boolean success = (res.exitCode == 0);

            if (success && displayId == 1) {
                try {
                    if (packageName != null && !"com.tgwgroup.MiRearScreenSwitcher".equals(packageName)) {
                        RearScreenBroadcastReceiver.saveLastTask(packageName, taskId);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "❌ Failed to save task info", e);
                }
            }
            return success;
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in moveTaskToDisplay", e);
            return false;
        }
    }

    private String getPackageNameFromTaskId(int taskId) {
        try {
            ShellExecutor.Result res = runShell("am stack list");
            BufferedReader reader = new BufferedReader(new StringReader(res.stdout));

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("taskId=" + taskId) && line.contains("/")) {
                    int pkgStart = line.indexOf(':') + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    if (pkgEnd > pkgStart) {
                        String packageName = line.substring(pkgStart, pkgEnd).trim();
                        reader.close();
                        return packageName;
                    }
                }
            }
            reader.close();
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error getting package name from taskId", e);
            return null;
        }
    }

    @Override
    public boolean launchWakeActivity(int displayId) throws RemoteException {
        try {
            String cmd = "am start --display " + displayId +
                        " -n com.tgwgroup.MiRearScreenSwitcher/.RearScreenWakeupActivity";
            ShellExecutor.Result res = runShell(cmd);
            return (res.exitCode == 0);
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in launchWakeActivity", e);
            return false;
        }
    }

    @Override
    public boolean disableSubScreenLauncher() throws RemoteException {
        try {
            String killCmd = "am force-stop com.xiaomi.subscreencenter";
            ShellExecutor.Result res = runShell(killCmd);
            return (res.exitCode == 0);
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in disableSubScreenLauncher", e);
            return false;
        }
    }

    @Override
    public boolean isLauncherProcessRunning() throws RemoteException {
        try {
            String cmd = "ps -A | grep com.xiaomi.subscreencenter";
            ShellExecutor.Result res = runShell(cmd);
            String line = res.stdout != null ? res.stdout.trim() : "";
            return !line.isEmpty();
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in isLauncherProcessRunning", e);
            return false;
        }
    }

    @Override
    public boolean killLauncherProcess() throws RemoteException {
        try {
            String cmd = "am force-stop com.xiaomi.subscreencenter";
            ShellExecutor.Result res = runShell(cmd);
            return (res.exitCode == 0);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean enableSubScreenLauncher() throws RemoteException {
        try {
            String startCmd = "am start --display 1 -n com.xiaomi.subscreencenter/.SubScreenLauncher";
            runShell(startCmd);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in enableSubScreenLauncher", e);
            return false;
        }
    }

    @Override
    public boolean forceStatusBarToMainDisplay() throws RemoteException {
        try {
            String expandCmd = "cmd statusbar expand-settings";
            ShellExecutor.Result res1 = runShell(expandCmd);

            if (res1.exitCode == 0) {
                Thread.sleep(30);
                String collapseCmd = "cmd statusbar collapse";
                runShell(collapseCmd);
            }

            String wmCmd = "wm set-display-type 0 home";
            runShell(wmCmd);

            runShell("dumpsys window displays | grep -A20 'Display: 0'");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in forceStatusBarToMainDisplay", e);
            return false;
        }
    }

    @Override
    public boolean collapseStatusBar() throws RemoteException {
        try {
            String cmd = "cmd statusbar collapse";
            ShellExecutor.Result res = runShell(cmd);
            return (res.exitCode == 0);
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in collapseStatusBar", e);
            return false;
        }
    }

    @Override
    public int getCurrentRearDpi() throws RemoteException {
        try {
            String cmd = "wm density -d 1";
            ShellExecutor.Result res = runShell(cmd);
            BufferedReader reader = new BufferedReader(new StringReader(res.stdout));

            String line;
            int dpi = 0;
            while ((line = reader.readLine()) != null) {
                if (line.contains("density:")) {
                    String[] parts = line.split(":");
                    if (parts.length > 1) {
                        try {
                            String dpiStr = parts[1].trim();
                            if (line.contains("Override density")) {
                                dpi = Integer.parseInt(dpiStr);
                                break;
                            } else if (dpi == 0) {
                                dpi = Integer.parseInt(dpiStr);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }
            reader.close();
            return dpi;
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in getCurrentRearDpi", e);
            return 0;
        }
    }

    @Override
    public boolean setRearDpi(int dpi) throws RemoteException {
        try {
            String cmd = "wm density " + dpi + " -d 1";
            ShellExecutor.Result res = runShell(cmd);
            return (res.exitCode == 0);
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in setRearDpi", e);
            return false;
        }
    }

    @Override
    public boolean resetRearDpi() throws RemoteException {
        try {
            String cmd = "wm density reset -d 1";
            ShellExecutor.Result res = runShell(cmd);
            return (res.exitCode == 0);
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in resetRearDpi", e);
            return false;
        }
    }

    @Override
    public boolean takeRearScreenshot() throws RemoteException {
        try {
            try {
                executeShellCommand("input -d 1 keyevent KEYCODE_WAKEUP");
                Thread.sleep(200);
            } catch (Exception e) {
                Log.w(TAG, "背屏keycode wakeup失败: " + e.getMessage());
            }

            String mkdirCmd = "mkdir -p /storage/emulated/0/Pictures/RearDisplay";
            runShell(mkdirCmd);

            String getDisplayIdCmd = "dumpsys SurfaceFlinger --display-id | grep -oE 'Display [0-9]+' | awk 'NR==2{print $2}'";
            ShellExecutor.Result dispRes = runShell(getDisplayIdCmd);
            String displayId = dispRes.stdout != null ? dispRes.stdout.trim() : "";
            if (displayId.isEmpty()) {
                displayId = "1";
            } else if (displayId.contains("\n")) {
                displayId = displayId.split("\\r?\\n")[0].trim();
            }

            String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss")
                .format(new java.util.Date());
            String filename = "/storage/emulated/0/Pictures/RearDisplay/RD_" + timestamp + ".png";

            String screenshotCmd = "screencap -p -d " + displayId + " " + filename;
            runShell(screenshotCmd);

            String refreshCmd = "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://" + filename;
            runShell(refreshCmd);

            return true;
        } catch (Exception e) {
            Log.e(TAG, "❌ EXCEPTION in takeRearScreenshot", e);
            return true;
        }
    }

    @Override
    public boolean isTaskOnDisplay(int taskId, int displayId) throws RemoteException {
        try {
            ShellExecutor.Result res = runShell("am stack list");
            BufferedReader reader = new BufferedReader(new StringReader(res.stdout));

            boolean inTargetDisplay = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("RootTask")) {
                    inTargetDisplay = line.contains("displayId=" + displayId);
                    continue;
                }

                if (inTargetDisplay && line.contains("taskId=" + taskId)) {
                    reader.close();
                    return true;
                }
            }
            reader.close();
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Error checking task on display", e);
            return false;
        }
    }

    @Override
    public String getForegroundAppOnDisplay(int displayId) throws RemoteException {
        try {
            ShellExecutor.Result res = runShell("am stack list");
            BufferedReader reader = new BufferedReader(new StringReader(res.stdout));

            boolean inTargetDisplay = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("RootTask")) {
                    inTargetDisplay = line.contains("displayId=" + displayId);
                    continue;
                }

                if (inTargetDisplay && line.contains("taskId=") && line.contains("/")) {
                    int tidStart = line.indexOf("taskId=") + 7;
                    int tidEnd = line.indexOf(':', tidStart);
                    if (tidEnd <= tidStart) continue;
                    String taskId = line.substring(tidStart, tidEnd).trim();

                    int pkgStart = tidEnd + 2;
                    int pkgEnd = line.indexOf('/', pkgStart);
                    if (pkgEnd <= pkgStart) continue;
                    String packageName = line.substring(pkgStart, pkgEnd).trim();

                    reader.close();
                    return packageName + ":" + taskId;
                }
            }
            reader.close();
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error getting foreground app on display", e);
            return null;
        }
    }

    @Override
    public boolean setDisplayRotation(int displayId, int rotation) throws RemoteException {
        try {
            String currentApp;
            int currentTaskId = -1;
            if (displayId == 1) {
                currentApp = getForegroundAppOnDisplay(1);
                if (currentApp != null && currentApp.contains(":")) {
                    String[] parts = currentApp.split(":");
                    try {
                        currentTaskId = Integer.parseInt(parts[1]);
                    } catch (Exception ignored) {}
                }
            }

            String cmd = "wm user-rotation -d " + displayId + " lock " + rotation;
            ShellExecutor.Result res = runShell(cmd);

            if (displayId == 1 && res.exitCode == 0 && currentTaskId > 0) {
                Thread.sleep(500);
                boolean stillOnRear = isTaskOnDisplay(currentTaskId, 1);
                if (!stillOnRear) {
                    moveTaskToDisplay(currentTaskId, 1);
                }
            }

            return (res.exitCode == 0);
        } catch (Exception e) {
            Log.e(TAG, "设置旋转异常", e);
            return false;
        }
    }

    @Override
    public int getDisplayRotation(int displayId) throws RemoteException {
        try {
            String cmd = "wm user-rotation -d " + displayId;
            ShellExecutor.Result res = runShell(cmd);
            BufferedReader reader = new BufferedReader(new StringReader(res.stdout));
            String line = reader.readLine();
            reader.close();

            if (line != null && !line.isEmpty()) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 2) {
                    try {
                        return Integer.parseInt(parts[1]);
                    } catch (Exception ignored) {}
                }
            }
            return 0;
        } catch (Exception e) {
            Log.e(TAG, "获取旋转异常", e);
            return 0;
        }
    }

    @Override
    public boolean executeShellCommand(String cmd) throws RemoteException {
        try {
            ShellExecutor.Result res = runShell(cmd);
            if (!res.stdout.trim().isEmpty()) {
                Log.d(TAG, "Command stdout: " + res.stdout.trim());
            }
            if (!res.stderr.trim().isEmpty()) {
                Log.w(TAG, "Command stderr: " + res.stderr.trim());
            }
            return (res.exitCode == 0);
        } catch (Exception e) {
            Log.e(TAG, "执行命令失败: " + cmd, e);
            return false;
        }
    }

    @Override
    public String executeShellCommandWithResult(String cmd) throws RemoteException {
        try {
            ShellExecutor.Result res = runShell(cmd);
            return res.stdout;
        } catch (Exception e) {
            Log.e(TAG, "执行命令失败: " + cmd, e);
            return "";
        }
    }

    @Override
    public boolean startRearPullDownWatcher() throws RemoteException {
        // In Local ADB mode (customExecutor != null), RearPullDownWatcher streams directly from MrssShellDaemon.
        if (customExecutor != null) {
            return false;
        }
        synchronized (pullDownWatcherLock) {
            if (pullDownWatcherRunning && pullDownWatcherProcess != null && pullDownWatcherProcess.isAlive()) {
                return true;
            }
            stopRearPullDownWatcherLocked();
            pullDownWatcherRunning = true;

            Thread watcherThread = new Thread(() -> {
                Process proc = null;
                try {
                    ProcessBuilder pb = new ProcessBuilder(
                            "logcat", "-v", "brief", "-T", "1",
                            "-s", "SubScreenCenter_ServiceAssistantGesture:D");
                    pb.redirectErrorStream(true);
                    proc = pb.start();
                    synchronized (pullDownWatcherLock) {
                        if (!pullDownWatcherRunning) {
                            proc.destroyForcibly();
                            return;
                        }
                        pullDownWatcherProcess = proc;
                    }
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8), 4096)) {
                        String line;
                        while (pullDownWatcherRunning && (line = reader.readLine()) != null) {
                            if (line.contains("mTargetPosition = 0")) {
                                long now = System.currentTimeMillis();
                                if (now - lastPullDownBroadcastMs >= 1500L) {
                                    lastPullDownBroadcastMs = now;
                                    Log.d(TAG, "⬇️ Shizuku watcher matched [" + line.trim() + "] -> broadcasting ACTION_REAR_PULLDOWN");
                                    try {
                                        runShell("am broadcast -a com.tgwgroup.MiRearScreenSwitcher.ACTION_REAR_PULLDOWN -p com.tgwgroup.MiRearScreenSwitcher");
                                    } catch (Throwable ignored) {}
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "RearPullDownWatcher (Shizuku) stopped: " + t.getMessage());
                } finally {
                    if (proc != null) {
                        try {
                            proc.destroyForcibly();
                        } catch (Throwable ignored) {}
                    }
                    synchronized (pullDownWatcherLock) {
                        if (pullDownWatcherProcess == proc) {
                            pullDownWatcherProcess = null;
                        }
                    }
                }
            }, "MRSS-ShizukuPullDownWatcher");
            watcherThread.setDaemon(true);
            watcherThread.start();
            return true;
        }
    }

    @Override
    public boolean stopRearPullDownWatcher() throws RemoteException {
        synchronized (pullDownWatcherLock) {
            stopRearPullDownWatcherLocked();
        }
        return true;
    }

    private void stopRearPullDownWatcherLocked() {
        pullDownWatcherRunning = false;
        Process proc = pullDownWatcherProcess;
        pullDownWatcherProcess = null;
        if (proc != null) {
            try {
                proc.destroyForcibly();
            } catch (Throwable ignored) {}
        }
    }

    @Override
    public void showRearMediaPill(boolean isPlaying, int x, int y) throws RemoteException {
        try {
            if (customExecutor != null) {
                customExecutor.showRearMediaPill(isPlaying, x, y);
            } else {
                pillRenderer.showOrUpdate(isPlaying, x, y);
            }
        } catch (Throwable t) {
            Log.w(TAG, "showRearMediaPill failed: " + t.getMessage());
        }
    }

    @Override
    public void showRearMediaPillStyled(boolean isPlaying, int x, int y, float scale, int tintColor) throws RemoteException {
        try {
            if (customExecutor != null) {
                customExecutor.showRearMediaPillStyled(isPlaying, x, y, scale, tintColor);
            } else {
                pillRenderer.showOrUpdate(isPlaying, x, y, scale, tintColor);
            }
        } catch (Throwable t) {
            Log.w(TAG, "showRearMediaPillStyled failed: " + t.getMessage());
        }
    }

    @Override
    public void hideRearMediaPill() throws RemoteException {
        try {
            if (customExecutor != null) {
                customExecutor.hideRearMediaPill();
            } else {
                pillRenderer.hide();
            }
        } catch (Throwable t) {
            Log.w(TAG, "hideRearMediaPill failed: " + t.getMessage());
        }
    }
}

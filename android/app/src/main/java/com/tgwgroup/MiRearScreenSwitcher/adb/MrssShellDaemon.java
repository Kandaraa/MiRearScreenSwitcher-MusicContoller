package com.tgwgroup.MiRearScreenSwitcher.adb;

import androidx.annotation.Keep;

import com.tgwgroup.MiRearScreenSwitcher.RearSurfacePillRenderer;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Resident privileged shell daemon running as UID 2000 (shell), spawned via app_process
 * once Local ADB connects.
 *
 * Features:
 * - Loopback TCP (127.0.0.1:43821) authenticated with a 192-bit random token.
 * - Immediately binds 127.0.0.1:43821 and flushes MRSS_DAEMON_READY:<port> to stdout.
 * - Reports its loaded APK signature (CMD_GET_INFO) and supports offline self-handover
 *   (CMD_UPGRADE) when the APK is updated without requiring an active Wi-Fi ADB session.
 */
@Keep
public class MrssShellDaemon {
    public static final int DEFAULT_PORT = 43821;
    public static final int PROTOCOL_VERSION = 1;
    private static final String APP_PACKAGE = "com.tgwgroup.MiRearScreenSwitcher";

    public static final byte CMD_PING = 1;
    public static final byte CMD_EXEC = 2;
    public static final byte CMD_EXEC_NOWAIT = 3;
    public static final byte CMD_GET_INFO = 4;
    public static final byte CMD_UPGRADE = 5;
    public static final byte CMD_STREAM_EXEC = 6;
    public static final byte CMD_PILL_SHOW = 7;
    public static final byte CMD_PILL_HIDE = 8;
    public static final byte CMD_SHUTDOWN = 9;
    public static final byte CMD_PILL_SHOW_STYLED = 10;

    private static final RearSurfacePillRenderer pillRenderer = new RearSurfacePillRenderer();

    @Keep
    public static void main(String[] args) {
        int port = DEFAULT_PORT;
        String expectedToken = "";
        String loadedApkSig = "";
        if (args != null && args.length >= 1) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException ignored) {}
        }
        if (args != null && args.length >= 2) {
            expectedToken = args[1];
        }
        if (args != null && args.length >= 3) {
            loadedApkSig = args[2];
        }

        final int daemonPort = port;
        final String token = expectedToken;
        final String apkSignature = loadedApkSig;
        final ExecutorService pool = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(daemonPort, 64, InetAddress.getByName("127.0.0.1"))) {
            serverSocket.setReuseAddress(true);
            System.out.println("MRSS_DAEMON_READY:" + daemonPort);
            System.out.flush();

            while (!serverSocket.isClosed()) {
                final Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                client.setSoTimeout(30000);
                pool.execute(() -> handleClient(serverSocket, client, daemonPort, token, apkSignature, pool));
            }
        } catch (Throwable t) {
            System.err.println("MrssShellDaemon fatal: " + t.getMessage());
            t.printStackTrace(System.err);
            System.err.flush();
        } finally {
            try {
                pillRenderer.shutdown();
            } catch (Throwable ignored) {}
            pool.shutdownNow();
            System.exit(0);
        }
    }

    private static void handleClient(
            ServerSocket serverSocket,
            Socket client,
            int daemonPort,
            String expectedToken,
            String apkSignature,
            ExecutorService pool) {
        try (Socket s = client;
             DataInputStream in = new DataInputStream(s.getInputStream());
             DataOutputStream out = new DataOutputStream(s.getOutputStream())) {

            String clientToken = in.readUTF();
            if (expectedToken != null && !expectedToken.isEmpty() && !expectedToken.equals(clientToken)) {
                out.writeBoolean(false);
                out.flush();
                return;
            }
            out.writeBoolean(true);

            while (!s.isClosed()) {
                byte cmdType = in.readByte();
                if (cmdType == CMD_PING) {
                    out.writeInt(PROTOCOL_VERSION);
                    out.flush();
                } else if (cmdType == CMD_GET_INFO) {
                    out.writeInt(PROTOCOL_VERSION);
                    writeString(out, apkSignature);
                    out.flush();
                } else if (cmdType == CMD_EXEC) {
                    String shellCmd = readString(in);
                    ExecResult res = executeLocalProcess(shellCmd);
                    out.writeInt(res.exitCode);
                    writeString(out, res.stdout);
                    writeString(out, res.stderr);
                    out.flush();
                } else if (cmdType == CMD_EXEC_NOWAIT) {
                    final String shellCmd = readString(in);
                    pool.execute(() -> executeLocalProcess(shellCmd));
                    out.writeInt(0);
                    out.flush();
                } else if (cmdType == CMD_UPGRADE) {
                    // Offline-safe self-handover: close ServerSocket first so the port is free,
                    // spawn the updated APK's MrssShellDaemon as UID 2000, reply OK, and exit.
                    String newApkPath = readString(in);
                    String newApkSig = readString(in);
                    try {
                        pillRenderer.shutdown();
                    } catch (Throwable ignored) {}
                    out.writeInt(0);
                    out.flush();
                    try {
                        serverSocket.close();
                    } catch (Throwable ignored) {}
                    String launchCmd = LocalAdbBackend.buildDetachedDaemonLaunchCommand(
                            newApkPath,
                            daemonPort,
                            expectedToken,
                            newApkSig
                    );
                    executeLocalProcess(launchCmd);
                    return;
                } else if (cmdType == CMD_STREAM_EXEC) {
                    // Dedicated long-lived streaming command on its own client socket thread.
                    // Never blocks other CMD_EXEC clients; destroys the child process immediately on socket close.
                    String shellCmd = readString(in);
                    s.setSoTimeout(0);
                    streamLocalProcess(s, in, out, shellCmd, pool);
                    return;
                } else if (cmdType == CMD_PILL_SHOW) {
                    boolean isPlaying = in.readBoolean();
                    int x = in.readInt();
                    int y = in.readInt();
                    pillRenderer.showOrUpdate(isPlaying, x, y);
                    out.writeInt(0);
                    out.flush();
                } else if (cmdType == CMD_PILL_SHOW_STYLED) {
                    boolean isPlaying = in.readBoolean();
                    int x = in.readInt();
                    int y = in.readInt();
                    float scale = in.readFloat();
                    int tintColor = in.readInt();
                    pillRenderer.showOrUpdate(isPlaying, x, y, scale, tintColor);
                    out.writeInt(0);
                    out.flush();
                } else if (cmdType == CMD_PILL_HIDE) {
                    pillRenderer.hide();
                    out.writeInt(0);
                    out.flush();
                } else if (cmdType == CMD_SHUTDOWN) {
                    try {
                        pillRenderer.shutdown();
                    } catch (Throwable ignored) {}
                    out.writeInt(0);
                    out.flush();
                    serverSocket.close();
                    return;
                } else {
                    return;
                }
            }
        } catch (Throwable ignored) {
            // Client disconnected or socket closed
        }
    }

    private static void streamLocalProcess(
            Socket socket,
            DataInputStream in,
            DataOutputStream out,
            String cmd,
            ExecutorService pool) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            process = pb.start();

            final Process p = process;
            // Monitor client socket EOF so closing the client kills the child process immediately
            // even while stdout is idle (e.g. logcat waiting for new lines).
            pool.execute(() -> {
                try {
                    while (!socket.isClosed() && in.read() != -1) {
                        // Ignore any keepalive bytes
                    }
                } catch (Throwable ignored) {
                } finally {
                    try {
                        p.destroyForcibly();
                    } catch (Throwable ignored) {}
                }
            });

            out.writeBoolean(true);
            out.flush();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 4096)) {
                String line;
                while (!socket.isClosed() && (line = reader.readLine()) != null) {
                    writeString(out, line);
                    out.flush();
                }
            }
        } catch (Throwable ignored) {
            // Client disconnected or process terminated
        } finally {
            if (process != null) {
                try {
                    process.destroyForcibly();
                } catch (Throwable ignored) {}
            }
        }
    }

    private static ExecResult executeLocalProcess(String cmd) {
        Process process = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            process = pb.start();

            final Process p = process;
            final StringBuilder stderrBuf = new StringBuilder();
            Thread errThread = new Thread(() -> {
                try (BufferedReader errReader = new BufferedReader(
                        new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8), 4096)) {
                    String line;
                    while ((line = errReader.readLine()) != null) {
                        stderrBuf.append(line).append('\n');
                    }
                } catch (Throwable ignored) {}
            });
            errThread.start();

            StringBuilder stdoutBuf = new StringBuilder();
            try (BufferedReader outReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8), 8192)) {
                String line;
                while ((line = outReader.readLine()) != null) {
                    stdoutBuf.append(line).append('\n');
                }
            }

            errThread.join(2000);
            int exitCode = process.waitFor();
            return new ExecResult(exitCode, stdoutBuf.toString(), stderrBuf.toString());
        } catch (Throwable t) {
            return new ExecResult(-1, "", t.getMessage() != null ? t.getMessage() : "Exec error");
        } finally {
            if (process != null) {
                try {
                    process.destroy();
                } catch (Throwable ignored) {}
            }
        }
    }

    public static void writeString(DataOutputStream out, String str) throws Exception {
        byte[] bytes = (str != null ? str : "").getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        if (bytes.length > 0) {
            out.write(bytes);
        }
    }

    public static String readString(DataInputStream in) throws Exception {
        int len = in.readInt();
        if (len <= 0) return "";
        if (len > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Payload too large: " + len);
        }
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static final class ExecResult {
        final int exitCode;
        final String stdout;
        final String stderr;

        ExecResult(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }
    }
}

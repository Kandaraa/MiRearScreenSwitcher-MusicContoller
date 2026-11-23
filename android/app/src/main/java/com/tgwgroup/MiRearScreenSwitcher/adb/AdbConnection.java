package com.tgwgroup.MiRearScreenSwitcher.adb;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * Full-featured ADB Transport Protocol client supporting both:
 * 1. Android 11+ Wireless Debugging (CNXN -> STLS -> TLS 1.3 handshake -> CNXN)
 * 2. Classic ADB over TCP (CNXN -> AUTH token signature / public key -> CNXN)
 * Supports concurrent multiplexed "shell:" streams with exact exit-code extraction.
 */
public class AdbConnection implements Closeable {
    private static final String TAG = "AdbConnection";

    public static final int A_CNXN = 0x4e584e43;
    public static final int A_AUTH = 0x48545541;
    public static final int A_STLS = 0x534c5453;
    public static final int A_OPEN = 0x4e45504f;
    public static final int A_OKAY = 0x59414b4f;
    public static final int A_CLSE = 0x45534c43;
    public static final int A_WRTE = 0x45545257;

    private static final int ADB_VERSION = 0x01000000;
    private static final int ADB_MAXDATA = 1024 * 1024;
    private static final int STLS_VERSION = 0x01000000;

    private static final int ADB_AUTH_TOKEN = 1;
    private static final int ADB_AUTH_SIGNATURE = 2;
    private static final int ADB_AUTH_RSAPUBLICKEY = 3;

    private static final String EXIT_CODE_MARKER = "__MRSS_EXIT_CODE__:";

    private final String host;
    private final int port;
    private final AdbKeyManager keyManager;

    private Socket rawSocket;
    private DataInputStream in;
    private DataOutputStream out;
    private final Object writeLock = new Object();

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger nextLocalId = new AtomicInteger(1);
    private final ConcurrentHashMap<Integer, ShellStream> activeStreams = new ConcurrentHashMap<>();
    private Thread readerThread;

    public AdbConnection(String host, int port, AdbKeyManager keyManager) {
        this.host = host;
        this.port = port;
        this.keyManager = keyManager;
    }

    public int getPort() {
        return port;
    }

    public boolean isConnected() {
        return connected.get() && !closed.get();
    }

    /**
     * Connects to adbd and completes the CNXN / STLS (TLS 1.3) or AUTH handshake.
     */
    public void connect(int connectTimeoutMs) throws Exception {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(12000);

        this.rawSocket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = new DataOutputStream(socket.getOutputStream());

        // Send initial CNXN
        byte[] banner = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,abb,fixed_push_symlink_timestamp,abb_exec,remount_shell,track_app,sendrecv_v2,sendrecv_v2_brotli,sendrecv_v2_lz4,sendrecv_v2_zstd,sendrecv_v2_dry_run_send\0"
                .getBytes(StandardCharsets.UTF_8);
        sendPacket(A_CNXN, ADB_VERSION, ADB_MAXDATA, banner);

        boolean sentSignature = false;
        while (true) {
            AdbPacket pkt = readPacket(this.in);
            if (pkt.command == A_STLS) {
                // Upgrade connection to TLS 1.3
                sendPacket(A_STLS, STLS_VERSION, 0, null);
                SSLContext sslContext = keyManager.createSslContext();
                SSLSocket sslSocket = (SSLSocket) sslContext.getSocketFactory()
                        .createSocket(this.rawSocket, host, port, true);
                try {
                    sslSocket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
                } catch (Exception ignored) {}
                sslSocket.startHandshake();
                sslSocket.setSoTimeout(0); // Reader thread blocks for multiplexed packets

                this.rawSocket = sslSocket;
                this.in = new DataInputStream(sslSocket.getInputStream());
                this.out = new DataOutputStream(sslSocket.getOutputStream());
            } else if (pkt.command == A_AUTH && pkt.arg0 == ADB_AUTH_TOKEN) {
                if (!sentSignature) {
                    sentSignature = true;
                    byte[] sig = keyManager.signAdbToken(pkt.payload);
                    sendPacket(A_AUTH, ADB_AUTH_SIGNATURE, 0, sig);
                } else {
                    // Server didn't recognize our RSA signature; send public key for user prompt
                    Log.w(TAG, "adbd requested public key authorization; sending RSA public key...");
                    this.rawSocket.setSoTimeout(30000);
                    byte[] pubKey = keyManager.getAdbPublicKeyPayload("mrss@localhost");
                    sendPacket(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, pubKey);
                }
            } else if (pkt.command == A_CNXN) {
                this.rawSocket.setSoTimeout(0);
                connected.set(true);
                startReaderLoop();
                Log.d(TAG, "✅ Connected to adbd on " + host + ":" + port);
                return;
            } else {
                throw new IOException(String.format("Unexpected ADB handshake packet: 0x%08x", pkt.command));
            }
        }
    }

    private void startReaderLoop() {
        readerThread = new Thread(() -> {
            try {
                while (!closed.get()) {
                    AdbPacket pkt = readPacket(this.in);
                    handleIncomingPacket(pkt);
                }
            } catch (EOFException eof) {
                Log.w(TAG, "ADB connection closed by peer (" + host + ":" + port + ")");
            } catch (Exception e) {
                if (!closed.get()) {
                    Log.w(TAG, "ADB reader loop terminated: " + e.getMessage());
                }
            } finally {
                close();
            }
        }, "MRSS-AdbReader-" + port);
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void handleIncomingPacket(AdbPacket pkt) {
        switch (pkt.command) {
            case A_OKAY: {
                int remoteId = pkt.arg0;
                int localId = pkt.arg1;
                ShellStream stream = activeStreams.get(localId);
                if (stream != null) {
                    stream.remoteId = remoteId;
                }
                break;
            }
            case A_WRTE: {
                int remoteId = pkt.arg0;
                int localId = pkt.arg1;
                ShellStream stream = activeStreams.get(localId);
                if (stream != null) {
                    stream.remoteId = remoteId;
                    if (pkt.payload != null && pkt.payload.length > 0) {
                        stream.appendData(pkt.payload);
                    }
                    try {
                        sendPacket(A_OKAY, localId, remoteId, null);
                    } catch (IOException ignored) {}
                }
                break;
            }
            case A_CLSE: {
                int remoteId = pkt.arg0;
                int localId = pkt.arg1;
                ShellStream stream = activeStreams.remove(localId);
                if (stream != null) {
                    try {
                        if (remoteId != 0) {
                            sendPacket(A_CLSE, localId, remoteId, null);
                        }
                    } catch (IOException ignored) {}
                    stream.complete();
                }
                break;
            }
            default:
                break;
        }
    }

    /**
     * Executes a shell command over a multiplexed ADB "shell:" stream and returns exitCode + stdout.
     */
    public ShellOutput executeShellCommand(String command, long timeoutMs) throws IOException {
        if (!isConnected()) {
            throw new IOException("ADB connection is not active");
        }
        int localId = nextLocalId.getAndIncrement();
        ShellStream stream = new ShellStream(localId);
        activeStreams.put(localId, stream);

        // Wrap command so we always capture the exact exit code at the end of stdout
        String wrapped = "(" + command + "\n); echo \"\\n" + EXIT_CODE_MARKER + "$?\"";
        byte[] openDest = ("shell:" + wrapped + "\0").getBytes(StandardCharsets.UTF_8);

        try {
            sendPacket(A_OPEN, localId, 0, openDest);
            boolean finished = stream.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                activeStreams.remove(localId);
                if (stream.remoteId != 0) {
                    try {
                        sendPacket(A_CLSE, localId, stream.remoteId, null);
                    } catch (IOException ignored) {}
                }
                throw new IOException("ADB shell command timed out after " + timeoutMs + "ms: " + command);
            }
            return parseShellOutput(stream.getOutputString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            activeStreams.remove(localId);
            throw new IOException("Interrupted waiting for ADB shell command", ie);
        }
    }

    static ShellOutput parseShellOutput(String rawOutput) {
        if (rawOutput == null) {
            return new ShellOutput(0, "");
        }
        int idx = rawOutput.lastIndexOf(EXIT_CODE_MARKER);
        if (idx < 0) {
            return new ShellOutput(0, rawOutput);
        }
        String cleanStdout = rawOutput.substring(0, idx);
        // Strip the synthetic newline immediately preceding the exit code marker
        if (cleanStdout.endsWith("\r\n")) {
            cleanStdout = cleanStdout.substring(0, cleanStdout.length() - 2);
        } else if (cleanStdout.endsWith("\n")) {
            cleanStdout = cleanStdout.substring(0, cleanStdout.length() - 1);
        }
        String codePart = rawOutput.substring(idx + EXIT_CODE_MARKER.length()).trim();
        int nl = codePart.indexOf('\n');
        if (nl >= 0) {
            codePart = codePart.substring(0, nl).trim();
        }
        int exitCode = 0;
        try {
            exitCode = Integer.parseInt(codePart);
        } catch (NumberFormatException ignored) {}
        return new ShellOutput(exitCode, cleanStdout);
    }

    private void sendPacket(int command, int arg0, int arg1, byte[] payload) throws IOException {
        byte[] data = payload != null ? payload : new byte[0];
        int crc = computeAdbCrc(data);
        int magic = ~command;

        ByteBuffer buf = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(command);
        buf.putInt(arg0);
        buf.putInt(arg1);
        buf.putInt(data.length);
        buf.putInt(crc);
        buf.putInt(magic);
        if (data.length > 0) {
            buf.put(data);
        }

        synchronized (writeLock) {
            if (out == null) {
                throw new IOException("ADB output stream closed");
            }
            out.write(buf.array());
            out.flush();
        }
    }

    private static AdbPacket readPacket(DataInputStream in) throws IOException {
        byte[] hdrBytes = new byte[24];
        in.readFully(hdrBytes);
        ByteBuffer hdr = ByteBuffer.wrap(hdrBytes).order(ByteOrder.LITTLE_ENDIAN);
        int command = hdr.getInt();
        int arg0 = hdr.getInt();
        int arg1 = hdr.getInt();
        int dataLen = hdr.getInt();
        int crc = hdr.getInt();
        int magic = hdr.getInt();

        if (magic != ~command) {
            throw new IOException(String.format("Invalid ADB packet magic: cmd=0x%08x magic=0x%08x", command, magic));
        }
        if (dataLen < 0 || dataLen > ADB_MAXDATA * 2) {
            throw new IOException("Invalid ADB packet payload size: " + dataLen);
        }
        byte[] payload = new byte[dataLen];
        if (dataLen > 0) {
            in.readFully(payload);
        }
        return new AdbPacket(command, arg0, arg1, crc, payload);
    }

    private static int computeAdbCrc(byte[] data) {
        int sum = 0;
        for (byte b : data) {
            sum += (b & 0xFF);
        }
        return sum;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            connected.set(false);
            for (ShellStream stream : activeStreams.values()) {
                stream.complete();
            }
            activeStreams.clear();
            try {
                if (rawSocket != null) {
                    rawSocket.close();
                }
            } catch (IOException ignored) {}
        }
    }

    public static final class ShellOutput {
        public final int exitCode;
        public final String stdout;

        public ShellOutput(int exitCode, String stdout) {
            this.exitCode = exitCode;
            this.stdout = stdout != null ? stdout : "";
        }
    }

    private static final class AdbPacket {
        final int command;
        final int arg0;
        final int arg1;
        final int crc;
        final byte[] payload;

        AdbPacket(int command, int arg0, int arg1, int crc, byte[] payload) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.crc = crc;
            this.payload = payload;
        }
    }

    private static final class ShellStream {
        final int localId;
        volatile int remoteId = 0;
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        final CountDownLatch latch = new CountDownLatch(1);

        ShellStream(int localId) {
            this.localId = localId;
        }

        synchronized void appendData(byte[] data) {
            buffer.write(data, 0, data.length);
        }

        synchronized String getOutputString() {
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }

        void complete() {
            latch.countDown();
        }
    }
}


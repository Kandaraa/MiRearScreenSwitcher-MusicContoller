package com.tgwgroup.MiRearScreenSwitcher.adb;

import android.util.Log;

import org.conscrypt.Conscrypt;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

/**
 * Android 11-17 Wireless Debugging Pairing Client (_adb-tls-pairing._tcp).
 * Uses the public org.conscrypt:conscrypt-android library for TLS 1.3 RFC 5705
 * exportKeyingMaterial without hidden-API reflection or sun.misc.Unsafe.
 */
public class AdbPairingClient {
    private static final String TAG = "AdbPairingClient";

    private static final byte HEADER_VERSION = 1;
    private static final byte PACKET_TYPE_SPAKE2_MSG = 0;
    private static final byte PACKET_TYPE_PEER_INFO = 1;

    private static final int PEER_INFO_SIZE = 8192;
    private static final byte PEER_INFO_TYPE_RSA_PUB_KEY = 0;
    private static final int GCM_TAG_BITS = 128;

    private static final String EXPORT_KEY_LABEL = "adb-label\u0000";
    private static final int EXPORT_KEY_SIZE = 64;
    private static final byte[] HKDF_INFO =
            "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.UTF_8);

    private final AdbKeyManager keyManager;

    public AdbPairingClient(AdbKeyManager keyManager) {
        this.keyManager = keyManager;
    }

    /**
     * Pairs with local adbd on the specified host and pairing port using the 6-digit pairing code.
     */
    public boolean pair(String host, int port, String pairingCode) throws Exception {
        if (pairingCode == null || pairingCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Pairing code cannot be empty");
        }
        String cleanCode = pairingCode.trim();
        SSLContext sslContext = keyManager.createSslContext();

        Socket plainSocket = new Socket();
        plainSocket.connect(new InetSocketAddress(host, port), 5000);
        plainSocket.setSoTimeout(15000);
        plainSocket.setTcpNoDelay(true);

        try (SSLSocket sslSocket = (SSLSocket) sslContext.getSocketFactory()
                .createSocket(plainSocket, host, port, true)) {
            try {
                sslSocket.setEnabledProtocols(new String[]{"TLSv1.3", "TLSv1.2"});
            } catch (Exception ignored) {}
            sslSocket.startHandshake();

            // 1. Export 64 bytes of TLS keying material ("adb-label\0") via public Conscrypt API
            byte[] exportedKey = Conscrypt.exportKeyingMaterial(sslSocket, EXPORT_KEY_LABEL, null, EXPORT_KEY_SIZE);
            if (exportedKey == null || exportedKey.length != EXPORT_KEY_SIZE) {
                throw new IOException("Failed to export TLS keying material from Conscrypt SSLSocket");
            }

            // 2. Concatenate pairingCode + exportedKeyMaterial
            byte[] codeBytes = cleanCode.getBytes(StandardCharsets.UTF_8);
            byte[] fullPassword = new byte[codeBytes.length + exportedKey.length];
            System.arraycopy(codeBytes, 0, fullPassword, 0, codeBytes.length);
            System.arraycopy(exportedKey, 0, fullPassword, codeBytes.length, exportedKey.length);

            DataInputStream in = new DataInputStream(sslSocket.getInputStream());
            DataOutputStream out = new DataOutputStream(sslSocket.getOutputStream());

            // 3. Generate & send 32-byte SPAKE2 message
            Spake2Engine spake2 = Spake2Engine.createAdbClient();
            byte[] ourSpakeMsg = spake2.generateMessage(fullPassword);
            writePacket(out, PACKET_TYPE_SPAKE2_MSG, ourSpakeMsg);

            // 4. Read peer's 32-byte SPAKE2 message
            Packet theirSpakePacket = readPacket(in);
            if (theirSpakePacket.type != PACKET_TYPE_SPAKE2_MSG || theirSpakePacket.payload.length != 32) {
                throw new IOException("Unexpected SPAKE2 packet from adbd (type=" + theirSpakePacket.type + ")");
            }

            // 5. Derive 64-byte SPAKE2 shared secret -> 16-byte AES-128-GCM key via HKDF-SHA256
            byte[] spakeSharedKey = spake2.processMessage(theirSpakePacket.payload);
            byte[] aesKey = hkdfSha256(spakeSharedKey, new byte[0], HKDF_INFO, 16);

            // 6. Construct 8192-byte PeerInfo containing our RSA public key
            byte[] pubKeyPayload = keyManager.getAdbPublicKeyPayload("mrss@localhost");
            byte[] peerInfo = new byte[PEER_INFO_SIZE];
            peerInfo[0] = PEER_INFO_TYPE_RSA_PUB_KEY;
            int copyLen = Math.min(pubKeyPayload.length, PEER_INFO_SIZE - 1);
            System.arraycopy(pubKeyPayload, 0, peerInfo, 1, copyLen);

            // 7. Encrypt and send PeerInfo (sequence = 0)
            byte[] encryptedPeerInfo = aesGcmTransform(Cipher.ENCRYPT_MODE, aesKey, 0L, peerInfo);
            writePacket(out, PACKET_TYPE_PEER_INFO, encryptedPeerInfo);

            // 8. Read and decrypt server's PeerInfo (sequence = 0)
            Packet theirPeerInfoPacket = readPacket(in);
            if (theirPeerInfoPacket.type != PACKET_TYPE_PEER_INFO) {
                throw new IOException("Expected PEER_INFO packet from adbd, got type=" + theirPeerInfoPacket.type);
            }
            byte[] serverPeerInfo = aesGcmTransform(Cipher.DECRYPT_MODE, aesKey, 0L, theirPeerInfoPacket.payload);
            if (serverPeerInfo.length != PEER_INFO_SIZE) {
                throw new IOException("Invalid decrypted PeerInfo size: " + serverPeerInfo.length);
            }

            String guid = extractNullTerminatedString(serverPeerInfo, 1);
            Log.d(TAG, "✅ Wireless Debugging pairing succeeded! Device GUID: " + guid);
            return true;
        }
    }

    /**
     * Standard RFC 5869 HKDF-SHA256 (Extract + Expand).
     */
    public static byte[] hkdfSha256(byte[] ikm, byte[] salt, byte[] info, int outLen) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        byte[] actualSalt = (salt == null || salt.length == 0) ? new byte[32] : salt;
        hmac.init(new SecretKeySpec(actualSalt, "HmacSHA256"));
        byte[] prk = hmac.doFinal(ikm);

        hmac.init(new SecretKeySpec(prk, "HmacSHA256"));
        byte[] result = new byte[outLen];
        byte[] t = new byte[0];
        int offset = 0;
        byte counter = 1;
        while (offset < outLen) {
            hmac.reset();
            hmac.update(t);
            if (info != null) {
                hmac.update(info);
            }
            hmac.update(counter++);
            t = hmac.doFinal();
            int copy = Math.min(t.length, outLen - offset);
            System.arraycopy(t, 0, result, offset, copy);
            offset += copy;
        }
        return result;
    }

    /**
     * AES-128-GCM encryption/decryption using AOSP pairing_auth's 12-byte little-endian sequence IV.
     */
    public static byte[] aesGcmTransform(int cipherMode, byte[] key16, long sequence, byte[] input) throws Exception {
        byte[] iv = new byte[12];
        ByteBuffer.wrap(iv).order(ByteOrder.LITTLE_ENDIAN).putLong(0, sequence);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(cipherMode, new SecretKeySpec(key16, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(input);
    }

    private static void writePacket(DataOutputStream out, byte type, byte[] payload) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN);
        header.put(HEADER_VERSION);
        header.put(type);
        header.putInt(payload.length);
        out.write(header.array());
        out.write(payload);
        out.flush();
    }

    private static Packet readPacket(DataInputStream in) throws IOException {
        byte version = in.readByte();
        if (version != HEADER_VERSION) {
            throw new IOException("Unsupported PairingPacketHeader version: " + version);
        }
        byte type = in.readByte();
        int len = in.readInt();
        if (len <= 0 || len > 65536) {
            throw new IOException("Invalid PairingPacketHeader payload length: " + len);
        }
        byte[] payload = new byte[len];
        in.readFully(payload);
        return new Packet(type, payload);
    }

    private static String extractNullTerminatedString(byte[] buf, int start) {
        int end = start;
        while (end < buf.length && buf[end] != 0) {
            end++;
        }
        return new String(buf, start, end - start, StandardCharsets.UTF_8);
    }

    private static final class Packet {
        final byte type;
        final byte[] payload;

        Packet(byte type, byte[] payload) {
            this.type = type;
            this.payload = payload;
        }
    }
}

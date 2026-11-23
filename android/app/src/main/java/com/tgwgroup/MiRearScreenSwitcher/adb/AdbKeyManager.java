package com.tgwgroup.MiRearScreenSwitcher.adb;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import org.conscrypt.Conscrypt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * Manages the persistent 2048-bit RSA keypair and self-signed X.509 certificate required for
 * both Android 11-17 Wireless Debugging (TLS 1.3 client certificate authentication) and classic
 * ADB over TCP (AUTH token signature & Android RSAPublicKey binary struct).
 *
 * Uses the public org.conscrypt:conscrypt-android provider so TLS 1.3 keying material export
 * works without hidden-API reflection or sun.misc.Unsafe on Android 17 (API 37).
 */
public class AdbKeyManager {
    private static final String TAG = "AdbKeyManager";
    private static final String PRIVATE_KEY_FILE = "mrss_adb_key.pk8";
    private static final String PUBLIC_KEY_FILE = "mrss_adb_key.pub";
    private static final String CERT_FILE = "mrss_adb_cert.der";
    private static final String KEY_ALIAS = "mrss_local_adb";

    // Standard ASN.1 DigestInfo header for SHA-1 (15 bytes) used in ADB_AUTH_TOKEN PKCS#1 v1.5 signing
    private static final byte[] SHA1_DIGEST_INFO_HEADER = new byte[]{
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private static volatile AdbKeyManager instance;

    private final File filesDir;
    private KeyPair keyPair;
    private X509Certificate certificate;

    private AdbKeyManager(Context context) {
        this.filesDir = context.getApplicationContext().getFilesDir();
        ensureKeysLoaded();
    }

    public static AdbKeyManager getInstance(Context context) {
        if (instance == null) {
            synchronized (AdbKeyManager.class) {
                if (instance == null) {
                    instance = new AdbKeyManager(context);
                }
            }
        }
        return instance;
    }

    /**
     * Package-private constructor for unit testing without Android Context.
     */
    AdbKeyManager(File storageDir) {
        this.filesDir = storageDir;
        ensureKeysLoaded();
    }

    public boolean hasPersistedKeys() {
        File privFile = new File(filesDir, PRIVATE_KEY_FILE);
        File pubFile = new File(filesDir, PUBLIC_KEY_FILE);
        File certFile = new File(filesDir, CERT_FILE);
        return privFile.exists() && pubFile.exists() && certFile.exists();
    }

    private synchronized void ensureKeysLoaded() {
        if (keyPair != null && certificate != null) {
            return;
        }
        File privFile = new File(filesDir, PRIVATE_KEY_FILE);
        File pubFile = new File(filesDir, PUBLIC_KEY_FILE);
        File certFile = new File(filesDir, CERT_FILE);

        if (privFile.exists() && pubFile.exists() && certFile.exists()) {
            try {
                byte[] privBytes = readFileBytes(privFile);
                byte[] pubBytes = readFileBytes(pubFile);
                byte[] certBytes = readFileBytes(certFile);

                KeyFactory kf = KeyFactory.getInstance("RSA");
                PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(privBytes));
                PublicKey publicKey = kf.generatePublic(new X509EncodedKeySpec(pubBytes));
                CertificateFactory cf = CertificateFactory.getInstance("X.509");
                X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certBytes));

                this.keyPair = new KeyPair(publicKey, privateKey);
                this.certificate = cert;
                return;
            } catch (Exception e) {
                Log.w(TAG, "Failed to load saved ADB keypair, regenerating: " + e.getMessage());
            }
        }

        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4), new SecureRandom());
            this.keyPair = kpg.generateKeyPair();

            byte[] certDer = generateSelfSignedX509Der(this.keyPair);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            this.certificate = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(certDer));

            writeFileBytes(privFile, this.keyPair.getPrivate().getEncoded());
            writeFileBytes(pubFile, this.keyPair.getPublic().getEncoded());
            writeFileBytes(certFile, certDer);
            Log.d(TAG, "✓ Generated and persisted new 2048-bit RSA keypair and X.509 cert for Local ADB");
        } catch (Exception e) {
            throw new RuntimeException("Unable to initialize Local ADB keypair", e);
        }
    }

    public KeyPair getKeyPair() {
        ensureKeysLoaded();
        return keyPair;
    }

    public X509Certificate getCertificate() {
        ensureKeysLoaded();
        return certificate;
    }

    /**
     * Signs a 20-byte ADB_AUTH_TOKEN payload according to AOSP adbd's RSA verification.
     */
    public byte[] signAdbToken(byte[] token) throws Exception {
        ensureKeysLoaded();
        byte[] digestInfo = new byte[SHA1_DIGEST_INFO_HEADER.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO_HEADER, 0, digestInfo, 0, SHA1_DIGEST_INFO_HEADER.length);
        System.arraycopy(token, 0, digestInfo, SHA1_DIGEST_INFO_HEADER.length, token.length);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.getPrivate());
        return cipher.doFinal(digestInfo);
    }

    /**
     * Formats the 2048-bit RSA public key into Android's 524-byte RSAPublicKey C-struct,
     * Base64-encodes it, and appends " <comment>\0".
     */
    public byte[] getAdbPublicKeyPayload(String comment) {
        ensureKeysLoaded();
        RSAPublicKey rsaPub = (RSAPublicKey) keyPair.getPublic();
        byte[] structBytes = encodeAndroidRsaPublicKeyStruct(rsaPub);
        String b64;
        try {
            b64 = Base64.encodeToString(structBytes, Base64.NO_WRAP);
        } catch (Throwable t) {
            b64 = java.util.Base64.getEncoder().encodeToString(structBytes);
        }
        String formatted = b64 + " " + (comment != null ? comment : "mrss@localhost") + "\0";
        return formatted.getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] encodeAndroidRsaPublicKeyStruct(RSAPublicKey publicKey) {
        final int numWords = 64; // 2048 / 32
        BigInteger b32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n = publicKey.getModulus();
        BigInteger r = BigInteger.ONE.shiftLeft(2048);
        BigInteger rr = r.multiply(r).mod(n);

        BigInteger n0 = n.mod(b32);
        BigInteger n0inv = n0.modInverse(b32).negate().mod(b32);

        int[] nWords = new int[numWords];
        int[] rrWords = new int[numWords];
        BigInteger tempN = n;
        BigInteger tempRR = rr;
        for (int i = 0; i < numWords; i++) {
            BigInteger[] divRemN = tempN.divideAndRemainder(b32);
            tempN = divRemN[0];
            nWords[i] = divRemN[1].intValue();

            BigInteger[] divRemRR = tempRR.divideAndRemainder(b32);
            tempRR = divRemRR[0];
            rrWords[i] = divRemRR[1].intValue();
        }

        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(numWords);
        buf.putInt(n0inv.intValue());
        for (int w : nWords) {
            buf.putInt(w);
        }
        for (int w : rrWords) {
            buf.putInt(w);
        }
        buf.putInt(publicKey.getPublicExponent().intValue());
        return buf.array();
    }

    /**
     * Creates a TLS 1.3 SSLContext backed by the public org.conscrypt provider (with standard
     * provider fallback for non-Android desktop JVM unit tests).
     */
    public SSLContext createSslContext() throws Exception {
        ensureKeysLoaded();
        KeyManager km = new X509ExtendedKeyManager() {
            @Override
            public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                return KEY_ALIAS;
            }

            @Override
            public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
                return KEY_ALIAS;
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return null;
            }

            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return new String[]{KEY_ALIAS};
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return null;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return KEY_ALIAS.equals(alias) ? new X509Certificate[]{certificate} : null;
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return KEY_ALIAS.equals(alias) ? keyPair.getPrivate() : null;
            }
        };

        TrustManager tm = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        SSLContext sslContext = null;
        try {
            if (Conscrypt.isAvailable()) {
                Provider conscryptProvider = Conscrypt.newProvider();
                sslContext = SSLContext.getInstance("TLSv1.3", conscryptProvider);
            }
        } catch (Throwable ignored) {
            // Fallback when running on desktop JVM without Android JNI libconscrypt_jni.so
        }
        if (sslContext == null) {
            try {
                sslContext = SSLContext.getInstance("TLSv1.3");
            } catch (Exception e) {
                sslContext = SSLContext.getInstance("TLS");
            }
        }
        sslContext.init(new KeyManager[]{km}, new TrustManager[]{tm}, new SecureRandom());
        return sslContext;
    }

    // =========================================================================
    // Pure-Java ASN.1 DER X.509 v3 Self-Signed Certificate Builder
    // =========================================================================

    private static byte[] generateSelfSignedX509Der(KeyPair keyPair) throws Exception {
        byte[] sha256WithRsaOid = new byte[]{
                0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b,
                0x05, 0x00
        };
        byte[] sigAlgId = derTag(0x30, sha256WithRsaOid);

        byte[] version = derTag(0xA0, new byte[]{0x02, 0x01, 0x02});
        byte[] serialNumber = new byte[]{0x02, 0x01, 0x01};
        byte[] cnValue = "MRSSLocalAdb".getBytes(StandardCharsets.UTF_8);
        byte[] atv = derTag(0x30, concat(new byte[]{0x06, 0x03, 0x55, 0x04, 0x03}, derTag(0x0C, cnValue)));
        byte[] rdn = derTag(0x31, atv);
        byte[] name = derTag(0x30, rdn);

        byte[] notBefore = derTag(0x18, "20240101000000Z".getBytes(StandardCharsets.US_ASCII));
        byte[] notAfter = derTag(0x18, "20491231235959Z".getBytes(StandardCharsets.US_ASCII));
        byte[] validity = derTag(0x30, concat(notBefore, notAfter));

        byte[] spki = keyPair.getPublic().getEncoded();

        byte[] tbsCertificate = derTag(0x30, concat(
                version,
                serialNumber,
                sigAlgId,
                name,
                validity,
                name,
                spki
        ));

        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(tbsCertificate);
        byte[] signatureBytes = signer.sign();

        byte[] bitStringBody = new byte[signatureBytes.length + 1];
        bitStringBody[0] = 0x00;
        System.arraycopy(signatureBytes, 0, bitStringBody, 1, signatureBytes.length);
        byte[] signatureBitString = derTag(0x03, bitStringBody);

        return derTag(0x30, concat(tbsCertificate, sigAlgId, signatureBitString));
    }

    private static byte[] derTag(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = content.length;
        if (len < 128) {
            out.write(len);
        } else if (len < 256) {
            out.write(0x81);
            out.write(len);
        } else if (len < 65536) {
            out.write(0x82);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(0x83);
            out.write((len >> 16) & 0xFF);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.write(content, 0, content.length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... arrays) {
        int total = 0;
        for (byte[] a : arrays) total += a.length;
        byte[] res = new byte[total];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, res, pos, a.length);
            pos += a.length;
        }
        return res;
    }

    private static byte[] readFileBytes(File f) throws IOException {
        byte[] data = new byte[(int) f.length()];
        try (FileInputStream fis = new FileInputStream(f)) {
            int read = 0;
            while (read < data.length) {
                int r = fis.read(data, read, data.length - read);
                if (r < 0) break;
                read += r;
            }
        }
        return data;
    }

    private static void writeFileBytes(File f, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
            fos.flush();
        }
    }
}

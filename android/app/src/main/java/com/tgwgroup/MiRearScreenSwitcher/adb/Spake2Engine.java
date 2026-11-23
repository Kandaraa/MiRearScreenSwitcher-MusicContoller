package com.tgwgroup.MiRearScreenSwitcher.adb;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Pure-Java implementation of BoringSSL's SPAKE2 over Ed25519 (crypto/curve25519/spake25519.cc).
 * Bit-for-bit compatible with Android 11+ adbd Wireless Debugging pairing_auth.
 */
public class Spake2Engine {
    // Field prime p = 2^255 - 19
    public static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    // Prime subgroup order l = 2^252 + 27742317777372353535851937790883648493
    public static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    // Ed25519 curve constant d = -121665 * 121666^-1 mod p
    public static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    public static final BigInteger TWO_D = D.add(D).mod(P);
    // sqrt(-1) mod p = 2^((p-1)/4) mod p
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2)
            .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);

    // Standard Ed25519 base point B
    public static final EdPoint BASE_POINT = new EdPoint(
            new BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
            new BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960")
    );

    // BoringSSL SPAKE2 M point (Alice generator)
    public static final EdPoint POINT_M = new EdPoint(
            new BigInteger("31406539342727633121250288103050113562375374900226415211311216773867585644232"),
            new BigInteger("21177308356423958466833845032658859666296341766942662650232962324899758529114")
    );

    // BoringSSL SPAKE2 N point (Bob generator)
    public static final EdPoint POINT_N = new EdPoint(
            new BigInteger("49918732221787544735331783592030787422991506689877079631459872391322455579424"),
            new BigInteger("54629554431565467720832445949441049581317094546788069926228343916274969994000")
    );

    public enum Role {
        ALICE, // adb pair client
        BOB    // adb pair server
    }

    private final Role myRole;
    private final byte[] myName;
    private final byte[] theirName;

    private BigInteger privateKeyScalar;
    private BigInteger passwordScalar;
    private byte[] passwordHash;
    private byte[] myMsg;

    public Spake2Engine(Role myRole, byte[] myName, byte[] theirName) {
        this.myRole = myRole;
        this.myName = Arrays.copyOf(myName, myName.length);
        this.theirName = Arrays.copyOf(theirName, theirName.length);
    }

    /**
     * Convenience factory for Android Wireless Debugging client role ("adb pair client\0").
     */
    public static Spake2Engine createAdbClient() {
        return new Spake2Engine(
                Role.ALICE,
                new byte[]{'a', 'd', 'b', ' ', 'p', 'a', 'i', 'r', ' ', 'c', 'l', 'i', 'e', 'n', 't', 0},
                new byte[]{'a', 'd', 'b', ' ', 'p', 'a', 'i', 'r', ' ', 's', 'e', 'r', 'v', 'e', 'r', 0}
        );
    }

    /**
     * Generates the 32-byte SPAKE2 message to send to the peer (SPAKE2_generate_msg).
     */
    public byte[] generateMessage(byte[] password) throws Exception {
        byte[] rand64 = new byte[64];
        new SecureRandom().nextBytes(rand64);
        return generateMessageWithEntropy(password, rand64);
    }

    public byte[] generateMessageWithEntropy(byte[] password, byte[] rand64) throws Exception {
        // 1. Ephemeral private key: (rand64 mod L) * 8 (left_shift_3)
        BigInteger reducedPriv = fromLittleEndianUnsigned(rand64).mod(L);
        this.privateKeyScalar = reducedPriv.shiftLeft(3);

        // 2. P = [privateKeyScalar] * B
        EdPoint pPoint = BASE_POINT.scalarMultiply(this.privateKeyScalar);

        // 3. Password hash & scalar reduction
        MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
        this.passwordHash = sha512.digest(password);
        BigInteger pwScalar = fromLittleEndianUnsigned(this.passwordHash).mod(L);

        // BoringSSL cofactor clearing hack on password_scalar:
        // Add L, 2*L, 4*L conditionally so bottom 3 bits of pwScalar are 0.
        if (pwScalar.testBit(0)) {
            pwScalar = pwScalar.add(L);
        }
        if (pwScalar.testBit(1)) {
            pwScalar = pwScalar.add(L.shiftLeft(1));
        }
        if (pwScalar.testBit(2)) {
            pwScalar = pwScalar.add(L.shiftLeft(2));
        }
        this.passwordScalar = pwScalar;

        // 4. mask = [passwordScalar] * (M for Alice, N for Bob)
        EdPoint generator = (myRole == Role.ALICE) ? POINT_M : POINT_N;
        EdPoint mask = generator.scalarMultiply(this.passwordScalar);

        // 5. P* = P + mask
        EdPoint pStar = pPoint.add(mask);
        this.myMsg = pStar.encode();
        return Arrays.copyOf(this.myMsg, 32);
    }

    /**
     * Processes the peer's 32-byte SPAKE2 message and returns the 64-byte shared key (SPAKE2_process_msg).
     */
    public byte[] processMessage(byte[] theirMsg) throws Exception {
        if (myMsg == null || theirMsg == null || theirMsg.length != 32) {
            throw new IllegalArgumentException("Invalid SPAKE2 state or message length");
        }

        // 1. Decode Q* from theirMsg
        EdPoint qStar = EdPoint.decode(theirMsg);

        // 2. Unmask peer's point: Q = Q* - [passwordScalar] * (N for Alice, M for Bob)
        EdPoint peerGenerator = (myRole == Role.ALICE) ? POINT_N : POINT_M;
        EdPoint peerMask = peerGenerator.scalarMultiply(this.passwordScalar);
        EdPoint qPoint = qStar.subtract(peerMask);

        // 3. Shared DH point: K = [privateKeyScalar] * Q
        EdPoint dhShared = qPoint.scalarMultiply(this.privateKeyScalar);
        byte[] dhSharedEncoded = dhShared.encode();

        // 4. Transcript hash (SHA-512 with 8-byte little-endian length prefixes)
        MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
        if (myRole == Role.ALICE) {
            updateWithLengthPrefix(sha512, myName);
            updateWithLengthPrefix(sha512, theirName);
            updateWithLengthPrefix(sha512, myMsg);
            updateWithLengthPrefix(sha512, theirMsg);
        } else {
            updateWithLengthPrefix(sha512, theirName);
            updateWithLengthPrefix(sha512, myName);
            updateWithLengthPrefix(sha512, theirMsg);
            updateWithLengthPrefix(sha512, myMsg);
        }
        updateWithLengthPrefix(sha512, dhSharedEncoded);
        updateWithLengthPrefix(sha512, passwordHash);

        return sha512.digest();
    }

    private static void updateWithLengthPrefix(MessageDigest digest, byte[] data) {
        long len = data.length;
        byte[] lenLe = new byte[8];
        for (int i = 0; i < 8; i++) {
            lenLe[i] = (byte) (len & 0xFF);
            len >>>= 8;
        }
        digest.update(lenLe);
        digest.update(data);
    }

    public static BigInteger fromLittleEndianUnsigned(byte[] bytes) {
        byte[] be = new byte[bytes.length + 1];
        for (int i = 0; i < bytes.length; i++) {
            be[bytes.length - i] = bytes[i];
        }
        return new BigInteger(be);
    }

    public static byte[] toLittleEndian32(BigInteger val) {
        byte[] res = new byte[32];
        byte[] be = val.toByteArray();
        // Copy least-significant bytes reversed into little-endian array
        int copyLen = Math.min(32, be.length);
        for (int i = 0; i < copyLen; i++) {
            res[i] = be[be.length - 1 - i];
        }
        return res;
    }

    // =========================================================================
    // Extended Twisted Edwards Curve Point (X : Y : Z : T) over F_p
    // -x^2 + y^2 = 1 + d*x^2*y^2, where x = X/Z, y = Y/Z, x*y = T/Z
    // =========================================================================

    public static final class EdPoint {
        public static final EdPoint IDENTITY = new EdPoint(
                BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);

        public final BigInteger X;
        public final BigInteger Y;
        public final BigInteger Z;
        public final BigInteger T;

        public EdPoint(BigInteger x, BigInteger y) {
            this.X = x.mod(P);
            this.Y = y.mod(P);
            this.Z = BigInteger.ONE;
            this.T = this.X.multiply(this.Y).mod(P);
        }

        public EdPoint(BigInteger X, BigInteger Y, BigInteger Z, BigInteger T) {
            this.X = X;
            this.Y = Y;
            this.Z = Z;
            this.T = T;
        }

        public EdPoint negate() {
            return new EdPoint(P.subtract(X).mod(P), Y, Z, P.subtract(T).mod(P));
        }

        public EdPoint subtract(EdPoint other) {
            return this.add(other.negate());
        }

        /**
         * Unified extended twisted Edwards addition formula (Hisil et al. 2008).
         */
        public EdPoint add(EdPoint other) {
            BigInteger A = (Y.subtract(X)).multiply(other.Y.subtract(other.X)).mod(P);
            BigInteger B = (Y.add(X)).multiply(other.Y.add(other.X)).mod(P);
            BigInteger C = T.multiply(TWO_D).mod(P).multiply(other.T).mod(P);
            BigInteger D_val = Z.multiply(other.Z).shiftLeft(1).mod(P);
            BigInteger E = B.subtract(A).mod(P);
            BigInteger F = D_val.subtract(C).mod(P);
            BigInteger G = D_val.add(C).mod(P);
            BigInteger H = B.add(A).mod(P);

            BigInteger X3 = E.multiply(F).mod(P);
            BigInteger Y3 = G.multiply(H).mod(P);
            BigInteger T3 = E.multiply(H).mod(P);
            BigInteger Z3 = F.multiply(G).mod(P);
            return new EdPoint(X3, Y3, Z3, T3);
        }

        public EdPoint scalarMultiply(BigInteger scalar) {
            EdPoint result = IDENTITY;
            EdPoint addend = this;
            int bitLen = scalar.bitLength();
            for (int i = 0; i < bitLen; i++) {
                if (scalar.testBit(i)) {
                    result = result.add(addend);
                }
                addend = addend.add(addend);
            }
            return result;
        }

        public byte[] encode() {
            BigInteger zInv = Z.modInverse(P);
            BigInteger x = X.multiply(zInv).mod(P);
            BigInteger y = Y.multiply(zInv).mod(P);
            byte[] out = toLittleEndian32(y);
            if (x.testBit(0)) {
                out[31] |= (byte) 0x80;
            }
            return out;
        }

        public static EdPoint decode(byte[] bytes) {
            if (bytes == null || bytes.length != 32) {
                throw new IllegalArgumentException("Ed25519 point must be 32 bytes");
            }
            byte[] yBytes = Arrays.copyOf(bytes, 32);
            int xSign = (yBytes[31] >>> 7) & 1;
            yBytes[31] &= 0x7F;

            BigInteger y = fromLittleEndianUnsigned(yBytes);
            if (y.compareTo(P) >= 0) {
                throw new IllegalArgumentException("Ed25519 y coordinate >= p");
            }

            // x^2 = (y^2 - 1) / (d*y^2 + 1) mod p
            BigInteger y2 = y.multiply(y).mod(P);
            BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
            BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
            BigInteger x2 = u.multiply(v.modInverse(P)).mod(P);

            if (x2.equals(BigInteger.ZERO)) {
                if (xSign == 1) {
                    throw new IllegalArgumentException("Invalid Ed25519 point: x=0 with sign=1");
                }
                return new EdPoint(BigInteger.ZERO, y);
            }

            // Candidate root: x = x2^((p+3)/8) mod p
            BigInteger x = x2.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
            if (!x.multiply(x).mod(P).equals(x2)) {
                x = x.multiply(SQRT_M1).mod(P);
            }
            if (!x.multiply(x).mod(P).equals(x2)) {
                throw new IllegalArgumentException("Point does not lie on Ed25519 curve");
            }
            if ((x.testBit(0) ? 1 : 0) != xSign) {
                x = P.subtract(x);
            }
            return new EdPoint(x, y);
        }
    }
}


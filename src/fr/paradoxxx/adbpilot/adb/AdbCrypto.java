package fr.paradoxxx.adbpilot.adb;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/** Clé RSA d'ADB Pilot (équivalent de ~/.android/adbkey) : signature du jeton AUTH et clé publique au format Android. */
public final class AdbCrypto {
    private static final int KEY_BITS = 2048;
    private static final int WORDS = KEY_BITS / 32;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a,
            0x05, 0x00, 0x04, 0x14};

    private final PrivateKey priv;
    private final RSAPublicKey pub;
    private final String name;

    private AdbCrypto(KeyPair kp, String name) {
        this.priv = kp.getPrivate();
        this.pub = (RSAPublicKey) kp.getPublic();
        this.name = name;
    }

    public static synchronized AdbCrypto loadOrCreate(File dir, String name) throws Exception {
        File fPriv = new File(dir, "adbkey");
        File fPub = new File(dir, "adbkey.x509");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (fPriv.isFile() && fPub.isFile()) {
            try {
                PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(read(fPriv)));
                PublicKey pb = kf.generatePublic(new X509EncodedKeySpec(read(fPub)));
                return new AdbCrypto(new KeyPair(pb, pk), name);
            } catch (Exception ignored) {
                // clé abîmée : on en refait une
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(new RSAKeyGenParameterSpec(KEY_BITS, RSAKeyGenParameterSpec.F4));
        KeyPair kp = g.generateKeyPair();
        dir.mkdirs();
        write(fPriv, kp.getPrivate().getEncoded());
        write(fPub, kp.getPublic().getEncoded());
        return new AdbCrypto(kp, name);
    }

    public byte[] sign(byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, priv);
        c.update(SHA1_PREFIX);
        return c.doFinal(token);
    }

    /** « base64(clé Android) nom\0 » envoyé avec AUTH RSAPUBLICKEY. */
    public byte[] publicKeyPayload() {
        byte[] enc = androidPublicKey();
        String s = b64(enc) + " " + name;
        byte[] b = s.getBytes();
        byte[] out = new byte[b.length + 1];
        System.arraycopy(b, 0, out, 0, b.length);
        return out;
    }

    /** Empreinte MD5 de la clé, comme l'affiche Android dans la demande d'autorisation. */
    public String fingerprint() {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] d = md.digest(b64(androidPublicKey()).getBytes());
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < d.length; i++) {
                if (i > 0) sb.append(':');
                sb.append(String.format("%02X", d[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    static String b64(byte[] d) {
        final String a = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int b0 = d[i] & 0xff, b1 = i + 1 < d.length ? d[i + 1] & 0xff : 0, b2 = i + 2 < d.length ? d[i + 2] & 0xff : 0;
            int v = (b0 << 16) | (b1 << 8) | b2;
            sb.append(a.charAt((v >> 18) & 63)).append(a.charAt((v >> 12) & 63));
            sb.append(i + 1 < d.length ? a.charAt((v >> 6) & 63) : '=');
            sb.append(i + 2 < d.length ? a.charAt(v & 63) : '=');
        }
        return sb.toString();
    }

    private byte[] androidPublicKey() {
        BigInteger r32 = BigInteger.ZERO.setBit(32);
        BigInteger n = pub.getModulus();
        BigInteger r = BigInteger.ZERO.setBit(WORDS * 32);
        BigInteger rr = r.modPow(BigInteger.valueOf(2), n);
        BigInteger rem = n.remainder(r32);
        BigInteger n0inv = rem.modInverse(r32);
        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + WORDS * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(WORDS);
        bb.putInt(n0inv.negate().intValue());
        BigInteger x = n;
        for (int i = 0; i < WORDS; i++) {
            BigInteger[] qr = x.divideAndRemainder(r32);
            x = qr[0];
            bb.putInt(qr[1].intValue());
        }
        x = rr;
        for (int i = 0; i < WORDS; i++) {
            BigInteger[] qr = x.divideAndRemainder(r32);
            x = qr[0];
            bb.putInt(qr[1].intValue());
        }
        bb.putInt(pub.getPublicExponent().intValue());
        return bb.array();
    }

    private static byte[] read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] b = new byte[(int) f.length()];
            int off = 0;
            while (off < b.length) {
                int n = in.read(b, off, b.length - off);
                if (n < 0) break;
                off += n;
            }
            return b;
        } finally {
            in.close();
        }
    }

    private static void write(File f, byte[] b) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(b);
        } finally {
            o.close();
        }
    }
}

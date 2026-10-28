package com.tvdash.launcher;

import android.content.Context;
import android.os.Looper;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * Talks to the TV's own debugging service on localhost:5555 and runs {@code input keyevent}.
 * That is the same path a real remote uses, so buttons work in any app. The user turns on
 * ADB debugging once and taps Allow. No extra libraries: this is the small ADB handshake.
 */
final class AdbRemote {
    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int VERSION = 0x01000000;
    private static final int MAX_DATA = 256 * 1024;
    private static final int PORT = 5555;

    private static final Object LOCK = new Object();
    private static Socket socket;
    private static InputStream in;
    private static OutputStream out;
    private static boolean ready;
    private static int nextId = 1;
    private static volatile long portCheckedAt;
    private static volatile boolean portWasOpen;

    private AdbRemote() {}

    static boolean ready() {
        synchronized (LOCK) {
            return ready && socket != null && socket.isConnected() && !socket.isClosed();
        }
    }

    /**
     * Quick check that something is listening. Never call this on the UI thread: a socket connect
     * there throws NetworkOnMainThreadException and kills the screen. Cached so polls stay cheap.
     */
    static boolean portOpen() {
        if (ready()) return true;
        if (Looper.getMainLooper().getThread() == Thread.currentThread()) return portWasOpen;
        long now = System.currentTimeMillis();
        if (now - portCheckedAt < 4000) return portWasOpen;
        boolean open = probe();
        portCheckedAt = now;
        portWasOpen = open;
        return open;
    }

    /** Used after the user turns debugging on. Waits long enough for them to tap Allow. */
    static String connectForAllow(Context context) {
        portCheckedAt = 0;
        synchronized (LOCK) {
            closeLocked();
            return ensureLocked(context.getApplicationContext(), 50000);
        }
    }

    static String keyevent(Context context, int code) {
        if (code <= 0) return "Unknown button";
        return shell(context, "input keyevent " + code);
    }

    static String shell(Context context, String command) {
        synchronized (LOCK) {
            String err = ensureLocked(context.getApplicationContext(), 8000);
            if (!ready) return err != null ? err : "Couldn't connect to the TV's debugging service.";
            try {
                execLocked(command);
                return null;
            } catch (IOException e) {
                closeLocked();
                return "The button connection dropped. Press it again.";
            }
        }
    }

    private static boolean probe() {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 400);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** @return null when the shell can take commands */
    private static String ensureLocked(Context context, int authWaitMs) {
        if (ready && socket != null && socket.isConnected() && !socket.isClosed()) return null;
        closeLocked();
        boolean sentKey = false;
        try {
            Keys keys = keys(context);
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.setSoTimeout(8000);
            socket = s;
            in = s.getInputStream();
            out = s.getOutputStream();
            sendLocked(A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes("UTF-8"));
            boolean signed = false;
            long deadline = System.currentTimeMillis() + authWaitMs;
            while (System.currentTimeMillis() < deadline) {
                int left = (int) Math.max(1, deadline - System.currentTimeMillis());
                s.setSoTimeout(Math.min(left, 8000));
                Msg msg = readLocked();
                if (msg.cmd == A_AUTH && msg.arg0 == 1) {
                    if (!signed) {
                        sendLocked(A_AUTH, 2, 0, sign(keys.priv, msg.data));
                        signed = true;
                    } else if (!sentKey) {
                        sendLocked(A_AUTH, 3, 0, keys.pub);
                        sentKey = true;
                        deadline = System.currentTimeMillis() + authWaitMs;
                    } else {
                        closeLocked();
                        return "The TV refused this app. Turn ADB debugging off and on, then try again.";
                    }
                } else if (msg.cmd == A_CNXN) {
                    ready = true;
                    s.setSoTimeout(5000);
                    return null;
                } else if (msg.cmd == A_CLSE) {
                    break;
                }
            }
        } catch (IOException e) {
            closeLocked();
            if (sentKey) return "Look at the TV and tap Allow on the debugging question, then try again.";
            if (!probe()) return "ADB debugging looks off.";
            return "Couldn't finish the debugging connection.";
        }
        closeLocked();
        if (sentKey) return "Look at the TV and tap Allow on the debugging question, then try again.";
        if (!probe()) return "ADB debugging looks off.";
        return "Couldn't finish the debugging connection.";
    }

    private static void execLocked(String command) throws IOException {
        int local = nextId++;
        if (nextId > 1000000) nextId = 1;
        sendLocked(A_OPEN, local, 0, ("shell:" + command + "\0").getBytes("UTF-8"));
        boolean opened = false;
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            int left = (int) Math.max(1, deadline - System.currentTimeMillis());
            socket.setSoTimeout(left);
            Msg msg = readLocked();
            if (msg.cmd == A_OKAY && msg.arg1 == local) {
                opened = true;
            } else if (msg.cmd == A_WRTE && msg.arg1 == local) {
                sendLocked(A_OKAY, local, msg.arg0, null);
            } else if (msg.cmd == A_CLSE && (msg.arg0 == local || msg.arg1 == local)) {
                if (!opened) throw new IOException("closed");
                return;
            }
        }
        throw new IOException("timeout");
    }

    private static void sendLocked(int cmd, int arg0, int arg1, byte[] data) throws IOException {
        if (data == null) data = new byte[0];
        writeInt(out, cmd);
        writeInt(out, arg0);
        writeInt(out, arg1);
        writeInt(out, data.length);
        writeInt(out, checksum(data));
        writeInt(out, cmd ^ 0xffffffff);
        if (data.length > 0) out.write(data);
        out.flush();
    }

    private static Msg readLocked() throws IOException {
        int cmd = readInt(in);
        int arg0 = readInt(in);
        int arg1 = readInt(in);
        int len = readInt(in);
        readInt(in); // checksum, ignored
        int magic = readInt(in);
        if (magic != (cmd ^ 0xffffffff)) throw new IOException("bad header");
        if (len < 0 || len > MAX_DATA) throw new IOException("bad length");
        byte[] data = len == 0 ? new byte[0] : readFully(in, len);
        Msg msg = new Msg();
        msg.cmd = cmd;
        msg.arg0 = arg0;
        msg.arg1 = arg1;
        msg.data = data;
        return msg;
    }

    private static void closeLocked() {
        ready = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        in = null;
        out = null;
    }

    private static final class Msg {
        int cmd, arg0, arg1;
        byte[] data;
    }

    private static final class Keys {
        final PrivateKey priv;
        final byte[] pub;

        Keys(PrivateKey priv, byte[] pub) {
            this.priv = priv;
            this.pub = pub;
        }
    }

    private static Keys keys(Context context) throws IOException {
        File dir = context.getFilesDir();
        File privFile = new File(dir, "adbkey.pk8");
        File pubFile = new File(dir, "adbkey.x509");
        try {
            if (!privFile.exists() || !pubFile.exists()) {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                gen.initialize(2048);
                KeyPair pair = gen.generateKeyPair();
                write(privFile, pair.getPrivate().getEncoded());
                write(pubFile, pair.getPublic().getEncoded());
            }
            KeyFactory factory = KeyFactory.getInstance("RSA");
            PrivateKey priv = factory.generatePrivate(new PKCS8EncodedKeySpec(read(privFile)));
            RSAPublicKey pub = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(read(pubFile)));
            byte[] blob = androidPublicKey(pub);
            String line = Base64.encodeToString(blob, Base64.NO_WRAP) + " tvdash@tv\0";
            return new Keys(priv, line.getBytes("UTF-8"));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not make a debugging key");
        }
    }

    /** Android's adb public-key struct: 2048-bit modulus, little-endian, with R^2 and n0inv. */
    static byte[] androidPublicKey(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate();
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        byte[] out = new byte[524];
        writeIntAt(out, 0, 64);
        writeIntAt(out, 4, n0inv.intValue());
        System.arraycopy(little(n, 256), 0, out, 8, 256);
        System.arraycopy(little(rr, 256), 0, out, 264, 256);
        writeIntAt(out, 520, key.getPublicExponent().intValue());
        return out;
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            Signature sig = Signature.getInstance("SHA1withRSA");
            sig.initSign(key);
            sig.update(token);
            return sig.sign();
        } catch (Exception e) {
            throw new IOException("Could not sign the debugging token");
        }
    }

    private static byte[] little(BigInteger v, int len) {
        byte[] be = v.toByteArray();
        byte[] le = new byte[len];
        for (int i = 0; i < len; i++) {
            int src = be.length - 1 - i;
            if (src >= 0) le[i] = be[src];
        }
        return le;
    }

    private static int checksum(byte[] data) {
        long sum = 0;
        for (byte b : data) sum += b & 0xff;
        return (int) sum;
    }

    private static void writeInt(OutputStream o, int v) throws IOException {
        o.write(v & 0xff);
        o.write((v >> 8) & 0xff);
        o.write((v >> 16) & 0xff);
        o.write((v >> 24) & 0xff);
    }

    private static void writeIntAt(byte[] b, int at, int v) {
        b[at] = (byte) (v & 0xff);
        b[at + 1] = (byte) ((v >> 8) & 0xff);
        b[at + 2] = (byte) ((v >> 16) & 0xff);
        b[at + 3] = (byte) ((v >> 24) & 0xff);
    }

    private static int readInt(InputStream i) throws IOException {
        int b0 = readByte(i);
        int b1 = readByte(i);
        int b2 = readByte(i);
        int b3 = readByte(i);
        return b0 | (b1 << 8) | (b2 << 16) | (b3 << 24);
    }

    private static int readByte(InputStream i) throws IOException {
        int b = i.read();
        if (b < 0) throw new IOException("closed");
        return b;
    }

    private static byte[] readFully(InputStream i, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int got = i.read(buf, off, n - off);
            if (got < 0) throw new IOException("closed");
            off += got;
        }
        return buf;
    }

    private static void write(File f, byte[] data) throws IOException {
        FileOutputStream o = new FileOutputStream(f);
        try {
            o.write(data);
        } finally {
            o.close();
        }
    }

    private static byte[] read(File f) throws IOException {
        FileInputStream i = new FileInputStream(f);
        try {
            byte[] buf = new byte[(int) f.length()];
            int off = 0;
            while (off < buf.length) {
                int n = i.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
            return buf;
        } finally {
            i.close();
        }
    }
}

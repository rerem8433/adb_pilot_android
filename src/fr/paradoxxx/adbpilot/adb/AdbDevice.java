package fr.paradoxxx.adbpilot.adb;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Commandes de haut niveau sur un appareil connecté (équivalent de « adb -s SERIE … »). */
public final class AdbDevice {
    public static final class Result {
        public final int rc;
        public final String out, err;

        public Result(int rc, String out, String err) {
            this.rc = rc;
            this.out = out;
            this.err = err;
        }

        public boolean ok() {
            return rc == 0;
        }

        public String text() {
            String e = err == null ? "" : err.trim();
            return (out + (e.isEmpty() ? "" : "\n" + e)).trim();
        }
    }

    public interface Progress {
        void onProgress(long done, long total);
    }

    public final String serial;
    public final AdbConnection conn;

    public AdbDevice(String serial, AdbConnection conn) {
        this.serial = serial;
        this.conn = conn;
    }

    public boolean shellV2() {
        return conn.hasFeature("shell_v2");
    }

    private static String norm(byte[] b) {
        return new String(b, AdbConnection.UTF8).replace("\r\n", "\n");
    }

    // ------------------------------------------------------------------ shell
    public Result shell(String cmd) {
        return shell(cmd, 60);
    }

    /** Commande shell non interactive ; code de sortie si l'appareil gère shell_v2. */
    public Result shell(String cmd, int timeoutSec) {
        final AdbStream s;
        try {
            s = conn.open((shellV2() ? "shell,v2,raw:" : "shell:") + cmd);
        } catch (IOException e) {
            return new Result(1, "", e.getMessage());
        }
        Watchdog wd = watchdog(s, timeoutSec);
        try {
            if (!shellV2()) {
                byte[] all = s.readAll();
                return new Result(0, norm(all), "");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
            int rc = -1;
            byte[] h = new byte[5];
            while (true) {
                try {
                    s.readFully(h, 0, 5);
                } catch (IOException e) {
                    break;
                }
                int id = h[0];
                int len = ByteBuffer.wrap(h, 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
                byte[] d = new byte[len];
                s.readFully(d, 0, len);
                if (id == 1) out.write(d);
                else if (id == 2) err.write(d);
                else if (id == 3 && len > 0) {
                    rc = d[0] & 0xff;
                    break;
                }
            }
            if (rc < 0) {
                if (wd != null && wd.fired) return new Result(124, norm(out.toByteArray()), "délai dépassé");
                rc = 0;
            }
            return new Result(rc, norm(out.toByteArray()), norm(err.toByteArray()));
        } catch (IOException e) {
            return new Result(1, "", e.getMessage());
        } finally {
            if (wd != null) wd.interrupt();
            s.close();
        }
    }

    static final class Watchdog extends Thread {
        volatile boolean fired;
        private final AdbStream s;
        private final int sec;

        Watchdog(AdbStream s, int sec) {
            this.s = s;
            this.sec = sec;
            setDaemon(true);
        }

        public void run() {
            try {
                Thread.sleep(sec * 1000L);
                fired = true;
                s.close();
            } catch (InterruptedException ignored) {
            }
        }
    }

    private static Watchdog watchdog(AdbStream s, int sec) {
        if (sec <= 0) return null;
        Watchdog w = new Watchdog(s, sec);
        w.start();
        return w;
    }

    /** Sortie binaire brute (exec-out). */
    public byte[] execOut(String cmd, int timeoutSec) throws IOException {
        AdbStream s = conn.open("exec:" + cmd);
        Watchdog wd = watchdog(s, timeoutSec);
        try {
            return s.readAll();
        } finally {
            if (wd != null) wd.interrupt();
            s.close();
        }
    }

    public AdbStream openExec(String cmd) throws IOException {
        return conn.open("exec:" + cmd);
    }

    /** Shell interactif (pty) pour la console. */
    public AdbStream openShell() throws IOException {
        return conn.open("shell:");
    }

    public AdbStream openService(String svc) throws IOException {
        return conn.open(svc);
    }

    public Result reboot(String target) {
        try {
            AdbStream s = conn.open("reboot:" + (target == null ? "" : target));
            byte[] b = s.poll(3000);
            s.close();
            return new Result(0, b == null ? "" : norm(b), "");
        } catch (IOException e) {
            return new Result(0, "", "");  // l'appareil coupe la connexion en redémarrant
        }
    }

    public Result tcpip(int port) {
        try {
            AdbStream s = conn.open("tcpip:" + port);
            byte[] b = s.readAll();
            s.close();
            return new Result(0, norm(b).trim(), "");
        } catch (IOException e) {
            return new Result(1, "", e.getMessage());
        }
    }

    // ------------------------------------------------------------------ sync (push / pull / stat)
    private static byte[] req(String id, int n) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        b.put(id.getBytes(AdbConnection.UTF8));
        b.putInt(n);
        return b.array();
    }

    private static byte[] cat(byte[] a, byte[] b) {
        byte[] r = new byte[a.length + b.length];
        System.arraycopy(a, 0, r, 0, a.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    private static int le(byte[] b, int off) {
        return ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /** {mode, taille, mtime} ou null si absent. */
    public long[] stat(String remote) throws IOException {
        AdbStream s = conn.open("sync:");
        try {
            byte[] p = remote.getBytes(AdbConnection.UTF8);
            s.write(cat(req("STAT", p.length), p));
            byte[] r = new byte[16];
            s.readFully(r, 0, 16);
            long mode = le(r, 4) & 0xffffffffL, size = le(r, 8) & 0xffffffffL, mtime = le(r, 12) & 0xffffffffL;
            s.write(req("QUIT", 0));
            if (mode == 0 && size == 0 && mtime == 0) return null;
            return new long[]{mode, size, mtime};
        } finally {
            s.close();
        }
    }

    public void push(File f, String remote, Progress pg) throws IOException {
        FileInputStream in = new FileInputStream(f);
        try {
            push(in, f.length(), remote, 0644, pg);
        } finally {
            in.close();
        }
    }

    public void push(InputStream in, long total, String remote, int mode, Progress pg) throws IOException {
        AdbStream s = conn.open("sync:");
        try {
            byte[] spec = (remote + "," + (0100000 | mode)).getBytes(AdbConnection.UTF8);
            s.write(cat(req("SEND", spec.length), spec));
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf, 0, buf.length)) > 0) {
                byte[] chunk = new byte[8 + n];
                System.arraycopy(req("DATA", n), 0, chunk, 0, 8);
                System.arraycopy(buf, 0, chunk, 8, n);
                s.write(chunk);
                done += n;
                if (pg != null) pg.onProgress(done, total);
            }
            s.write(req("DONE", (int) (System.currentTimeMillis() / 1000)));
            byte[] r = new byte[8];
            s.readFully(r, 0, 8);
            String id = new String(r, 0, 4, AdbConnection.UTF8);
            if ("FAIL".equals(id)) {
                byte[] msg = new byte[le(r, 4)];
                s.readFully(msg, 0, msg.length);
                throw new IOException(new String(msg, AdbConnection.UTF8));
            }
            s.write(req("QUIT", 0));
        } finally {
            s.close();
        }
    }

    public void pull(String remote, OutputStream out, Progress pg) throws IOException {
        long total = 0;
        try {
            long[] st = stat(remote);
            if (st != null) total = st[1];
        } catch (IOException ignored) {
        }
        AdbStream s = conn.open("sync:");
        try {
            byte[] p = remote.getBytes(AdbConnection.UTF8);
            s.write(cat(req("RECV", p.length), p));
            byte[] h = new byte[8];
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            while (true) {
                s.readFully(h, 0, 8);
                String id = new String(h, 0, 4, AdbConnection.UTF8);
                int len = le(h, 4);
                if ("DONE".equals(id)) break;
                if ("FAIL".equals(id)) {
                    byte[] msg = new byte[len];
                    s.readFully(msg, 0, len);
                    throw new IOException(new String(msg, AdbConnection.UTF8));
                }
                if (!"DATA".equals(id)) throw new IOException("réponse sync inattendue : " + id);
                while (len > 0) {
                    int n = Math.min(len, buf.length);
                    s.readFully(buf, 0, n);
                    out.write(buf, 0, n);
                    len -= n;
                    done += n;
                }
                if (pg != null) pg.onProgress(done, total);
            }
            s.write(req("QUIT", 0));
        } finally {
            s.close();
        }
    }

    public String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    // ------------------------------------------------------------------ installation
    /** Installe un APK ou un ensemble split (même application). flags : ex. ["-r","-g"]. */
    public Result install(List<File> apks, List<String> flags, Progress pg) {
        StringBuilder fl = new StringBuilder();
        for (String f : flags) fl.append(f).append(' ');
        long total = 0;
        for (File f : apks) total += f.length();
        final long tot = total;
        try {
            if (apks.size() == 1) {
                String tmp = "/data/local/tmp/adbpilot_install.apk";
                push(apks.get(0), tmp, pg);
                Result r = shell("pm install " + fl + q(tmp), 900);
                if (!r.ok() || r.text().contains("Unknown option")) {
                    if (r.text().contains("Unknown option -g")) {
                        r = shell("pm install " + fl.toString().replace("-g ", "") + q(tmp), 900);
                    }
                }
                shell("rm -f " + q(tmp), 20);
                return fixRc(r);
            }
            Result c = shell("pm install-create " + fl + "-S " + total, 60);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[(\\d+)\\]").matcher(c.text());
            if (!m.find()) return new Result(1, c.out, c.err.isEmpty() ? "install-create : " + c.text() : c.err);
            String sid = m.group(1);
            long base = 0;
            int idx = 0;
            for (final File f : apks) {
                String tmp = "/data/local/tmp/adbpilot_split_" + idx + ".apk";
                final long b0 = base;
                final Progress p0 = pg;
                push(f, tmp, new Progress() {
                    public void onProgress(long d, long t) {
                        if (p0 != null) p0.onProgress(b0 + d, tot);
                    }
                });
                base += f.length();
                Result w = shell("pm install-write -S " + f.length() + " " + sid + " " + idx + "_" + q(f.getName()) + " " + q(tmp), 600);
                shell("rm -f " + q(tmp), 20);
                if (!w.ok() || w.text().contains("Failure") || w.text().contains("Error")) {
                    shell("pm install-abandon " + sid, 30);
                    return new Result(1, w.out, w.err.isEmpty() ? w.text() : w.err);
                }
                idx++;
            }
            return fixRc(shell("pm install-commit " + sid, 600));
        } catch (IOException e) {
            return new Result(1, "", e.getMessage());
        }
    }

    private static Result fixRc(Result r) {
        if (r.text().contains("Failure") || r.text().contains("Exception")) return new Result(1, r.out, r.err);
        return r;
    }

    public static List<String> lines(String s) {
        List<String> out = new ArrayList<String>();
        for (String l : s.split("\n")) out.add(l);
        return out;
    }
}

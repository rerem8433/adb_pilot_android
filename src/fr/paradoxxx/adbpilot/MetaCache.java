package fr.paradoxxx.adbpilot;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import fr.paradoxxx.adbpilot.adb.AdbDevice;
import fr.paradoxxx.adbpilot.adb.AdbStream;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/** Noms / versions / icônes lus sur l'appareil par le helper, mis en cache sur la tablette. */
public final class MetaCache {
    public static final int ICON = 96;

    public interface Sink {
        /** false = arrêter. */
        boolean onMeta(Pkg p, boolean ok, int done, int total);
    }

    private static File dir() {
        return Config.cacheDir("meta");
    }

    private static String key(Pkg p, String lang) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] d = md.digest((p.name + "|" + p.path + "|" + p.baseSize + "|" + lang).getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 10; i++) sb.append(String.format("%02x", d[i]));
            return sb.toString();
        } catch (Exception e) {
            return p.name;
        }
    }

    public static boolean loadCached(Pkg p, String lang) {
        File f = new File(dir(), key(p, lang) + ".txt");
        if (!f.isFile()) return false;
        try {
            String[] parts = new String(Core.readAll(new java.io.FileInputStream(f)), "UTF-8").split("\t", -1);
            p.label = parts.length > 0 && !parts[0].isEmpty() ? parts[0] : null;
            p.version = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : null;
            File png = new File(dir(), key(p, lang) + ".png");
            if (png.isFile()) p.icon = BitmapFactory.decodeFile(png.getAbsolutePath());
            p.metaDone = true;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void save(Pkg p, String lang, byte[] png) {
        try {
            String k = key(p, lang);
            FileOutputStream o = new FileOutputStream(new File(dir(), k + ".txt"));
            o.write(((p.label == null ? "" : p.label) + "\t" + (p.version == null ? "" : p.version)).getBytes("UTF-8"));
            o.close();
            if (png != null && png.length > 0) {
                o = new FileOutputStream(new File(dir(), k + ".png"));
                o.write(png);
                o.close();
            }
        } catch (IOException ignored) {
        }
    }

    public static void clear() {
        Core.deleteTree(dir());
    }

    /** Lit noms/icônes de tous les paquets demandés en un seul appel du helper (sortie en continu). */
    public static void fetch(AdbDevice d, List<Pkg> todo, String lang, Sink sink) throws IOException {
        if (!Core.ensureHelper(d)) throw new IOException("impossible d'envoyer le helper sur l'appareil");
        StringBuilder list = new StringBuilder();
        Map<String, Pkg> byName = new java.util.HashMap<String, Pkg>();
        for (Pkg p : todo) {
            list.append(p.name).append('\t').append(p.path).append('\n');
            byName.put(p.name, p);
        }
        byte[] lb = list.toString().getBytes("UTF-8");
        String listPath = "/data/local/tmp/adbpilot-list.txt";
        d.push(new java.io.ByteArrayInputStream(lb), lb.length, listPath, 0644, null);
        AdbStream s = d.openExec(Core.HELPER_CMD + " meta " + listPath + " " + (lang.isEmpty() ? "''" : lang) + " " + ICON + " 2>/dev/null");
        int total = todo.size(), done = 0;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        try {
            int n;
            outer:
            while ((n = s.read(buf, 0, buf.length)) >= 0) {
                for (int i = 0; i < n; i++) {
                    if (buf[i] != '\n') {
                        line.write(buf[i]);
                        continue;
                    }
                    String ln = new String(line.toByteArray(), "UTF-8");
                    line.reset();
                    if (ln.equals("END")) break outer;
                    String[] p = ln.split("\t", -1);
                    if (p.length < 2) continue;
                    Pkg pk = byName.get(p[1]);
                    if (pk == null) continue;
                    boolean ok = false;
                    if ("M".equals(p[0]) && p.length >= 5) {
                        pk.label = p[2].isEmpty() ? null : p[2];
                        pk.version = p[3].isEmpty() ? null : p[3];
                        byte[] png = null;
                        if (!p[4].isEmpty()) {
                            try {
                                png = Base64.decode(p[4], Base64.DEFAULT);
                                Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length);
                                if (b != null) pk.icon = b;
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                        ok = pk.label != null || pk.icon != null;
                        save(pk, lang, png);
                    }
                    pk.metaDone = true;
                    done++;
                    if (!sink.onMeta(pk, ok, done, total)) break outer;
                }
            }
        } finally {
            s.close();
        }
        if (done < total) {
            for (Pkg p : todo) {
                if (!p.metaDone) {
                    done++;
                    if (!sink.onMeta(p, false, done, total)) break;
                }
            }
        }
    }
}

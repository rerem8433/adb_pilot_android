package fr.paradoxxx.adbpilot;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mises à jour : dernière release GitHub, téléchargement de l'APK, installation par-dessus (PackageInstaller). */
public final class Updater {
    public static final String REPO = "rerem8433/adb_pilot_android";
    public static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    public static final String RELEASES = "https://github.com/" + REPO + "/releases";
    public static final String ACTION_STATUS = "fr.paradoxxx.adbpilot.INSTALL_STATUS";

    public static final class Release {
        public String version, tag, notes, url, assetName, assetUrl;
        public long assetSize;
    }

    static int[] parse(String v) {
        int[] k = new int[4];
        Matcher m = Pattern.compile("\\d+").matcher(v == null ? "" : v);
        int i = 0;
        while (m.find() && i < 4) k[i++] = Integer.parseInt(m.group());
        return k;
    }

    public static boolean isNewer(String remote, String local) {
        int[] a = parse(remote), b = parse(local);
        for (int i = 0; i < 4; i++) if (a[i] != b[i]) return a[i] > b[i];
        return false;
    }

    /** Release plus récente que la version installée, sinon null. */
    public static Release check() throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "ADBPilot-Android/" + MainActivity.VERSION);
        c.setRequestProperty("Accept", "application/vnd.github+json");
        int code = c.getResponseCode();
        if (code == 404) {
            c.disconnect();
            return null;  // dépôt sans release publiée
        }
        JSONObject d = new JSONObject(new String(Core.readAll(c.getInputStream()), "UTF-8"));
        c.disconnect();
        String tag = d.optString("tag_name", "");
        if (d.optBoolean("draft") || d.optBoolean("prerelease") || !isNewer(tag, MainActivity.VERSION)) return null;
        Release r = new Release();
        r.tag = tag;
        r.version = tag.replaceFirst("^[vV]", "");
        r.notes = d.optString("body", "");
        r.url = d.optString("html_url", RELEASES);
        JSONArray as = d.optJSONArray("assets");
        if (as != null) for (int i = 0; i < as.length(); i++) {
            JSONObject a = as.getJSONObject(i);
            String n = a.optString("name");
            if (n.toLowerCase().endsWith(".apk")) {
                r.assetName = n;
                r.assetUrl = a.optString("browser_download_url");
                r.assetSize = a.optLong("size");
                break;
            }
        }
        return r;
    }

    public interface Prog {
        void on(long n, long total);
    }

    public static File download(Release r, Prog pg) throws Exception {
        File dest = new File(Config.cacheDir("update"), "adbpilot-update.apk");
        File tmp = new File(dest.getPath() + ".part");
        String u = r.assetUrl;
        HttpURLConnection c = null;
        for (int i = 0; i < 8; i++) {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "ADBPilot-Android/" + MainActivity.VERSION);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                u = new URL(new URL(u), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            break;
        }
        long total = c.getContentLength() > 0 ? c.getContentLength() : r.assetSize;
        InputStream in = c.getInputStream();
        FileOutputStream o = new FileOutputStream(tmp);
        try {
            byte[] b = new byte[256 * 1024];
            long n = 0;
            int k;
            while ((k = in.read(b)) > 0) {
                o.write(b, 0, k);
                n += k;
                if (pg != null) pg.on(n, total);
            }
        } finally {
            o.close();
            in.close();
            c.disconnect();
        }
        if (r.assetSize > 0 && tmp.length() != r.assetSize) {
            tmp.delete();
            throw new Exception("téléchargement incomplet");
        }
        dest.delete();
        tmp.renameTo(dest);
        return dest;
    }

    /** Installe l'APK par-dessus la version actuelle (Android demande confirmation). */
    public static void install(Context ctx, File apk) throws Exception {
        PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams p = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        p.setAppPackageName(ctx.getPackageName());
        p.setSize(apk.length());
        int id = pi.createSession(p);
        PackageInstaller.Session s = pi.openSession(id);
        try {
            OutputStream o = s.openWrite("base.apk", 0, apk.length());
            FileInputStream in = new FileInputStream(apk);
            byte[] b = new byte[256 * 1024];
            int k;
            while ((k = in.read(b)) > 0) o.write(b, 0, k);
            in.close();
            s.fsync(o);
            o.close();
            Intent it = new Intent(ACTION_STATUS).setPackage(ctx.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0);
            PendingIntent cb = PendingIntent.getBroadcast(ctx, id, it, flags);
            s.commit(cb.getIntentSender());
        } catch (Exception e) {
            s.abandon();
            throw e;
        } finally {
            s.close();
        }
    }
}

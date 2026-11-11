package fr.paradoxxx.adbpilot;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Catalogue du Store (copie GitHub sinon celle fournie), résolution des liens, téléchargement. */
public final class Store {
    public static final String REMOTE = "https://raw.githubusercontent.com/rerem8433/adb_pilot/main/store/catalog.json";
    static final String UA = "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 ADBPilot";

    /** GET avec redirections suivies même de https vers http (miroirs) et un User-Agent de navigateur. */
    public static byte[] http(String url, int timeoutMs) throws Exception {
        String u = url;
        for (int i = 0; i < 8; i++) {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            try {
                int code = c.getResponseCode();
                String loc = c.getHeaderField("Location");
                if (code >= 300 && code < 400 && loc != null) {
                    u = new URL(new URL(u), loc).toString();
                    continue;
                }
                if (code >= 400) throw new Exception("HTTP " + code + " sur " + new URL(u).getHost());
                return Core.readAll(c.getInputStream());
            } finally {
                c.disconnect();
            }
        }
        throw new Exception("trop de redirections");
    }

    private static File cached() {
        return new File(Config.cacheDir("store"), "catalog.json");
    }

    public static JSONObject load() {
        try {
            if (cached().isFile()) {
                JSONObject o = new JSONObject(new String(Core.readAll(new java.io.FileInputStream(cached())), "UTF-8"));
                if (o.optJSONArray("apps") != null) return o;
            }
        } catch (Exception ignored) {
        }
        try {
            return new JSONObject(new String(Core.readAll(Config.ctx.getAssets().open("store.json")), "UTF-8"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** true si le catalogue a changé. */
    public static boolean sync() {
        try {
            String t = Debloat.get(REMOTE);
            JSONObject o = new JSONObject(t);
            if (o.optJSONArray("apps") == null) return false;
            String old = cached().isFile() ? new String(Core.readAll(new java.io.FileInputStream(cached())), "UTF-8") : "";
            if (old.equals(t)) return false;
            FileOutputStream f = new FileOutputStream(cached());
            f.write(t.getBytes("UTF-8"));
            f.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static String family(String abi) {
        abi = abi == null ? "" : abi.toLowerCase(Locale.ROOT);
        if (abi.contains("arm64") || abi.contains("aarch64")) return "arm64";
        if (abi.contains("x86_64")) return "x86_64";
        if (abi.contains("x86")) return "x86";
        return "arm";
    }

    static String abiName(String fam) {
        if (fam.equals("arm64")) return "arm64-v8a";
        if (fam.equals("arm")) return "armeabi-v7a";
        return fam;
    }

    /** {url, name} ou {page} */
    public static String[] resolve(JSONObject app, String abi) throws Exception {
        JSONObject src = app.optJSONObject("source");
        if (src == null) throw new Exception("source inconnue");
        String fam = family(abi);
        if (src.has("fdroid")) {
            // F-Droid : dernière version publiée, une construction par architecture (suffixe du versionCode)
            String pkg = src.getString("fdroid");
            JSONObject d = new JSONObject(new String(http("https://f-droid.org/api/v1/packages/" + pkg, 20000), "UTF-8"));
            JSONArray ps = d.optJSONArray("packages");
            JSONObject suf = src.optJSONObject("abi_suffix");
            int want = suf != null && suf.has(fam) ? suf.getInt(fam) : -1;
            JSONObject best = null;
            if (ps != null) for (int i = 0; i < ps.length(); i++) {
                JSONObject p = ps.getJSONObject(i);
                if (want >= 0 && p.getLong("versionCode") % 10 != want) continue;
                if (best == null || p.getLong("versionCode") > best.getLong("versionCode")) best = p;
            }
            if (best == null) throw new Exception("aucune version F-Droid pour cette architecture (" + fam + ")");
            return new String[]{"https://f-droid.org/repo/" + pkg + "_" + best.getLong("versionCode") + ".apk",
                    pkg + "_" + best.optString("versionName", String.valueOf(best.getLong("versionCode"))) + "_" + abiName(fam) + ".apk"};
        }
        if (src.has("url")) {
            String u = src.getString("url");
            String path = new URL(u).getPath();
            String name = URLDecoder.decode(path.substring(path.lastIndexOf('/') + 1), "UTF-8");
            if (name.isEmpty()) name = app.optString("id") + ".apk";
            return new String[]{u, name};
        }
        if (src.has("github")) {
            // github.com/<repo>/releases/latest -> page HTML avec la liste des fichiers (sans l'API, souvent limitée)
            JSONObject rel = null;
            try {
                rel = new JSONObject(Debloat.get("https://api.github.com/repos/" + src.getString("github") + "/releases/latest"));
            } catch (Exception ignored) {
            }
            String pat = src.optString("asset_" + fam, src.optString("asset", "\\.apk$"));
            Pattern p = Pattern.compile(pat);
            if (rel != null) {
                JSONArray as = rel.optJSONArray("assets");
                if (as != null) {
                    for (int i = 0; i < as.length(); i++) {
                        JSONObject a = as.getJSONObject(i);
                        if (p.matcher(a.getString("name")).find())
                            return new String[]{a.getString("browser_download_url"), a.getString("name")};
                    }
                }
                throw new Exception("aucun APK correspondant dans la dernière version GitHub");
            }
            String tag = latestTag(src.getString("github"));
            String html = Debloat.get("https://github.com/" + src.getString("github") + "/releases/expanded_assets/" + tag);
            Matcher m = Pattern.compile("href=\"(/[^\"]+/releases/download/[^\"]+)\"").matcher(html);
            while (m.find()) {
                String href = m.group(1);
                String name = URLDecoder.decode(href.substring(href.lastIndexOf('/') + 1), "UTF-8");
                if (p.matcher(name).find()) return new String[]{"https://github.com" + href, name};
            }
            throw new Exception("aucun APK correspondant dans la dernière version GitHub");
        }
        if (src.has("index")) {
            String idx = src.getString("index");
            String html = Debloat.get(idx);
            String pat = src.optString("pattern", "[^\"'<>]+\\.apk").replace("{abi}", Pattern.quote(abiName(fam)));
            Matcher m = Pattern.compile(pat).matcher(html);
            TreeSet<String> names = new TreeSet<String>();
            while (m.find()) names.add(m.group());
            if (names.isEmpty()) throw new Exception("aucun APK trouvé sur " + idx);
            String name = names.last();
            return new String[]{new URL(new URL(idx), name).toString(), name};
        }
        if (src.has("page")) return new String[]{null, null, src.getString("page")};
        throw new Exception("source inconnue");
    }

    private static String latestTag(String repo) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL("https://github.com/" + repo + "/releases/latest").openConnection();
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(15000);
        String loc = c.getHeaderField("Location");
        c.disconnect();
        if (loc == null) throw new Exception("version GitHub introuvable");
        return loc.substring(loc.lastIndexOf('/') + 1);
    }

    public static String hostOf(JSONObject app) {
        JSONObject src = app.optJSONObject("source");
        if (src == null) return "";
        if (src.has("github")) return "GitHub · " + src.optString("github");
        if (src.has("fdroid")) return "F-Droid";
        String u = src.optString("url", src.optString("index", src.optString("page", "")));
        try {
            return new URL(u).getHost().replace("www.", "");
        } catch (Exception e) {
            return u;
        }
    }

    public interface Prog {
        void on(long n, long total);

        boolean cancelled();
    }

    public static File download(String url, String name, String appId, Prog pg) throws Exception {
        name = name.replaceAll("[^\\w.\\-]+", "_");
        String low = name.toLowerCase(Locale.ROOT);
        if (!(low.endsWith(".apk") || low.endsWith(".apks") || low.endsWith(".xapk") || low.endsWith(".apkm"))) name += ".apk";
        File dest = new File(Config.userDir("store"), appId + "__" + name);
        File tmp = new File(dest.getPath() + ".part");
        HttpURLConnection c = null;
        String u = url;
        for (int i = 0; i < 8; i++) {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", UA);
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
        String ctype = c.getContentType();
        long total = c.getContentLength();
        InputStream in = c.getInputStream();
        FileOutputStream o = new FileOutputStream(tmp);
        try {
            byte[] b = new byte[256 * 1024];
            long n = 0;
            int k;
            while ((k = in.read(b)) > 0) {
                if (pg != null && pg.cancelled()) throw new Exception("annulé");
                o.write(b, 0, k);
                n += k;
                if (pg != null) pg.on(n, total);
            }
        } finally {
            o.close();
            in.close();
            c.disconnect();
        }
        byte[] head = new byte[4];
        java.io.FileInputStream fi = new java.io.FileInputStream(tmp);
        int r = fi.read(head);
        fi.close();
        if (r < 4 || head[0] != 'P' || head[1] != 'K' || head[2] != 3 || head[3] != 4) {
            tmp.delete();
            throw new Exception("le fichier reçu n'est pas un APK (" + (ctype == null ? "type inconnu" : ctype)
                    + ") : le lien a sans doute expiré ou demande un navigateur");
        }
        dest.delete();
        tmp.renameTo(dest);
        return dest;
    }

    /** Lit nom/paquet/version/icône d'un APK local avec le PackageManager de la tablette. */
    public static Object[] apkInfo(File f, int iconSize) {
        try {
            PackageManager pm = Config.ctx.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(f.getAbsolutePath(), 0);
            if (pi == null) return null;
            pi.applicationInfo.sourceDir = f.getAbsolutePath();
            pi.applicationInfo.publicSourceDir = f.getAbsolutePath();
            String label = String.valueOf(pi.applicationInfo.loadLabel(pm));
            Bitmap icon = null;
            try {
                Drawable d = pi.applicationInfo.loadIcon(pm);
                icon = Bitmap.createBitmap(iconSize, iconSize, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(icon);
                d.setBounds(0, 0, iconSize, iconSize);
                d.draw(cv);
            } catch (Throwable ignored) {
            }
            return new Object[]{label, pi.packageName, pi.versionName, icon};
        } catch (Throwable e) {
            return null;
        }
    }

    public static List<JSONObject> apps(JSONObject cat) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        JSONArray a = cat.optJSONArray("apps");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null && o.has("id")) out.add(o);
        }
        return out;
    }
}

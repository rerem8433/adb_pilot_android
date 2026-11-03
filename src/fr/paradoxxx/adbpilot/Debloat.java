package fr.paradoxxx.adbpilot;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Listes debloat0-3 (dépôt GitHub d'ADB Pilot) + descriptions UAD-ng. */
public final class Debloat {
    public static final String[][] LEVELS = {
            {"Basique", "#8fd18f", "Bloatware courant des box et téléphones : applis préinstallées, services constructeur/opérateur, démos et outils d'usine (liste debloat0)."},
            {"Sûr", "#32d65a", "Basique + applis secondaires sans impact sur le système (calculatrice, fonds d'écran, outils constructeur…) (debloat0 + debloat1)."},
            {"Moyen", "#e6e62e", "Sûr + services constructeur plus profonds et applis Google « lite » (debloat0 à debloat2). À relire attentivement."},
            {"Maximal", "#ff4d4d", "Tout : ajoute des composants comme le Play Store ou la sauvegarde système (debloat0 à debloat3). Relis chaque ligne avant d'appliquer."},
    };
    public static final String REMOTE = "https://raw.githubusercontent.com/rerem8433/adb_pilot/main/debloat/";
    private static final Pattern PKG = Pattern.compile("^[A-Za-z][\\w]*(\\.[\\w]+)+$");
    private static JSONObject db;
    @SuppressWarnings("unchecked")
    private static final Set<String>[] cache = new Set[4];

    public static synchronized JSONObject db() {
        if (db == null) {
            try {
                db = new JSONObject(new String(Core.readAll(Config.ctx.getAssets().open("uad_lists.json")), "UTF-8"));
            } catch (Exception e) {
                db = new JSONObject();
            }
        }
        return db;
    }

    public static JSONObject info(String pkg) {
        return db().optJSONObject(pkg);
    }

    public static String removalFr(String r) {
        if ("Recommended".equals(r)) return "Recommandé";
        if ("Advanced".equals(r)) return "Avancé";
        if ("Expert".equals(r)) return "Expert";
        if ("Unsafe".equals(r)) return "Risqué";
        return "?";
    }

    private static Set<String> parse(String text) {
        Set<String> s = new HashSet<String>();
        for (String ln : text.split("\n")) {
            int h = ln.indexOf('#');
            if (h >= 0) ln = ln.substring(0, h);
            ln = ln.trim().replace("package:", "");
            if (!ln.isEmpty() && PKG.matcher(ln).matches()) s.add(ln);
        }
        return s;
    }

    private static synchronized Set<String> read(int lv) {
        if (cache[lv] == null) {
            String name = "debloat" + lv + ".db";
            File f = new File(Config.cacheDir("debloat"), name);
            try {
                byte[] b = f.isFile() ? Core.readAll(new java.io.FileInputStream(f)) : Core.readAll(Config.ctx.getAssets().open("debloat/" + name));
                cache[lv] = parse(new String(b, "UTF-8"));
            } catch (Exception e) {
                cache[lv] = new HashSet<String>();
            }
        }
        return cache[lv];
    }

    public static Set<String> levelPackages(int level) {
        Set<String> out = new HashSet<String>();
        for (int i = 0; i <= level; i++) out.addAll(read(i));
        return out;
    }

    /** Synchronisation silencieuse avec GitHub. */
    public static void sync() {
        try {
            String[] texts = new String[4];
            for (int lv = 0; lv < 4; lv++) {
                String t = get(REMOTE + "debloat" + lv + ".db");
                if (t.toLowerCase().contains("<html") || (lv == 0 && parse(t).size() < 5)) return;
                texts[lv] = t;
            }
            for (int lv = 0; lv < 4; lv++) {
                FileOutputStream o = new FileOutputStream(new File(Config.cacheDir("debloat"), "debloat" + lv + ".db"));
                o.write(texts[lv].getBytes("UTF-8"));
                o.close();
            }
            synchronized (Debloat.class) {
                for (int i = 0; i < 4; i++) cache[i] = null;
            }
        } catch (Exception ignored) {
        }
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "ADBPilot-Android");
        InputStream in = c.getInputStream();
        try {
            return new String(Core.readAll(in), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    static final String[] PROTECTED_PHONE = {
            "com.android.providers.", "com.android.phone", "com.android.server.telecom", "com.android.dialer",
            "com.google.android.dialer", "com.android.contacts", "com.google.android.contacts", "com.android.mms",
            "com.google.android.apps.messaging", "com.android.messaging", "com.android.nfc", "com.android.stk",
            "com.google.android.ims", "com.android.ims", "com.android.cellbroadcast", "com.google.android.cellbroadcast",
            "com.android.deskclock", "com.google.android.deskclock", "com.android.captiveportallogin",
            "com.android.companiondevicemanager", "com.android.inputmethod", "com.google.android.inputmethod",
            "com.google.android.apps.inputmethod", "com.android.managedprovisioning", "com.android.vending",
            "com.google.android.gms", "com.google.android.gsf", "com.google.android.syncadapters.",
            "com.google.android.tts", "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.systemui",
            "com.android.settings", "com.android.launcher", "com.google.android.apps.nexuslauncher",
            "com.android.camera", "com.google.android.GoogleCamera", "com.google.android.backuptransport",
            "com.android.backupconfirm", "com.android.shell", "com.google.android.marvin.talkback",
            "com.android.bluetooth", "com.android.wifi", "com.google.android.networkstack", "com.android.networkstack"};

    public static boolean isProtectedPhone(String pkg) {
        for (String p : PROTECTED_PHONE) {
            if (pkg.equals(p) || (p.endsWith(".") && pkg.startsWith(p)) || pkg.startsWith(p + ".")
                    || (!p.endsWith(".") && pkg.startsWith(p) && (p.endsWith("inputmethod") || p.endsWith("launcher") || p.endsWith("camera"))))
                return true;
        }
        return false;
    }

    public static boolean matchesLevel(String pkg, int level, boolean isTv) {
        if (!isTv && isProtectedPhone(pkg)) return false;
        return levelPackages(level).contains(pkg);
    }

    public static int levelOf(String pkg) {
        for (int i = 0; i < 4; i++) if (read(i).contains(pkg)) return i;
        return -1;
    }
}

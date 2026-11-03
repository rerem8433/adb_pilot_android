package fr.paradoxxx.adbpilot;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Réglages (équivalent de config.json) et dossiers ~/ADB_Pilot. */
public final class Config {
    private static SharedPreferences sp;
    public static Context ctx;

    public static void init(Context c) {
        ctx = c.getApplicationContext();
        sp = ctx.getSharedPreferences("adbpilot", Context.MODE_PRIVATE);
        if (!sp.contains("install_grant")) {
            sp.edit().putBoolean("install_grant", true).putBoolean("install_replace", true)
                    .putBoolean("uninstall_keep_data", true).putBoolean("load_meta", true)
                    .putBoolean("auto_reconnect", true).putString("label_lang", "fr")
                    .putString("scrcpy_bitrate", "8M").putBoolean("scrcpy_stay_awake", true)
                    .putInt("scrcpy_res", 1080).putString("port", "auto").apply();
        }
    }

    public static boolean getBool(String k, boolean def) {
        return sp.getBoolean(k, def);
    }

    public static void setBool(String k, boolean v) {
        sp.edit().putBoolean(k, v).apply();
    }

    public static String getStr(String k, String def) {
        try {
            return sp.getString(k, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    public static void setStr(String k, String v) {
        sp.edit().putString(k, v).apply();
    }

    public static int getInt(String k, int def) {
        try {
            return sp.getInt(k, def);
        } catch (ClassCastException e) {
            return def;
        }
    }

    public static void setInt(String k, int v) {
        sp.edit().putInt(k, v).apply();
    }

    public static List<String> getList(String k) {
        List<String> out = new ArrayList<String>();
        try {
            JSONArray a = new JSONArray(sp.getString(k, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void setList(String k, List<String> v) {
        sp.edit().putString(k, new JSONArray(v).toString()).apply();
    }

    public static Map<String, String> getMap(String k) {
        Map<String, String> out = new HashMap<String, String>();
        try {
            JSONObject o = new JSONObject(sp.getString(k, "{}"));
            Iterator<String> it = o.keys();
            while (it.hasNext()) {
                String key = it.next();
                out.put(key, o.getString(key));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void setMap(String k, Map<String, String> m) {
        sp.edit().putString(k, new JSONObject(m).toString()).apply();
    }

    // ------------------------------------------------------------------ dossiers
    public static final String[] SUBDIRS = {"images", "videos", "install", "apps", "store", "presets"};

    public static File userDir() {
        File base = Environment.getExternalStorageDirectory();
        return new File(base, "ADB_Pilot");
    }

    public static File userDir(String sub) {
        File d;
        if ("install".equals(sub) && !getStr("install_dir", "").isEmpty()) d = new File(getStr("install_dir", ""));
        else if ("images".equals(sub) && !getStr("screens_dir", "").isEmpty()) d = new File(getStr("screens_dir", ""));
        else d = new File(userDir(), sub);
        d.mkdirs();
        return d;
    }

    public static File cacheDir(String sub) {
        File d = new File(ctx.getCacheDir(), sub);
        d.mkdirs();
        return d;
    }

    public static File dataDir(String sub) {
        File d = new File(ctx.getFilesDir(), sub);
        d.mkdirs();
        return d;
    }
}

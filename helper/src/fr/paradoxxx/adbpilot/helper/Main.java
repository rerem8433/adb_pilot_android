package fr.paradoxxx.adbpilot.helper;

import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Exécuté SUR l'appareil cible par ADB Pilot (Android) :
 *   CLASSPATH=/data/local/tmp/adbpilot-helper.jar app_process /system/bin fr.paradoxxx.adbpilot.helper.Main meta liste fr 96
 * Lit nom, version et icône de chaque APK avec le framework Android de l'appareil (icônes adaptatives,
 * vectorielles, traductions…). Sortie : une ligne par paquet.
 */
public final class Main {
    private static final int ATTR_LABEL = 0x01010001;
    private static final int ATTR_ICON = 0x01010002;
    private static final int ATTR_VERSION_NAME = 0x0101021c;
    private static final int ATTR_VERSION_CODE = 0x0101021b;
    private static final int ATTR_BANNER = 0x0101031f;
    private static final int ATTR_ROUND_ICON = 0x0101052c;
    private static final int ATTR_COMPILE_SDK = 0x01010572;

    private static PrintStream out;

    public static void main(String[] args) throws Exception {
        out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        try {
            Looper.prepareMainLooper();
        } catch (Throwable ignored) {
        }
        String cmd = args.length > 0 ? args[0] : "";
        if ("meta".equals(cmd)) {
            meta(args[1], args.length > 2 ? args[2] : "fr", args.length > 3 ? Integer.parseInt(args[3]) : 96);
        } else if ("sdk".equals(cmd)) {
            sdk();
        } else {
            out.println("usage: meta <liste> <langue> <taille> | sdk");
        }
        out.flush();
        System.exit(0);
    }

    private static Object[] open(String apk) throws Exception {
        AssetManager am = AssetManager.class.newInstance();
        Method add = AssetManager.class.getMethod("addAssetPath", String.class);
        int cookie = (Integer) add.invoke(am, apk);
        return new Object[]{am, cookie};
    }

    private static String clean(CharSequence s) {
        if (s == null) return "";
        return s.toString().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static void meta(String listFile, String lang, int size) throws Exception {
        Locale loc = lang == null || lang.isEmpty() ? Locale.getDefault()
                : ("fr".equals(lang) ? Locale.FRANCE : ("en".equals(lang) ? Locale.US : new Locale(lang)));
        int dpi = size >= 144 ? 480 : (size >= 96 ? 320 : 240);
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(listFile), "UTF-8"));
        String line;
        while ((line = br.readLine()) != null) {
            int t = line.indexOf('\t');
            if (t <= 0) continue;
            String pkg = line.substring(0, t);
            String apk = line.substring(t + 1);
            try {
                out.println(one(pkg, apk, loc, dpi, size));
            } catch (Throwable e) {
                out.println("E\t" + pkg + "\t" + clean(e.toString()));
            }
        }
        br.close();
        out.println("END");
    }

    @SuppressWarnings("deprecation")
    private static String one(String pkg, String apk, Locale loc, int dpi, int size) throws Exception {
        Object[] o = open(apk);
        AssetManager am = (AssetManager) o[0];
        int cookie = (Integer) o[1];
        if (cookie == 0) throw new IllegalStateException("APK illisible");
        String label = null, version = null;
        int labelRes = 0, iconRes = 0, roundRes = 0, bannerRes = 0, versionRes = 0;
        XmlResourceParser p = am.openXmlResourceParser(cookie, "AndroidManifest.xml");
        int ev;
        boolean done = false;
        while (!done && (ev = p.next()) != XmlResourceParser.END_DOCUMENT) {
            if (ev != XmlResourceParser.START_TAG) continue;
            String tag = p.getName();
            for (int i = 0; i < p.getAttributeCount(); i++) {
                int id = p.getAttributeNameResource(i);
                if ("manifest".equals(tag) && id == ATTR_VERSION_NAME) {
                    versionRes = p.getAttributeResourceValue(i, 0);
                    if (versionRes == 0) version = p.getAttributeValue(i);
                } else if ("application".equals(tag)) {
                    if (id == ATTR_LABEL) {
                        labelRes = p.getAttributeResourceValue(i, 0);
                        if (labelRes == 0) label = p.getAttributeValue(i);
                    } else if (id == ATTR_ICON) {
                        iconRes = p.getAttributeResourceValue(i, 0);
                    } else if (id == ATTR_ROUND_ICON) {
                        roundRes = p.getAttributeResourceValue(i, 0);
                    } else if (id == ATTR_BANNER) {
                        bannerRes = p.getAttributeResourceValue(i, 0);
                    }
                }
            }
            if ("application".equals(tag)) done = true;
        }
        p.close();
        DisplayMetrics dm = new DisplayMetrics();
        dm.setToDefaults();
        dm.densityDpi = dpi;
        dm.density = dpi / 160f;
        dm.scaledDensity = dm.density;
        Configuration cfg = new Configuration();
        cfg.setToDefaults();
        cfg.locale = loc;
        try {
            cfg.setLocale(loc);
        } catch (Throwable ignored) {
        }
        cfg.densityDpi = dpi;
        Resources res = new Resources(am, dm, cfg);
        if (labelRes != 0) {
            try {
                label = res.getText(labelRes).toString();
            } catch (Throwable ignored) {
            }
        }
        if (versionRes != 0) {
            try {
                version = res.getString(versionRes);
            } catch (Throwable ignored) {
            }
        }
        String b64 = "";
        int[] ids = {iconRes, roundRes, bannerRes};
        for (int id : ids) {
            if (id == 0) continue;
            try {
                Drawable d = res.getDrawableForDensity(id, dpi);
                if (d == null) continue;
                b64 = render(d, size);
                if (!b64.isEmpty()) break;
            } catch (Throwable ignored) {
            }
        }
        try {
            am.close();
        } catch (Throwable ignored) {
        }
        return "M\t" + pkg + "\t" + clean(label) + "\t" + clean(version) + "\t" + b64;
    }

    private static String render(Drawable d, int size) {
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        int bw = size, bh = size;
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        if (w > 0 && h > 0 && Math.abs(w - h) > Math.max(w, h) / 8) {
            // bannière (TV) : centrée dans le carré
            float s = Math.min((float) bw / w, (float) bh / h);
            int dw = Math.round(w * s), dh = Math.round(h * s);
            d.setBounds((bw - dw) / 2, (bh - dh) / 2, (bw - dw) / 2 + dw, (bh - dh) / 2 + dh);
        } else {
            d.setBounds(0, 0, bw, bh);
        }
        d.draw(c);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        bmp.recycle();
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    /** Version réelle d'Android : lue dans framework-res.apk (les ROM truquées modifient seulement build.prop). */
    private static void sdk() {
        String code = "", name = "";
        try {
            Object[] o = open("/system/framework/framework-res.apk");
            AssetManager am = (AssetManager) o[0];
            int cookie = (Integer) o[1];
            if (cookie == 0) cookie = 1;
            XmlResourceParser p = am.openXmlResourceParser(cookie, "AndroidManifest.xml");
            int ev;
            while ((ev = p.next()) != XmlResourceParser.END_DOCUMENT) {
                if (ev != XmlResourceParser.START_TAG || !"manifest".equals(p.getName())) continue;
                for (int i = 0; i < p.getAttributeCount(); i++) {
                    String n = p.getAttributeName(i);
                    int id = p.getAttributeNameResource(i);
                    String v = p.getAttributeValue(i);
                    if ("platformBuildVersionCode".equals(n) || (code.isEmpty() && id == ATTR_COMPILE_SDK)) {
                        code = v;
                    } else if ("platformBuildVersionName".equals(n) && v != null) {
                        name = v;
                    } else if (code.isEmpty() && id == ATTR_VERSION_CODE) {
                        code = v;
                    }
                }
                break;
            }
            p.close();
        } catch (Throwable e) {
            out.println("E\t" + clean(e.toString()));
        }
        out.println("SDK\t" + clean(code) + "\t" + clean(name));
    }
}

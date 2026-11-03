package fr.paradoxxx.adbpilot;

import fr.paradoxxx.adbpilot.adb.AdbDevice;
import fr.paradoxxx.adbpilot.adb.AdbDevice.Result;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Portage de adbcore.py : infos appareil, paquets, actions, sauvegarde, installation. */
public final class Core {
    public static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    public static Map<String, String> PROPS = new LinkedHashMap<String, String>();

    static {
        String[][] p = {{"brand", "ro.product.brand"}, {"manufacturer", "ro.product.manufacturer"},
                {"model", "ro.product.model"}, {"device", "ro.product.device"}, {"serial", "ro.serialno"},
                {"board", "ro.board.platform"}, {"hardware", "ro.hardware"}, {"abi", "ro.product.cpu.abi"},
                {"release", "ro.build.version.release"}, {"sdk", "ro.build.version.sdk"},
                {"build", "ro.build.display.id"}, {"fingerprint", "ro.build.fingerprint"},
                {"security_patch", "ro.build.version.security_patch"}, {"soc_manufacturer", "ro.soc.manufacturer"},
                {"soc_model", "ro.soc.model"}, {"product_board", "ro.product.board"}, {"chipname", "ro.chipname"},
                {"vendor_sdk", "ro.vendor.build.version.sdk"}, {"vndk", "ro.vndk.version"}};
        for (String[] kv : p) PROPS.put(kv[0], kv[1]);
    }

    // ------------------------------------------------------------------ infos
    public static Map<String, String> deviceInfo(AdbDevice d) {
        Result r = d.shell("getprop", 15);
        Map<String, String> props = new HashMap<String, String>();
        Matcher m = Pattern.compile("^\\[([^\\]]+)\\]: \\[(.*)\\]$", Pattern.MULTILINE).matcher(r.out);
        while (m.find()) props.put(m.group(1), m.group(2));
        Map<String, String> info = new HashMap<String, String>();
        for (Map.Entry<String, String> e : PROPS.entrySet()) {
            String v = props.get(e.getValue());
            info.put(e.getKey(), v == null ? "" : v);
        }
        if (info.get("serial").isEmpty()) info.put("serial", d.serial);
        return info;
    }

    static final Map<Integer, String> SDK_RELEASE = new HashMap<Integer, String>();
    static final Map<String, String> AMLOGIC = new HashMap<String, String>(), ALLWINNER = new HashMap<String, String>(),
            QUALCOMM = new HashMap<String, String>();

    static {
        String[] rel = {"14", "4.0", "15", "4.0.3", "16", "4.1", "17", "4.2", "18", "4.3", "19", "4.4", "20", "4.4W",
                "21", "5.0", "22", "5.1", "23", "6.0", "24", "7.0", "25", "7.1", "26", "8.0", "27", "8.1", "28", "9",
                "29", "10", "30", "11", "31", "12", "32", "12L", "33", "13", "34", "14", "35", "15", "36", "16", "37", "17"};
        for (int i = 0; i < rel.length; i += 2) SDK_RELEASE.put(Integer.parseInt(rel[i]), rel[i + 1]);
        put(AMLOGIC, "gxbb", "S905", "gxl", "S905X/S905W/S905L", "gxm", "S912", "g12a", "S905X2", "g12b", "S922X/A311D",
                "sm1", "S905X3", "sc2", "S905X4", "s4", "S905Y4/S905W2", "t5d", "T962D", "txlx", "T962", "tm2", "T962E2",
                "s5", "S905X5");
        put(ALLWINNER, "cupid", "H616/H618", "petrel", "H6", "dolphin", "H3", "sun50iw9p1", "H616/H618/H313",
                "sun50iw6p1", "H6", "sun50iw1p1", "A64/H64", "sun50iw2p1", "H5", "sun8iw7p1", "H3", "sun50iw12p1", "H713",
                "sun50iw10p1", "A133");
        put(QUALCOMM, "msm8917", "Snapdragon 425", "msm8937", "Snapdragon 430", "msm8940", "Snapdragon 435",
                "msm8953", "Snapdragon 625", "msm8996", "Snapdragon 820", "msm8998", "Snapdragon 835",
                "sdm450", "Snapdragon 450", "sdm660", "Snapdragon 660", "sdm710", "Snapdragon 710",
                "sdm845", "Snapdragon 845", "msmnile", "Snapdragon 855", "kona", "Snapdragon 865",
                "lahaina", "Snapdragon 888", "taro", "Snapdragon 8 Gen 1", "kalama", "Snapdragon 8 Gen 2",
                "pineapple", "Snapdragon 8 Gen 3", "trinket", "Snapdragon 665", "bengal", "Snapdragon 662/460",
                "lito", "Snapdragon 765", "atoll", "Snapdragon 720G", "holi", "Snapdragon 480",
                "sm6150", "Snapdragon 675", "sm7150", "Snapdragon 730");
    }

    private static void put(Map<String, String> m, String... kv) {
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
    }

    private static String g(Map<String, String> m, String k) {
        String v = m.get(k);
        return v == null ? "" : v;
    }

    public static String socName(Map<String, String> info, String cpuinfo, String dtCompat) {
        String dt = dtCompat == null ? "" : dtCompat.toLowerCase(Locale.ROOT);
        Matcher m = Pattern.compile("(?:^|[^a-z0-9])(s9\\d\\d[a-z0-9]*|a311d|t9\\d\\d[a-z0-9]*)").matcher(dt);
        if (m.find()) return "Amlogic " + m.group(1).toUpperCase(Locale.ROOT);
        Matcher fm = Pattern.compile("amlogic,\\s*([a-z0-9]+)").matcher(dt);
        String fam = null;
        while (fm.find()) {
            if (AMLOGIC.containsKey(fm.group(1))) {
                fam = fm.group(1);
                break;
            }
        }
        if (fam != null) {
            if (fam.equals("g12a") || fam.equals("sm1")) {
                boolean a55 = Pattern.compile("CPU part\\s*:\\s*0xd05", Pattern.CASE_INSENSITIVE).matcher(cpuinfo).find();
                return "Amlogic " + (a55 ? "S905X3" : fam.equals("sm1") ? "S905X3" : "S905X2");
            }
            return "Amlogic " + AMLOGIC.get(fam);
        }
        String plat = g(info, "board").toLowerCase(Locale.ROOT), hw = g(info, "hardware").toLowerCase(Locale.ROOT);
        String man = g(info, "soc_manufacturer"), model = g(info, "soc_model");
        Matcher hm = Pattern.compile("^Hardware\\s*:\\s*(.+)$", Pattern.MULTILINE).matcher(cpuinfo);
        String hwline = hm.find() ? hm.group(1).trim() : "";
        String hwl = hwline.toLowerCase(Locale.ROOT);
        if (AMLOGIC.containsKey(plat) || hw.equals("amlogic") || hwl.contains("amlogic")) {
            if (AMLOGIC.containsKey(plat) || !model.isEmpty())
                return "Amlogic " + (AMLOGIC.containsKey(plat) ? AMLOGIC.get(plat) : model);
            return !plat.isEmpty() && !plat.equals("amlogic") ? "Amlogic (" + plat + ")" : "Amlogic";
        }
        if (plat.startsWith("rk") || hw.startsWith("rk") || hwl.contains("rockchip")) {
            String chip = plat;
            if (!plat.startsWith("rk3")) {
                Matcher rk = Pattern.compile("rk\\d+").matcher(hwl);
                chip = rk.find() ? rk.group() : (plat.isEmpty() ? hw : plat);
            }
            return "Rockchip " + chip.toUpperCase(Locale.ROOT);
        }
        if (ALLWINNER.containsKey(plat) || hw.startsWith("sun") || (man + hwline).toLowerCase(Locale.ROOT).contains("allwinner")) {
            String v = ALLWINNER.get(plat);
            if (v == null) v = ALLWINNER.get(hw);
            if (v == null) v = !model.isEmpty() ? model : (!plat.isEmpty() ? plat : hw);
            return "Allwinner " + v;
        }
        if (plat.matches("mt\\d+.*") || hw.matches("mt\\d+.*") || man.toLowerCase(Locale.ROOT).startsWith("mediatek")) {
            return "MediaTek " + (!model.isEmpty() ? model : (plat.startsWith("mt") ? plat : hw)).toUpperCase(Locale.ROOT);
        }
        if (QUALCOMM.containsKey(plat) || hw.equals("qcom") || man.equalsIgnoreCase("QTI") || man.equalsIgnoreCase("QUALCOMM")) {
            String name = QUALCOMM.get(plat);
            return "Qualcomm " + (name != null ? name : (!model.isEmpty() ? model : plat).toUpperCase(Locale.ROOT))
                    + (!model.isEmpty() && name != null ? " (" + model + ")" : "");
        }
        if (plat.startsWith("exynos") || hw.startsWith("exynos") || man.toLowerCase(Locale.ROOT).contains("samsung")) {
            String chip = plat.startsWith("exynos") ? plat : (hw.startsWith("exynos") ? hw : model);
            return "Samsung " + chip.replace("exynos", "Exynos ").trim();
        }
        if (plat.startsWith("ums") || plat.startsWith("sp") || plat.startsWith("sc9") || (man + hwline).toLowerCase(Locale.ROOT).contains("unisoc"))
            return "Unisoc " + (!model.isEmpty() ? model : plat).toUpperCase(Locale.ROOT);
        if (plat.startsWith("tegra") || plat.startsWith("t210") || hw.startsWith("tegra") || hw.startsWith("foster") || hw.startsWith("darcy"))
            return "NVIDIA Tegra X1";
        if (!man.isEmpty() && !model.isEmpty()) return man + " " + model;
        if (!hwline.isEmpty()) return hwline;
        if (!plat.isEmpty()) return plat;
        return hw.isEmpty() ? "?" : hw;
    }

    static double roundUp(double v, double[] steps) {
        for (double s : steps) if (v <= s) return s;
        return Math.round(v);
    }

    public static String fmtNum(double x) {
        String s = String.format(Locale.ROOT, "%.6f", x);
        s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.replace(".", ",");
    }

    public static long realRamKb = 0;

    /** Specs (RAM réelle, stockage, écran, SoC, Android réel…) — même logique que la version PC. */
    public static Map<String, Object> deviceSpecs(AdbDevice d, Map<String, String> info) {
        String script = "head -1 /proc/meminfo; echo @@TV; getprop ro.build.characteristics; "
                + "pm list features 2>/dev/null | grep -ciE 'leanback|android.hardware.type.television'; "
                + "echo @@DF; toybox df -k /data 2>/dev/null || df -k /data 2>/dev/null || df /data; "
                + "echo @@STATF; stat -f -c '%S %b %f %a' /data 2>/dev/null; "
                + "echo @@WM; wm size 2>/dev/null; wm density 2>/dev/null; "
                + "echo @@DTCOMPAT; cat /sys/firmware/devicetree/base/amlogic-dt-id 2>/dev/null; echo; "
                + "cat /sys/firmware/devicetree/base/compatible 2>/dev/null; echo; "
                + "echo @@CPU; grep -iE '^(Hardware|model name|Processor)' /proc/cpuinfo | head -3; grep -m1 'CPU part' /proc/cpuinfo; "
                + "echo @@NCPU; ls -d /sys/devices/system/cpu/cpu[0-9]* 2>/dev/null | wc -l; "
                + "echo @@FREQ; cat /sys/devices/system/cpu/cpu*/cpufreq/cpuinfo_max_freq 2>/dev/null; "
                + "echo @@ZONE; grep -E '^ +present' /proc/zoneinfo 2>/dev/null; "
                + "echo @@DTC; od -An -tx1 '/sys/firmware/devicetree/base/#address-cells' "
                + "'/sys/firmware/devicetree/base/#size-cells' 2>/dev/null; "
                + "echo @@DT; for f in /sys/firmware/devicetree/base/memory*/reg; do "
                + "[ -r \"$f\" ] && od -An -tx1 -v \"$f\"; echo '|'; done 2>/dev/null";
        String out = d.shell(script, 20).out;
        Map<String, String> sec = new HashMap<String, String>();
        String cur = "MEM";
        StringBuilder sb = new StringBuilder();
        for (String ln : out.split("\n")) {
            if (ln.startsWith("@@")) {
                sec.put(cur, sb.toString());
                cur = ln.substring(2).trim();
                sb = new StringBuilder();
            } else sb.append(ln).append('\n');
        }
        sec.put(cur, sb.toString());
        Map<String, Object> sp = new HashMap<String, Object>();
        String tv = g(sec, "TV").trim();
        String first = tv.isEmpty() ? "" : tv.split("\n")[0].toLowerCase(Locale.ROOT);
        int tvCount = 0;
        Matcher nm = Pattern.compile("^(\\d+)$", Pattern.MULTILINE).matcher(tv);
        if (nm.find()) tvCount = Integer.parseInt(nm.group(1));
        sp.put("is_tv", Pattern.compile("\\btv\\b").matcher(first).find() || tvCount > 0);
        Matcher mm = Pattern.compile("MemTotal:\\s+(\\d+)").matcher(g(sec, "MEM"));
        double[] ramSteps = {0.5, 1, 1.5, 2, 3, 4, 6, 8, 10, 12, 16, 18, 24, 32};
        if (mm.find()) {
            long memKb = Long.parseLong(mm.group(1));
            long real = physicalRamKb(sec);
            realRamKb = 0;
            double gb = memKb / 1048576.0;
            sp.put("ram_exact", fmtNum(Math.round(gb * 100) / 100.0) + " Go (MemTotal)");
            if (real > 0 && real < memKb * 0.75 && roundUp(real / 1048576.0, ramSteps) < roundUp(gb, ramSteps)) {
                realRamKb = real;
                double rgb = real / 1048576.0;
                sp.put("ram_fake", fmtNum(roundUp(gb, ramSteps)));
                sp.put("ram_exact", fmtNum(Math.round(rgb * 100) / 100.0) + " Go réels (MemTotal annonce "
                        + fmtNum(Math.round(gb * 100) / 100.0) + " Go)");
                gb = rgb;
            }
            sp.put("ram_gb", fmtNum(roundUp(gb, ramSteps)));
        }
        double totalKb = 0, usedKb = -1;
        String[] st = g(sec, "STATF").trim().split("\\s+");
        if (st.length >= 4 && st[0].matches("\\d+") && st[1].matches("\\d+") && st[3].matches("\\d+")) {
            double bs = Double.parseDouble(st[0]), blocks = Double.parseDouble(st[1]), avail = Double.parseDouble(st[3]);
            if (bs > 0 && blocks > 0) {
                totalKb = blocks * bs / 1024;
                usedKb = (blocks - avail) * bs / 1024;
            }
        }
        if (totalKb <= 0) {
            String[] dfl = g(sec, "DF").split("\n");
            StringBuilder all = new StringBuilder();
            for (int i = 1; i < dfl.length; i++) all.append(dfl[i]).append(' ');
            List<Double> nums = new ArrayList<Double>();
            for (String tok : all.toString().trim().split("\\s+")) {
                if (tok.endsWith("%")) continue;
                Double k = kb(tok);
                if (k != null) nums.add(k);
            }
            if (nums.size() >= 3) {
                totalKb = nums.get(0);
                usedKb = totalKb - nums.get(2);
            }
        }
        if (totalKb > 0) {
            double gb = totalKb / 1048576;
            sp.put("storage_exact", fmtNum(Math.round(gb * 10) / 10.0) + " Go utilisables");
            sp.put("storage_gb", fmtNum(roundUp(gb, new double[]{2, 4, 8, 16, 32, 64, 128, 256, 512, 1024})));
            sp.put("storage_total", gb);
            if (usedKb >= 0) sp.put("storage_used", Math.max(0, usedKb / 1048576));
        }
        String wm = g(sec, "WM");
        String[][] pats = {{"res", "Physical size:\\s*(\\d+x\\d+)"}, {"res_over", "Override size:\\s*(\\d+x\\d+)"},
                {"dpi", "Physical density:\\s*(\\d+)"}, {"dpi_over", "Override density:\\s*(\\d+)"}};
        for (String[] p : pats) {
            Matcher x = Pattern.compile(p[1]).matcher(wm);
            if (x.find()) sp.put(p[0], x.group(1));
        }
        sp.put("soc", socName(info, g(sec, "CPU"), g(sec, "DTCOMPAT")));
        String n = g(sec, "NCPU").trim();
        if (n.matches("\\d+")) sp.put("cores", Integer.parseInt(n));
        long maxf = 0;
        for (String f : g(sec, "FREQ").trim().split("\\s+")) if (f.matches("\\d+")) maxf = Math.max(maxf, Long.parseLong(f));
        if (maxf > 0) sp.put("max_ghz", Math.round(maxf / 1e4) / 100.0);
        int code = 0;
        String name = null;
        String[] real = realSdk(d);
        if (real != null && real[0].matches("\\d+")) {
            code = Integer.parseInt(real[0]);
            if (code < 14 || code > 60) code = 0;
            name = real[1];
        }
        if (code == 0) {
            for (String k : new String[]{"vendor_sdk", "vndk"}) {
                String v = g(info, k);
                if (v.matches("\\d+")) {
                    code = Integer.parseInt(v);
                    break;
                }
            }
        }
        String claimedSdk = g(info, "sdk");
        sp.put("claimed", g(info, "release").isEmpty() ? "?" : g(info, "release"));
        sp.put("claimed_sdk", claimedSdk.isEmpty() ? "?" : claimedSdk);
        if (code > 0) {
            sp.put("sdk", String.valueOf(code));
            String rel = SDK_RELEASE.get(code);
            sp.put("android", rel != null ? rel : (name != null && !name.isEmpty() && Character.isDigit(name.charAt(0)) ? name : String.valueOf(code)));
            sp.put("fake", claimedSdk.matches("\\d+") && Integer.parseInt(claimedSdk) != code);
        } else {
            sp.put("sdk", claimedSdk.isEmpty() ? "?" : claimedSdk);
            sp.put("android", g(info, "release").isEmpty() ? "?" : g(info, "release"));
            sp.put("fake", false);
        }
        return sp;
    }

    private static Double kb(String tok) {
        if (tok.matches("\\d+")) return Double.parseDouble(tok);
        Matcher m = Pattern.compile("([\\d.,]+)([KMGT])").matcher(tok);
        if (m.matches()) {
            double v = Double.parseDouble(m.group(1).replace(",", "."));
            String u = m.group(2);
            return v * (u.equals("K") ? 1 : u.equals("M") ? 1024 : u.equals("G") ? 1048576 : 1073741824.0);
        }
        return null;
    }

    static long physicalRamKb(Map<String, String> sec) {
        List<Long> vals = new ArrayList<Long>();
        long pages = 0;
        Matcher m = Pattern.compile("present\\s+(\\d+)").matcher(g(sec, "ZONE"));
        while (m.find()) pages += Long.parseLong(m.group(1));
        if (pages > 0) vals.add(pages * 4);
        String[] cs = g(sec, "DTC").trim().split("\\s+");
        int ac = 2, sc = 2;
        if (cs.length >= 8) {
            try {
                ac = Integer.parseInt(cs[0] + cs[1] + cs[2] + cs[3], 16);
                sc = Integer.parseInt(cs[4] + cs[5] + cs[6] + cs[7], 16);
            } catch (NumberFormatException ignored) {
            }
        }
        long total = 0;
        if (ac >= 1 && ac <= 3 && sc >= 1 && sc <= 2) {
            for (String blob : g(sec, "DT").split("\\|")) {
                List<Integer> b = new ArrayList<Integer>();
                for (String x : blob.trim().split("\\s+")) if (x.matches("[0-9a-fA-F]{2}")) b.add(Integer.parseInt(x, 16));
                int step = 4 * (ac + sc);
                for (int i = 0; i + step <= b.size(); i += step) {
                    BigInteger v = BigInteger.ZERO;
                    for (int j = i + 4 * ac; j < i + step; j++) v = v.shiftLeft(8).add(BigInteger.valueOf(b.get(j)));
                    total += v.longValue();
                }
            }
        }
        if (total > 0) vals.add(total / 1024);
        long best = 0;
        for (long v : vals) if (v >= 131072 && (best == 0 || v < best)) best = v;
        return best;
    }

    /** {code, nom} lus par le helper dans framework-res.apk, ou null. */
    public static String[] realSdk(AdbDevice d) {
        try {
            if (!ensureHelper(d)) return null;
            byte[] o = d.execOut(HELPER_CMD + " sdk", 30);
            for (String ln : new String(o, "UTF-8").split("\n")) {
                if (ln.startsWith("SDK\t")) {
                    String[] p = ln.split("\t", -1);
                    return new String[]{p.length > 1 ? p[1].trim() : "", p.length > 2 ? p[2].trim() : ""};
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static final String HELPER_PATH = "/data/local/tmp/adbpilot-helper.jar";
    public static final String HELPER_CMD = "CLASSPATH=" + HELPER_PATH + " app_process /system/bin fr.paradoxxx.adbpilot.helper.Main";
    private static byte[] helperBytes;

    public static synchronized boolean ensureHelper(AdbDevice d) {
        try {
            if (helperBytes == null) helperBytes = readAll(Config.ctx.getAssets().open("helper.jar"));
            long[] st = d.stat(HELPER_PATH);
            if (st != null && st[1] == helperBytes.length) return true;
            d.push(new java.io.ByteArrayInputStream(helperBytes), helperBytes.length, HELPER_PATH, 0644, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        in.close();
        return bos.toByteArray();
    }

    /** {utilisé, total} en Mo. */
    public static long[] ramInfo(AdbDevice d) {
        Result r = d.shell("cat /proc/meminfo", 10);
        Map<String, Long> v = new HashMap<String, Long>();
        Matcher m = Pattern.compile("^(\\w+):\\s+(\\d+)", Pattern.MULTILINE).matcher(r.out);
        while (m.find()) v.put(m.group(1), Long.parseLong(m.group(2)));
        long total = (v.containsKey("MemTotal") ? v.get("MemTotal") : 0) / 1024;
        long avail = (v.containsKey("MemAvailable") ? v.get("MemAvailable") : (v.containsKey("MemFree") ? v.get("MemFree") : 0)) / 1024;
        long used = total - avail;
        if (realRamKb > 0) {
            total = realRamKb / 1024;
            used = Math.min(used, total);
        }
        return new long[]{used, total};
    }

    public static String deviceIp(AdbDevice d) {
        Result r = d.shell("ip -o -4 addr show 2>/dev/null; ip route 2>/dev/null", 10);
        for (String iface : new String[]{"wlan0", "eth0", "wlan1"}) {
            Matcher m = Pattern.compile("\\d+:\\s+" + iface + "\\s+inet\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(r.out);
            if (m.find()) return m.group(1);
        }
        Matcher m = Pattern.compile("src (\\d+\\.\\d+\\.\\d+\\.\\d+)").matcher(r.out);
        return m.find() ? m.group(1) : null;
    }

    // ------------------------------------------------------------------ paquets
    static final String LIST_SCRIPT = "echo @@ALL; pm list packages -f -u 2>/dev/null; "
            + "echo @@INST; pm list packages 2>/dev/null; "
            + "echo @@SYS; pm list packages -s -u 2>/dev/null; "
            + "echo @@DIS; pm list packages -d 2>/dev/null; "
            + "echo @@SIZE; pm list packages -f -u 2>/dev/null | while read -r l; do "
            + "p=${l#package:}; p=${p%=*}; "
            + "case $p in /data/app/*) f=\"${p%/*}\"/*.apk;; *) f=$p;; esac; "
            + "s=0; for z in $(stat -c %s $f 2>/dev/null); do s=$((s+z)); done; "
            + "b=$(stat -c %s \"$p\" 2>/dev/null); echo \"$s|${b:-0}|$p\"; done; "
            + "echo @@DISK; dumpsys diskstats 2>/dev/null | grep -E '^(Package Names|App Data Sizes|Cache Sizes):'";

    private static final Set<String> diskTried = new HashSet<String>();

    static Map<String, Long> parseDiskstats(List<String> lines) {
        Map<String, JSONArray> vals = new HashMap<String, JSONArray>();
        for (String ln : lines) {
            int i = ln.indexOf(':');
            if (i < 0) continue;
            try {
                vals.put(ln.substring(0, i).trim(), new JSONArray(ln.substring(i + 1).trim()));
            } catch (Exception ignored) {
            }
        }
        Map<String, Long> out = new HashMap<String, Long>();
        JSONArray names = vals.get("Package Names"), data = vals.get("App Data Sizes"), cache = vals.get("Cache Sizes");
        if (names == null || data == null) return out;
        for (int i = 0; i < names.length(); i++) {
            try {
                if (i < data.length())
                    out.put(names.getString(i), data.getLong(i) + (cache != null && i < cache.length() ? cache.getLong(i) : 0));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    static Map<String, Long> computeDiskstats(AdbDevice d) {
        diskTried.add(d.serial);
        d.shell("dumpsys battery set ac 1; dumpsys battery set status 2; sleep 1; cmd jobscheduler run -f android 1145656139", 20);
        try {
            for (int i = 0; i < 10; i++) {
                Thread.sleep(2000);
                Result r = d.shell("dumpsys diskstats 2>/dev/null | grep -E '^(Package Names|App Data Sizes|Cache Sizes):'", 20);
                Map<String, Long> m = parseDiskstats(AdbDevice.lines(r.out));
                if (!m.isEmpty()) return m;
            }
        } catch (InterruptedException ignored) {
        } finally {
            d.shell("dumpsys battery reset", 10);
        }
        return new HashMap<String, Long>();
    }

    public static List<Pkg> listPackages(AdbDevice d) {
        Result r = d.shell(LIST_SCRIPT, 120);
        Map<String, List<String>> sections = new HashMap<String, List<String>>();
        String cur = null;
        for (String line : r.out.split("\n")) {
            line = line.trim();
            if (line.startsWith("@@")) {
                cur = line.substring(2);
                sections.put(cur, new ArrayList<String>());
            } else if (cur != null && !line.isEmpty()) sections.get(cur).add(line);
        }
        Map<String, Pkg> pkgs = new LinkedHashMap<String, Pkg>();
        for (String ln : sec(sections, "ALL")) {
            if (!ln.startsWith("package:")) continue;
            String s = ln.substring(8);
            int eq = s.lastIndexOf('=');
            if (eq < 0) continue;
            String name = s.substring(eq + 1);
            if (name.isEmpty()) continue;
            Pkg p = new Pkg(name);
            p.path = s.substring(0, eq);
            pkgs.put(name, p);
        }
        Map<String, Long> sizes = new HashMap<String, Long>(), bases = new HashMap<String, Long>();
        for (String ln : sec(sections, "SIZE")) {
            String[] parts = ln.split("\\|", 3);
            if (parts.length < 3) continue;
            if (parts[0].matches("\\d+")) sizes.put(parts[2], Long.parseLong(parts[0]));
            if (parts[1].matches("\\d+")) bases.put(parts[2], Long.parseLong(parts[1]));
        }
        Set<String> inst = names(sections, "INST"), sys = names(sections, "SYS"), dis = names(sections, "DIS");
        Map<String, Long> disk = parseDiskstats(sec(sections, "DISK"));
        if (disk.isEmpty() && !diskTried.contains(d.serial)) disk = computeDiskstats(d);
        for (Pkg p : pkgs.values()) {
            Long a = sizes.get(p.path), b = bases.get(p.path);
            p.apkSize = a == null ? 0 : a;
            p.baseSize = b != null && b > 0 ? b : p.apkSize;
            Long ds = disk.get(p.name);
            p.dataSize = ds == null ? -1 : ds;
            p.size = p.apkSize + Math.max(0, p.dataSize);
            p.system = sys.contains(p.name) || p.path.startsWith("/system") || p.path.startsWith("/product")
                    || p.path.startsWith("/vendor") || p.path.startsWith("/apex") || p.path.startsWith("/odm");
            p.disabled = dis.contains(p.name);
            p.deleted = !inst.contains(p.name);
        }
        List<Pkg> out = new ArrayList<Pkg>(pkgs.values());
        Collections.sort(out, new Comparator<Pkg>() {
            public int compare(Pkg a, Pkg b) {
                return a.name.compareTo(b.name);
            }
        });
        return out;
    }

    private static List<String> sec(Map<String, List<String>> s, String k) {
        List<String> l = s.get(k);
        return l == null ? new ArrayList<String>() : l;
    }

    private static Set<String> names(Map<String, List<String>> s, String k) {
        Set<String> out = new HashSet<String>();
        for (String ln : sec(s, k)) if (ln.startsWith("package:")) out.add(ln.substring(8));
        return out;
    }

    public static List<String> packagePaths(AdbDevice d, String name) {
        Result r = d.shell("pm path " + q(name), 20);
        List<String> out = new ArrayList<String>();
        for (String ln : r.out.split("\n")) if (ln.startsWith("package:")) out.add(ln.substring(8).trim());
        return out;
    }

    public static String dumpsysPackage(AdbDevice d, String name) {
        return d.shell("dumpsys package " + q(name), 30).out;
    }

    /** {permission, accordée, runtime} */
    public static List<Object[]> parsePermissions(String dump, String name) {
        Matcher m = Pattern.compile("Package \\[" + Pattern.quote(name) + "\\]").matcher(dump);
        String sect = m.find() ? dump.substring(m.start()) : dump;
        List<String> requested = new ArrayList<String>();
        Map<String, Boolean> granted = new LinkedHashMap<String, Boolean>();
        Set<String> runtime = new HashSet<String>();
        String mode = null;
        for (String line : sect.split("\n")) {
            String s = line.trim();
            if (s.startsWith("Package [") && !s.startsWith("Package [" + name + "]") && !requested.isEmpty()) break;
            if (s.equals("requested permissions:")) {
                mode = "req";
                continue;
            }
            if (s.equals("install permissions:")) {
                mode = "inst";
                continue;
            }
            if (s.equals("runtime permissions:")) {
                mode = "rt";
                continue;
            }
            if ("req".equals(mode)) {
                if (s.matches("^[\\w.]+(?:: restricted=\\w+)?$")) requested.add(s.split(":")[0]);
                else if (!s.isEmpty()) mode = null;
            } else if ("inst".equals(mode) || "rt".equals(mode)) {
                Matcher mm = Pattern.compile("^([\\w.]+): granted=(true|false)").matcher(s);
                if (mm.find()) {
                    granted.put(mm.group(1), mm.group(2).equals("true"));
                    if ("rt".equals(mode)) runtime.add(mm.group(1));
                } else if (!s.isEmpty() && !s.startsWith("User ")) mode = null;
            }
        }
        List<String> seen = new ArrayList<String>();
        for (String p : requested) if (!seen.contains(p)) seen.add(p);
        for (String p : granted.keySet()) if (!seen.contains(p)) seen.add(p);
        List<Object[]> out = new ArrayList<Object[]>();
        for (String p : seen) out.add(new Object[]{p, granted.containsKey(p) && granted.get(p), runtime.contains(p)});
        return out;
    }

    public static Map<String, String> parseAppInfo(String dump, String name) {
        Matcher m0 = Pattern.compile("Package \\[" + Pattern.quote(name) + "\\]").matcher(dump);
        String sect = m0.find() ? dump.substring(m0.start()) : dump;
        String[][] keys = {{"versionName", "versionName=(\\S+)"}, {"versionCode", "versionCode=(\\d+)"},
                {"targetSdk", "targetSdk=(\\d+)"}, {"minSdk", "minSdk=(\\d+)"}, {"codePath", "codePath=(\\S+)"},
                {"dataDir", "dataDir=(\\S+)"}, {"firstInstallTime", "firstInstallTime=([^\\n]+)"},
                {"lastUpdateTime", "lastUpdateTime=([^\\n]+)"}, {"installer", "installerPackageName=(\\S+)"},
                {"uid", "(?:userId|appId)=(\\d+)"}, {"primaryCpuAbi", "primaryCpuAbi=(\\S+)"}};
        Map<String, String> out = new HashMap<String, String>();
        for (String[] k : keys) {
            Matcher m = Pattern.compile(k[1]).matcher(sect);
            out.put(k[0], m.find() ? m.group(1).trim() : "");
        }
        return out;
    }

    // ------------------------------------------------------------------ actions
    public static final int ACT_DISABLE = 0, ACT_ENABLE = 1, ACT_UNINSTALL = 2, ACT_RESTORE = 3, ACT_FORCE_STOP = 4,
            ACT_CLEAR = 5, ACT_CLEAR_CACHE = 6, ACT_BACKUP = 7, ACT_LAUNCH = 8;

    public static Result act(AdbDevice d, int act, Pkg p) {
        switch (act) {
            case ACT_DISABLE:
                return d.shell("pm disable-user --user 0 " + q(p.name));
            case ACT_ENABLE:
                return d.shell("pm enable " + q(p.name));
            case ACT_UNINSTALL:
                if (p.system) {
                    boolean keep = Config.getBool("uninstall_keep_data", true);
                    return d.shell("pm uninstall " + (keep ? "-k " : "") + "--user 0 " + q(p.name));
                }
                return d.shell("pm uninstall " + q(p.name));
            case ACT_RESTORE: {
                Result r = d.shell("cmd package install-existing --user 0 " + q(p.name));
                if (r.out.contains("inaccessible or not found") || !r.ok() || r.out.contains("Unknown command"))
                    r = d.shell("pm install-existing --user 0 " + q(p.name));
                return r;
            }
            case ACT_FORCE_STOP:
                return d.shell("am force-stop " + q(p.name));
            case ACT_CLEAR:
                return d.shell("pm clear " + q(p.name));
            case ACT_CLEAR_CACHE: {
                Result r = d.shell("pm clear --cache-only " + q(p.name));
                String low = r.text().toLowerCase(Locale.ROOT);
                if (r.ok() && low.contains("success")) return r;
                if (low.contains("unknown") || low.contains("error") || low.contains("usage") || !r.ok() || low.contains("exception"))
                    return new Result(2, r.out, r.err.isEmpty() ? "cache-only non supporté" : r.err);
                return r;
            }
            case ACT_LAUNCH:
                return d.shell("monkey -p " + q(p.name) + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || "
                        + "monkey -p " + q(p.name) + " -c android.intent.category.LEANBACK_LAUNCHER 1");
        }
        return new Result(1, "", "action inconnue");
    }

    public static Result uninstallCompletely(AdbDevice d, String name) {
        return d.shell("pm uninstall " + q(name));
    }

    public static Result trimCaches(AdbDevice d) {
        return d.shell("pm trim-caches 999999M");
    }

    public static Result grant(AdbDevice d, String pkg, String perm, boolean on) {
        return d.shell("pm " + (on ? "grant " : "revoke ") + q(pkg) + " " + q(perm));
    }

    static String safe(String s) {
        String r = s.trim().replaceAll("[^\\w.()+]+", "-").replaceAll("^-+|-+$", "");
        return r.isEmpty() ? "app" : r;
    }

    /** Sauvegarde dans ADB_Pilot/apps/<appareil>/nomappli_(paquet)_version.apk (.apks si split). {ok, message} */
    public static Object[] backupApk(AdbDevice d, Pkg p, String deviceLabel) {
        List<String> paths = packagePaths(d, p.name);
        if (paths.isEmpty() && !p.path.isEmpty()) paths.add(p.path);
        if (paths.isEmpty()) return new Object[]{false, "chemin introuvable"};
        String version = p.version;
        if (version == null || version.isEmpty()) {
            Matcher m = Pattern.compile("versionName=(\\S+)").matcher(dumpsysPackage(d, p.name));
            version = m.find() ? m.group(1) : "inconnue";
        }
        File dest = new File(Config.userDir("apps"), deviceLabel.replaceAll("[^\\w.\\-]+", "_").replaceAll("^_+|_+$", ""));
        dest.mkdirs();
        String lab = p.label != null && !p.label.isEmpty() ? p.label : p.name.substring(p.name.lastIndexOf('.') + 1);
        String base = safe(lab) + "_(" + p.name + ")_" + safe(version);
        try {
            if (paths.size() == 1) {
                File target = new File(dest, base + ".apk");
                FileOutputStream fo = new FileOutputStream(target);
                try {
                    d.pull(paths.get(0), fo, null);
                } finally {
                    fo.close();
                }
                return new Object[]{true, target.getAbsolutePath()};
            }
            File target = new File(dest, base + ".apks");
            ZipOutputStream z = new ZipOutputStream(new FileOutputStream(target));
            z.setMethod(ZipOutputStream.STORED);
            try {
                for (String sp : paths) {
                    File tmp = File.createTempFile("bk", ".apk", Config.cacheDir("bk"));
                    FileOutputStream fo = new FileOutputStream(tmp);
                    try {
                        d.pull(sp, fo, null);
                    } finally {
                        fo.close();
                    }
                    ZipEntry e = new ZipEntry(sp.substring(sp.lastIndexOf('/') + 1));
                    e.setSize(tmp.length());
                    e.setCompressedSize(tmp.length());
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    java.io.FileInputStream fi = new java.io.FileInputStream(tmp);
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = fi.read(buf)) > 0) crc.update(buf, 0, n);
                    fi.close();
                    e.setCrc(crc.getValue());
                    z.putNextEntry(e);
                    fi = new java.io.FileInputStream(tmp);
                    while ((n = fi.read(buf)) > 0) z.write(buf, 0, n);
                    fi.close();
                    z.closeEntry();
                    tmp.delete();
                }
            } finally {
                z.close();
            }
            return new Object[]{true, target.getAbsolutePath()};
        } catch (IOException e) {
            return new Object[]{false, String.valueOf(e.getMessage())};
        }
    }

    // ------------------------------------------------------------------ installation
    public static List<String> installFlags() {
        List<String> f = new ArrayList<String>();
        if (Config.getBool("install_replace", true)) f.add("-r");
        if (Config.getBool("install_grant", true)) f.add("-g");
        if (Config.getBool("install_downgrade", false)) f.add("-d");
        return f;
    }

    public interface Log {
        void log(String s);
    }

    /** APK simple ou archive XAPK/APKS/APKM (avec OBB). */
    public static Result installFile(AdbDevice d, File f, AdbDevice.Progress pg, Log log) {
        String low = f.getName().toLowerCase(Locale.ROOT);
        if (low.endsWith(".apk")) {
            List<File> one = new ArrayList<File>();
            one.add(f);
            return d.install(one, installFlags(), pg);
        }
        File tmp = new File(Config.cacheDir("bundle"), "b" + System.nanoTime());
        tmp.mkdirs();
        try {
            ZipFile z;
            try {
                z = new ZipFile(f);
            } catch (IOException e) {
                return new Result(1, "", "Archive illisible (APKM chiffré ?) : " + e.getMessage());
            }
            List<File> apks = new ArrayList<File>();
            List<String[]> obbs = new ArrayList<String[]>();
            try {
                java.util.Enumeration<? extends ZipEntry> en = z.entries();
                while (en.hasMoreElements()) {
                    ZipEntry e = en.nextElement();
                    if (e.isDirectory()) continue;
                    String n = e.getName();
                    if (n.contains("..")) continue;
                    boolean isApk = n.toLowerCase(Locale.ROOT).endsWith(".apk");
                    boolean isObb = n.startsWith("Android/obb/");
                    if (!isApk && !isObb) continue;
                    File out = new File(tmp, n);
                    out.getParentFile().mkdirs();
                    InputStream in = z.getInputStream(e);
                    FileOutputStream fo = new FileOutputStream(out);
                    byte[] b = new byte[65536];
                    int k;
                    while ((k = in.read(b)) > 0) fo.write(b, 0, k);
                    fo.close();
                    in.close();
                    if (isObb) obbs.add(new String[]{out.getAbsolutePath(), "/sdcard/" + n});
                    else apks.add(out);
                }
            } finally {
                z.close();
            }
            if (apks.isEmpty()) return new Result(1, "", "Aucun APK dans l'archive");
            Collections.sort(apks, new Comparator<File>() {
                public int compare(File a, File b) {
                    int ka = a.getName().equals("base.apk") ? 0 : 1, kb = b.getName().equals("base.apk") ? 0 : 1;
                    return ka != kb ? ka - kb : a.getPath().compareTo(b.getPath());
                }
            });
            Result r = d.install(apks, installFlags(), pg);
            if (r.ok() && !obbs.isEmpty()) {
                if (log != null) log.log("Copie des OBB…");
                for (String[] o : obbs) {
                    try {
                        d.shell("mkdir -p " + q(o[1].substring(0, o[1].lastIndexOf('/'))), 20);
                        d.push(new File(o[0]), o[1], null);
                    } catch (IOException e) {
                        if (log != null) log.log("OBB : " + e.getMessage());
                    }
                }
            }
            return r;
        } catch (IOException e) {
            return new Result(1, "", String.valueOf(e.getMessage()));
        } finally {
            deleteTree(tmp);
        }
    }

    public static void deleteTree(File f) {
        File[] l = f.listFiles();
        if (l != null) for (File c : l) deleteTree(c);
        f.delete();
    }
}

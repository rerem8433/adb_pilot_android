package fr.paradoxxx.adbpilot;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import fr.paradoxxx.adbpilot.adb.AdbConnection;
import fr.paradoxxx.adbpilot.adb.AdbCrypto;
import fr.paradoxxx.adbpilot.adb.AdbDevice;
import fr.paradoxxx.adbpilot.adb.Fastboot;
import fr.paradoxxx.adbpilot.adb.Transport;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** État de l'appareil courant, connexions, tâches en arrière-plan (portage de controller.py). */
public final class Controller {
    public interface Done<T> {
        void done(T result, Exception error);
    }

    public interface Ui {
        void log(String html, String level);

        void status(String text);

        void deviceChanged(Dev d);

        void devicesChanged();

        void packages(List<Pkg> pkgs);

        void metaStarted(int total);

        void meta(Pkg p, boolean ok, int n, int total);

        void ram(long used, long total);

        void info(Map<String, String> info, Map<String, Object> specs);

        void connecting(String target);

        void connectResult(String target, boolean ok, String text);

        void authPrompt(Dev d);
    }

    /** Appareil connu (adb ou fastboot). */
    public static final class Dev {
        public String serial;
        public String state;      // device | unauthorized | connecting | fastboot
        public boolean usb;
        public AdbDevice adb;
        public Fastboot fastboot;
        public UsbDevice usbDevice;
        public String model = "";

        public boolean isNetwork() {
            return !usb;
        }

        public String label() {
            return model == null || model.isEmpty() ? serial : model;
        }
    }

    public final Handler main = new Handler(Looper.getMainLooper());
    public final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final ExecutorService metaPool = Executors.newSingleThreadExecutor();
    public final List<Dev> devices = new ArrayList<Dev>();
    public Dev current;
    public List<Pkg> packages = new ArrayList<Pkg>();
    public Map<String, String> lastInfo;
    public Map<String, Object> lastSpecs;
    private Ui ui;
    private AdbCrypto crypto;
    private volatile int metaGen;
    private int metaTotal;
    public UsbManager usbManager;

    public static Controller instance;

    public Controller(Ui ui) {
        this.ui = ui;
        instance = this;
    }

    // ------------------------------------------------------------------ utilitaires
    public <T> void submit(final Callable<T> fn, final Done<T> done) {
        pool.submit(new Runnable() {
            public void run() {
                T r = null;
                Exception err = null;
                try {
                    r = fn.call();
                } catch (Exception e) {
                    err = e;
                    log("Erreur : " + Html.esc(String.valueOf(e.getMessage())), "err");
                }
                if (done != null) {
                    final T rr = r;
                    final Exception ee = err;
                    main.post(new Runnable() {
                        public void run() {
                            done.done(rr, ee);
                        }
                    });
                }
            }
        });
    }

    public void run(final Runnable r) {
        pool.submit(new Runnable() {
            public void run() {
                try {
                    r.run();
                } catch (Exception e) {
                    log("Erreur : " + Html.esc(String.valueOf(e.getMessage())), "err");
                }
            }
        });
    }

    public void ui(Runnable r) {
        main.post(r);
    }

    public void log(final String text, final String level) {
        main.post(new Runnable() {
            public void run() {
                ui.log(text, level);
            }
        });
    }

    public void status(final String s) {
        main.post(new Runnable() {
            public void run() {
                ui.status(s);
            }
        });
    }

    public boolean ready() {
        return current != null && "device".equals(current.state) && current.adb != null;
    }

    public AdbDevice adb() {
        return current == null ? null : current.adb;
    }

    /** Commande loguée dans la console (« >> adb shell … »). */
    public AdbDevice.Result shellLogged(String cmd, int timeout, String okMsg) {
        log(">> adb shell " + Html.esc(cmd), "cmd");
        AdbDevice d = adb();
        if (d == null) {
            log("[Erreur] : aucun appareil connecté", "err");
            return new AdbDevice.Result(1, "", "aucun appareil");
        }
        AdbDevice.Result r = d.shell(cmd, timeout);
        logRes(r, okMsg);
        return r;
    }

    public void logRes(AdbDevice.Result r, String okMsg) {
        if (r.ok()) {
            if (!r.text().isEmpty()) log(Html.esc(r.text()), "out");
            if (okMsg != null) log("[Succès] : " + okMsg, "ok");
        } else {
            log("[Erreur] : " + Html.esc(r.text().isEmpty() ? "code " + r.rc : r.text()), "err");
        }
    }

    private synchronized AdbCrypto crypto() throws Exception {
        if (crypto == null) {
            String name = "adbpilot@" + (Build.MODEL == null ? "android" : Build.MODEL.replace(' ', '_'));
            crypto = AdbCrypto.loadOrCreate(Config.dataDir("keys"), name);
        }
        return crypto;
    }

    public String keyFingerprint() {
        try {
            return crypto().fingerprint();
        } catch (Exception e) {
            return "?";
        }
    }

    // ------------------------------------------------------------------ appareils
    public void start() {
        run(new Runnable() {
            public void run() {
                try {
                    crypto();
                } catch (Exception e) {
                    log("[Erreur] : clé ADB : " + Html.esc(String.valueOf(e.getMessage())), "err");
                }
            }
        });
        if (Config.getBool("auto_reconnect", true) && !Config.getStr("last_ip", "").isEmpty())
            connectIp(Config.getStr("last_ip", ""), true);
        scanUsb();
    }

    private void notifyDevices() {
        main.post(new Runnable() {
            public void run() {
                ui.devicesChanged();
            }
        });
    }

    private void setCurrent(final Dev d) {
        main.post(new Runnable() {
            public void run() {
                current = d;
                metaGen++;
                ui.deviceChanged(d);
                if (d != null && "device".equals(d.state)) {
                    ui.log("Connecté à " + Html.esc(d.label()) + " (" + Html.esc(d.serial) + ")", "ok");
                    if (d.isNetwork()) {
                        Map<String, String> names = Config.getMap("ip_names");
                        names.put(d.serial.split(":")[0], d.label());
                        Config.setMap("ip_names", names);
                    }
                    loadInfo();
                    refreshPackages();
                    refreshRam();
                }
            }
        });
    }

    public void select(Dev d) {
        if (d != current) setCurrent(d);
    }

    private void addDevice(Dev d) {
        synchronized (devices) {
            for (int i = 0; i < devices.size(); i++) {
                if (devices.get(i).serial.equals(d.serial)) {
                    devices.set(i, d);
                    notifyDevices();
                    return;
                }
            }
            devices.add(d);
        }
        notifyDevices();
    }

    private void removeDevice(final Dev d) {
        synchronized (devices) {
            devices.remove(d);
        }
        notifyDevices();
        main.post(new Runnable() {
            public void run() {
                if (current == d) {
                    Dev next = null;
                    synchronized (devices) {
                        for (Dev x : devices) if ("device".equals(x.state)) next = x;
                    }
                    current = null;
                    setCurrent(next);
                }
            }
        });
    }

    /** Ouvre la connexion ADB (TCP ou USB) ; met à jour l'état « non autorisé » pendant l'attente. */
    private void openAdb(final Dev dev, Transport t, int timeoutMs) throws Exception {
        final AdbConnection c = new AdbConnection(t);
        c.connect(crypto(), new AdbConnection.Listener() {
            public void onAuthPrompt() {
                dev.state = "unauthorized";
                addDevice(dev);
                main.post(new Runnable() {
                    public void run() {
                        ui.authPrompt(dev);
                    }
                });
            }

            public void onClosed(AdbConnection cc, String reason) {
                if (dev.adb != null && dev.adb.conn == cc) {
                    log("Appareil déconnecté : " + Html.esc(dev.label()) + " (" + Html.esc(reason) + ")", "info");
                    removeDevice(dev);
                }
            }
        }, timeoutMs);
        dev.adb = new AdbDevice(dev.serial, c);
        String model = c.prop("ro.product.model");
        if (model.isEmpty()) model = c.prop("ro.product.name");
        dev.model = model.replace("_", " ").trim();
        dev.state = "device";
    }

    public void connectIp(String text, final boolean quiet) {
        text = text.trim();
        if (text.isEmpty()) return;
        String port = Config.getStr("port", "auto").trim();
        if (!port.matches("\\d+")) port = "5555";
        final String target = text.contains(":") ? text : text + ":" + port;
        final String typed = text;
        if (!quiet) ui.connecting(target);
        run(new Runnable() {
            public void run() {
                // Un seul appareil réseau à la fois (comme la version PC)
                List<Dev> old = new ArrayList<Dev>();
                synchronized (devices) {
                    for (Dev d : devices) if (d.isNetwork()) old.add(d);
                }
                for (Dev d : old) {
                    if (d.adb != null) d.adb.conn.close("déconnexion");
                    removeDevice(d);
                }
                if (!quiet) log(">> adb connect " + Html.esc(target), "cmd");
                // Connexion par le Wi-Fi / Ethernet, jamais par les données mobiles
                NetScan.bindToLan(Config.ctx);
                String host = target.substring(0, target.lastIndexOf(':'));
                int p = Integer.parseInt(target.substring(target.lastIndexOf(':') + 1));
                final Dev dev = new Dev();
                dev.serial = target;
                dev.state = "connecting";
                boolean ok;
                String msg;
                try {
                    Transport.Tcp t = new Transport.Tcp(host, p, 6000);
                    t.setReadTimeout(0);
                    openAdb(dev, t, 60000);
                    ok = true;
                    msg = "connected to " + target;
                } catch (Exception e) {
                    ok = false;
                    msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    removeDevice(dev);
                }
                if (!quiet || ok) log(Html.esc(msg), ok ? "ok" : "err");
                if (ok) {
                    List<String> hist = Config.getList("ip_history");
                    hist.remove(typed);
                    hist.add(0, typed);
                    while (hist.size() > 15) hist.remove(hist.size() - 1);
                    Config.setList("ip_history", hist);
                    Config.setStr("last_ip", typed);
                    addDevice(dev);
                    setCurrent(dev);
                }
                final boolean fok = ok;
                final String fmsg = msg;
                if (!quiet || ok) main.post(new Runnable() {
                    public void run() {
                        ui.connectResult(target, fok, fmsg);
                    }
                });
            }
        });
    }

    public void disconnect(final Dev d) {
        if (d == null) return;
        if (d.isNetwork() && Config.getStr("last_ip", "").length() > 0 && d.serial.startsWith(Config.getStr("last_ip", "").split(":")[0]))
            Config.setStr("last_ip", "");
        run(new Runnable() {
            public void run() {
                log(">> adb disconnect " + Html.esc(d.serial), "cmd");
                if (d.adb != null) d.adb.conn.close("déconnecté");
                if (d.fastboot != null) d.fastboot.close();
                removeDevice(d);
                log("disconnected " + Html.esc(d.serial), "out");
            }
        });
    }

    public void disconnectAllNetwork() {
        Config.setStr("last_ip", "");
        List<Dev> l = new ArrayList<Dev>();
        synchronized (devices) {
            for (Dev d : devices) if (d.isNetwork()) l.add(d);
        }
        for (Dev d : l) disconnect(d);
    }

    // ------------------------------------------------------------------ USB
    public static UsbInterface adbInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == 0xff && f.getInterfaceSubclass() == 0x42 && f.getInterfaceProtocol() == 0x01)
                return f;
        }
        return null;
    }

    public interface UsbPermission {
        void request(UsbDevice d);
    }

    public UsbPermission usbPermission;

    public void scanUsb() {
        if (usbManager == null) return;
        for (UsbDevice d : usbManager.getDeviceList().values()) usbAttached(d);
    }

    public void usbAttached(final UsbDevice ud) {
        if (usbManager == null) return;
        final UsbInterface ai = adbInterface(ud), fi = Fastboot.findInterface(ud);
        if (ai == null && fi == null) return;
        synchronized (devices) {
            for (Dev d : devices) if (d.usb && d.usbDevice != null && d.usbDevice.getDeviceName().equals(ud.getDeviceName())) return;
        }
        if (!usbManager.hasPermission(ud)) {
            if (usbPermission != null) usbPermission.request(ud);
            return;
        }
        run(new Runnable() {
            public void run() {
                Dev dev = new Dev();
                dev.usb = true;
                dev.usbDevice = ud;
                String serial = null;
                try {
                    serial = ud.getSerialNumber();
                } catch (SecurityException ignored) {
                }
                dev.serial = TextUtils.isEmpty(serial) ? ud.getDeviceName() : serial;
                try {
                    if (ai != null) {
                        dev.state = "connecting";
                        log(">> USB : " + Html.esc(ud.getProductName() == null ? dev.serial : ud.getProductName()), "cmd");
                        openAdb(dev, new Transport.Usb(usbManager, ud, ai), 90000);
                        addDevice(dev);
                        setCurrent(dev);
                    } else {
                        dev.fastboot = new Fastboot(usbManager, ud, fi);
                        dev.state = "fastboot";
                        dev.model = ud.getProductName() == null ? "" : ud.getProductName();
                        addDevice(dev);
                        log("Appareil fastboot détecté : " + Html.esc(dev.serial), "info");
                    }
                } catch (Exception e) {
                    log("[Erreur] : USB : " + Html.esc(String.valueOf(e.getMessage())), "err");
                    removeDevice(dev);
                }
            }
        });
    }

    public void usbDetached(UsbDevice ud) {
        List<Dev> gone = new ArrayList<Dev>();
        synchronized (devices) {
            for (Dev d : devices)
                if (d.usb && d.usbDevice != null && d.usbDevice.getDeviceName().equals(ud.getDeviceName())) gone.add(d);
        }
        for (Dev d : gone) {
            if (d.adb != null) d.adb.conn.close("débranché");
            if (d.fastboot != null) d.fastboot.close();
            removeDevice(d);
        }
    }

    public Dev fastbootDevice() {
        synchronized (devices) {
            for (Dev d : devices) if ("fastboot".equals(d.state)) return d;
        }
        return null;
    }

    // ------------------------------------------------------------------ informations
    public void loadInfo() {
        final Dev d = current;
        if (!ready()) return;
        submit(new Callable<Object[]>() {
            public Object[] call() {
                Map<String, String> info = Core.deviceInfo(d.adb);
                Map<String, Object> specs;
                try {
                    specs = Core.deviceSpecs(d.adb, info);
                } catch (Exception e) {
                    specs = new java.util.HashMap<String, Object>();
                }
                return new Object[]{info, specs};
            }
        }, new Done<Object[]>() {
            @SuppressWarnings("unchecked")
            public void done(Object[] r, Exception e) {
                if (r == null || current != d) return;
                lastInfo = (Map<String, String>) r[0];
                lastSpecs = (Map<String, Object>) r[1];
                ui.info(lastInfo, lastSpecs);
            }
        });
    }

    public void refreshRam() {
        if (!ready()) return;
        final AdbDevice d = adb();
        submit(new Callable<long[]>() {
            public long[] call() {
                return Core.ramInfo(d);
            }
        }, new Done<long[]>() {
            public void done(long[] r, Exception e) {
                if (r != null && r[1] > 0) ui.ram(r[0], r[1]);
            }
        });
    }

    // ------------------------------------------------------------------ paquets
    public void refreshPackages() {
        if (!ready()) return;
        ui.status("Lecture de la liste des applications…");
        final Dev d = current;
        submit(new Callable<List<Pkg>>() {
            public List<Pkg> call() {
                return Core.listPackages(d.adb);
            }
        }, new Done<List<Pkg>>() {
            public void done(List<Pkg> pkgs, Exception e) {
                if (pkgs == null || !ready() || current != d) return;
                String lang = Config.getStr("label_lang", "fr");
                Map<String, Pkg> old = new java.util.HashMap<String, Pkg>();
                for (Pkg p : packages) old.put(p.name, p);
                for (Pkg p : pkgs) {
                    Pkg o = old.get(p.name);
                    if (o != null && o.path.equals(p.path) && o.baseSize == p.baseSize && (o.label != null || o.icon != null || o.metaDone)) {
                        p.label = o.label;
                        p.icon = o.icon;
                        p.version = o.version;
                        p.metaDone = o.metaDone;
                    } else if (Config.getBool("load_meta", true)) {
                        MetaCache.loadCached(p, lang);
                    }
                }
                packages = pkgs;
                ui.packages(pkgs);
                ui.status(pkgs.size() + " applications");
                ui.log("[Succès] : Données des applications mises à jour.", "ok");
                if (Config.getBool("load_meta", true)) startMeta();
            }
        });
    }

    public void startMeta() {
        final int gen = ++metaGen;
        final Dev d = current;
        final String lang = Config.getStr("label_lang", "fr");
        final List<Pkg> todo = new ArrayList<Pkg>();
        for (Pkg p : packages) if (!p.metaDone && p.label == null && p.icon == null && !p.path.isEmpty()) todo.add(p);
        final int total = todo.size();
        if (total == 0 || d == null) return;
        metaTotal = total;
        ui.metaStarted(total);
        metaPool.submit(new Runnable() {
            public void run() {
                try {
                    MetaCache.fetch(d.adb, todo, lang, new MetaCache.Sink() {
                        public boolean onMeta(final Pkg p, final boolean ok, final int n, final int tot) {
                            if (gen != metaGen) return false;
                            if (!ok) log("[Erreur] : nom/icône illisibles pour " + Html.esc(p.name) + " (" + Html.esc(p.path) + ")", "err");
                            main.post(new Runnable() {
                                public void run() {
                                    if (gen == metaGen) ui.meta(p, ok, n, tot);
                                }
                            });
                            return true;
                        }
                    });
                } catch (final Exception e) {
                    log("[Erreur] : noms/icônes : " + Html.esc(String.valueOf(e.getMessage())), "err");
                    main.post(new Runnable() {
                        public void run() {
                            if (gen == metaGen) ui.meta(null, false, total, total);
                        }
                    });
                }
            }
        });
    }

    public void cancelMeta() {
        metaGen++;
        ui.meta(null, false, metaTotal, metaTotal);
    }

    public void shutdown() {
        metaGen++;
        synchronized (devices) {
            for (Dev d : devices) {
                if (d.adb != null) d.adb.conn.close("fermeture");
                if (d.fastboot != null) d.fastboot.close();
            }
        }
        pool.shutdownNow();
        metaPool.shutdownNow();
    }
}

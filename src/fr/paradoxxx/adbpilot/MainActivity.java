package fr.paradoxxx.adbpilot;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import fr.paradoxxx.adbpilot.ui.AboutTab;
import fr.paradoxxx.adbpilot.ui.AppsTab;
import fr.paradoxxx.adbpilot.ui.ConsoleTab;
import fr.paradoxxx.adbpilot.ui.Dlg;
import fr.paradoxxx.adbpilot.ui.ProcessTab;
import fr.paradoxxx.adbpilot.ui.ProgressOverlay;
import fr.paradoxxx.adbpilot.ui.ScanPanel;
import fr.paradoxxx.adbpilot.ui.SettingsTab;
import fr.paradoxxx.adbpilot.ui.StoreTab;
import fr.paradoxxx.adbpilot.ui.Tab;
import fr.paradoxxx.adbpilot.ui.ToolsTab;
import fr.paradoxxx.adbpilot.ui.Ui;
import fr.paradoxxx.adbpilot.ui.Widgets;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

public final class MainActivity extends Activity implements Controller.Ui {
    public static final String VERSION = "0.3.2";
    private static final String ACTION_USB_PERMISSION = "fr.paradoxxx.adbpilot.USB_PERMISSION";
    private static final int REQ_FILES = 41, REQ_PUSH = 42;

    public Controller ctl;
    public ProgressOverlay overlay;
    private FrameLayout root, content;
    private LinearLayout header;
    private TextView devLabel, statusLbl, toastView;
    private EditText ip, search;
    private Ui.Btn bGo, bInfo, bTcpip, bScan;
    private ScanPanel scan;
    private final List<Tab> tabs = new ArrayList<Tab>();
    private final List<TextView> tabBtns = new ArrayList<TextView>();
    private int curTab = -1;
    private android.widget.HorizontalScrollView tabScroll;
    public AppsTab apps;
    public ToolsTab tools;
    public StoreTab store;
    public ConsoleTab console;
    public ProcessTab procs;
    private String specsText = "";
    private final Handler h = new Handler();
    private Runnable ramTick;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Config.init(this);
        Ui.init(this);
        Ui.fontScale = Config.getInt("font_scale", 100) / 100f;
        // Tablette : paysage comme la version PC · smartphone : portrait (scrcpy passe en paysage tout seul)
        setRequestedOrientation(Ui.phone ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ctl = new Controller(this);
        ctl.usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        build();
        askStorage();
        for (String sd : Config.SUBDIRS) Config.userDir(sd);
        IntentFilter f = new IntentFilter();
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(ACTION_USB_PERMISSION);
        registerReceiver(usbReceiver, f);
        registerReceiver(installReceiver, new IntentFilter(Updater.ACTION_STATUS));
        h.postDelayed(new Runnable() {
            public void run() {
                if (!isFinishing()) fr.paradoxxx.adbpilot.ui.Dialogs.targetNoticeOnce(MainActivity.this);
            }
        }, 700);
        if (Config.getBool("update_check", true)) h.postDelayed(new Runnable() {
            public void run() {
                fr.paradoxxx.adbpilot.ui.UpdateDialog.check(MainActivity.this, false);
            }
        }, 6000);
        ctl.usbPermission = new Controller.UsbPermission() {
            public void request(UsbDevice d) {
                int flags = Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0;
                Intent it = new Intent(ACTION_USB_PERMISSION).setPackage(getPackageName());
                PendingIntent pi = PendingIntent.getBroadcast(MainActivity.this, 0, it, flags);
                ctl.usbManager.requestPermission(d, pi);
            }
        };
        new Thread(new Runnable() {
            public void run() {
                Debloat.sync();
            }
        }).start();
        h.postDelayed(new Runnable() {
            public void run() {
                ctl.start();
            }
        }, 300);
        ramTick = new Runnable() {
            public void run() {
                ctl.refreshRam();
                h.postDelayed(this, 10000);
            }
        };
        h.postDelayed(ramTick, 10000);
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent it) {
        super.onNewIntent(it);
        handleIntent(it);
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(usbReceiver);
            unregisterReceiver(installReceiver);
        } catch (Exception ignored) {
        }
        for (Tab t : tabs) t.onHidden();
        if (console != null) console.shutdown();
        ctl.shutdown();
        super.onDestroy();
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (d == null) return;
            String a = i.getAction();
            if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) ctl.usbDetached(d);
            else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) ctl.usbAttached(d);
            else if (ACTION_USB_PERMISSION.equals(a)) {
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) ctl.usbAttached(d);
                else toast("Accès USB refusé : rebranche l'appareil et accepte la demande.");
            }
        }
    };

    /** Résultat de l'installation d'une mise à jour (PackageInstaller). */
    private final BroadcastReceiver installReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            int st = i.getIntExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, -999);
            if (st == android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(confirm);
                }
            } else if (st != android.content.pm.PackageInstaller.STATUS_SUCCESS) {
                String msg = i.getStringExtra(android.content.pm.PackageInstaller.EXTRA_STATUS_MESSAGE);
                toast("Mise à jour non installée : " + (msg == null ? "annulée" : msg), 8000);
            }
        }
    };

    private void askStorage() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE}, 1);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        for (String sd : Config.SUBDIRS) Config.userDir(sd);
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager() && !Config.userDir().canWrite()) {
            Dlg.confirm(this, "Accès aux fichiers", "ADB Pilot range captures, sauvegardes et APK dans « ADB_Pilot » "
                    + "(stockage interne). Autoriser l'accès à tous les fichiers ?", new Dlg.Ok() {
                public void ok() {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                }
            });
        }
    }

    // ------------------------------------------------------------------ construction
    private void build() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Ui.BG);
        LinearLayout main = Ui.col(this);
        root.addView(main, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        boolean ph = Ui.phone;

        // --- éléments de l'en-tête (disposés ensuite selon la taille de l'écran)
        ImageView logo = new ImageView(this);
        logo.setImageBitmap(Ui.iconBitmap(this, "logo"));
        TextView la = Ui.text(this, "ADB", ph ? 18 : 22, Ui.TITLE);
        la.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        TextView lb = Ui.text(this, "Pilot", ph ? 15 : 18, Ui.TEXT);
        lb.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        ImageView folder = Ui.iconButton(this, "folder", Ui.TEXT, 22);
        folder.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openFolder(Config.userDir());
            }
        });
        ip = Ui.edit(this, "IP de l'appareil");
        ip.setTypeface(Typeface.MONOSPACE);
        ip.setBackground(Ui.box(0x40000000, 0xff555555, 0, 1));
        ip.setText(Config.getStr("last_ip", ""));
        ip.setImeOptions(EditorInfo.IME_ACTION_GO);
        ip.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        ip.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                connectClicked();
                return true;
            }
        });
        bScan = Ui.button(this, "Détecter", "scan");
        bScan.icon(Ui.icon(this, "search", 0xffffffff), 16);
        bScan.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hideKeyboard();
                scan.toggle();
            }
        });
        bGo = Ui.button(this, "GO !", "go");
        bGo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                connectClicked();
            }
        });
        final ImageView hist = Ui.iconButton(this, "history", Ui.TEXT, 22);
        hist.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                historyMenu(hist);
            }
        });
        final EditText port = Ui.edit(this, "");
        String pv = Config.getStr("port", "auto");
        port.setText(pv.equalsIgnoreCase("auto") ? "Auto" : pv);
        port.setPadding(dp(5), dp(3), dp(2), dp(3));
        port.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable e) {
                String t = e.toString().trim();
                Config.setStr("port", t.isEmpty() || t.equalsIgnoreCase("auto") ? "auto" : t);
            }
        });
        LinearLayout portBox = Widgets.editableCombo(this, port, "Auto", "5555", "5556", "5037");
        ImageView refresh = Ui.iconButton(this, "refresh", Ui.TEXT, 22);
        refresh.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                refreshAll();
            }
        });
        devLabel = Ui.single(Ui.bold(Ui.text(this, "Aucun appareil connecté", ph ? 11 : 13, Ui.MUTED)));
        devLabel.setPadding(dp(4), 0, dp(ph ? 6 : 10), 0);
        devLabel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pickDevice();
            }
        });
        bInfo = Ui.button(this, infoText(), "tcpip");
        bInfo.setEnabled(false);
        bInfo.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!specsText.isEmpty()) toast(specsText, 12000);
            }
        });
        bTcpip = Ui.button(this, "Passer en Wi-Fi", "tcpip");
        bTcpip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                tools.toWifi();
            }
        });
        LinearLayout sbox = Ui.row(this);
        sbox.setBackgroundColor(0x40000000);
        sbox.addView(Ui.image(this, "search", 0xffcccccc, 18));
        search = Ui.edit(this, "Rechercher…");
        search.setBackground(null);
        search.setImeOptions(EditorInfo.IME_ACTION_DONE);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable e) {
                apps.setSearch(e.toString());
                store.setSearch(e.toString());
            }
        });
        sbox.addView(search, Ui.lpw(0, dp(34), 1));
        ImageView clr = Ui.iconButton(this, "minus", 0xff999999, 14);
        clr.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                search.setText("");
            }
        });
        sbox.addView(clr);
        sbox.setPadding(dp(6), 0, dp(2), 0);

        header = Ui.col(this);
        header.setBackgroundColor(Ui.HEADER);
        if (ph) {
            // Smartphone : 4 lignes compactes sur toute la largeur
            header.setPadding(dp(6), dp(4), dp(6), dp(6));
            LinearLayout r1 = Ui.row(this);
            r1.addView(logo, Ui.lp(dp(28), dp(28)));
            r1.addView(Ui.space(this, 4, 1));
            r1.addView(la);
            r1.addView(lb);
            r1.addView(Ui.stretch(this));
            r1.addView(bScan, Ui.lp(Ui.WRAP, dp(32)));
            r1.addView(Ui.space(this, 2, 1));
            r1.addView(hist);
            r1.addView(folder);
            header.addView(r1);
            LinearLayout r2 = Ui.row(this);
            r2.setPadding(0, dp(4), 0, 0);
            r2.addView(ip, Ui.lpw(0, dp(36), 1));
            LinearLayout.LayoutParams pl = Ui.lp(dp(80), dp(36));
            pl.leftMargin = dp(4);
            r2.addView(portBox, pl);
            LinearLayout.LayoutParams gl = Ui.lp(dp(56), dp(36));
            gl.leftMargin = dp(4);
            r2.addView(bGo, gl);
            header.addView(r2);
            LinearLayout r3 = Ui.row(this);
            r3.setPadding(0, dp(3), 0, 0);
            r3.addView(refresh);
            r3.addView(devLabel, Ui.lpw(0, Ui.WRAP, 1));
            r3.addView(bInfo, Ui.lp(Ui.WRAP, dp(28)));
            LinearLayout.LayoutParams tl = Ui.lp(Ui.WRAP, dp(28));
            tl.leftMargin = dp(4);
            r3.addView(bTcpip, tl);
            header.addView(r3);
            LinearLayout.LayoutParams sl = Ui.lp(Ui.MATCH, dp(36));
            sl.topMargin = dp(4);
            header.addView(sbox, sl);
        } else {
            header.setPadding(dp(8), dp(4), dp(10), dp(6));
            LinearLayout r1 = Ui.row(this);
            r1.addView(logo, Ui.lp(dp(34), dp(34)));
            r1.addView(Ui.space(this, 4, 1));
            r1.addView(la);
            r1.addView(lb);
            r1.addView(Ui.space(this, 14, 1));
            r1.addView(folder);
            r1.addView(Ui.stretch(this));
            LinearLayout conn = Ui.col(this);
            LinearLayout c0 = Ui.row(this);
            c0.addView(ip, Ui.lp(dp(170), dp(32)));
            LinearLayout.LayoutParams sl = Ui.lp(Ui.WRAP, dp(32));
            sl.leftMargin = dp(4);
            c0.addView(bScan, sl);
            LinearLayout.LayoutParams gl = Ui.lp(dp(58), dp(32));
            gl.leftMargin = dp(4);
            c0.addView(bGo, gl);
            c0.addView(hist);
            conn.addView(c0);
            LinearLayout c1 = Ui.row(this);
            c1.setPadding(0, dp(2), 0, 0);
            c1.addView(Ui.stretch(this));
            c1.addView(Ui.text(this, "Port : ", 8, Ui.TEXT));
            c1.addView(portBox, Ui.lp(dp(84), dp(28)));
            LinearLayout pr = Ui.row(this);
            pr.addView(c1, Ui.lp(dp(170), Ui.WRAP));
            conn.addView(pr);
            r1.addView(conn);
            header.addView(r1);

            LinearLayout r2 = Ui.row(this);
            LinearLayout left = Ui.row(this);
            left.addView(refresh);
            left.addView(devLabel, Ui.lpw(Ui.WRAP, Ui.WRAP, 0));
            left.addView(bInfo, Ui.lp(Ui.WRAP, dp(26)));
            LinearLayout.LayoutParams tl = Ui.lp(Ui.WRAP, dp(26));
            tl.leftMargin = dp(6);
            left.addView(bTcpip, tl);
            r2.addView(left, Ui.lpw(0, Ui.WRAP, 1));
            r2.addView(sbox, Ui.lp(dp(260), dp(34)));
            r2.addView(new View(this), Ui.lpw(0, 1, 1));
            r2.setPadding(0, dp(2), 0, 0);
            header.addView(r2);
        }
        main.addView(header, Ui.lp(Ui.MATCH, Ui.WRAP));

        // Onglets
        LinearLayout tw = Ui.col(this);
        tw.setPadding(dp(ph ? 2 : 4), dp(4), dp(ph ? 2 : 4), 0);
        LinearLayout tabBar = Ui.row(this);
        if (ph) {
            tabScroll = Ui.hscroll(this, tabBar);
            tabScroll.setFillViewport(false);
            tw.addView(tabScroll, Ui.lp(Ui.MATCH, Ui.WRAP));
        } else tw.addView(tabBar);
        content = new FrameLayout(this);
        content.setBackground(Ui.box(Ui.PANEL, Ui.BORDER, 0, 1));
        tw.addView(content, Ui.lpw(Ui.MATCH, 0, 1));
        main.addView(tw, Ui.lpw(Ui.MATCH, 0, 1));

        apps = new AppsTab(this);
        store = new StoreTab(this);
        tools = new ToolsTab(this);
        procs = new ProcessTab(this);
        console = new ConsoleTab(this);
        String[] names = {"Applications", "Store", "Outils", "Processus", "Console", "Paramètres", "À propos"};
        Tab[] all = {apps, store, tools, procs, console, new SettingsTab(this), new AboutTab(this)};
        for (int i = 0; i < all.length; i++) {
            final int idx = i;
            tabs.add(all[i]);
            TextView t = Ui.text(this, names[i], 10, Ui.TEXT);
            t.setPadding(dp(ph ? 12 : 14), dp(ph ? 9 : 7), dp(ph ? 12 : 14), dp(ph ? 9 : 7));
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    showTab(idx);
                }
            });
            LinearLayout.LayoutParams lp = Ui.lp(Ui.WRAP, Ui.WRAP);
            lp.rightMargin = dp(1);
            tabBar.addView(t, lp);
            tabBtns.add(t);
            all[i].view.setVisibility(View.GONE);
            content.addView(all[i].view, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        }

        LinearLayout sb = Ui.row(this);
        sb.setBackgroundColor(Ui.HEADER);
        statusLbl = Ui.single(Ui.text(this, "Prêt", 10, Ui.TEXT));
        sb.setPadding(dp(6), dp(4), dp(6), dp(4));
        sb.addView(statusLbl, Ui.lpw(0, Ui.WRAP, 1));
        main.addView(sb, Ui.lp(Ui.MATCH, Ui.WRAP));

        overlay = new ProgressOverlay(this);
        root.addView(overlay, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        scan = new ScanPanel(this, ctl, new ScanPanel.Connect() {
            public void connect(String ipAddr, int p) {
                String target = p == 5555 || p == 0 ? ipAddr : ipAddr + ":" + p;
                ip.setText(target);
                ctl.connectIp(target, false);
            }
        });
        int sw = getResources().getDisplayMetrics().widthPixels;
        final FrameLayout.LayoutParams sp = ph ? new FrameLayout.LayoutParams(Ui.MATCH, Ui.WRAP, Gravity.TOP)
                : new FrameLayout.LayoutParams(Math.min(dp(780), sw - dp(16)), Ui.WRAP, Gravity.TOP | Gravity.END);
        sp.topMargin = dp(46);
        sp.rightMargin = dp(8);
        if (ph) sp.leftMargin = dp(8);
        root.addView(scan, sp);
        toastView = Ui.text(this, "", 10, 0xffffffff);
        toastView.setGravity(Gravity.CENTER);
        toastView.setBackground(Ui.box(0xed141416, Ui.ACCENT_LINE, 6, 1));
        toastView.setPadding(dp(18), dp(12), dp(18), dp(12));
        toastView.setVisibility(View.GONE);
        toastView.setElevation(dp(20));
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(ph ? Ui.MATCH : dp(560), Ui.WRAP, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        tlp.bottomMargin = dp(50);
        if (ph) tlp.leftMargin = tlp.rightMargin = dp(12);
        root.addView(toastView, tlp);
        root.setOnTouchListener(null);
        setContentView(root);
        header.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                overlay.topOffset = b;
                if (Ui.phone && sp.topMargin != b) {  // panneau Détecter juste sous l'en-tête
                    sp.topMargin = b;
                    scan.setLayoutParams(sp);
                }
            }
        });
        showTab(0);
        bTcpip.setVisibility(View.GONE);
    }

    private static String infoText() {
        return Ui.phone ? "Infos" : "Infos appareil";
    }

    public void showTab(int i) {
        if (i == curTab) return;
        if (curTab >= 0) {
            tabs.get(curTab).view.setVisibility(View.GONE);
            tabs.get(curTab).onHidden();
        }
        curTab = i;
        for (int k = 0; k < tabBtns.size(); k++) {
            TextView t = tabBtns.get(k);
            boolean sel = k == i;
            t.setBackground(Ui.box(sel ? Ui.TAB_SEL : Ui.TAB, Ui.BORDER, 0, 1));
            t.setTextColor(sel ? 0xff111111 : Ui.TEXT);
        }
        tabs.get(i).view.setVisibility(View.VISIBLE);
        tabs.get(i).onShown();
        View sbox = (View) search.getParent();
        sbox.setVisibility(i == 0 || i == 1 ? View.VISIBLE : (Ui.phone ? View.GONE : View.INVISIBLE));
        if (tabScroll != null) {
            final View tb = tabBtns.get(i);
            tabScroll.post(new Runnable() {
                public void run() {
                    int x = tb.getLeft() - (tabScroll.getWidth() - tb.getWidth()) / 2;
                    tabScroll.smoothScrollTo(Math.max(0, x), 0);
                }
            });
        }
    }

    public void showTab(Tab t) {
        showTab(tabs.indexOf(t));
    }

    public boolean isCurrent(Tab t) {
        return curTab >= 0 && tabs.get(curTab) == t;
    }

    // ------------------------------------------------------------------ messages
    public void setStatus(String s) {
        statusLbl.setText(s.replace("\n", " — "));
    }

    private final Runnable hideToast = new Runnable() {
        public void run() {
            toastView.setVisibility(View.GONE);
        }
    };

    public void toast(String s) {
        toast(s, 6000);
    }

    public void toast(String s, int ms) {
        toastView.setText(s);
        toastView.setVisibility(View.VISIBLE);
        toastView.bringToFront();
        h.removeCallbacks(hideToast);
        h.postDelayed(hideToast, ms);
        setStatus(s);
    }

    public void notifyUser(String title, String body) {
        toast(title + "\n" + body, 8000);
    }

    // ------------------------------------------------------------------ Controller.Ui
    @Override
    public void log(String html, String level) {
        console.append(html, level);
    }

    @Override
    public void status(String text) {
        setStatus(text);
    }

    @Override
    public void deviceChanged(Controller.Dev d) {
        if (d == null) {
            devLabel.setText("Aucun appareil connecté");
            devLabel.setTextColor(Ui.MUTED);
            setStatus("En attente d'un appareil (USB ou Wi-Fi)…");
        } else if ("device".equals(d.state)) {
            devLabel.setText(d.label());
            devLabel.setTextColor(Ui.ACCENT_LINE);
        } else {
            devLabel.setText(d.serial + " — " + d.state);
            devLabel.setTextColor(Ui.MUTED);
        }
        bTcpip.setVisibility(d != null && d.usb ? View.VISIBLE : View.GONE);
        if (d == null || !"device".equals(d.state)) {
            specsText = "";
            bInfo.setText(infoText());
            bInfo.setEnabled(false);
        }
        for (Tab t : tabs) t.onDevice(d);
    }

    @Override
    public void devicesChanged() {
        Controller.Dev cur = ctl.current;
        if (cur == null) {
            synchronized (ctl.devices) {
                for (Controller.Dev d : ctl.devices) {
                    if ("unauthorized".equals(d.state)) {
                        devLabel.setText(d.serial + " — non autorisé");
                        devLabel.setTextColor(Ui.MUTED);
                    }
                }
            }
        }
        console.devicesChanged();
    }

    @Override
    public void packages(List<Pkg> pkgs) {
        for (Tab t : tabs) t.onPackages(pkgs);
    }

    private boolean metaOverlay;

    @Override
    public void metaStarted(int total) {
        metaOverlay = total >= 5;
        if (metaOverlay) {
            String dev = ctl.current != null ? ctl.current.label() : "";
            overlay.start("Lecture des noms et icônes", dev + " · lecture directe dans les APK", total, new Runnable() {
                public void run() {
                    ctl.cancelMeta();
                }
            });
        }
        apps.metaProgress(0, total);
    }

    @Override
    public void meta(Pkg p, boolean ok, int n, int total) {
        if (p != null) {
            for (Tab t : tabs) t.onMeta(p);
            if (metaOverlay && overlay.isShown2())
                overlay.item(p.name, p.display(), p.icon, ok ? "ok" : (p.metaDone ? "skip" : "err"), null);
        }
        apps.metaProgress(n, total);
        if (metaOverlay && overlay.isShown2()) {
            overlay.setDone(n);
            if (n >= total) {
                metaOverlay = false;
                overlay.finish("Noms et icônes à jour");
            }
        }
    }

    @Override
    public void ram(long used, long total) {
        apps.onRam(used, total);
    }

    @Override
    public void info(Map<String, String> info, Map<String, Object> sp) {
        List<String> parts = new ArrayList<String>();
        if (sp.get("ram_gb") != null) parts.add("RAM " + sp.get("ram_gb") + " Go");
        if (sp.get("storage_gb") != null) parts.add("Stockage " + sp.get("storage_gb") + " GB");
        if (sp.get("android") != null) parts.add("Android " + sp.get("android"));
        boolean warn = Boolean.TRUE.equals(sp.get("fake")) || sp.get("ram_fake") != null;
        StringBuilder summary = new StringBuilder();
        for (String s : parts) summary.append(summary.length() > 0 ? " · " : "").append(s);
        StringBuilder tip = new StringBuilder();
        tip.append("RAM détectée : ").append(sp.get("ram_exact") == null ? "?" : sp.get("ram_exact")).append('\n');
        tip.append("Stockage /data : ").append(sp.get("storage_exact") == null ? "?" : sp.get("storage_exact")).append('\n');
        tip.append("Android réel : ").append(sp.get("android")).append(" (API ").append(sp.get("sdk")).append(") — lu dans framework-res.apk\n");
        tip.append("build.prop annonce : Android ").append(sp.get("claimed")).append(" (API ").append(sp.get("claimed_sdk")).append(")");
        if (Boolean.TRUE.equals(sp.get("fake")))
            tip.append("\n⚠ build.prop semble modifié : la version annoncée ne correspond pas au système réel.");
        if (sp.get("ram_fake") != null)
            tip.append("\n⚠ RAM truquée par la ROM : elle annonce ").append(sp.get("ram_fake")).append(" Go, l'appareil n'en a que ").append(sp.get("ram_gb")).append(" Go.");
        specsText = summary + "\n\n" + tip;
        bInfo.setText(infoText() + (warn ? "  ⚠" : ""));
        bInfo.setEnabled(true);
        for (Tab t : tabs) t.onInfo(info, sp);
    }

    @Override
    public void connecting(String target) {
        bGo.setEnabled(false);
        toast("Connexion à " + target + "…", 15000);
    }

    @Override
    public void connectResult(String target, boolean ok, String text) {
        bGo.setEnabled(true);
        if (ok) {
            toast("Connecté à " + target, 3000);
            return;
        }
        String low = text == null ? "" : text.toLowerCase();
        String hint;
        if (low.contains("autorisation") || low.contains("unauthorized")) {
            hint = "Accepte la demande de débogage qui s'affiche sur l'écran de l'appareil.";
        } else if (low.contains("refused") || low.contains("econnrefused")) {
            hint = "L'appareil refuse la connexion : le débogage ADB réseau n'est pas activé (Options développeur → Débogage ADB / ADB sans fil), ou le port n'est pas 5555.";
        } else if (low.contains("timed out") || low.contains("timeout") || low.contains("unreachable") || low.contains("no route")
                || low.contains("ehostunreach") || low.contains("after ") || low.contains("connexion fermée") || low.isEmpty()) {
            hint = "Pas de réponse : vérifie l'IP, que l'appareil est allumé sur le même réseau, et que le débogage ADB réseau est activé.";
        } else hint = text;
        toast("Échec de la connexion à " + target + "\n" + hint, 10000);
    }

    @Override
    public void authPrompt(Controller.Dev d) {
        devLabel.setText(d.serial + " — non autorisé");
        devLabel.setTextColor(Ui.MUTED);
        toast("Accepte la demande « Autoriser le débogage ? » sur l'écran de " + d.serial
                + "\n(coche « Toujours autoriser depuis cet appareil »)\nEmpreinte : " + ctl.keyFingerprint(), 30000);
        log("Appareil " + Html.esc(d.serial) + " non autorisé : accepte la demande de débogage sur l'écran de l'appareil.", "err");
    }

    // ------------------------------------------------------------------ actions d'en-tête
    private void connectClicked() {
        hideKeyboard();
        String text = ip.getText().toString().trim();
        Controller.Dev cur = ctl.current;
        String host = text.split(":")[0];
        if (cur != null && cur.isNetwork() && (text.isEmpty() || cur.serial.split(":")[0].equals(host))) {
            ctl.disconnect(cur);
            return;
        }
        if (text.isEmpty()) {
            toast("Saisis l'adresse IP de l'appareil (débogage ADB réseau activé).");
            return;
        }
        ctl.connectIp(text, false);
    }

    private void historyMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        final List<String> hist = Config.getList("ip_history");
        Map<String, String> names = Config.getMap("ip_names");
        if (hist.isEmpty()) pm.getMenu().add(0, -1, 0, "(historique vide)").setEnabled(false);
        for (int i = 0; i < hist.size(); i++) {
            String hh = hist.get(i);
            String name = names.get(hh.split(":")[0]);
            pm.getMenu().add(0, i, i, name != null ? name + "  —  " + hh : hh);
        }
        pm.getMenu().add(1, 1000, 1000, "Déconnecter tous les appareils réseau");
        if (!hist.isEmpty()) pm.getMenu().add(1, 1001, 1001, "Effacer l'historique");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem it) {
                int id = it.getItemId();
                if (id == 1000) ctl.disconnectAllNetwork();
                else if (id == 1001) Config.setList("ip_history", new ArrayList<String>());
                else if (id >= 0 && id < hist.size()) {
                    ip.setText(hist.get(id));
                    ctl.connectIp(hist.get(id), false);
                }
                return true;
            }
        });
        pm.show();
    }

    private void pickDevice() {
        final List<Controller.Dev> l = new ArrayList<Controller.Dev>();
        synchronized (ctl.devices) {
            l.addAll(ctl.devices);
        }
        if (l.size() < 2) return;
        List<String> labels = new ArrayList<String>();
        for (Controller.Dev d : l) labels.add(d.label() + "  (" + d.serial + " · " + (d.usb ? "USB" : "Wi-Fi") + " · " + d.state + ")");
        Dlg.list(this, "Appareil", labels, new Dlg.Pick() {
            public void pick(int i) {
                if ("device".equals(l.get(i).state)) ctl.select(l.get(i));
            }
        });
    }

    public void refreshAll() {
        ctl.scanUsb();
        if (ctl.ready()) {
            ctl.loadInfo();
            ctl.refreshPackages();
            ctl.refreshRam();
        }
    }

    public void hideKeyboard() {
        View v = getCurrentFocus();
        if (v != null) {
            InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            im.hideSoftInputFromWindow(v.getWindowToken(), 0);
            v.clearFocus();
        }
    }

    public void openFolder(File dir) {
        dir.mkdirs();
        String rel = dir.getAbsolutePath().replace(Environment.getExternalStorageDirectory().getAbsolutePath(), "").replaceAll("^/+", "");
        try {
            Uri u = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A" + Uri.encode(rel));
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "vnd.android.document/directory");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Exception e) {
            toast("Dossier : " + dir.getAbsolutePath());
        }
    }

    // ------------------------------------------------------------------ fichiers (sélecteur Android)
    public interface FilesPicked {
        void picked(List<Uri> uris);
    }

    private FilesPicked pickCb;

    public void pickFiles(String mime, FilesPicked cb) {
        pickCb = cb;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_FILES);
        } catch (Exception e) {
            toast("Aucun sélecteur de fichiers disponible.");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_FILES || res != RESULT_OK || data == null || pickCb == null) return;
        List<Uri> l = new ArrayList<Uri>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) l.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) l.add(data.getData());
        FilesPicked cb = pickCb;
        pickCb = null;
        cb.picked(l);
    }

    public String displayName(Uri u) {
        String name = null;
        try {
            Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null) {
                if (c.moveToFirst()) name = c.getString(0);
                c.close();
            }
        } catch (Exception ignored) {
        }
        if (name == null) name = u.getLastPathSegment();
        return name == null ? "fichier" : name.substring(name.lastIndexOf('/') + 1);
    }

    public long uriSize(Uri u) {
        try {
            Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.SIZE}, null, null, null);
            if (c != null) {
                long s = c.moveToFirst() ? c.getLong(0) : -1;
                c.close();
                return s;
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    public File copyUri(Uri u, File dir) throws Exception {
        File dst = new File(dir, displayName(u));
        InputStream in = getContentResolver().openInputStream(u);
        FileOutputStream o = new FileOutputStream(dst);
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        o.close();
        in.close();
        return dst;
    }

    /** APK ouvert / partagé depuis une autre appli : ajouté à la bibliothèque d'installation. */
    private void handleIntent(Intent it) {
        if (it == null) return;
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(it.getAction())) {
            UsbDevice d = it.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            if (d != null) ctl.usbAttached(d);
            return;
        }
        final List<Uri> uris = new ArrayList<Uri>();
        if (Intent.ACTION_VIEW.equals(it.getAction()) && it.getData() != null) uris.add(it.getData());
        else if (Intent.ACTION_SEND.equals(it.getAction())) {
            Uri u = it.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) uris.add(u);
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(it.getAction())) {
            ArrayList<Uri> l = it.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) uris.addAll(l);
        }
        if (uris.isEmpty()) return;
        h.postDelayed(new Runnable() {
            public void run() {
                showTab(0);
                apps.showInstaller();
                apps.installer.addUris(uris);
            }
        }, 600);
    }

    @Override
    public void onBackPressed() {
        if (scan.getVisibility() == View.VISIBLE) {
            scan.setVisibility(View.GONE);
            return;
        }
        if (overlay.isShown2()) return;
        if (curTab == 0 && apps.installerVisible()) {
            apps.showApps();
            return;
        }
        Dlg.confirm(this, "Quitter", "Fermer ADB Pilot ? Les connexions aux appareils seront coupées.", new Dlg.Ok() {
            public void ok() {
                finish();
            }
        });
    }
}

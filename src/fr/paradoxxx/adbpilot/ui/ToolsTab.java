package fr.paradoxxx.adbpilot.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import fr.paradoxxx.adbpilot.Config;
import fr.paradoxxx.adbpilot.Controller;
import fr.paradoxxx.adbpilot.Core;
import fr.paradoxxx.adbpilot.Html;
import fr.paradoxxx.adbpilot.MainActivity;
import fr.paradoxxx.adbpilot.ScrcpyActivity;
import fr.paradoxxx.adbpilot.adb.AdbDevice;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

/** Onglet Outils : fiche appareil, télécommande, scrcpy (tools_tab.py). */
public final class ToolsTab extends Tab {
    private final TextView lblInfo, lblAudio, lblScrcpy;
    private final Widgets.Combo cmbRes;
    private final Widgets.Check cbRecord;
    static final int[] RES = {480, 720, 1080, 1920};

    /** Télécommande dessinée ; chaque touche envoie un keyevent. */
    final class Remote extends FrameLayout {
        static final int W = 210, H = 560;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Remote(Context c) {
            super(c);
            setWillNotDraw(false);
            int cx = W / 2;
            Object[][] keys = {
                    {"power", 46, 46, 42, "red", -1}, {"camera", W - 46, 46, 42, "grey", -2},
                    {"menu", 46, 108, 42, "grey", 82}, {"gear", W - 46, 108, 42, "grey", -3},
                    {"up", cx, 156, 34, "dpad", 19}, {"down", cx, 256, 34, "dpad", 20},
                    {"left", cx - 50, 206, 34, "dpad", 21}, {"right", cx + 50, 206, 34, "dpad", 22},
                    {null, cx, 206, 50, "ok", 23}, {"back", 46, 318, 42, "grey", 4},
                    {"home", cx, 334, 46, "white", 3}, {"recents", W - 46, 318, 42, "grey", 187},
                    {"mute", cx, 430, 38, "grey", 164}, {"plus", 46, 398, 36, "rocker", 24},
                    {"minus", 46, 462, 36, "rocker", 25}, {"up", W - 46, 398, 36, "rocker", 166},
                    {"down", W - 46, 462, 36, "rocker", 167}};
            for (Object[] k : keys) {
                String icon = (String) k[0];
                int x = (Integer) k[1], y = (Integer) k[2], size = (Integer) k[3];
                String st = (String) k[4];
                final int code = (Integer) k[5];
                int bg, press, fg;
                if ("red".equals(st)) {
                    bg = 0xffd83a3a;
                    press = 0xffef5a5a;
                    fg = 0xffffffff;
                } else if ("white".equals(st)) {
                    bg = 0xfff2f3f5;
                    press = 0xffffffff;
                    fg = 0xff454c5c;
                } else if ("dpad".equals(st) || "rocker".equals(st)) {
                    bg = 0;
                    press = 0x1affffff;
                    fg = 0xffe8e8ea;
                } else if ("ok".equals(st)) {
                    bg = 0xff14171d;
                    press = 0xff22262f;
                    fg = 0xffffffff;
                } else {
                    bg = 0xff454c5c;
                    press = 0xff5a6274;
                    fg = 0xffffffff;
                }
                ImageView b = new ImageView(c);
                b.setBackground(Ui.states(Ui.box(bg, 0, size / 2f, 0), Ui.box(Ui.ACCENT, 0, size / 2f, 0), null, null));
                if (icon != null) b.setImageDrawable(Ui.icon(c, icon, fg));
                int pad = dp(size * 0.25f);
                b.setPadding(pad, pad, pad, pad);
                b.setClickable(true);
                b.setOnClickListener(new OnClickListener() {
                    public void onClick(View v) {
                        if (code == -1) powerMenu(v);
                        else if (code == -2) screenshot();
                        else if (code == -3) openSettings();
                        else keyevent(code);
                    }
                });
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(size), dp(size));
                lp.leftMargin = dp(x - size / 2f);
                lp.topMargin = dp(y - size / 2f);
                addView(b, lp);
                if (press == 0) press = 0;
            }
        }

        @Override
        protected void onMeasure(int w, int h) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(dp(W), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(dp(H), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas cv) {
            RectF body = new RectF(dp(6), dp(4), dp(W - 6), dp(H - 4));
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xff2c3240);
            cv.drawRoundRect(body, dp(44), dp(44), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(1.5f));
            p.setColor(0xff4a5163);
            cv.drawRoundRect(body, dp(44), dp(44), p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xff11141a);
            cv.drawCircle(dp(W / 2f), dp(47), dp(3), p);
            float cx = dp(W / 2f), cy = dp(206);
            p.setColor(0xff1b1f27);
            cv.drawCircle(cx, cy, dp(80), p);
            p.setColor(0xff2c3240);
            cv.drawCircle(cx, cy, dp(30), p);
            p.setColor(0xff454c5c);
            for (int x : new int[]{46, W - 46}) cv.drawRoundRect(new RectF(dp(x - 22), dp(372), dp(x + 22), dp(488)), dp(22), dp(22), p);
            p.setColor(0xff8a91a3);
            p.setTextSize(Ui.pt(9) * getResources().getDisplayMetrics().scaledDensity);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
            cv.drawText("ADB Pilot", cx, dp(H - 36), p);
        }
    }

    public ToolsTab(MainActivity m) {
        super(m);
        LinearLayout lay = Ui.row(c);
        lay.setPadding(dp(10), dp(10), dp(10), dp(10));
        lay.setGravity(Gravity.CENTER_VERTICAL);
        ScrollView sv = new ScrollView(c);
        LinearLayout info = Ui.col(c);
        info.setBackground(Ui.box(0, Ui.BORDER, 0, 1));
        info.setPadding(dp(8), dp(8), dp(8), dp(8));
        lblInfo = Ui.text(c, "Aucun appareil", 10, Ui.TEXT);
        lblInfo.setTextIsSelectable(true);
        info.addView(lblInfo);
        sv.addView(info);
        LinearLayout ic = Ui.col(c);
        ic.addView(sv, Ui.lp(dp(220), Ui.WRAP));
        ic.addView(Ui.vstretch(c));
        lay.addView(ic, Ui.lp(dp(220), Ui.MATCH));
        lay.addView(Ui.stretch(c));
        ScrollView rs = new ScrollView(c);
        FrameLayout rf = new FrameLayout(c);
        rf.addView(new Remote(c), new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER));
        rs.addView(rf);
        lay.addView(rs, Ui.lp(dp(210), Ui.MATCH));
        lay.addView(Ui.space(c, 40, 1));

        LinearLayout rcol = Ui.col(c);
        rcol.setGravity(Gravity.CENTER_HORIZONTAL);
        rcol.addView(Ui.vstretch(c));
        lblScrcpy = Ui.html(c, "<font color='#2fd06b'>scrcpy v3.3.4 intégré</font>", 10, Ui.TEXT);
        lblScrcpy.setGravity(Gravity.CENTER);
        rcol.addView(lblScrcpy);
        Ui.Btn bs = Ui.button(c, "Contrôler l'écran\n(scrcpy)", "btn");
        bs.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                launchScrcpy();
            }
        });
        LinearLayout.LayoutParams bl = Ui.lp(dp(180), dp(60));
        bl.topMargin = dp(6);
        rcol.addView(bs, bl);
        cbRecord = new Widgets.Check(c, "Enregistrer la vidéo");
        cbRecord.setChecked(Config.getBool("scrcpy_record", false));
        cbRecord.onChange(new Widgets.OnChange() {
            public void changed(boolean on) {
                Config.setBool("scrcpy_record", on);
            }
        });
        rcol.addView(cbRecord);
        LinearLayout rr = Ui.row(c);
        rr.addView(Ui.text(c, "Résolution : ", 10, Ui.TEXT));
        cmbRes = new Widgets.Combo(c).items("480 px", "720 px", "1080 px", "1920 px");
        int cur = Config.getInt("scrcpy_res", 1080), idx = 2;
        for (int i = 0; i < RES.length; i++) if (RES[i] == cur) idx = i;
        cmbRes.select(idx);
        cmbRes.onSelect(new Widgets.Combo.OnSelect() {
            public void selected(int i, String t) {
                Config.setInt("scrcpy_res", RES[i]);
            }
        });
        rr.addView(cmbRes, Ui.lp(dp(96), dp(34)));
        rcol.addView(rr);
        lblAudio = Ui.text(c, "", 8, Ui.YELLOW);
        lblAudio.setGravity(Gravity.CENTER);
        lblAudio.setVisibility(View.GONE);
        rcol.addView(lblAudio, Ui.lp(dp(200), Ui.WRAP));
        Ui.Btn pair = Ui.button(c, "Appairer (Android 11+)", "btn");
        pair.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Dlg.message(ToolsTab.this.m, "Appairer un appareil (Android 11+)",
                        "Le « débogage sans fil » d'Android 11+ (appairage par code + TLS) n'est pas encore pris en charge "
                                + "dans cette version de test.\n\nUtilise le débogage ADB réseau classique (port 5555) : "
                                + "branche l'appareil en USB OTG puis touche « Passer en Wi-Fi », ou active « ADB par réseau » "
                                + "dans les options développeur de la box.");
            }
        });
        LinearLayout.LayoutParams pl = Ui.lp(dp(180), dp(38));
        pl.topMargin = dp(16);
        rcol.addView(pair, pl);
        rcol.addView(Ui.vstretch(c));
        lay.addView(rcol, Ui.lp(Ui.WRAP, Ui.MATCH));
        lay.addView(Ui.stretch(c));
        view = lay;
    }

    @Override
    public void onDevice(Controller.Dev d) {
        if (d == null || !"device".equals(d.state)) {
            lblAudio.setVisibility(View.GONE);
            lblInfo.setText(d == null ? "Aucun appareil" : d.serial + "\n" + d.state);
        }
    }

    @Override
    public void onInfo(Map<String, String> i, Map<String, Object> sp) {
        int sdk = 0;
        try {
            sdk = Integer.parseInt(String.valueOf(sp.get("sdk")));
        } catch (Exception ignored) {
        }
        if (sdk > 0 && sdk < 30) {
            lblAudio.setText("Pas de son avec scrcpy : il faut Android 11 ou plus (cet appareil est en Android " + sp.get("android") + ")");
            lblAudio.setVisibility(View.VISIBLE);
        } else lblAudio.setVisibility(View.GONE);
        String cpu = sp.get("soc") != null ? String.valueOf(sp.get("soc")) : i.get("board");
        List<String> extra = new ArrayList<String>();
        if (sp.get("cores") != null) extra.add(sp.get("cores") + " cœurs");
        if (sp.get("max_ghz") != null) extra.add(String.format(Locale.ROOT, "%.2f GHz", (Double) sp.get("max_ghz")).replace(".", ","));
        if (!extra.isEmpty()) cpu += "\n" + android.text.TextUtils.join(" · ", extra);
        String andr = sp.get("android") + " (API " + sp.get("sdk") + ")";
        if (Boolean.TRUE.equals(sp.get("fake"))) andr += "\n⚠ build.prop annonce " + i.get("release");
        List<String> mem = new ArrayList<String>();
        if (sp.get("ram_gb") != null)
            mem.add("RAM " + sp.get("ram_gb") + " Go" + (sp.get("ram_fake") != null ? "\n⚠ la ROM annonce " + sp.get("ram_fake") + " Go" : ""));
        if (sp.get("storage_gb") != null) mem.add("Stockage " + sp.get("storage_gb") + " GB");
        String stor = null;
        if (sp.get("storage_total") != null) {
            double tot = (Double) sp.get("storage_total");
            Object u = sp.get("storage_used");
            stor = u != null ? String.format(Locale.ROOT, "%.1f Go utilisés sur %.1f Go\n%d %% occupé", (Double) u, tot,
                    Math.round(100 * (Double) u / tot)).replace(".", ",") : String.format(Locale.ROOT, "%.1f Go", tot).replace(".", ",");
        }
        String screen = null;
        if (sp.get("res") != null) {
            screen = String.valueOf(sp.get("res")).replace("x", " × ");
            if (sp.get("res_over") != null && !sp.get("res_over").equals(sp.get("res")))
                screen += " (forcée : " + String.valueOf(sp.get("res_over")).replace("x", " × ") + ")";
            if (sp.get("dpi") != null) {
                screen += "\nDensité " + sp.get("dpi") + " dpi";
                if (sp.get("dpi_over") != null && !sp.get("dpi_over").equals(sp.get("dpi")))
                    screen += " (forcée : " + sp.get("dpi_over") + ")";
            }
        }
        String brand = i.get("brand") != null && !i.get("brand").isEmpty() ? i.get("brand") : i.get("manufacturer");
        StringBuilder sb = new StringBuilder("<big><b>" + Html.esc(brand) + "</b></big><br><br>");
        String[][] rows = {{"Modèle", i.get("model")}, {"N° de série", i.get("serial")}, {"Processeur", cpu},
                {"Mémoire", android.text.TextUtils.join("\n", mem)}, {"Stockage utilisateur", stor}, {"Écran", screen},
                {"ABI", i.get("abi")}, {"Android", andr}, {"ROM", i.get("build")}};
        for (String[] r : rows) {
            if (r[1] == null || r[1].isEmpty()) continue;
            sb.append("<b>").append(r[0]).append(" :</b><br>").append(Html.esc(r[1]).replace("\n", "<br>")).append("<br>");
        }
        lblInfo.setText(Ui.fromHtml(sb.toString()));
    }

    // ------------------------------------------------------------------ actions
    void keyevent(final int code) {
        if (!need()) return;
        final AdbDevice d = ctl.adb();
        ctl.run(new Runnable() {
            public void run() {
                d.shell("input keyevent " + code, 10);
            }
        });
    }

    static String devName(Controller.Dev d) {
        return (d == null ? "appareil" : d.label()).replaceAll("[^\\w.\\-]+", "_").replaceAll("^_+|_+$", "");
    }

    void screenshot() {
        if (!need()) return;
        final Controller.Dev dev = ctl.current;
        ctl.submit(new Callable<File>() {
            public File call() throws Exception {
                byte[] data = dev.adb.execOut("screencap -p", 30);
                if (data.length < 8 || data[1] != 'P' || data[2] != 'N' || data[3] != 'G') throw new Exception("capture impossible");
                File f = new File(Config.userDir("images"), devName(dev) + "_" + new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.ROOT).format(new Date()) + ".png");
                FileOutputStream o = new FileOutputStream(f);
                o.write(data);
                o.close();
                return f;
            }
        }, new Controller.Done<File>() {
            public void done(File f, Exception e) {
                if (f == null) return;
                ctl.log("[Succès] : Capture enregistrée : " + Html.esc(f.getAbsolutePath()), "ok");
                m.notifyUser("Capture d'écran enregistrée", f.getAbsolutePath());
            }
        });
    }

    void powerMenu(View anchor) {
        if (!need()) return;
        PopupMenu pm = new PopupMenu(c, anchor);
        final String[][] items = {{"Éteindre", "sh:reboot -p"}, {"Redémarrer", ""}, {"Redémarrer en recovery", "recovery"},
                {"Redémarrer en bootloader", "bootloader"}, {"Redémarrer en fastboot (fastbootd)", "fastboot"},
                {"Mettre en veille / réveiller", "key"}};
        for (int i = 0; i < items.length; i++) pm.getMenu().add(0, i, i, items[i][0]);
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem it) {
                final String a = items[it.getItemId()][1];
                if ("key".equals(a)) {
                    keyevent(26);
                    return true;
                }
                final AdbDevice d = ctl.adb();
                ctl.run(new Runnable() {
                    public void run() {
                        if (a.startsWith("sh:")) {
                            ctl.shellLogged(a.substring(3), 15, null);
                        } else {
                            ctl.log(">> adb reboot " + a, "cmd");
                            d.reboot(a);
                        }
                    }
                });
                return true;
            }
        });
        pm.show();
    }

    void openSettings() {
        if (!need()) return;
        final AdbDevice d = ctl.adb();
        ctl.run(new Runnable() {
            public void run() {
                d.shell("am start -a android.settings.SETTINGS", 15);
            }
        });
    }

    public void toWifi() {
        if (!need()) return;
        final Controller.Dev dev = ctl.current;
        if (dev.isNetwork()) {
            m.toast("L'appareil est déjà connecté en Wi-Fi.");
            return;
        }
        String ps = Config.getStr("port", "auto");
        final int port = ps.matches("\\d+") ? Integer.parseInt(ps) : 5555;
        ctl.submit(new Callable<String>() {
            public String call() throws Exception {
                String ip = Core.deviceIp(dev.adb);
                ctl.log(">> adb tcpip " + port, "cmd");
                AdbDevice.Result r = dev.adb.tcpip(port);
                ctl.logRes(r, null);
                Thread.sleep(2500);
                return ip;
            }
        }, new Controller.Done<String>() {
            public void done(String ip, Exception e) {
                if (ip == null) {
                    ctl.log("[Erreur] : IP introuvable", "err");
                    return;
                }
                ctl.connectIp(port == 5555 ? ip : ip + ":" + port, false);
            }
        });
    }

    void launchScrcpy() {
        if (!need()) return;
        Intent i = new Intent(c, ScrcpyActivity.class);
        i.putExtra("max_size", RES[Math.max(0, cmbRes.index())]);
        i.putExtra("record", cbRecord.isChecked());
        c.startActivity(i);
    }
}

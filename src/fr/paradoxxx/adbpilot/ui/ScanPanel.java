package fr.paradoxxx.adbpilot.ui;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import fr.paradoxxx.adbpilot.Config;
import fr.paradoxxx.adbpilot.Controller;
import fr.paradoxxx.adbpilot.Html;
import fr.paradoxxx.adbpilot.NetScan;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

/** Panneau « Détecter » : appareils Android trouvés sur le réseau local. */
public final class ScanPanel extends LinearLayout {
    public interface Connect {
        void connect(String ip, int port);
    }

    private final TextView sub, empty;
    private final Widgets.Bar bar;
    private final LinearLayout table;
    private final Ui.Btn again;
    private volatile boolean running, stop;
    private final Controller ctl;
    private final Connect connect;
    private String nets = "?";
    private List<NetScan.Found> rows = new ArrayList<NetScan.Found>();

    public ScanPanel(Context c, Controller ctl, Connect connect) {
        super(c);
        this.ctl = ctl;
        this.connect = connect;
        setOrientation(VERTICAL);
        setBackground(Ui.box(0xff2f2f33, Ui.ACCENT_LINE, 12, 1.5f));
        setPadding(dp(16), dp(14), dp(16), dp(12));
        setElevation(dp(14));
        setClickable(true);
        setVisibility(GONE);
        LinearLayout top = Ui.row(c);
        top.addView(Ui.bold(Ui.text(c, "Appareils Android sur le réseau", 12, Ui.TEXT)));
        top.addView(Ui.stretch(c));
        again = Ui.button(c, "Relancer", "btn");
        again.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                start();
            }
        });
        top.addView(again, Ui.lp(Ui.WRAP, dp(36)));
        addView(top);
        sub = Ui.text(c, "", 9, Ui.MUTED);
        sub.setPadding(0, dp(6), 0, dp(6));
        addView(sub);
        bar = new Widgets.Bar(c).style(Ui.ACCENT_LINE, Ui.INPUT, Ui.BORDER, false, false);
        addView(bar, Ui.lp(Ui.MATCH, dp(6)));
        LinearLayout hdr = Ui.row(c);
        hdr.setBackgroundColor(Ui.PANEL);
        String[] h = {"Appareil", "IP", "Port", "État", ""};
        int[] w = {0, 120, 62, 240, 112};
        for (int i = 0; i < h.length; i++) {
            TextView t = Ui.text(c, h[i], 8, Ui.MUTED);
            t.setGravity(Gravity.CENTER);
            hdr.addView(t, i == 0 ? Ui.lpw(0, dp(26), 1) : Ui.lp(dp(w[i]), dp(26)));
        }
        LinearLayout.LayoutParams hl = Ui.lp(Ui.MATCH, Ui.WRAP);
        hl.topMargin = dp(8);
        addView(hdr, hl);
        table = Ui.col(c);
        ScrollView sv = new ScrollView(c) {
            @Override
            protected void onMeasure(int wm, int hm) {
                super.onMeasure(wm, MeasureSpec.makeMeasureSpec(dp(46 * 7), MeasureSpec.AT_MOST));
            }
        };
        sv.addView(table);
        addView(sv, Ui.lp(Ui.MATCH, Ui.WRAP));
        empty = Ui.text(c, "", 10, Ui.MUTED);
        empty.setPadding(dp(12), dp(12), dp(12), dp(12));
        empty.setVisibility(GONE);
        addView(empty);
        TextView hint = Ui.text(c, "Nom lu via ADB, mDNS (Cast / Android TV / débogage sans fil), l'historique ou le réseau · touche une ligne = connecter", 8, 0xff8a8a8e);
        hint.setPadding(0, dp(8), 0, 0);
        addView(hint);
    }

    public void toggle() {
        if (getVisibility() == VISIBLE) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        bringToFront();
        if (!running) start();
    }

    public void start() {
        if (running) return;
        running = true;
        stop = false;
        again.setEnabled(false);
        if (!NetScan.bindToLan(getContext())) {
            running = false;
            again.setEnabled(true);
            sub.setText("Pas de réseau Wi-Fi ou Ethernet : connecte la tablette au même réseau que les appareils.");
            fill(new ArrayList<NetScan.Found>(), false);
            empty.setText("Le scan n'est pas lancé sur les données mobiles (il analyserait le réseau de l'opérateur, pas ta maison).");
            empty.setVisibility(VISIBLE);
            return;
        }
        List<String> hist = new ArrayList<String>(Config.getList("ip_history"));
        hist.addAll(Config.getMap("ip_names").keySet());
        final List<String> n = NetScan.scanNetworks(Config.getBool("scan_common_nets", true), Config.getStr("scan_extra_nets", ""), hist);
        StringBuilder sb = new StringBuilder();
        for (String s : n) sb.append(sb.length() > 0 ? ", " : "").append(s).append("0/24");
        nets = sb.length() == 0 ? "?" : sb.toString();
        sub.setText("Analyse de " + nets + "…");
        ctl.log(">> Détection : analyse de " + nets, "cmd");
        bar.setIndeterminate(true);
        bar.setVisibility(VISIBLE);
        fill(new ArrayList<NetScan.Found>(), false);
        String ps = Config.getStr("port", "auto");
        final int port = ps.matches("\\d+") ? Integer.parseInt(ps) : 5555;
        final Map<String, String> names = Config.getMap("ip_names");
        final long t0 = System.currentTimeMillis();
        new Thread(new Runnable() {
            public void run() {
                WifiManager.MulticastLock lock = null;
                try {
                    WifiManager wm = (WifiManager) getContext().getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    lock = wm.createMulticastLock("adbpilot-mdns");
                    lock.acquire();
                } catch (Exception ignored) {
                }
                final List<NetScan.Found> res = NetScan.scan(port, names, new NetScan.Progress() {
                    public void on(final int n, final int total, final List<NetScan.Found> found) {
                        post(new Runnable() {
                            public void run() {
                                bar.setMax(total);
                                bar.setValue(n);
                                sub.setText("Analyse de " + nets + "… " + n + "/" + total);
                                java.util.Collections.sort(found, new java.util.Comparator<NetScan.Found>() {
                                    public int compare(NetScan.Found a, NetScan.Found b) {
                                        return Long.compare(NetScan.ipKey(a.ip), NetScan.ipKey(b.ip));
                                    }
                                });
                                if (running) fill(found, true);
                            }
                        });
                    }

                    public boolean stopped() {
                        return stop;
                    }
                }, n);
                if (lock != null) try {
                    lock.release();
                } catch (Exception ignored) {
                }
                post(new Runnable() {
                    public void run() {
                        running = false;
                        again.setEnabled(true);
                        bar.setIndeterminate(false);
                        bar.setVisibility(GONE);
                        int nadb = 0;
                        for (NetScan.Found f : res) if (f.connectable()) nadb++;
                        sub.setText(nets + " · analysé en " + Math.round((System.currentTimeMillis() - t0) / 1000.0) + " s · "
                                + nadb + " appareil(s) avec ADB, " + res.size() + " au total");
                        fill(res, false);
                        for (NetScan.Found f : res)
                            ctl.log(fr.paradoxxx.adbpilot.Html.esc(f.ip + ":" + (f.port > 0 ? f.port : "-") + "  " + (f.adb.isEmpty() ? "-" : f.adb) + "  " + f.name), "out");
                        ctl.log("Détection terminée : " + nadb + " appareil(s) avec ADB, " + res.size() + " au total", nadb > 0 ? "ok" : "info");
                    }
                });
            }
        }).start();
    }

    private void fill(List<NetScan.Found> found, boolean partial) {
        rows = new ArrayList<NetScan.Found>(found);
        table.removeAllViews();
        Context c = getContext();
        Controller.Dev cur = ctl.current;
        String curIp = cur != null && cur.isNetwork() ? cur.serial.split(":")[0] : null;
        for (int r = 0; r < rows.size(); r++) {
            final NetScan.Found f = rows.get(r);
            String lab;
            int col;
            boolean can = f.connectable();
            if ("open".equals(f.adb)) {
                lab = "ADB ouvert";
                col = Ui.GREEN;
            } else if ("auth".equals(f.adb)) {
                lab = partial ? "Port ADB ouvert…" : "ADB actif · autorisation à accepter sur l'écran";
                col = partial ? Ui.MUTED : 0xff7fa4ff;
            } else if ("tls".equals(f.adb)) {
                lab = "Débogage sans fil (Android 11+)";
                col = Ui.ORANGE;
            } else if ("port".equals(f.adb)) {
                lab = "Port ouvert · pas de réponse ADB";
                col = 0xff77777b;
            } else {
                lab = "ADB fermé";
                col = 0xff77777b;
            }
            LinearLayout row = Ui.row(c);
            row.setBackgroundColor(r % 2 == 0 ? Ui.ROW : Ui.ROW_ALT);
            String name = f.name.isEmpty() ? f.ip : f.name;
            LinkedHashSet<String> extra = new LinkedHashSet<String>();
            for (String d : f.details) if (d != null && !d.isEmpty() && !d.equals(name)) extra.add(d);
            StringBuilder ex = new StringBuilder();
            for (String e : extra) ex.append(ex.length() > 0 ? " · " : "").append(e);
            TextView n = Ui.html(c, "<b>" + Html.esc(name) + "</b><br><small><font color='#a9a9ad'>" + Html.esc(ex.toString()) + "</font></small>", 10, can ? Ui.TEXT : 0xff77777b);
            n.setPadding(dp(6), 0, 0, 0);
            row.addView(n, Ui.lpw(0, dp(46), 1));
            n.setGravity(Gravity.CENTER_VERTICAL);
            TextView ip = Ui.text(c, f.ip, 10, can ? 0xffdcdcdc : 0xff77777b);
            ip.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(ip, Ui.lp(dp(120), dp(46)));
            TextView pt = Ui.text(c, f.port > 0 ? String.valueOf(f.port) : "—", 10, can ? 0xffdcdcdc : 0xff77777b);
            pt.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(pt, Ui.lp(dp(62), dp(46)));
            TextView st = Ui.single(Ui.text(c, (can ? "● " : "○ ") + lab, 10, col));
            st.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(st, Ui.lp(dp(240), dp(46)));
            LinearLayout bw = Ui.row(c);
            bw.setPadding(dp(4), dp(6), dp(8), dp(6));
            if (can) {
                boolean connected = f.ip.equals(curIp);
                Ui.Btn b = Ui.button(c, connected ? "Connecté" : "Connecter", "accent");
                b.setPadding(dp(4), 0, dp(4), 0);
                b.setEnabled(!connected);
                b.setOnClickListener(new OnClickListener() {
                    public void onClick(View v) {
                        pick(f);
                    }
                });
                bw.addView(b, Ui.lp(Ui.MATCH, Ui.MATCH));
            }
            row.addView(bw, Ui.lp(dp(112), dp(46)));
            if (can) {
                row.setClickable(true);
                row.setOnClickListener(new OnClickListener() {
                    public void onClick(View v) {
                        pick(f);
                    }
                });
            }
            table.addView(row, Ui.lp(Ui.MATCH, dp(46)));
        }
        if (rows.isEmpty() && !running) {
            empty.setText("Aucun appareil trouvé. Vérifie que le débogage ADB réseau est activé sur l'appareil et qu'il est sur le même réseau que la tablette.");
            empty.setVisibility(VISIBLE);
        } else empty.setVisibility(GONE);
    }

    private void pick(NetScan.Found f) {
        if (!f.connectable()) return;
        if (!f.name.isEmpty() && !f.name.equals(f.ip)) {
            Map<String, String> names = Config.getMap("ip_names");
            if (!names.containsKey(f.ip)) {
                names.put(f.ip, f.name);
                Config.setMap("ip_names", names);
            }
        }
        setVisibility(GONE);
        connect.connect(f.ip, f.port);
    }
}

package fr.paradoxxx.adbpilot.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import fr.paradoxxx.adbpilot.Config;
import fr.paradoxxx.adbpilot.Controller;
import fr.paradoxxx.adbpilot.Core;
import fr.paradoxxx.adbpilot.Html;
import fr.paradoxxx.adbpilot.MainActivity;
import fr.paradoxxx.adbpilot.Store;
import fr.paradoxxx.adbpilot.adb.AdbDevice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

/** Onglet Store : installation en un clic depuis le catalogue GitHub (store_tab.py). */
public final class StoreTab extends Tab {
    private JSONObject catalog = new JSONObject();
    private final LinearLayout chipRow, grid;
    private final ScrollView scroll;
    private final List<Ui.Btn> chips = new ArrayList<Ui.Btn>();
    private String category = "all", search = "";
    private final Map<String, Card> cards = new LinkedHashMap<String, Card>();
    private boolean iconsStarted;
    private int lastCols = -1;

    final class Card {
        final JSONObject app;
        final LinearLayout v;
        final ImageView icon;
        final TextView state;
        final Widgets.Bar bar;
        final Ui.Btn b;

        Card(final JSONObject app) {
            this.app = app;
            v = Ui.col(c);
            v.setBackground(Ui.box(Ui.ROW, Ui.BORDER, 14, 1));
            v.setPadding(dp(14), dp(12), dp(14), dp(10));
            LinearLayout top = Ui.row(c);
            top.setGravity(Gravity.TOP);
            icon = new ImageView(c);
            top.addView(icon, Ui.lp(dp(56), dp(56)));
            LinearLayout col = Ui.col(c);
            col.setPadding(dp(12), 0, 0, 0);
            col.addView(Ui.single(Ui.bold(Ui.text(c, app.optString("name", "?"), 12, Ui.TEXT))));
            TextView d = Ui.text(c, app.optString("desc", ""), 9, Ui.TEXT);
            d.setMaxLines(3);
            d.setPadding(0, dp(3), 0, 0);
            col.addView(d);
            top.addView(col, Ui.lpw(0, Ui.WRAP, 1));
            v.addView(top, Ui.lpw(Ui.MATCH, 0, 1));
            LinearLayout bot = Ui.row(c);
            LinearLayout prog = Ui.col(c);
            state = Ui.single(Ui.text(c, "", 9, 0xff7fb8ff));
            state.setVisibility(View.GONE);
            prog.addView(state);
            bar = new Widgets.Bar(c).style(Ui.ACCENT_LINE, Ui.INPUT, 0, false, false);
            bar.setVisibility(View.GONE);
            LinearLayout.LayoutParams bl = Ui.lp(Ui.MATCH, dp(4));
            bl.topMargin = dp(3);
            prog.addView(bar, bl);
            bot.addView(prog, Ui.lpw(0, Ui.WRAP, 1));
            b = Ui.button(c, "Installer", "card");
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View vv) {
                    install(Card.this);
                }
            });
            LinearLayout.LayoutParams ll = Ui.lp(Ui.WRAP, dp(34));
            ll.leftMargin = dp(10);
            bot.addView(b, ll);
            v.addView(bot);
            setIcon(null);
        }

        void setIcon(Bitmap bm) {
            icon.setImageBitmap(bm != null ? Ui.rounded(bm, dp(56)) : Ui.letterIcon(app.optString("name", "?"), dp(56)));
        }

        void progress(String t, long val, long max) {
            state.setText(t);
            state.setVisibility(View.VISIBLE);
            bar.setVisibility(View.VISIBLE);
            if (max <= 0) bar.setIndeterminate(true);
            else {
                bar.setMax(max);
                bar.setValue(val);
            }
        }

        void busy(boolean on) {
            b.setEnabled(!on);
            if (!on) {
                bar.setIndeterminate(false);
                bar.setVisibility(View.GONE);
                state.setVisibility(View.GONE);
            }
        }
    }

    public StoreTab(MainActivity m) {
        super(m);
        LinearLayout lay = Ui.col(c);
        lay.setPadding(dp(10), dp(8), dp(10), dp(8));
        view = lay;
        chipRow = Ui.row(c);
        lay.addView(chipRow);
        scroll = new ScrollView(c);
        grid = Ui.col(c);
        grid.setPadding(dp(4), dp(4), dp(4), dp(4));
        scroll.addView(grid);
        LinearLayout.LayoutParams sl = Ui.lpw(Ui.MATCH, 0, 1);
        sl.topMargin = dp(8);
        lay.addView(scroll, sl);
        scroll.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (columns() != lastCols) layoutCards();
            }
        });
        load();
        new Thread(new Runnable() {
            public void run() {
                if (Store.sync()) ctl.ui(new Runnable() {
                    public void run() {
                        load();
                    }
                });
            }
        }).start();
    }

    private void load() {
        catalog = Store.load();
        chipRow.removeAllViews();
        chips.clear();
        List<String[]> cats = new ArrayList<String[]>();
        cats.add(new String[]{"all", "Tout"});
        JSONArray ca = catalog.optJSONArray("categories");
        if (ca != null) for (int i = 0; i < ca.length(); i++) {
            JSONObject o = ca.optJSONObject(i);
            if (o != null) cats.add(new String[]{o.optString("id"), o.optString("name", o.optString("id"))});
        }
        for (final String[] cat : cats) {
            Ui.Btn b = Ui.button(c, cat[1], "chip");
            b.setTag(cat[0]);
            b.setChecked(cat[0].equals(category));
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    category = cat[0];
                    for (Ui.Btn x : chips) x.setChecked(x.getTag().equals(category));
                    layoutCards();
                    scroll.scrollTo(0, 0);
                }
            });
            LinearLayout.LayoutParams lp = Ui.lp(Ui.WRAP, dp(34));
            lp.rightMargin = dp(6);
            chipRow.addView(b, lp);
            chips.add(b);
        }
        cards.clear();
        for (JSONObject a : Store.apps(catalog)) cards.put(a.optString("id"), new Card(a));
        iconsStarted = false;
        layoutCards();
        if (m.isCurrent(this)) startIcons();
    }

    private int columns() {
        int w = scroll.getWidth();
        if (w <= 0) w = c.getResources().getDisplayMetrics().widthPixels - dp(40);
        return Math.max(1, (w - dp(8)) / dp(332));
    }

    private boolean match(JSONObject a) {
        return search.isEmpty() || a.optString("name").toLowerCase(Locale.ROOT).contains(search)
                || a.optString("desc").toLowerCase(Locale.ROOT).contains(search);
    }

    private void layoutCards() {
        for (Card cd : cards.values()) if (cd.v.getParent() != null) ((LinearLayout) cd.v.getParent()).removeView(cd.v);
        grid.removeAllViews();
        int cols = lastCols = columns();
        List<String[]> cats = new ArrayList<String[]>();
        JSONArray ca = catalog.optJSONArray("categories");
        Map<String, Boolean> known = new HashMap<String, Boolean>();
        if (ca != null) for (int i = 0; i < ca.length(); i++) {
            JSONObject o = ca.optJSONObject(i);
            if (o == null) continue;
            cats.add(new String[]{o.optString("id"), o.optString("name", o.optString("id"))});
            known.put(o.optString("id"), true);
        }
        cats.add(new String[]{"_other", "Autres"});
        int shown = 0;
        for (String[] cat : cats) {
            if (!"all".equals(category) && !category.equals(cat[0])) continue;
            List<Card> l = new ArrayList<Card>();
            for (Card cd : cards.values()) {
                String ac = cd.app.optString("category");
                boolean in = "_other".equals(cat[0]) ? !known.containsKey(ac) : cat[0].equals(ac);
                if (in && match(cd.app)) l.add(cd);
            }
            if (l.isEmpty()) continue;
            TextView t = Ui.bold(Ui.text(c, cat[1], 13, Ui.TEXT));
            t.setPadding(dp(2), dp(10), dp(2), dp(6));
            grid.addView(t);
            LinearLayout row = null;
            for (int i = 0; i < l.size(); i++) {
                if (i % cols == 0) {
                    row = Ui.row(c);
                    row.setGravity(Gravity.TOP);
                    grid.addView(row);
                }
                LinearLayout.LayoutParams lp = Ui.lp(dp(320), dp(134));
                lp.rightMargin = dp(12);
                lp.bottomMargin = dp(12);
                row.addView(l.get(i).v, lp);
            }
            shown += l.size();
        }
        if (shown == 0) {
            TextView e = Ui.text(c, "Aucune application ne correspond.", 10, Ui.MUTED);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, dp(30), 0, 0);
            grid.addView(e, Ui.lp(Ui.MATCH, Ui.WRAP));
        }
    }

    @Override
    public void setSearch(String s) {
        search = s.trim().toLowerCase(Locale.ROOT);
        layoutCards();
    }

    @Override
    public void onShown() {
        startIcons();
    }

    /** Icônes : champ « icon » du catalogue, sinon icône lue dans l'APK déjà téléchargé. */
    private void startIcons() {
        if (iconsStarted) return;
        iconsStarted = true;
        final List<Card> l = new ArrayList<Card>(cards.values());
        new Thread(new Runnable() {
            public void run() {
                for (final Card cd : l) {
                    Bitmap bm = null;
                    String url = cd.app.optString("icon", "");
                    File f = new File(Config.cacheDir("store"), "icon_" + cd.app.optString("id") + ".png");
                    try {
                        if (!url.isEmpty()) {
                            if (!f.isFile()) {
                                java.net.HttpURLConnection h = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                                h.setConnectTimeout(15000);
                                byte[] b = Core.readAll(h.getInputStream());
                                java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                                o.write(b);
                                o.close();
                            }
                            bm = BitmapFactory.decodeFile(f.getPath());
                        }
                    } catch (Exception ignored) {
                    }
                    if (bm == null) {
                        File[] dl = Config.userDir("store").listFiles();
                        if (dl != null) for (File x : dl) {
                            if (x.getName().startsWith(cd.app.optString("id") + "__") && x.getName().endsWith(".apk")) {
                                Object[] r = Store.apkInfo(x, dp(56));
                                if (r != null) bm = (Bitmap) r[3];
                                break;
                            }
                        }
                    }
                    if (bm != null) {
                        final Bitmap fb = bm;
                        ctl.ui(new Runnable() {
                            public void run() {
                                cd.setIcon(fb);
                            }
                        });
                    }
                }
            }
        }).start();
    }

    private String abi() {
        return ctl.lastInfo == null ? "" : String.valueOf(ctl.lastInfo.get("abi"));
    }

    private void install(final Card cd) {
        final JSONObject app = cd.app;
        JSONObject src = app.optJSONObject("source");
        if (src != null && src.has("page")) {
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(src.optString("page"))));
            } catch (Exception ignored) {
            }
            m.toast("Ce site demande un navigateur : télécharge l'APK puis partage-le vers ADB Pilot pour l'installer.", 8000);
            return;
        }
        if (!need()) return;
        if (app.optBoolean("unofficial")) {
            Dlg.confirm(m, "Source non officielle", app.optString("name") + " provient d'un site tiers (" + Store.hostOf(app)
                    + "). Installer quand même ?", new Dlg.Ok() {
                public void ok() {
                    doInstall(cd);
                }
            });
            return;
        }
        doInstall(cd);
    }

    private void doInstall(final Card cd) {
        final JSONObject app = cd.app;
        cd.busy(true);
        cd.progress("Recherche du fichier…", 0, 0);
        final String abi = abi(), name = app.optString("name");
        final AdbDevice d = ctl.adb();
        ctl.submit(new Callable<AdbDevice.Result>() {
            public AdbDevice.Result call() throws Exception {
                String[] res = Store.resolve(app, abi);
                ctl.log(">> Store : " + Html.esc(name) + " ← " + Html.esc(res[0]), "cmd");
                File path = Store.download(res[0], res[1], app.optString("id"), new Store.Prog() {
                    long last;

                    public void on(final long n, final long t) {
                        long now = System.currentTimeMillis();
                        if (now - last < 250) return;
                        last = now;
                        ctl.ui(new Runnable() {
                            public void run() {
                                cd.progress(t > 0 ? "Téléchargement… " + (n * 100 / t) + " %"
                                        : String.format(Locale.ROOT, "Téléchargement… %.1f Mo", n / 1048576.0), n, t);
                            }
                        });
                    }

                    public boolean cancelled() {
                        return false;
                    }
                });
                ctl.ui(new Runnable() {
                    public void run() {
                        cd.progress("Installation sur l'appareil…", 0, 0);
                    }
                });
                return Core.installFile(d, path, null, new Core.Log() {
                    public void log(String s) {
                        ctl.log(Html.esc(s), "info");
                    }
                });
            }
        }, new Controller.Done<AdbDevice.Result>() {
            public void done(AdbDevice.Result r, Exception e) {
                cd.busy(false);
                if (e != null || r == null) {
                    String msg = e == null ? "échec" : String.valueOf(e.getMessage());
                    ctl.log("[Erreur] : " + Html.esc(name) + " — " + Html.esc(msg), "err");
                    m.toast(name + " : échec — " + msg, 9000);
                    return;
                }
                if (r.ok() && !r.out.contains("Failure")) {
                    ctl.log("[Succès] : " + Html.esc(name) + " installé", "ok");
                    m.toast(name + " installé", 4000);
                    ctl.refreshPackages();
                    iconsStarted = false;
                    startIcons();
                } else {
                    ctl.log("[Erreur] : " + Html.esc(name) + " — " + Html.esc(r.text()), "err");
                    m.toast(name + " : échec de l'installation — détails dans la Console", 8000);
                }
            }
        });
    }

    @SuppressWarnings("unused")
    private FrameLayout unusedFrame;
}

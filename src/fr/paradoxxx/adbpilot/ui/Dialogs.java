package fr.paradoxxx.adbpilot.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import fr.paradoxxx.adbpilot.Controller;
import fr.paradoxxx.adbpilot.Core;
import fr.paradoxxx.adbpilot.Debloat;
import fr.paradoxxx.adbpilot.Html;
import fr.paradoxxx.adbpilot.MainActivity;
import fr.paradoxxx.adbpilot.Pkg;
import fr.paradoxxx.adbpilot.adb.AdbDevice;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

/** Infos appli, permissions, assistant debloat (dialogs.py). */
public final class Dialogs {

    // ------------------------------------------------------------------ infos
    public static void appInfo(final MainActivity m, final Pkg p) {
        Context c = m;
        LinearLayout body = Ui.col(c);
        LinearLayout top = Ui.row(c);
        ImageView ic = new ImageView(c);
        if (p.icon != null) ic.setImageBitmap(p.icon);
        else ic.setImageDrawable(Ui.placeholder(c));
        top.addView(ic, Ui.lp(dp(56), dp(56)));
        TextView t = Ui.html(c, "<b><big>" + Html.esc(p.display()) + "</big></b><br>" + Html.esc(p.name), 10, Ui.TEXT);
        t.setTextIsSelectable(true);
        t.setPadding(dp(12), 0, 0, 0);
        top.addView(t, Ui.lpw(0, Ui.WRAP, 1));
        body.addView(top);
        final LinearLayout form = Ui.col(c);
        form.setPadding(0, dp(10), 0, 0);
        form.addView(Ui.text(c, "Lecture…", 10, Ui.MUTED));
        LinearLayout inner = Ui.col(c);
        inner.addView(form);
        JSONObject d = Debloat.info(p.name);
        if (d != null) {
            TextView dl = Ui.html(c, "<b>Debloat : " + Debloat.removalFr(d.optString("removal")) + "</b> ("
                    + Html.esc(d.optString("list")) + ")<br>" + Html.esc(d.optString("description")).replace("\n", "<br>"), 9, Ui.TEXT);
            dl.setPadding(0, dp(10), 0, 0);
            inner.addView(dl);
        }
        body.addView(Dlg.scroll(inner, 380));
        Dialog dlg = Dlg.make(c, p.display(), body, 560);
        LinearLayout b = Dlg.buttons(c);
        Dlg.addButton(b, "Fermer", "btn", dlg, null);
        body.addView(b);
        dlg.show();
        final AdbDevice dev = m.ctl.adb();
        if (dev == null) return;
        m.ctl.submit(new Callable<Map<String, String>>() {
            public Map<String, String> call() {
                return Core.parseAppInfo(Core.dumpsysPackage(dev, p.name), p.name);
            }
        }, new Controller.Done<Map<String, String>>() {
            public void done(Map<String, String> info, Exception e) {
                form.removeAllViews();
                if (info == null) return;
                String[][] rows = {
                        {"Version", (empty(info.get("versionName")) ? (p.version == null ? "?" : p.version) : info.get("versionName")) + " (" + nz(info.get("versionCode")) + ")"},
                        {"Type", p.system ? "Système" : "Utilisateur"},
                        {"État", p.deleted ? "Supprimée (utilisateur 0)" : (p.disabled ? "Désactivée" : "Active")},
                        {"SDK cible / min", nz(info.get("targetSdk")) + " / " + nz(info.get("minSdk"))},
                        {"ABI", info.get("primaryCpuAbi")}, {"Installée le", info.get("firstInstallTime")},
                        {"Mise à jour le", info.get("lastUpdateTime")}, {"Installée par", info.get("installer")},
                        {"UID", info.get("uid")}, {"APK", p.path}, {"Données", info.get("dataDir")}};
                for (String[] r : rows) {
                    LinearLayout row = Ui.row(m);
                    row.setGravity(Gravity.TOP);
                    row.addView(Ui.text(m, r[0] + " :", 10, Ui.MUTED), Ui.lp(dp(Ui.phone ? 100 : 140), Ui.WRAP));
                    TextView v = Ui.text(m, empty(r[1]) ? "–" : r[1], 10, Ui.TEXT);
                    v.setTextIsSelectable(true);
                    row.addView(v, Ui.lpw(0, Ui.WRAP, 1));
                    row.setPadding(0, dp(3), 0, dp(3));
                    form.addView(row);
                }
            }
        });
    }

    static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    static String nz(String s) {
        return empty(s) ? "?" : s;
    }

    // ------------------------------------------------------------------ permissions
    public static void permissions(final MainActivity m, final Pkg p) {
        final Context c = m;
        LinearLayout body = Ui.col(c);
        body.addView(Ui.text(c, p.name, 8, Ui.MUTED));
        final EditText search = Ui.edit(c, "Rechercher…");
        LinearLayout.LayoutParams sl = Ui.lp(Ui.MATCH, dp(38));
        sl.topMargin = dp(6);
        body.addView(search, sl);
        final LinearLayout lst = Ui.col(c);
        lst.setBackground(Ui.box(Ui.INPUT, Ui.BORDER, 0, 1));
        lst.setPadding(dp(6), dp(4), dp(6), dp(4));
        lst.addView(Ui.text(c, "Chargement…", 10, Ui.MUTED));
        LinearLayout.LayoutParams ll = Ui.lp(Ui.MATCH, Ui.WRAP);
        ll.topMargin = dp(6);
        body.addView(Dlg.scroll(lst, 360), ll);
        final Map<String, Boolean> initial = new HashMap<String, Boolean>();
        final Map<String, Widgets.Check> boxes = new HashMap<String, Widgets.Check>();
        final Dialog dlg = Dlg.make(c, "Permissions", body, 520);
        Ui.Btn apply = Ui.button(c, "Appliquer les permissions", "accent");
        LinearLayout.LayoutParams al = Ui.lp(Ui.MATCH, dp(40));
        al.topMargin = dp(8);
        body.addView(apply, al);
        TextView n = Ui.text(c, "Coche/décoche pour accorder/retirer. En rouge : permissions d'exécution (modifiables). "
                + "Les autres ne sont en général pas modifiables via adb, sauf permissions de développement (ex. WRITE_SECURE_SETTINGS).", 8, Ui.TEXT);
        n.setPadding(0, dp(6), 0, 0);
        body.addView(n);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int cc) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int cc) {
            }

            public void afterTextChanged(Editable e) {
                String t = e.toString().toLowerCase();
                for (Map.Entry<String, Widgets.Check> x : boxes.entrySet())
                    x.getValue().setVisibility(t.isEmpty() || x.getKey().toLowerCase().contains(t) ? View.VISIBLE : View.GONE);
            }
        });
        apply.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final List<Object[]> changes = new ArrayList<Object[]>();
                for (Map.Entry<String, Widgets.Check> x : boxes.entrySet()) {
                    boolean on = x.getValue().isChecked();
                    if (on != Boolean.TRUE.equals(initial.get(x.getKey()))) changes.add(new Object[]{x.getKey(), on});
                }
                dlg.dismiss();
                if (changes.isEmpty()) return;
                final AdbDevice d = m.ctl.adb();
                if (d == null) return;
                m.ctl.run(new Runnable() {
                    public void run() {
                        for (Object[] ch : changes) {
                            String perm = (String) ch[0];
                            boolean on = (Boolean) ch[1];
                            m.ctl.log(">> pm " + (on ? "grant" : "revoke") + " " + Html.esc(p.name) + " " + Html.esc(perm), "cmd");
                            AdbDevice.Result r = Core.grant(d, p.name, perm, on);
                            if (r.ok() && r.text().isEmpty())
                                m.ctl.log("[Succès] : " + Html.esc(perm) + " " + (on ? "accordée" : "retirée"), "ok");
                            else m.ctl.log("[Erreur] : " + Html.esc(r.text().isEmpty() ? "échec" : r.text().split("\n")[0]), "err");
                        }
                    }
                });
            }
        });
        dlg.show();
        final AdbDevice d = m.ctl.adb();
        if (d == null) return;
        m.ctl.submit(new Callable<List<Object[]>>() {
            public List<Object[]> call() {
                return Core.parsePermissions(Core.dumpsysPackage(d, p.name), p.name);
            }
        }, new Controller.Done<List<Object[]>>() {
            public void done(List<Object[]> perms, Exception e) {
                lst.removeAllViews();
                if (perms == null) return;
                Collections.sort(perms, new Comparator<Object[]>() {
                    public int compare(Object[] a, Object[] b) {
                        boolean ra = (Boolean) a[2], rb = (Boolean) b[2];
                        if (ra != rb) return ra ? -1 : 1;
                        return ((String) a[0]).compareTo((String) b[0]);
                    }
                });
                for (Object[] x : perms) {
                    String perm = (String) x[0];
                    Widgets.Check ch = new Widgets.Check(c, perm.replace("android.permission.", ""));
                    ch.setChecked((Boolean) x[1]);
                    if ((Boolean) x[2]) ch.label.setTextColor(0xffff4d4d);
                    lst.addView(ch);
                    boxes.put(perm, ch);
                    initial.put(perm, (Boolean) x[1]);
                }
                if (perms.isEmpty()) lst.addView(Ui.text(c, "Aucune permission demandée.", 10, Ui.MUTED));
            }
        });
    }

    // ------------------------------------------------------------------ assistant debloat
    public interface Level {
        void level(int level);
    }

    /** Sélecteur vertical à 4 crans (QSlider vertical). */
    static final class VSlider extends View {
        int value = 1;
        Runnable onChange;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        VSlider(Context c) {
            super(c);
        }

        float yOf(int v) {
            float top = dp(14), bot = getHeight() - dp(14);
            return bot - (bot - top) * v / 3f;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float x = getWidth() / 2f;
            p.setColor(0xff8a8a90);
            p.setStrokeWidth(dp(4));
            p.setStrokeCap(Paint.Cap.ROUND);
            cv.drawLine(x, yOf(3), x, yOf(0), p);
            p.setStrokeWidth(dp(1));
            for (int i = 0; i < 4; i++) cv.drawLine(x + dp(10), yOf(i), x + dp(18), yOf(i), p);
            p.setColor(Ui.ACCENT_LINE);
            p.setStrokeWidth(dp(4));
            cv.drawLine(x, yOf(value), x, yOf(0), p);
            p.setColor(0xffffffff);
            cv.drawCircle(x, yOf(value), dp(9), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setColor(Ui.ACCENT_LINE);
            cv.drawCircle(x, yOf(value), dp(9), p);
            p.setStyle(Paint.Style.FILL);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float y = e.getY();
            int best = 0;
            float bd = Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                float d = Math.abs(yOf(i) - y);
                if (d < bd) {
                    bd = d;
                    best = i;
                }
            }
            if (best != value) {
                value = best;
                invalidate();
                if (onChange != null) onChange.run();
            }
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
    }

    public static void debloatWizard(final MainActivity m, final Set<String> devicePkgs, final boolean isTv, final Level cb) {
        final Context c = m;
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout root = Ui.phone ? Ui.col(c) : Ui.row(c);
        root.setGravity(Gravity.TOP);
        LinearLayout side = Ui.col(c);
        side.setBackgroundColor(0xff1e3a7b);
        side.setPadding(dp(10), dp(10), dp(10), dp(8));
        side.addView(Ui.text(c, "Niveau de nettoyage :", 10, 0xffffffff));
        LinearLayout box = Ui.row(c);
        box.setBackground(Ui.box(0, 0xffd02020, 0, 2));
        box.setPadding(dp(6), dp(6), dp(6), dp(6));
        final VSlider slider = new VSlider(c);
        box.addView(slider, Ui.lp(dp(44), Ui.MATCH));
        LinearLayout labels = Ui.col(c);
        for (int i = 3; i >= 0; i--) {
            final int lv = i;
            TextView lb = Ui.bold(Ui.text(c, Debloat.LEVELS[i][0], 11, android.graphics.Color.parseColor(Debloat.LEVELS[i][1])));
            lb.setPadding(dp(4), dp(4), dp(4), dp(4));
            lb.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    slider.value = lv;
                    slider.invalidate();
                    slider.onChange.run();
                }
            });
            labels.addView(lb);
            if (i > 0) labels.addView(Ui.vstretch(c));
        }
        box.addView(labels, Ui.lpw(0, Ui.MATCH, 1));
        LinearLayout.LayoutParams bl = Ui.lpw(Ui.MATCH, 0, 1);
        bl.topMargin = dp(6);
        bl.bottomMargin = dp(6);
        side.addView(box, bl);
        TextView v = Ui.text(c, "Listes ADB Pilot (debloat0-3)", 7, 0xffc8d4f0);
        v.setGravity(Gravity.CENTER);
        side.addView(v, Ui.lp(Ui.MATCH, Ui.WRAP));
        root.addView(side, Ui.phone ? Ui.lp(Ui.MATCH, dp(196)) : Ui.lp(dp(200), Ui.MATCH));

        LinearLayout main = Ui.col(c);
        main.setBackgroundColor(0xffffffff);
        main.setPadding(dp(16), dp(14), dp(16), dp(16));
        main.addView(Ui.bold(Ui.text(c, "Assistant debloat", 12, 0xff111111)));
        TextView intro = Ui.text(c, "Selon le niveau choisi, la liste des applications jugées inutiles est cochée dans l'onglet "
                + "Applications. Relis et ajuste la liste avant de toucher Appliquer : rien n'est désactivé sans ta validation.", 9, 0xff111111);
        intro.setTypeface(Table.italic());
        intro.setPadding(0, dp(6), 0, dp(8));
        main.addView(intro);
        final TextView desc = Ui.html(c, "", 10, 0xff111111);
        desc.setGravity(Gravity.CENTER);
        desc.setMinHeight(dp(86));
        desc.setBackground(Ui.box(0, 0xff555555, 0, 1));
        desc.setPadding(dp(10), dp(10), dp(10), dp(10));
        main.addView(desc, Ui.lp(Ui.MATCH, Ui.WRAP));
        final TextView src = Ui.html(c, "", 8, 0xff333333);
        src.setPadding(0, dp(6), 0, 0);
        main.addView(src);
        main.addView(Ui.vstretch(c));
        Ui.Btn ok = Ui.button(c, "APPLIQUER", "soft");
        ok.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        LinearLayout okr = Ui.row(c);
        okr.setGravity(Gravity.CENTER);
        okr.addView(ok, Ui.lp(dp(160), dp(40)));
        main.addView(okr, Ui.lp(Ui.MATCH, Ui.WRAP));
        if (Ui.phone) {
            android.widget.ScrollView ms = new android.widget.ScrollView(c);
            ms.setBackgroundColor(0xffffffff);
            ms.addView(main);
            root.addView(ms, Ui.lpw(Ui.MATCH, 0, 1));
        } else root.addView(main, Ui.lpw(0, Ui.MATCH, 1));

        slider.onChange = new Runnable() {
            public void run() {
                int lv = slider.value;
                desc.setText(Ui.fromHtml("<b>" + Debloat.LEVELS[lv][0] + "</b> : " + Html.esc(Debloat.LEVELS[lv][2])));
                Set<String> pk = Debloat.levelPackages(lv);
                int nDev = 0;
                for (String s : pk) if (devicePkgs.contains(s) && Debloat.matchesLevel(s, lv, isTv)) nDev++;
                StringBuilder files = new StringBuilder();
                for (int i = 0; i <= lv; i++) files.append(i > 0 ? " + " : "").append("debloat").append(i).append(".db");
                String txt = "Listes : " + files + " — " + pk.size() + " paquets, <b>" + nDev + " présent(s) et actif(s) sur cet appareil</b>";
                if (!isTv) txt += "<br><b><font color='#c0392b'>Téléphone ou tablette détecté : les composants vitaux (contacts, téléphonie, horloge, clavier, Play Store…) ne seront jamais cochés.</font></b>";
                src.setText(Ui.fromHtml(txt));
            }
        };
        slider.onChange.run();
        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View vv) {
                d.dismiss();
                cb.level(slider.value);
            }
        });
        int dw = Ui.dlgW(600), dh = Ui.phone ? Math.min(dp(640), Ui.screenH() - dp(56)) : Math.min(dp(400), Ui.screenH() - dp(24));
        d.setContentView(root, new android.view.ViewGroup.LayoutParams(dw, dh));
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            android.view.WindowManager.LayoutParams lp = d.getWindow().getAttributes();
            lp.width = dw;
            lp.height = dh;
            d.getWindow().setAttributes(lp);
        }
        d.show();
    }

    @SuppressWarnings("unused")
    private static List<String> keys(Map<String, ?> m) {
        return new ArrayList<String>(m.keySet());
    }

    // ------------------------------------------------------------------ à quoi sert l'appli
    /** Avertissement « ADB Pilot pilote un AUTRE appareil » (premier lancement + onglet À propos). */
    public static String targetNoticeHtml() {
        String me = Ui.phone ? "ce téléphone" : "cette tablette";
        return "<b>ADB Pilot ne sert pas à nettoyer " + me + ".</b><br><br>"
                + "Comme la version PC, il pilote <b>un autre appareil</b> auquel il se connecte (box, TV, téléphone…), "
                + "en Wi-Fi ou par câble OTG : " + me + " joue le rôle du PC, c'est la « télécommande », pas la cible.<br><br>"
                + "C'est l'inverse de l'application Android d'ADB AppControl, qui gère l'appareil sur lequel elle est installée. "
                + "Pour débloater " + me + ", utilise ADB Pilot depuis un PC ou un autre appareil Android.";
    }

    /** Affiché une seule fois, au premier lancement. */
    public static void targetNoticeOnce(final MainActivity m) {
        if (fr.paradoxxx.adbpilot.Config.getBool("notice_target_seen", false)) return;
        TextView t = Ui.html(m, targetNoticeHtml(), 10, Ui.TEXT);
        LinearLayout body = Ui.col(m);
        body.addView(Dlg.scroll(t, 420), Ui.lp(Ui.MATCH, Ui.WRAP));
        LinearLayout br = Dlg.buttons(m);
        body.addView(br, Ui.lp(Ui.MATCH, Ui.WRAP));
        final android.app.Dialog d = Dlg.make(m, "⚠  À savoir avant de commencer", body, 520);
        Dlg.addButton(br, "J'ai compris", "accent", d, new Dlg.Ok() {
            public void ok() {
                fr.paradoxxx.adbpilot.Config.setBool("notice_target_seen", true);
            }
        });
        d.setCancelable(false);
        d.show();
    }
}

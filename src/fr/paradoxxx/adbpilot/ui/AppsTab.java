package fr.paradoxxx.adbpilot.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import fr.paradoxxx.adbpilot.Config;
import fr.paradoxxx.adbpilot.Controller;
import fr.paradoxxx.adbpilot.Core;
import fr.paradoxxx.adbpilot.Debloat;
import fr.paradoxxx.adbpilot.Html;
import fr.paradoxxx.adbpilot.MainActivity;
import fr.paradoxxx.adbpilot.Pkg;
import fr.paradoxxx.adbpilot.adb.AdbDevice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

/** Onglet Applications (apps_tab.py). */
public final class AppsTab extends Tab {
    static final int C_CHECK = 0, C_NAME = 1, C_PKG = 2, C_VER = 3, C_SIZE = 4;
    static final String BACKUP = "Sauvegarder le fichier d'installation";
    static final String[] ACT_NAMES = {"Désactiver", "Activer", "Désinstaller", "Restaurer (réinstaller)", "Forcer l'arrêt",
            "Effacer les données", "Effacer le cache", BACKUP};
    static final int[] ACT_CODES = {Core.ACT_DISABLE, Core.ACT_ENABLE, Core.ACT_UNINSTALL, Core.ACT_RESTORE, Core.ACT_FORCE_STOP,
            Core.ACT_CLEAR, Core.ACT_CLEAR_CACHE, Core.ACT_BACKUP};
    static final boolean[] ACT_CONFIRM = {true, false, true, false, false, true, false, false};
    static final String[] COMBO = {"Désactiver", "Désinstaller", "Effacer les données", "Effacer le cache", BACKUP};

    private final FrameLayout stack;
    private final LinearLayout page;
    public final InstallerPage installer;
    private List<Pkg> pkgs = new ArrayList<Pkg>();
    private final List<Pkg> visible = new ArrayList<Pkg>();
    final Set<String> checked = new HashSet<String>();
    private final Set<String> selected = new HashSet<String>();
    private String mode = "all", text = "";
    private final Table.Cols cols;
    private final Table.Header header;
    private final Table.List list;
    private final Adapter adapter = new Adapter();
    private final Widgets.Check cbAll, cbWipe;
    private final TextView lblTotal, lblSel, lblRam;
    private final Widgets.Bar barRam, metaBar;
    private final Widgets.RadioGroup filters = new Widgets.RadioGroup();
    private final Widgets.Combo cmbAction;
    private String serial;
    private long lastTap;
    private String lastTapName;

    public AppsTab(MainActivity m) {
        super(m);
        final boolean ph = Ui.phone;
        stack = new FrameLayout(c);
        view = stack;
        page = ph ? Ui.col(c) : Ui.row(c);
        page.setGravity(Gravity.TOP);
        stack.addView(page, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        installer = new InstallerPage(m, this);
        installer.view.setVisibility(View.GONE);
        stack.addView(installer.view, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));

        // --- liste
        LinearLayout top = Ui.row(c);
        top.setPadding(dp(6), dp(4), dp(6), 0);
        cbAll = new Widgets.Check(c, ph ? "Tout" : "Tout sélectionner").onChange(new Widgets.OnChange() {
            public void changed(boolean on) {
                selectAll(on);
            }
        });
        top.addView(cbAll);
        LinearLayout tot = Ui.col(c);
        tot.addView(Ui.text(c, "Total :", 7, Ui.MUTED));
        lblTotal = Ui.text(c, "0", 10, Ui.TEXT);
        tot.addView(lblTotal);
        tot.setPadding(dp(ph ? 10 : 16), 0, ph ? dp(6) : 0, 0);
        top.addView(tot);
        if (!ph) top.addView(Ui.stretch(c));
        String[][] f = {{"Toutes", "all"}, {"Système", "system"}, {"Utilisateur", "user"}, {"Désactivées", "disabled"}, {"Supprimées", "deleted"}};
        for (String[] x : f) {
            Widgets.Check rb = filters.add(c, x[0]);
            rb.setPadding(dp(6), dp(4), dp(4), dp(4));
            top.addView(rb);
        }
        filters.select(0, false);
        filters.onPick(new Widgets.RadioGroup.OnPick() {
            public void picked(int i) {
                String[] modes = {"all", "system", "user", "disabled", "deleted"};
                setMode(modes[i]);
            }
        });

        if (ph) {
            cols = new Table.Cols("cols_apps_phone", new String[]{"", "Application", "Taille"}, new int[]{34, 200, 74},
                    new boolean[]{true, false, false}, new int[]{Gravity.CENTER, Gravity.START, Gravity.END}).fill(1);
        } else {
            cols = new Table.Cols("cols_apps", new String[]{"", "Nom de l'application", "Paquet", "Version", "Taille"},
                    new int[]{34, 280, 260, 110, 90}, new boolean[]{true, false, false, false, false},
                    new int[]{Gravity.CENTER, Gravity.START, Gravity.START, Gravity.START, Gravity.END});
        }
        cols.sortCol = 1;  // nom de l'application dans les deux dispositions
        header = new Table.Header(c, cols, new Table.HeaderListener() {
            public void onSort(int col) {
                sortAndShow();
            }

            public void onResized() {
                adapter.notifyDataSetChanged();
            }
        });
        list = ph ? new Table.List(c, 9, 54, 40) : new Table.List(c, 10, 40, 56);
        list.setAdapter(adapter);
        list.onRowSize = new Runnable() {
            public void run() {
                adapter.notifyDataSetChanged();
            }
        };
        metaBar = new Widgets.Bar(c).style(Ui.ACCENT_LINE, Ui.INPUT, 0, false, false);
        metaBar.setVisibility(View.GONE);

        // --- actions
        final Runnable rUpd = new Runnable() {
            public void run() {
                ctl.refreshPackages();
            }
        }, rLoad = new Runnable() {
            public void run() {
                loadPreset();
            }
        }, rSave = new Runnable() {
            public void run() {
                savePreset();
            }
        }, rPerm = new Runnable() {
            public void run() {
                List<Pkg> sel = selectedPkgs();
                if (sel.isEmpty()) sel = targets();
                if (sel.isEmpty()) {
                    AppsTab.this.m.toast("Sélectionne une application (touche sa ligne).");
                    return;
                }
                Dialogs.permissions(AppsTab.this.m, sel.get(0));
            }
        }, rPush = new Runnable() {
            public void run() {
                pushFiles();
            }
        }, rInst = new Runnable() {
            public void run() {
                showInstaller();
            }
        };
        lblRam = Ui.text(c, "– / – Mo", 10, Ui.TEXT);
        barRam = new Widgets.Bar(c).style(Ui.ACCENT_LINE, Ui.INPUT, Ui.BORDER, false, false);
        lblSel = Ui.text(c, ph ? "0 cochée(s)" : "Sélection : 0", 10, Ui.TEXT);
        cbWipe = new Widgets.Check(c, "Effacer aussi les données de l'appli");
        cmbAction = new Widgets.Combo(c).items(COMBO);
        cmbAction.select(0);
        cmbAction.onSelect(new Widgets.Combo.OnSelect() {
            public void selected(int i, String t) {
                boolean on = "Désactiver".equals(t) || "Désinstaller".equals(t);
                cbWipe.setVisibility(on ? View.VISIBLE : (ph ? View.GONE : View.INVISIBLE));
            }
        });
        Ui.Btn apply = btn("Appliquer", new Runnable() {
            public void run() {
                applyAction();
            }
        });

        if (ph) {
            page.addView(Ui.hscroll(c, top), Ui.lp(Ui.MATCH, Ui.WRAP));
            page.addView(header, Ui.lp(Ui.MATCH, Ui.WRAP));
            page.addView(list, Ui.lpw(Ui.MATCH, 0, 1));
            page.addView(metaBar, Ui.lp(Ui.MATCH, dp(4)));
            LinearLayout bot = Ui.col(c);
            bot.setPadding(dp(6), dp(4), dp(6), dp(6));
            bot.setBackgroundColor(Ui.BG);
            LinearLayout ram = Ui.row(c);
            ram.addView(Ui.text(c, "RAM", 9, Ui.MUTED));
            LinearLayout.LayoutParams rl = Ui.lpw(0, dp(8), 1);
            rl.leftMargin = rl.rightMargin = dp(8);
            ram.addView(barRam, rl);
            lblRam.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Ui.pt(9));
            ram.addView(lblRam);
            bot.addView(ram);
            LinearLayout tools = Ui.row(c);
            tools.setPadding(0, dp(5), 0, 0);
            Ui.Btn bi = btn("Installer…", rInst);
            tools.addView(bi, Ui.lpw(0, dp(40), 1));
            Ui.Btn bw = btn("Debloat me =)", new Runnable() {
                public void run() {
                    openWizard();
                }
            });
            bw.icon(Ui.icon(c, "debloat", 0), 22);
            LinearLayout.LayoutParams wl = Ui.lpw(0, dp(40), 1.25f);
            wl.leftMargin = wl.rightMargin = dp(4);
            tools.addView(bw, wl);
            final Ui.Btn more = Ui.button(c, "⋮  Plus", "btn");
            more.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    PopupMenu pm = new PopupMenu(c, more);
                    final String[] t = {"Mettre à jour les données", "Charger un preset", "Enregistrer le preset", "Permissions",
                            "Envoyer des fichiers"};
                    final Runnable[] r = {rUpd, rLoad, rSave, rPerm, rPush};
                    for (int i = 0; i < t.length; i++) pm.getMenu().add(0, i, i, t[i]);
                    pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                        public boolean onMenuItemClick(android.view.MenuItem it) {
                            r[it.getItemId()].run();
                            return true;
                        }
                    });
                    pm.show();
                }
            });
            tools.addView(more, Ui.lpw(0, dp(40), 0.8f));
            bot.addView(tools);
            bot.addView(cbWipe);
            LinearLayout act = Ui.row(c);
            act.addView(lblSel);
            LinearLayout.LayoutParams cl = Ui.lpw(0, dp(40), 1);
            cl.leftMargin = dp(8);
            act.addView(cmbAction, cl);
            LinearLayout.LayoutParams al = Ui.lp(Ui.WRAP, dp(42));
            al.leftMargin = dp(6);
            apply.setPadding(dp(14), 0, dp(14), 0);
            act.addView(apply, al);
            bot.addView(act);
            page.addView(bot, Ui.lp(Ui.MATCH, Ui.WRAP));
            return;
        }

        LinearLayout left = Ui.col(c);
        left.addView(top);
        left.addView(header, Ui.lp(Ui.MATCH, Ui.WRAP));
        left.addView(list, Ui.lpw(Ui.MATCH, 0, 1));
        left.addView(metaBar, Ui.lp(Ui.MATCH, dp(4)));
        page.addView(left, Ui.lpw(0, Ui.MATCH, 1));

        // Panneau droit
        LinearLayout right = Ui.col(c);
        right.setPadding(dp(6), dp(6), dp(6), dp(6));
        right.addView(btn("Mettre à jour les données", rUpd), Ui.lp(Ui.MATCH, dp(42)));
        right.addView(pair(btn("Charger un preset", rLoad), btn("Enregistrer le preset", rSave)));
        right.addView(pair(btn("Permissions", rPerm), btn("Envoyer des fichiers", rPush)));
        right.addView(btn("Installer…", rInst), Ui.lp(Ui.MATCH, dp(42)));
        right.addView(Ui.space(c, 1, 10));
        LinearLayout ram = Ui.row(c);
        ram.addView(Ui.text(c, "RAM", 10, Ui.TEXT));
        ram.addView(Ui.stretch(c));
        ram.addView(lblRam);
        right.addView(ram);
        LinearLayout.LayoutParams rl = Ui.lp(Ui.MATCH, dp(10));
        rl.topMargin = dp(4);
        right.addView(barRam, rl);
        right.addView(Ui.space(c, 1, 18));
        LinearLayout wiz = Ui.row(c);
        wiz.setBackground(Ui.states(Ui.box(0xff2c2c2f, Ui.BORDER, 0, 1), Ui.box(Ui.BTN_HOVER, Ui.BORDER, 0, 1), null, null));
        wiz.setPadding(dp(10), dp(6), dp(10), dp(6));
        wiz.setClickable(true);
        ImageView wi = new ImageView(c);
        wi.setImageBitmap(Ui.iconBitmap(c, "debloat"));
        wiz.addView(wi, Ui.lp(dp(36), dp(36)));
        TextView wt = Ui.html(c, "<b><big>Debloat me =)</big></b><br><i><small>Lister et supprimer les logiciels indésirables</small></i>", 10, Ui.TEXT);
        wt.setPadding(dp(10), 0, 0, 0);
        wiz.addView(wt, Ui.lpw(0, Ui.WRAP, 1));
        wiz.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                openWizard();
            }
        });
        right.addView(wiz, Ui.lp(Ui.MATCH, dp(72)));
        right.addView(Ui.vstretch(c));
        lblSel.setBackground(Ui.box(0, Ui.BORDER, 0, 1));
        lblSel.setPadding(dp(6), dp(5), dp(6), dp(5));
        right.addView(lblSel, Ui.lp(Ui.MATCH, Ui.WRAP));
        right.addView(cbWipe);
        LinearLayout act = Ui.row(c);
        act.addView(cmbAction, Ui.lpw(0, dp(40), 1));
        LinearLayout.LayoutParams al = Ui.lp(dp(96), dp(42));
        al.leftMargin = dp(6);
        act.addView(apply, al);
        right.addView(act);
        page.addView(right, Ui.lp(dp(290), Ui.MATCH));
    }

    /** Colonne affichée -> critère de tri (C_CHECK, C_NAME, C_PKG, C_VER, C_SIZE). */
    private int logical(int col) {
        if (!Ui.phone || col < 0) return col;
        return new int[]{C_CHECK, C_NAME, C_SIZE}[Math.min(col, 2)];
    }

    private Ui.Btn btn(String t, final Runnable r) {
        Ui.Btn b = Ui.button(c, t, "btn");
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                r.run();
            }
        });
        return b;
    }

    private LinearLayout pair(View a, View b) {
        LinearLayout r = Ui.row(c);
        r.addView(a, Ui.lpw(0, dp(42), 1));
        r.addView(b, Ui.lpw(0, dp(42), 1));
        return r;
    }

    // ------------------------------------------------------------------ installateur
    public void showInstaller() {
        installer.refresh();
        page.setVisibility(View.GONE);
        installer.view.setVisibility(View.VISIBLE);
    }

    public void showApps() {
        installer.view.setVisibility(View.GONE);
        page.setVisibility(View.VISIBLE);
    }

    public boolean installerVisible() {
        return installer.view.getVisibility() == View.VISIBLE;
    }

    // ------------------------------------------------------------------ événements
    @Override
    public void onDevice(Controller.Dev d) {
        String s = d == null ? null : d.serial;
        if (s == null ? serial != null : !s.equals(serial)) {
            serial = s;
            checked.clear();
            cmbAction.select(0);
            cbWipe.setChecked(false);
        }
        if (d == null || !"device".equals(d.state)) {
            pkgs = new ArrayList<Pkg>();
            sortAndShow();
            lblRam.setText("– / – Mo");
            barRam.setValue(0);
        }
        installer.onDevice();
    }

    @Override
    public void onPackages(List<Pkg> p) {
        pkgs = p;
        Set<String> names = new HashSet<String>();
        for (Pkg x : p) names.add(x.name);
        checked.retainAll(names);
        selected.retainAll(names);
        sortAndShow();
        installer.fill();
    }

    @Override
    public void onMeta(Pkg p) {
        adapter.notifyDataSetChanged();
    }

    public void onRam(long used, long total) {
        lblRam.setText(used + " / " + total + " Mo");
        barRam.setMax(Math.max(total, 1));
        barRam.setValue(used);
    }

    public void metaProgress(int n, int total) {
        if (n >= total) {
            metaBar.setVisibility(View.GONE);
            sortAndShow();
            m.setStatus("Noms et icônes à jour");
        } else {
            metaBar.setVisibility(View.VISIBLE);
            metaBar.setMax(total);
            metaBar.setValue(n);
            if (n % 10 == 0) m.setStatus("Lecture des noms et icônes… " + n + "/" + total);
        }
    }

    void clearChecks() {
        checked.clear();
        selected.clear();
        updateCounts();
        adapter.notifyDataSetChanged();
    }

    private void setMode(String md) {
        clearChecks();
        mode = md;
        sortAndShow();
    }

    @Override
    public void setSearch(String t) {
        installer.setSearch(t);
        text = t.trim().toLowerCase(Locale.ROOT);
        sortAndShow();
    }

    private boolean accept(Pkg p) {
        boolean ok;
        if ("deleted".equals(mode)) ok = p.deleted;
        else if (p.deleted) ok = false;
        else if ("system".equals(mode)) ok = p.system;
        else if ("user".equals(mode)) ok = !p.system;
        else if ("disabled".equals(mode)) ok = p.disabled;
        else ok = true;
        if (ok && !text.isEmpty())
            ok = p.name.toLowerCase(Locale.ROOT).contains(text) || (p.label != null && p.label.toLowerCase(Locale.ROOT).contains(text));
        return ok;
    }

    static String verKey(String v) {
        String[] parts = (v == null ? "" : v).split("[.\\-_ ]");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            String x = i < parts.length ? parts[i] : "0";
            long n = 0;
            if (x.matches("\\d{1,9}")) n = Long.parseLong(x);
            sb.append(String.format(Locale.ROOT, "%09d.", n));
        }
        return sb.toString();
    }

    void sortAndShow() {
        visible.clear();
        for (Pkg p : pkgs) if (accept(p)) visible.add(p);
        final int col = logical(cols.sortCol);
        final boolean asc = cols.sortAsc;
        Collections.sort(visible, new Comparator<Pkg>() {
            public int compare(Pkg a, Pkg b) {
                int r;
                switch (col) {
                    case C_CHECK:
                        r = (checked.contains(a.name) ? 0 : 1) - (checked.contains(b.name) ? 0 : 1);
                        if (r == 0) r = a.display().compareToIgnoreCase(b.display());
                        break;
                    case C_PKG:
                        r = a.name.compareTo(b.name);
                        break;
                    case C_VER:
                        r = verKey(a.version).compareTo(verKey(b.version));
                        break;
                    case C_SIZE:
                        r = a.size < b.size ? -1 : (a.size == b.size ? 0 : 1);
                        break;
                    default:
                        r = a.display().toLowerCase(Locale.ROOT).compareTo(b.display().toLowerCase(Locale.ROOT));
                }
                return asc ? r : -r;
            }
        });
        adapter.notifyDataSetChanged();
        updateCounts();
    }

    void updateCounts() {
        lblTotal.setText(String.valueOf(visible.size()));
        lblSel.setText(Ui.phone ? checked.size() + " cochée(s)" : "Sélection : " + checked.size());
        boolean all = !visible.isEmpty();
        for (Pkg p : visible) if (!checked.contains(p.name)) {
            all = false;
            break;
        }
        cbAll.setChecked(all);
    }

    private void selectAll(boolean on) {
        for (Pkg p : visible) {
            if (on) checked.add(p.name);
            else checked.remove(p.name);
        }
        adapter.notifyDataSetChanged();
        updateCounts();
    }

    void setChecked(List<String> names, boolean on) {
        Set<String> known = new HashSet<String>();
        for (Pkg p : pkgs) known.add(p.name);
        for (String n : names) {
            if (!known.contains(n)) continue;
            if (on) checked.add(n);
            else checked.remove(n);
        }
        adapter.notifyDataSetChanged();
        updateCounts();
    }

    List<Pkg> selectedPkgs() {
        List<Pkg> out = new ArrayList<Pkg>();
        for (Pkg p : visible) if (selected.contains(p.name)) out.add(p);
        return out;
    }

    List<Pkg> targets() {
        if (!checked.isEmpty()) {
            List<Pkg> out = new ArrayList<Pkg>();
            for (Pkg p : pkgs) if (checked.contains(p.name)) out.add(p);
            return out;
        }
        return selectedPkgs();
    }

    public List<Pkg> allPackages() {
        return pkgs;
    }

    static String fmtSize(long n) {
        if (n <= 0) return "";
        return String.format(Locale.ROOT, "%.2f", n / 1048576.0).replace(".", ",") + " Mo";
    }

    // ------------------------------------------------------------------ liste
    private final class Adapter extends BaseAdapter {
        public int getCount() {
            return visible.size();
        }

        public Object getItem(int i) {
            return visible.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout row = (LinearLayout) cv;
            if (row == null) row = makeRow();
            final Pkg p = visible.get(pos);
            row.setBackgroundColor(Table.rowColor(pos, selected.contains(p.name)));
            cols.apply(row);
            row.setLayoutParams(new android.widget.AbsListView.LayoutParams(Ui.MATCH, list.rowHeight));
            Widgets.Box box = (Widgets.Box) ((FrameLayout) row.getChildAt(0)).getChildAt(0);
            box.set(checked.contains(p.name));
            LinearLayout nc = (LinearLayout) row.getChildAt(1);
            ImageView ic = (ImageView) nc.getChildAt(0);
            LinearLayout.LayoutParams il = (LinearLayout.LayoutParams) ic.getLayoutParams();
            il.width = il.height = list.iconSize;
            ic.setLayoutParams(il);
            if (p.icon != null) ic.setImageBitmap(p.icon);
            else ic.setImageDrawable(Ui.placeholder(c));
            int col = p.deleted ? 0xffff5c5c : (p.disabled ? 0xff8a8a8e : Ui.TEXT);
            int muted = p.deleted ? 0xffff5c5c : (p.disabled ? 0xff8a8a8e : Ui.MUTED);
            if (Ui.phone) {
                // 2 lignes : nom / paquet · version — taille à droite
                LinearLayout tc = (LinearLayout) nc.getChildAt(1);
                TextView n1 = (TextView) tc.getChildAt(0), n2 = (TextView) tc.getChildAt(1);
                n1.setText(p.display());
                n1.setTextColor(col);
                n1.setTypeface(p.disabled || p.deleted ? Table.italic() : Typeface.DEFAULT);
                n2.setText(p.version == null || p.version.isEmpty() ? p.name : p.name + "  ·  " + p.version);
                n2.setTextColor(muted);
                TextView sz = (TextView) row.getChildAt(2);
                sz.setText(fmtSize(p.size));
                sz.setTextColor(muted);
                row.setTag(p);
                return row;
            }
            TextView name = (TextView) nc.getChildAt(1);
            name.setText(p.display());
            name.setTextColor(col);
            name.setTypeface(p.disabled || p.deleted ? Table.italic() : Typeface.DEFAULT);
            TextView pk = (TextView) row.getChildAt(2), ver = (TextView) row.getChildAt(3), sz = (TextView) row.getChildAt(4);
            pk.setText(p.name);
            pk.setTextColor(muted);
            ver.setText(p.version == null ? "" : p.version);
            ver.setTextColor(muted);
            sz.setText(fmtSize(p.size));
            sz.setTextColor(muted);
            row.setTag(p);
            return row;
        }
    }

    private LinearLayout makeRow() {
        final LinearLayout row = Ui.row(c);
        FrameLayout cf = new FrameLayout(c);
        Widgets.Box box = new Widgets.Box(c, false);
        cf.addView(box, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        cf.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Pkg p = (Pkg) row.getTag();
                if (checked.contains(p.name)) checked.remove(p.name);
                else checked.add(p.name);
                adapter.notifyDataSetChanged();
                updateCounts();
            }
        });
        row.addView(cf);
        LinearLayout nc = Ui.row(c);
        nc.setPadding(dp(Ui.phone ? 4 : 8), 0, dp(4), 0);
        ImageView ic = new ImageView(c);
        ic.setScaleType(ImageView.ScaleType.FIT_CENTER);
        nc.addView(ic, Ui.lp(dp(30), dp(30)));
        if (Ui.phone) {
            LinearLayout tc = Ui.col(c);
            tc.setPadding(dp(10), 0, 0, 0);
            tc.setGravity(Gravity.CENTER_VERTICAL);
            tc.addView(Ui.single(Ui.text(c, "", 11, Ui.TEXT)));
            TextView sub = Ui.single(Ui.text(c, "", 8, Ui.MUTED));
            sub.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            sub.setPadding(0, dp(3), 0, 0);
            tc.addView(sub);
            nc.addView(tc, Ui.lpw(0, Ui.WRAP, 1));
            row.addView(nc);
            TextView t = Ui.single(Ui.text(c, "", 9, Ui.MUTED));
            t.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
            t.setPadding(dp(4), 0, dp(8), 0);
            row.addView(t);
        } else {
            TextView name = Ui.single(Ui.text(c, "", 11, Ui.TEXT));
            name.setPadding(dp(10), 0, 0, 0);
            nc.addView(name, Ui.lpw(0, Ui.WRAP, 1));
            row.addView(nc);
            for (int i = 2; i < 5; i++) {
                TextView t = Ui.single(Ui.text(c, "", 10, Ui.MUTED));
                t.setGravity(Gravity.CENTER_VERTICAL | (i == C_SIZE ? Gravity.END : Gravity.START));
                t.setPadding(dp(6), 0, dp(8), 0);
                row.addView(t);
            }
        }
        View.OnClickListener tap = new View.OnClickListener() {
            public void onClick(View v) {
                Pkg p = (Pkg) row.getTag();
                long now = System.currentTimeMillis();
                if (p.name.equals(lastTapName) && now - lastTap < 400) {
                    lastTapName = null;
                    Dialogs.appInfo(m, p);
                    return;
                }
                lastTap = now;
                lastTapName = p.name;
                selected.clear();
                selected.add(p.name);
                adapter.notifyDataSetChanged();
            }
        };
        View.OnLongClickListener longTap = new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                Pkg p = (Pkg) row.getTag();
                if (!selected.contains(p.name)) {
                    selected.clear();
                    selected.add(p.name);
                    adapter.notifyDataSetChanged();
                }
                contextMenu(row, p);
                return true;
            }
        };
        for (int i = 1; i < row.getChildCount(); i++) {
            row.getChildAt(i).setOnClickListener(tap);
            row.getChildAt(i).setOnLongClickListener(longTap);
        }
        return row;
    }

    private void contextMenu(View anchor, final Pkg p) {
        final List<Pkg> sel = selectedPkgs().isEmpty() ? java.util.Arrays.asList(p) : selectedPkgs();
        PopupMenu pm = new PopupMenu(c, anchor, Gravity.START);
        android.view.Menu mn = pm.getMenu();
        mn.add(0, 1, 1, "Infos…");
        mn.add(0, 2, 2, "Lancer").setEnabled(!p.deleted);
        mn.add(0, 3, 3, "Permissions…").setEnabled(!p.deleted);
        mn.add(1, 4, 4, "Cocher");
        mn.add(1, 5, 5, "Décocher");
        for (int i = 0; i < ACT_NAMES.length; i++) mn.add(2, 100 + i, 100 + i, ACT_NAMES[i]);
        mn.add(3, 200, 200, "Copier le nom du paquet");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem it) {
                int id = it.getItemId();
                List<String> names = new ArrayList<String>();
                for (Pkg x : sel) names.add(x.name);
                if (id == 1) Dialogs.appInfo(m, p);
                else if (id == 2) runAction(Core.ACT_LAUNCH, java.util.Arrays.asList(p), "Lancement", false, false);
                else if (id == 3) Dialogs.permissions(m, p);
                else if (id == 4) setChecked(names, true);
                else if (id == 5) setChecked(names, false);
                else if (id >= 100 && id < 100 + ACT_NAMES.length) {
                    int k = id - 100;
                    if (ACT_CODES[k] == Core.ACT_BACKUP) backup(sel);
                    else runAction(ACT_CODES[k], sel, ACT_NAMES[k], ACT_CONFIRM[k], false);
                } else if (id == 200) {
                    ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("paquets", TextUtils.join("\n", names)));
                    m.toast("Copié dans le presse-papiers.", 2000);
                }
                return true;
            }
        });
        pm.show();
    }

    // ------------------------------------------------------------------ actions
    private void applyAction() {
        String name = cmbAction.value();
        List<Pkg> t = targets();
        if (t.isEmpty()) {
            m.toast("Aucune application cochée.");
            return;
        }
        int k = 0;
        for (int i = 0; i < ACT_NAMES.length; i++) if (ACT_NAMES[i].equals(name)) k = i;
        if (ACT_CODES[k] == Core.ACT_BACKUP) {
            backup(t);
            return;
        }
        boolean wipe = cbWipe.isChecked() && ("Désactiver".equals(name) || "Désinstaller".equals(name));
        runAction(ACT_CODES[k], t, name, ACT_CONFIRM[k], wipe);
    }

    private boolean isTv() {
        return ctl.lastSpecs != null && Boolean.TRUE.equals(ctl.lastSpecs.get("is_tv"));
    }

    void runAction(final int act, final List<Pkg> list, final String name, boolean confirm, final boolean wipe) {
        if (!need()) return;
        if (confirm) {
            StringBuilder lst = new StringBuilder();
            for (int i = 0; i < Math.min(15, list.size()); i++)
                lst.append("• ").append(list.get(i).display()).append(" (").append(list.get(i).name).append(")\n");
            if (list.size() > 15) lst.append("… et ").append(list.size() - 15).append(" autres\n");
            StringBuilder extra = new StringBuilder();
            if (wipe) extra.append("\n⚠ Les données de ces applications seront aussi effacées.");
            List<String> vital = new ArrayList<String>();
            for (Pkg p : list) if (Debloat.isProtectedPhone(p.name)) vital.add(p.name);
            if (!vital.isEmpty() && !isTv() && (act == Core.ACT_DISABLE || act == Core.ACT_UNINSTALL || act == Core.ACT_CLEAR)) {
                extra.append("\n\n⚠ ATTENTION : composants vitaux pour un téléphone :\n");
                for (int i = 0; i < Math.min(10, vital.size()); i++) extra.append("• ").append(vital.get(i)).append('\n');
                extra.append("Les désactiver peut rendre l'appareil inutilisable (appels, contacts, réveil, clavier…).");
            }
            Dlg.confirm(m, name, name + " " + list.size() + " application(s) ?\n\n" + lst + extra, new Dlg.Ok() {
                public void ok() {
                    doAction(act, list, name, wipe);
                }
            });
            return;
        }
        doAction(act, list, name, wipe);
    }

    private static boolean good(AdbDevice.Result r) {
        return r.ok() && !r.out.contains("Failure") && !r.out.contains("Error") && !r.out.contains("Exception");
    }

    private void doAction(final int act, final List<Pkg> list, final String name, final boolean wipe) {
        m.setStatus(name + "…");
        final boolean ov = list.size() > 1;
        final boolean[] stop = {false};
        if (ov) overlayStart(name + " " + list.size() + " applications", list.size(), new Runnable() {
            public void run() {
                stop[0] = true;
            }
        });
        final AdbDevice d = ctl.adb();
        ctl.submit(new Callable<Integer>() {
            public Integer call() {
                int ok = 0;
                for (int i = 0; i < list.size(); i++) {
                    if (stop[0]) break;
                    final Pkg p = list.get(i);
                    final int n = i + 1;
                    if (ov) ovItem(p, "run", null, -1);
                    if (wipe) {
                        ctl.log(">> Effacer les données : " + Html.esc(p.name), "cmd");
                        AdbDevice.Result rc = Core.act(d, Core.ACT_CLEAR, p);
                        ctl.log(Html.esc(rc.text()), good(rc) ? "out" : "err");
                    }
                    AdbDevice.Result r = Core.act(d, act, p);
                    ctl.log(">> " + Html.esc(name) + " : " + Html.esc(p.name), "cmd");
                    if (act == Core.ACT_CLEAR_CACHE && r.rc == 2) {
                        ctl.log("Vidage du cache appli par appli non supporté avant Android 14 (sans root) : vidage du cache de toutes les applications (pm trim-caches).", "info");
                        AdbDevice.Result rt = Core.trimCaches(d);
                        ctl.log(rt.ok() ? "[Succès] : caches vidés" : "[Erreur] : " + Html.esc(rt.text()), rt.ok() ? "ok" : "err");
                        return rt.ok() ? list.size() : 0;
                    }
                    if (good(r)) {
                        ok++;
                        if (!r.text().isEmpty()) ctl.log(Html.esc(r.text()), "out");
                        if (ov) ovItem(p, "ok", null, n);
                    } else {
                        String msg = r.text().isEmpty() ? "échec" : r.text();
                        ctl.log("[Erreur] : " + Html.esc(msg), "err");
                        String[] lines = msg.trim().split("\n");
                        if (ov) ovItem(p, "err", lines[lines.length - 1], n);
                    }
                }
                return ok;
            }
        }, new Controller.Done<Integer>() {
            public void done(Integer ok, Exception e) {
                if (ov) m.overlay.finish(name + " : terminé");
                if (ok == null) return;
                m.toast(name + " : " + ok + "/" + list.size() + " réussie(s)", 4000);
                clearChecks();
                ctl.log("[Succès] : " + Html.esc(name) + " — " + ok + "/" + list.size(), ok == list.size() ? "ok" : "err");
                if (act != Core.ACT_LAUNCH && act != Core.ACT_FORCE_STOP && act != Core.ACT_CLEAR_CACHE) {
                    ctl.refreshPackages();
                    ctl.refreshRam();
                }
            }
        });
    }

    void overlayStart(String title, int total, Runnable cancel) {
        String dev = ctl.current != null ? ctl.current.label() : "";
        m.overlay.start(title, dev + " · ne débranche pas l'appareil pendant l'opération", total, cancel);
    }

    void ovItem(final Pkg p, final String state, final String detail, final int done) {
        ctl.ui(new Runnable() {
            public void run() {
                m.overlay.item(p.name, p.display(), p.icon, state, detail);
                if (done >= 0) m.overlay.setDone(done);
            }
        });
    }

    void backup(final List<Pkg> list) {
        if (!need() || list.isEmpty()) return;
        final String dev = ctl.current.label();
        m.setStatus("Sauvegarde de " + list.size() + " application(s)…");
        final boolean ov = list.size() > 1;
        final boolean[] stop = {false};
        if (ov) overlayStart("Sauvegarder " + list.size() + " applications", list.size(), new Runnable() {
            public void run() {
                stop[0] = true;
            }
        });
        final AdbDevice d = ctl.adb();
        ctl.submit(new Callable<Object[]>() {
            public Object[] call() {
                int n = 0;
                String last = null;
                for (int i = 0; i < list.size(); i++) {
                    if (stop[0]) break;
                    Pkg p = list.get(i);
                    if (ov) ovItem(p, "run", null, -1);
                    ctl.log(">> Sauvegarde de " + Html.esc(p.name), "cmd");
                    Object[] r = Core.backupApk(d, p, dev);
                    boolean ok = (Boolean) r[0];
                    ctl.log((ok ? "[Succès] : " : "[Erreur] : ") + Html.esc((String) r[1]), ok ? "ok" : "err");
                    if (ov) ovItem(p, ok ? "ok" : "err", ok ? null : (String) r[1], i + 1);
                    if (ok) {
                        n++;
                        last = (String) r[1];
                    }
                }
                return new Object[]{n, last};
            }
        }, new Controller.Done<Object[]>() {
            public void done(Object[] r, Exception e) {
                if (ov) m.overlay.finish("Sauvegarde terminée");
                if (r == null) return;
                clearChecks();
                String folder = r[1] != null ? new File((String) r[1]).getParent() : "";
                m.toast(r[0] + " fichier(s) sauvegardé(s)" + (folder.isEmpty() ? "" : "\n" + folder), 6000);
            }
        });
    }

    private void pushFiles() {
        if (!need()) return;
        m.pickFiles("*/*", new MainActivity.FilesPicked() {
            public void picked(final List<Uri> uris) {
                final AdbDevice d = ctl.adb();
                ctl.submit(new Callable<Void>() {
                    public Void call() throws Exception {
                        for (Uri u : uris) {
                            String name = m.displayName(u);
                            ctl.log(">> adb push " + Html.esc(name) + " /sdcard/Download/", "cmd");
                            InputStream in = m.getContentResolver().openInputStream(u);
                            try {
                                d.push(in, m.uriSize(u), "/sdcard/Download/" + name, 0644, null);
                                ctl.log("[Succès] : " + Html.esc(name) + " envoyé dans /sdcard/Download", "ok");
                            } catch (Exception e) {
                                ctl.log("[Erreur] : " + Html.esc(String.valueOf(e.getMessage())), "err");
                            } finally {
                                in.close();
                            }
                        }
                        return null;
                    }
                }, new Controller.Done<Void>() {
                    public void done(Void r, Exception e) {
                        m.setStatus("Envoi terminé");
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ presets
    static File presetsDir() {
        return Config.userDir("presets");
    }

    private void savePreset() {
        final List<String> names = new ArrayList<String>(checked);
        Collections.sort(names);
        if (names.isEmpty()) {
            m.toast("Coche d'abord les applications à mettre dans le preset.");
            return;
        }
        Dlg.input(m, "Enregistrer le preset", "Nom du preset :", "preset", new Dlg.Text() {
            public void ok(String s) {
                if (s.isEmpty()) return;
                try {
                    JSONObject o = new JSONObject();
                    o.put("packages", new JSONArray(names));
                    File f = new File(presetsDir(), s.replaceAll("[^\\w.\\- ]+", "_") + ".json");
                    FileOutputStream fo = new FileOutputStream(f);
                    fo.write(o.toString(1).getBytes("UTF-8"));
                    fo.close();
                    m.setStatus("Preset enregistré (" + names.size() + " applis)");
                } catch (Exception e) {
                    m.toast("Impossible d'enregistrer : " + e.getMessage());
                }
            }
        });
    }

    private void loadPreset() {
        final List<File> files = new ArrayList<File>();
        File[] l = presetsDir().listFiles();
        if (l != null) for (File f : l) if (f.getName().endsWith(".json") || f.getName().endsWith(".txt")) files.add(f);
        Collections.sort(files);
        List<String> names = new ArrayList<String>();
        for (File f : files) names.add(f.getName());
        Dlg.list(m, "Charger un preset", names, new Dlg.Pick() {
            public void pick(int i) {
                try {
                    String txt = new String(Core.readAll(new java.io.FileInputStream(files.get(i))), "UTF-8");
                    List<String> names = new ArrayList<String>();
                    try {
                        Object o = new org.json.JSONTokener(txt).nextValue();
                        JSONArray a = o instanceof JSONObject ? ((JSONObject) o).optJSONArray("packages") : (JSONArray) o;
                        if (a != null) for (int k = 0; k < a.length(); k++) names.add(a.getString(k));
                    } catch (Exception e) {
                        for (String ln : txt.split("\n")) if (!ln.trim().isEmpty()) names.add(ln.trim().replace("package:", ""));
                    }
                    checked.clear();
                    setChecked(names, true);
                    m.setStatus("Preset chargé : " + checked.size() + "/" + names.size() + " applis présentes sur l'appareil");
                } catch (Exception e) {
                    m.toast("Preset illisible : " + e.getMessage());
                }
            }
        });
    }

    // ------------------------------------------------------------------ debloat
    private void openWizard() {
        if (pkgs.isEmpty()) {
            m.toast("Connecte un appareil et attends le chargement des applications.");
            return;
        }
        final boolean tv = isTv();
        final Set<String> dev = new HashSet<String>();
        for (Pkg p : pkgs) if (!p.deleted && !p.disabled) dev.add(p.name);
        Dialogs.debloatWizard(m, dev, tv, new Dialogs.Level() {
            public void level(int level) {
                List<String> names = new ArrayList<String>();
                for (Pkg p : pkgs)
                    if (!p.deleted && !p.disabled && Debloat.matchesLevel(p.name, level, tv)) names.add(p.name);
                filters.select(0, false);
                mode = "all";
                clearChecks();
                setChecked(names, true);
                cols.sortCol = C_CHECK;
                cols.sortAsc = true;
                header.invalidate();
                sortAndShow();
                list.setSelection(0);
                cmbAction.select(0);
                m.toast(names.size() + " application(s) cochée(s) (niveau " + Debloat.LEVELS[level][0]
                        + "). Vérifie la liste puis touche Appliquer pour les désactiver.");
            }
        });
    }

    public Bitmap iconOf(String pkg) {
        for (Pkg p : pkgs) if (p.name.equals(pkg)) return p.icon;
        return null;
    }

    public Pkg pkg(String name) {
        for (Pkg p : pkgs) if (p.name.equals(name)) return p;
        return null;
    }

}

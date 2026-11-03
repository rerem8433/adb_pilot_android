package fr.paradoxxx.adbpilot.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
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
import fr.paradoxxx.adbpilot.Html;
import fr.paradoxxx.adbpilot.MainActivity;
import fr.paradoxxx.adbpilot.Pkg;
import fr.paradoxxx.adbpilot.Store;
import fr.paradoxxx.adbpilot.adb.AdbDevice;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

/** Page « Installer » : bibliothèque d'APK (dossier install + dossier par appareil). */
public final class InstallerPage extends Tab {
    static final String[] EXTS = {".apk", ".apks", ".xapk", ".apkm"};
    static final int C_CHECK = 0, C_NAME = 1, C_FILE = 2, C_PKG = 3, C_VER = 4, C_STATE = 5, C_SIZE = 6;

    static final class Info {
        File path;
        long size, mtime;
        String label, pkg, version;
        Bitmap icon;
        boolean read;
    }

    private final AppsTab apps;
    private final Map<String, Info> cache = new HashMap<String, Info>();
    private List<Info> files = new ArrayList<Info>();
    private final List<Info> shown = new ArrayList<Info>();
    final Set<String> checked = new HashSet<String>();
    private final Set<String> selected = new HashSet<String>();
    private String mode = "install", search = "";
    private final Table.Cols cols;
    private final Table.List list;
    private final Adapter adapter = new Adapter();
    private final Widgets.Check cbAll;
    private final TextView lblTotal, lblHint, lblSel;
    private final Ui.Btn bInst, bDev, bQuick;
    private int gen;
    private long lastTap;
    private String lastKey;

    public InstallerPage(MainActivity m, AppsTab apps) {
        super(m);
        this.apps = apps;
        LinearLayout lay = Ui.row(c);
        lay.setGravity(Gravity.TOP);
        view = lay;
        LinearLayout left = Ui.col(c);
        LinearLayout top = Ui.row(c);
        top.setPadding(dp(6), dp(4), dp(6), 0);
        cbAll = new Widgets.Check(c, "Tout sélectionner").onChange(new Widgets.OnChange() {
            public void changed(boolean on) {
                for (Info f : shown) {
                    if (on) checked.add(f.path.getPath());
                    else checked.remove(f.path.getPath());
                }
                fill();
            }
        });
        top.addView(cbAll);
        LinearLayout tot = Ui.col(c);
        tot.addView(Ui.text(c, "Total :", 7, Ui.MUTED));
        lblTotal = Ui.text(c, "0", 10, Ui.TEXT);
        tot.addView(lblTotal);
        tot.setPadding(dp(16), 0, dp(20), 0);
        top.addView(tot);
        lblHint = Ui.single(Ui.html(c, "", 9, Ui.TEXT));
        top.addView(lblHint, Ui.lpw(0, Ui.WRAP, 1));
        left.addView(top);
        cols = new Table.Cols("cols_install", new String[]{"", "Application", "Fichier", "Paquet", "Version", "Sur l'appareil", "Taille"},
                new int[]{34, 220, 160, 190, 100, 150, 85}, new boolean[]{true, false, false, false, false, false, false},
                new int[]{Gravity.CENTER, Gravity.START, Gravity.START, Gravity.START, Gravity.START, Gravity.START, Gravity.END});
        cols.sortCol = C_NAME;
        left.addView(new Table.Header(c, cols, new Table.HeaderListener() {
            public void onSort(int col) {
                fill();
            }

            public void onResized() {
                adapter.notifyDataSetChanged();
            }
        }), Ui.lp(Ui.MATCH, Ui.WRAP));
        list = new Table.List(c, 10, 40, 48);
        list.setAdapter(adapter);
        list.onRowSize = new Runnable() {
            public void run() {
                adapter.notifyDataSetChanged();
            }
        };
        left.addView(list, Ui.lpw(Ui.MATCH, 0, 1));
        lay.addView(left, Ui.lpw(0, Ui.MATCH, 1));

        LinearLayout right = Ui.col(c);
        right.setPadding(dp(6), dp(6), dp(6), dp(6));
        Ui.Btn back = Ui.button(c, "  ←  Retour aux applications", "btn");
        back.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        back.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                InstallerPage.this.apps.showApps();
            }
        });
        right.addView(back, Ui.lp(Ui.MATCH, dp(38)));
        LinearLayout pr = Ui.row(c);
        Ui.Btn lp = Ui.button(c, "Charger un preset", "btn");
        lp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                loadPreset();
            }
        });
        Ui.Btn sp = Ui.button(c, "Enregistrer le preset", "btn");
        sp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                savePreset();
            }
        });
        pr.addView(lp, Ui.lpw(0, dp(42), 1));
        pr.addView(sp, Ui.lpw(0, dp(42), 1));
        LinearLayout.LayoutParams prl = Ui.lp(Ui.MATCH, Ui.WRAP);
        prl.topMargin = dp(6);
        right.addView(pr, prl);
        TextView lab = Ui.text(c, "Dossier des fichiers :", 10, Ui.TEXT);
        lab.setGravity(Gravity.CENTER);
        lab.setPadding(0, dp(12), 0, dp(4));
        right.addView(lab, Ui.lp(Ui.MATCH, Ui.WRAP));
        LinearLayout g2 = Ui.row(c);
        bInst = Ui.button(c, "Dossier\nInstall", "btn").checkable();
        bDev = Ui.button(c, "Dossier\nde l'appareil", "btn").checkable();
        bInst.setChecked(true);
        bInst.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setMode("install");
            }
        });
        bDev.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setMode("device");
            }
        });
        g2.addView(bInst, Ui.lpw(0, dp(50), 1));
        g2.addView(bDev, Ui.lpw(0, dp(50), 1));
        right.addView(g2);
        Ui.Btn open = Ui.button(c, "Ouvrir le dossier", "btn");
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                InstallerPage.this.m.openFolder(folder());
            }
        });
        right.addView(open, Ui.lp(Ui.MATCH, dp(40)));
        LinearLayout row = Ui.row(c);
        row.setPadding(0, dp(10), 0, 0);
        LinearLayout acts = Ui.col(c);
        Ui.Btn add = Ui.button(c, "＋  Ajouter des APK", "btn");
        add.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        add.setTextColor(Ui.ACCENT_LINE);
        add.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                addDialog();
            }
        });
        Ui.Btn del = Ui.button(c, "－  Supprimer", "btn");
        del.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        del.setTextColor(Ui.RED);
        del.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                delete(targets());
            }
        });
        acts.addView(add, Ui.lp(Ui.MATCH, dp(38)));
        acts.addView(del, Ui.lp(Ui.MATCH, dp(38)));
        row.addView(acts, Ui.lpw(0, Ui.WRAP, 1));
        bQuick = Ui.button(c, "Installation\nrapide", "soft");
        bQuick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                install(targets());
            }
        });
        LinearLayout.LayoutParams ql = Ui.lp(dp(120), dp(76));
        ql.leftMargin = dp(6);
        row.addView(bQuick, ql);
        right.addView(row);
        right.addView(Ui.space(c, 1, 10));
        String[][] opts = {{"Accorder les permissions (-g)", "install_grant"}, {"Remplacer si déjà installée (-r)", "install_replace"},
                {"Autoriser une version antérieure (-d)", "install_downgrade"}};
        for (final String[] o : opts) {
            Widgets.Check cb = new Widgets.Check(c, o[0]);
            cb.setChecked(Config.getBool(o[1], !o[1].equals("install_downgrade")));
            cb.onChange(new Widgets.OnChange() {
                public void changed(boolean on) {
                    Config.setBool(o[1], on);
                }
            });
            right.addView(cb);
        }
        right.addView(Ui.vstretch(c));
        lblSel = Ui.text(c, "Sélection : 0", 10, Ui.TEXT);
        lblSel.setBackground(Ui.box(0, Ui.BORDER, 0, 1));
        lblSel.setPadding(dp(6), dp(5), dp(6), dp(5));
        right.addView(lblSel, Ui.lp(Ui.MATCH, Ui.WRAP));
        lay.addView(right, Ui.lp(dp(290), Ui.MATCH));
    }

    // ------------------------------------------------------------------ dossiers
    String deviceName() {
        Controller.Dev d = ctl.current;
        if (d == null) return null;
        String s = d.label().replaceAll("[^\\w.\\- ]+", "_").trim();
        return s.isEmpty() ? "appareil" : s;
    }

    File folder() {
        File root = Config.userDir("install");
        File f = "device".equals(mode) && deviceName() != null ? new File(root, deviceName()) : root;
        f.mkdirs();
        return f;
    }

    void onDevice() {
        String name = deviceName();
        bDev.setText("Dossier\n" + (name == null ? "de l'appareil" : name));
        bDev.setEnabled(name != null);
        if (name == null && "device".equals(mode)) {
            mode = "install";
            bInst.setChecked(true);
            bDev.setChecked(false);
        }
        if (view.getVisibility() == View.VISIBLE) refresh();
        else fill();
    }

    private void setMode(String md) {
        mode = md;
        bInst.setChecked("install".equals(md));
        bDev.setChecked("device".equals(md));
        checked.clear();
        refresh();
    }

    // ------------------------------------------------------------------ liste
    public void refresh() {
        File folder = folder();
        lblHint.setText(Ui.fromHtml("Partage des APK vers ADB Pilot ou copie-les dans <b>" + Html.esc(folder.getAbsolutePath()) + "</b>"));
        File[] l = folder.listFiles();
        List<Info> out = new ArrayList<Info>();
        final List<Info> todo = new ArrayList<Info>();
        if (l != null) for (File p : l) {
            String n = p.getName().toLowerCase(Locale.ROOT);
            boolean ok = false;
            for (String e : EXTS) if (n.endsWith(e)) ok = true;
            if (!p.isFile() || !ok) continue;
            Info ci = cache.get(p.getPath());
            if (ci == null || ci.mtime != p.lastModified() || ci.size != p.length()) {
                ci = new Info();
                ci.path = p;
                ci.size = p.length();
                ci.mtime = p.lastModified();
                cache.put(p.getPath(), ci);
                todo.add(ci);
            }
            out.add(ci);
        }
        files = out;
        Set<String> names = new HashSet<String>();
        for (Info f : files) names.add(f.path.getPath());
        checked.retainAll(names);
        fill();
        if (!todo.isEmpty()) {
            final int g = ++gen;
            ctl.submit(new Callable<Void>() {
                public Void call() {
                    for (Info f : todo) readInfo(f);
                    return null;
                }
            }, new Controller.Done<Void>() {
                public void done(Void r, Exception e) {
                    if (g == gen) fill();
                }
            });
        }
    }

    /** Nom / paquet / version / icône (APK : PackageManager de la tablette ; archives : manifest.json + base.apk). */
    static void readInfo(Info info) {
        int size = Ui.dp(48);
        String n = info.path.getName().toLowerCase(Locale.ROOT);
        try {
            if (n.endsWith(".apk")) {
                Object[] r = Store.apkInfo(info.path, size);
                if (r != null) {
                    info.label = (String) r[0];
                    info.pkg = (String) r[1];
                    info.version = (String) r[2];
                    info.icon = (Bitmap) r[3];
                }
                return;
            }
            ZipFile z = new ZipFile(info.path);
            try {
                for (String jn : new String[]{"manifest.json", "info.json", "meta.sai_v2.json", "meta.sai_v1.json"}) {
                    ZipEntry e = z.getEntry(jn);
                    if (e == null) continue;
                    try {
                        JSONObject o = new JSONObject(new String(Core.readAll(z.getInputStream(e)), "UTF-8"));
                        info.label = first(o, "name", "app_name", "label");
                        info.pkg = first(o, "package_name", "pname", "package");
                        info.version = first(o, "version_name", "release_version", "version_name_str");
                    } catch (Exception ignored) {
                    }
                    break;
                }
                for (String in : new String[]{"icon.png", "icon.webp", "icon.jpg"}) {
                    ZipEntry e = z.getEntry(in);
                    if (e == null) continue;
                    byte[] b = Core.readAll(z.getInputStream(e));
                    info.icon = BitmapFactory.decodeByteArray(b, 0, b.length);
                    break;
                }
                if (info.pkg == null || info.icon == null) {
                    ZipEntry base = null;
                    java.util.Enumeration<? extends ZipEntry> en = z.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry e = en.nextElement();
                        String nm = e.getName();
                        if (nm.endsWith("base.apk")) {
                            base = e;
                            break;
                        }
                        if (base == null && nm.endsWith(".apk") && !nm.contains("config.") && !nm.contains("split_")) base = e;
                    }
                    if (base != null && base.getSize() < 400L * 1048576) {
                        File tmp = File.createTempFile("base", ".apk", Config.cacheDir("inst"));
                        FileOutputStream o = new FileOutputStream(tmp);
                        java.io.InputStream in = z.getInputStream(base);
                        byte[] b = new byte[65536];
                        int k;
                        while ((k = in.read(b)) > 0) o.write(b, 0, k);
                        o.close();
                        in.close();
                        Object[] r = Store.apkInfo(tmp, size);
                        tmp.delete();
                        if (r != null) {
                            if (info.pkg == null) {
                                info.label = info.label == null ? (String) r[0] : info.label;
                                info.pkg = (String) r[1];
                                info.version = info.version == null ? (String) r[2] : info.version;
                            }
                            if (info.icon == null) info.icon = (Bitmap) r[3];
                        }
                    }
                }
            } finally {
                z.close();
            }
        } catch (Exception ignored) {
        } finally {
            info.read = true;
        }
    }

    static String first(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.isEmpty()) return v;
        }
        return null;
    }

    @Override
    public void setSearch(String t) {
        search = t.trim().toLowerCase(Locale.ROOT);
        fill();
    }

    static int[] vkey(String v) {
        String[] p = (v == null ? "" : v).split("[.\\-_ ]");
        int[] k = new int[6];
        for (int i = 0; i < 6 && i < p.length; i++) {
            try {
                k[i] = p[i].matches("\\d{1,9}") ? Integer.parseInt(p[i]) : 0;
            } catch (NumberFormatException e) {
                k[i] = 0;
            }
        }
        return k;
    }

    static int cmpV(String a, String b) {
        int[] x = vkey(a), y = vkey(b);
        for (int i = 0; i < 6; i++) if (x[i] != y[i]) return x[i] < y[i] ? -1 : 1;
        return 0;
    }

    /** {texte, couleur} de la colonne « Sur l'appareil ». */
    String[] state(Info f) {
        if (f.pkg == null || !ctl.ready()) return new String[]{"", null};
        Pkg p = apps.pkg(f.pkg);
        if (p == null || p.deleted) return new String[]{"Non installée", null};
        if (f.version == null || p.version == null) return new String[]{"Installée", "#7fb8ff"};
        int c = cmpV(f.version, p.version);
        if (c > 0) return new String[]{"Mise à jour (" + p.version + ")", "#e6c22e"};
        if (c < 0) return new String[]{"Plus récente installée (" + p.version + ")", "#ff8c3a"};
        return new String[]{"Installée (identique)", "#2fd06b"};
    }

    void fill() {
        shown.clear();
        for (Info f : files) {
            if (search.isEmpty() || f.path.getName().toLowerCase(Locale.ROOT).contains(search)
                    || (f.label != null && f.label.toLowerCase(Locale.ROOT).contains(search))
                    || (f.pkg != null && f.pkg.toLowerCase(Locale.ROOT).contains(search))) shown.add(f);
        }
        final int col = cols.sortCol;
        final boolean asc = cols.sortAsc;
        Collections.sort(shown, new Comparator<Info>() {
            public int compare(Info a, Info b) {
                int r;
                switch (col) {
                    case C_FILE:
                        r = a.path.getName().compareToIgnoreCase(b.path.getName());
                        break;
                    case C_PKG:
                        r = String.valueOf(a.pkg).compareTo(String.valueOf(b.pkg));
                        break;
                    case C_VER:
                        r = cmpV(a.version, b.version);
                        break;
                    case C_SIZE:
                        r = a.size < b.size ? -1 : (a.size == b.size ? 0 : 1);
                        break;
                    case C_STATE:
                        r = state(a)[0].compareTo(state(b)[0]);
                        break;
                    default:
                        r = name(a).compareToIgnoreCase(name(b));
                }
                return asc ? r : -r;
            }
        });
        adapter.notifyDataSetChanged();
        lblTotal.setText(String.valueOf(shown.size()));
        lblSel.setText("Sélection : " + checked.size());
        boolean all = !shown.isEmpty();
        for (Info f : shown) if (!checked.contains(f.path.getPath())) all = false;
        cbAll.setChecked(all);
    }

    static String name(Info f) {
        if (f.label != null && !f.label.isEmpty()) return f.label;
        String n = f.path.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private final class Adapter extends BaseAdapter {
        public int getCount() {
            return shown.size();
        }

        public Object getItem(int i) {
            return shown.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout row = (LinearLayout) cv;
            if (row == null) row = makeRow();
            Info f = shown.get(pos);
            String key = f.path.getPath();
            row.setTag(f);
            row.setBackgroundColor(Table.rowColor(pos, selected.contains(key)));
            cols.apply(row);
            row.setLayoutParams(new android.widget.AbsListView.LayoutParams(Ui.MATCH, list.rowHeight));
            ((Widgets.Box) ((FrameLayout) row.getChildAt(0)).getChildAt(0)).set(checked.contains(key));
            LinearLayout nc = (LinearLayout) row.getChildAt(1);
            ImageView ic = (ImageView) nc.getChildAt(0);
            LinearLayout.LayoutParams il = (LinearLayout.LayoutParams) ic.getLayoutParams();
            il.width = il.height = list.iconSize;
            ic.setLayoutParams(il);
            if (f.icon != null) ic.setImageBitmap(f.icon);
            else ic.setImageDrawable(Ui.icon(c, "app", 0xff7fb8ff));
            ((TextView) nc.getChildAt(1)).setText(name(f));
            ((TextView) row.getChildAt(2)).setText(f.path.getName());
            ((TextView) row.getChildAt(3)).setText(f.pkg == null ? "" : f.pkg);
            ((TextView) row.getChildAt(4)).setText(f.version == null ? "" : f.version);
            String[] st = state(f);
            TextView s = (TextView) row.getChildAt(5);
            s.setText(st[0]);
            s.setTextColor(st[1] == null ? Ui.TEXT : android.graphics.Color.parseColor(st[1]));
            ((TextView) row.getChildAt(6)).setText(String.format(Locale.ROOT, "%.2f", f.size / 1048576.0).replace(".", ",") + " Mo");
            return row;
        }
    }

    private LinearLayout makeRow() {
        final LinearLayout row = Ui.row(c);
        FrameLayout cf = new FrameLayout(c);
        cf.addView(new Widgets.Box(c, false), new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        cf.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String k = ((Info) row.getTag()).path.getPath();
                if (!checked.remove(k)) checked.add(k);
                fill();
            }
        });
        row.addView(cf);
        LinearLayout nc = Ui.row(c);
        nc.setPadding(dp(8), 0, dp(4), 0);
        ImageView ic = new ImageView(c);
        nc.addView(ic, Ui.lp(dp(30), dp(30)));
        TextView n = Ui.single(Ui.text(c, "", 11, Ui.TEXT));
        n.setPadding(dp(10), 0, 0, 0);
        nc.addView(n, Ui.lpw(0, Ui.WRAP, 1));
        row.addView(nc);
        for (int i = 2; i < 7; i++) {
            TextView t = Ui.single(Ui.text(c, "", 10, Ui.MUTED));
            t.setGravity(Gravity.CENTER_VERTICAL | (i == C_SIZE ? Gravity.END : Gravity.START));
            t.setPadding(dp(6), 0, dp(8), 0);
            row.addView(t);
        }
        View.OnClickListener tap = new View.OnClickListener() {
            public void onClick(View v) {
                Info f = (Info) row.getTag();
                String k = f.path.getPath();
                long now = System.currentTimeMillis();
                if (k.equals(lastKey) && now - lastTap < 400) {
                    lastKey = null;
                    install(java.util.Arrays.asList(f));
                    return;
                }
                lastTap = now;
                lastKey = k;
                selected.clear();
                selected.add(k);
                adapter.notifyDataSetChanged();
            }
        };
        View.OnLongClickListener lt = new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                Info f = (Info) row.getTag();
                selected.clear();
                selected.add(f.path.getPath());
                adapter.notifyDataSetChanged();
                menu(row, f);
                return true;
            }
        };
        for (int i = 1; i < row.getChildCount(); i++) {
            row.getChildAt(i).setOnClickListener(tap);
            row.getChildAt(i).setOnLongClickListener(lt);
        }
        return row;
    }

    private void menu(View anchor, final Info f) {
        PopupMenu pm = new PopupMenu(c, anchor, Gravity.START);
        pm.getMenu().add(0, 1, 1, "Installer");
        final String other = "device".equals(mode) ? "install" : "device";
        if (deviceName() != null)
            pm.getMenu().add(1, 2, 2, "Copier vers le dossier " + ("install".equals(other) ? "Install" : deviceName()));
        pm.getMenu().add(2, 3, 3, "Supprimer");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem it) {
                if (it.getItemId() == 1) install(java.util.Arrays.asList(f));
                else if (it.getItemId() == 2) copyTo(java.util.Arrays.asList(f), other);
                else delete(java.util.Arrays.asList(f));
                return true;
            }
        });
        pm.show();
    }

    List<Info> targets() {
        List<Info> out = new ArrayList<Info>();
        if (!checked.isEmpty()) {
            for (Info f : files) if (checked.contains(f.path.getPath())) out.add(f);
            return out;
        }
        for (Info f : shown) if (selected.contains(f.path.getPath())) out.add(f);
        return out;
    }

    // ------------------------------------------------------------------ fichiers
    private void addDialog() {
        m.pickFiles("*/*", new MainActivity.FilesPicked() {
            public void picked(List<Uri> uris) {
                addUris(uris);
            }
        });
    }

    public void addUris(final List<Uri> uris) {
        final File dir = folder();
        ctl.submit(new Callable<List<String>>() {
            public List<String> call() throws Exception {
                List<String> added = new ArrayList<String>();
                for (Uri u : uris) {
                    String n = m.displayName(u).toLowerCase(Locale.ROOT);
                    boolean ok = false;
                    for (String e : EXTS) if (n.endsWith(e)) ok = true;
                    if (!ok) continue;
                    added.add(m.copyUri(u, dir).getPath());
                }
                return added;
            }
        }, new Controller.Done<List<String>>() {
            public void done(List<String> added, Exception e) {
                if (added == null) return;
                checked.addAll(added);
                if (!added.isEmpty()) m.toast(added.size() + " fichier(s) ajouté(s) à " + dir.getAbsolutePath(), 3000);
                else m.toast("Aucun fichier APK / APKS / XAPK / APKM dans la sélection.");
                refresh();
            }
        });
    }

    private void copyTo(List<Info> list, String md) {
        File root = Config.userDir("install");
        File dest = "device".equals(md) ? new File(root, deviceName()) : root;
        dest.mkdirs();
        int n = 0;
        for (Info f : list) {
            try {
                java.io.FileInputStream in = new java.io.FileInputStream(f.path);
                FileOutputStream o = new FileOutputStream(new File(dest, f.path.getName()));
                byte[] b = new byte[65536];
                int k;
                while ((k = in.read(b)) > 0) o.write(b, 0, k);
                o.close();
                in.close();
                n++;
            } catch (Exception ignored) {
            }
        }
        m.toast(n + " fichier(s) copié(s) vers " + dest.getAbsolutePath(), 3000);
    }

    private void delete(final List<Info> list) {
        if (list.isEmpty()) {
            m.toast("Coche d'abord les fichiers à supprimer.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(12, list.size()); i++) sb.append("• ").append(list.get(i).path.getName()).append('\n');
        Dlg.confirm(m, "Supprimer", "Supprimer " + list.size() + " fichier(s) ?\n\n" + sb, new Dlg.Ok() {
            public void ok() {
                for (Info f : list) {
                    f.path.delete();
                    checked.remove(f.path.getPath());
                }
                refresh();
            }
        });
    }

    // ------------------------------------------------------------------ installation
    void install(final List<Info> list) {
        if (!need()) return;
        if (list.isEmpty()) {
            m.toast("Coche les applications à installer.");
            return;
        }
        bQuick.setEnabled(false);
        final int total = list.size();
        final boolean ov = total > 1;
        final boolean[] stop = {false};
        if (ov) apps.overlayStart("Installer " + total + " applications", total, new Runnable() {
            public void run() {
                stop[0] = true;
            }
        });
        final List<Object[]> incompatible = new ArrayList<Object[]>();
        final List<String> installed = new ArrayList<String>();
        final AdbDevice d = ctl.adb();
        ctl.submit(new Callable<Integer>() {
            public Integer call() {
                int ok = 0;
                for (int i = 0; i < total; i++) {
                    if (stop[0]) break;
                    final Info f = list.get(i);
                    final String name = f.label != null ? f.label : f.path.getName();
                    final int k = i + 1;
                    item(ov, f, name, "run", null, -1);
                    ctl.status("Installation " + k + "/" + total + " : " + f.path.getName() + "…");
                    ctl.log(">> Installation : " + Html.esc(f.path.getName()), "cmd");
                    AdbDevice.Result r = Core.installFile(d, f.path, new AdbDevice.Progress() {
                        long last;

                        public void onProgress(long done, long tot) {
                            long now = System.currentTimeMillis();
                            if (now - last < 500 || tot <= 0) return;
                            last = now;
                            ctl.status("Envoi " + k + "/" + total + " : " + f.path.getName() + " — " + (done * 100 / tot) + " %");
                        }
                    }, new Core.Log() {
                        public void log(String s) {
                            ctl.log(Html.esc(s), "info");
                        }
                    });
                    if (r.ok() && !r.out.contains("Failure")) {
                        ok++;
                        installed.add(f.path.getPath());
                        ctl.log("[Succès] : " + Html.esc(f.path.getName()) + " installé", "ok");
                        item(ov, f, name, "ok", null, k);
                    } else {
                        ctl.log("[Erreur] : " + Html.esc(f.path.getName()) + " — " + Html.esc(r.text()), "err");
                        Matcher mm = Pattern.compile("INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package (\\S+) signatures").matcher(r.text());
                        if (mm.find()) incompatible.add(new Object[]{f, mm.group(1)});
                        String[] lines = r.text().trim().split("\n");
                        item(ov, f, name, "err", lines[lines.length - 1], k);
                    }
                }
                return ok;
            }
        }, new Controller.Done<Integer>() {
            public void done(Integer ok, Exception e) {
                bQuick.setEnabled(true);
                if (ov) m.overlay.finish("Installation terminée");
                checked.removeAll(installed);
                selected.clear();
                fill();
                if (ok == null) return;
                m.toast("Installation : " + ok + "/" + total + " réussie(s)" + (ok == total ? "" : " — détails dans la Console"));
                ctl.refreshPackages();
                if (!incompatible.isEmpty()) offerClean(incompatible);
            }
        });
    }

    private void item(final boolean ov, final Info f, final String name, final String state, final String detail, final int done) {
        if (!ov) return;
        ctl.ui(new Runnable() {
            public void run() {
                m.overlay.item(f.path.getPath(), name, f.icon, state, detail);
                if (done >= 0) m.overlay.setDone(done);
            }
        });
    }

    private void offerClean(final List<Object[]> items) {
        if (m.overlay.isShown2()) m.overlay.close();
        StringBuilder sb = new StringBuilder();
        for (Object[] x : items) sb.append("• ").append(((Info) x[0]).path.getName()).append(" (").append(x[1]).append(")\n");
        Dlg.confirm(m, "Signature différente", "L'appareil garde une version de ces applications signée différemment "
                + "(installée, ou désinstallée en gardant ses données) :\n\n" + sb
                + "\nLa supprimer complètement (données comprises) puis installer le nouveau fichier ?", new Dlg.Ok() {
            public void ok() {
                final AdbDevice d = ctl.adb();
                ctl.submit(new Callable<Void>() {
                    public Void call() {
                        for (Object[] x : items) {
                            ctl.log(">> Suppression complète : " + Html.esc((String) x[1]), "cmd");
                            AdbDevice.Result r = Core.uninstallCompletely(d, (String) x[1]);
                            ctl.log(Html.esc(r.text()), r.out.contains("Success") ? "out" : "err");
                        }
                        return null;
                    }
                }, new Controller.Done<Void>() {
                    public void done(Void r, Exception e) {
                        List<Info> again = new ArrayList<Info>();
                        for (Object[] x : items) again.add((Info) x[0]);
                        install(again);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ presets
    private void savePreset() {
        final List<String> names = new ArrayList<String>();
        for (String k : checked) names.add(new File(k).getName());
        Collections.sort(names);
        if (names.isEmpty()) {
            m.toast("Coche d'abord les fichiers à mettre dans le preset.");
            return;
        }
        Dlg.input(m, "Enregistrer le preset d'installation", "Nom du preset :", "install", new Dlg.Text() {
            public void ok(String s) {
                if (s.isEmpty()) return;
                try {
                    JSONObject o = new JSONObject();
                    o.put("install_files", new JSONArray(names));
                    FileOutputStream fo = new FileOutputStream(new File(AppsTab.presetsDir(), s.replaceAll("[^\\w.\\- ]+", "_") + ".json"));
                    fo.write(o.toString(1).getBytes("UTF-8"));
                    fo.close();
                    m.setStatus("Preset enregistré (" + names.size() + " fichiers)");
                } catch (Exception e) {
                    m.toast("Impossible d'enregistrer : " + e.getMessage());
                }
            }
        });
    }

    private void loadPreset() {
        final List<File> fl = new ArrayList<File>();
        File[] l = AppsTab.presetsDir().listFiles();
        if (l != null) for (File f : l) if (f.getName().endsWith(".json")) fl.add(f);
        Collections.sort(fl);
        List<String> names = new ArrayList<String>();
        for (File f : fl) names.add(f.getName());
        Dlg.list(m, "Charger un preset d'installation", names, new Dlg.Pick() {
            public void pick(int i) {
                try {
                    JSONObject o = new JSONObject(new String(Core.readAll(new java.io.FileInputStream(fl.get(i))), "UTF-8"));
                    JSONArray a = o.optJSONArray("install_files");
                    Set<String> want = new HashSet<String>();
                    if (a != null) for (int k = 0; k < a.length(); k++) want.add(a.getString(k));
                    checked.clear();
                    for (Info f : files) if (want.contains(f.path.getName())) checked.add(f.path.getPath());
                    fill();
                    m.setStatus("Preset chargé : " + checked.size() + "/" + want.size() + " fichiers trouvés");
                } catch (Exception e) {
                    m.toast("Preset illisible.");
                }
            }
        });
    }
}

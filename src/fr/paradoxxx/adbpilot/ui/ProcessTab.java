package fr.paradoxxx.adbpilot.ui;

import android.graphics.Typeface;
import android.os.Handler;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import fr.paradoxxx.adbpilot.Controller;
import fr.paradoxxx.adbpilot.Core;
import fr.paradoxxx.adbpilot.Html;
import fr.paradoxxx.adbpilot.MainActivity;
import fr.paradoxxx.adbpilot.Pkg;
import fr.paradoxxx.adbpilot.adb.AdbDevice;

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

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

/** Gestionnaire de processus (process_tab.py). */
public final class ProcessTab extends Tab {
    static final String PS = "head -3 /proc/meminfo; echo @@PS; toybox ps -A -o PID,USER,RSS,NAME 2>/dev/null || "
            + "ps -A -o PID,USER,RSS,NAME 2>/dev/null; echo @@TOP; "
            + "dumpsys activity activities 2>/dev/null | grep -m 4 -E 'ResumedActivity'";

    static final class Proc {
        int pid;
        String user, name, pkg;
        long rss;
    }

    private List<Proc> procs = new ArrayList<Proc>();
    private final List<Proc> rows = new ArrayList<Proc>();
    private final Set<Integer> selected = new HashSet<Integer>();
    private String top;
    private long memTotal, memUsed;
    private boolean busy;
    private final Widgets.Check cbApps, cbAuto;
    private final TextView lbl;
    private final Table.Cols cols;
    private final Table.List list;
    private final Adapter adapter = new Adapter();
    private final Handler h = new Handler();
    private final Runnable tick = new Runnable() {
        public void run() {
            refresh();
            h.postDelayed(this, 3000);
        }
    };

    public ProcessTab(MainActivity m) {
        super(m);
        LinearLayout lay = Ui.col(c);
        lay.setPadding(dp(6), dp(6), dp(6), dp(6));
        view = lay;
        LinearLayout tp = Ui.row(c);
        cbApps = new Widgets.Check(c, "Applications uniquement");
        cbApps.setChecked(true);
        cbApps.onChange(new Widgets.OnChange() {
            public void changed(boolean on) {
                fill();
            }
        });
        tp.addView(cbApps);
        cbAuto = new Widgets.Check(c, "Actualisation auto (3 s)");
        cbAuto.setChecked(true);
        cbAuto.setPadding(dp(12), dp(4), dp(4), dp(4));
        cbAuto.onChange(new Widgets.OnChange() {
            public void changed(boolean on) {
                h.removeCallbacks(tick);
                if (on && ProcessTab.this.m.isCurrent(ProcessTab.this)) h.postDelayed(tick, 3000);
            }
        });
        tp.addView(cbAuto);
        tp.addView(Ui.stretch(c));
        lbl = Ui.text(c, "", 10, Ui.TEXT);
        tp.addView(lbl);
        lay.addView(tp);
        cols = new Table.Cols("cols_procs", new String[]{"Application", "Processus", "RAM"}, new int[]{360, 320, 110},
                new boolean[]{false, false, false}, new int[]{Gravity.START, Gravity.START, Gravity.END});
        cols.sortCol = 2;
        cols.sortAsc = false;
        lay.addView(new Table.Header(c, cols, new Table.HeaderListener() {
            public void onSort(int col) {
                fill();
            }

            public void onResized() {
                adapter.notifyDataSetChanged();
            }
        }), Ui.lp(Ui.MATCH, Ui.WRAP));
        list = new Table.List(c, 10, 36, 40);
        list.setAdapter(adapter);
        list.onRowSize = new Runnable() {
            public void run() {
                adapter.notifyDataSetChanged();
            }
        };
        lay.addView(list, Ui.lpw(Ui.MATCH, 0, 1));
        LinearLayout bot = Ui.row(c);
        bot.setPadding(0, dp(6), 0, 0);
        String[] names = {"Rafraîchir", "Forcer l'arrêt", "Libérer la mémoire (tout)"};
        for (int i = 0; i < names.length; i++) {
            final int k = i;
            Ui.Btn b = Ui.button(c, names[i], "btn");
            b.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (k == 0) refresh();
                    else if (k == 1) forceStop(sel());
                    else run("Libération de la mémoire", java.util.Arrays.asList("am kill-all"));
                }
            });
            LinearLayout.LayoutParams lp = Ui.lp(Ui.WRAP, dp(38));
            lp.rightMargin = dp(6);
            bot.addView(b, lp);
        }
        lay.addView(bot);
    }

    @Override
    public void onShown() {
        refresh();
        h.removeCallbacks(tick);
        if (cbAuto.isChecked()) h.postDelayed(tick, 3000);
    }

    @Override
    public void onHidden() {
        h.removeCallbacks(tick);
    }

    @Override
    public void onDevice(Controller.Dev d) {
        procs = new ArrayList<Proc>();
        rows.clear();
        adapter.notifyDataSetChanged();
        lbl.setText("");
        if (d != null && "device".equals(d.state) && m.isCurrent(this)) refresh();
    }

    static List<Proc> parsePs(String text) {
        List<Proc> out = new ArrayList<Proc>();
        String[] lines = text.trim().split("\n");
        if (lines.length == 0 || lines[0].trim().isEmpty()) return out;
        List<String> hdr = java.util.Arrays.asList(lines[0].trim().split("\\s+"));
        int ip = hdr.indexOf("PID"), iu = hdr.indexOf("USER"), ir = hdr.indexOf("RSS");
        if (ip < 0 || iu < 0) return out;
        for (int i = 1; i < lines.length; i++) {
            String[] p = lines[i].trim().split("\\s+");
            if (p.length < hdr.size() - 1 || ip >= p.length || !p[ip].matches("\\d+")) continue;
            Proc x = new Proc();
            x.pid = Integer.parseInt(p[ip]);
            x.user = iu < p.length ? p[iu] : "";
            x.rss = ir >= 0 && ir < p.length && p[ir].matches("\\d+") ? Long.parseLong(p[ir]) : 0;
            x.name = p[p.length - 1];
            x.pkg = x.name.split(":")[0];
            out.add(x);
        }
        return out;
    }

    void refresh() {
        if (busy || !ctl.ready()) return;
        busy = true;
        final AdbDevice d = ctl.adb();
        ctl.submit(new Callable<Object[]>() {
            public Object[] call() {
                String out = d.shell(PS, 30).out;
                int a = out.indexOf("@@PS"), t = out.indexOf("@@TOP");
                String mem = a >= 0 ? out.substring(0, a) : "", ps = a >= 0 ? out.substring(a + 4, t > a ? t : out.length()) : "",
                        tp = t >= 0 ? out.substring(t + 5) : "";
                Matcher mt = Pattern.compile("MemTotal:\\s+(\\d+)").matcher(mem);
                long total = mt.find() ? Long.parseLong(mt.group(1)) : 0;
                Matcher ma = Pattern.compile("MemAvailable:\\s+(\\d+)").matcher(mem);
                if (!ma.find()) {
                    ma = Pattern.compile("MemFree:\\s+(\\d+)").matcher(mem);
                    if (!ma.find()) ma = null;
                }
                long used = ma != null && total > 0 ? total - Long.parseLong(ma.group(1)) : 0;
                List<Proc> l = parsePs(ps);
                if (l.size() < 5) l = parsePs(d.shell("toybox ps 2>/dev/null || ps", 30).out);
                Matcher m2 = Pattern.compile("\\s([\\w.]+)/[\\w.$]+").matcher(tp);
                return new Object[]{l, m2.find() ? m2.group(1) : null, total, used};
            }
        }, new Controller.Done<Object[]>() {
            @SuppressWarnings("unchecked")
            public void done(Object[] r, Exception e) {
                busy = false;
                if (r == null) return;
                procs = (List<Proc>) r[0];
                top = (String) r[1];
                memTotal = (Long) r[2];
                memUsed = (Long) r[3];
                if (Core.realRamKb > 0) {
                    memTotal = memTotal > 0 ? Math.min(memTotal, Core.realRamKb) : Core.realRamKb;
                    memUsed = Math.min(memUsed, memTotal);
                }
                fill();
            }
        });
    }

    static String fmtMem(long kb) {
        if (kb >= 1048576) return String.format(Locale.ROOT, "%.2f Go", kb / 1048576.0).replace(".", ",");
        return String.format(Locale.ROOT, "%.1f Mo", kb / 1024.0).replace(".", ",");
    }

    private Map<String, Pkg> pkgMap() {
        Map<String, Pkg> mp = new HashMap<String, Pkg>();
        for (Pkg p : ctl.packages) mp.put(p.name, p);
        return mp;
    }

    void fill() {
        final Map<String, Pkg> pk = pkgMap();
        rows.clear();
        for (Proc p : procs) if (pk.containsKey(p.pkg) || !cbApps.isChecked()) rows.add(p);
        final int col = cols.sortCol;
        final boolean asc = cols.sortAsc;
        Collections.sort(rows, new Comparator<Proc>() {
            public int compare(Proc a, Proc b) {
                int r;
                if (col == 2) r = a.rss < b.rss ? -1 : (a.rss == b.rss ? 0 : 1);
                else if (col == 1) r = a.name.compareTo(b.name);
                else r = label(a, pk).compareToIgnoreCase(label(b, pk));
                return asc ? r : -r;
            }
        });
        adapter.notifyDataSetChanged();
        if (memTotal > 0)
            lbl.setText(rows.size() + " processus · RAM utilisée : " + fmtMem(memUsed) + " / " + fmtMem(memTotal) + " (" + Math.round(memUsed * 100.0 / memTotal) + " %)");
        else lbl.setText(rows.size() + " processus");
    }

    static String label(Proc p, Map<String, Pkg> pk) {
        Pkg x = pk.get(p.pkg);
        return x != null ? x.display() : p.name;
    }

    private final class Adapter extends BaseAdapter {
        public int getCount() {
            return rows.size();
        }

        public Object getItem(int i) {
            return rows.get(i);
        }

        public long getItemId(int i) {
            return i;
        }

        public View getView(int pos, View cv, ViewGroup parent) {
            LinearLayout row = (LinearLayout) cv;
            if (row == null) row = makeRow();
            Proc p = rows.get(pos);
            Pkg pkg = pkgMap().get(p.pkg);
            row.setTag(p);
            row.setBackgroundColor(Table.rowColor(pos, selected.contains(p.pid)));
            cols.apply(row);
            row.setLayoutParams(new android.widget.AbsListView.LayoutParams(Ui.MATCH, list.rowHeight));
            LinearLayout nc = (LinearLayout) row.getChildAt(0);
            ImageView ic = (ImageView) nc.getChildAt(0);
            LinearLayout.LayoutParams il = (LinearLayout.LayoutParams) ic.getLayoutParams();
            il.width = il.height = Math.min(list.iconSize, dp(30));
            ic.setLayoutParams(il);
            ic.setVisibility(pkg != null ? View.VISIBLE : View.INVISIBLE);
            if (pkg != null) {
                if (pkg.icon != null) ic.setImageBitmap(pkg.icon);
                else ic.setImageDrawable(Ui.placeholder(c));
            }
            boolean fg = top != null && p.pkg.equals(top) && !p.name.contains(":");
            TextView t = (TextView) nc.getChildAt(1);
            t.setText((fg ? "▶ " : "") + (pkg != null ? pkg.display() : p.name));
            t.setTextColor(fg ? 0xff7fa4ff : Ui.TEXT);
            t.setTypeface(fg ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            ((TextView) row.getChildAt(1)).setText(p.name);
            double pct = memTotal > 0 && p.rss > 0 ? p.rss * 100.0 / memTotal : 0;
            ((TextView) row.getChildAt(2)).setText(p.rss > 0 ? String.format(Locale.ROOT, "%.1f %%", pct).replace(".", ",") : "");
            return row;
        }
    }

    private LinearLayout makeRow() {
        final LinearLayout row = Ui.row(c);
        LinearLayout nc = Ui.row(c);
        nc.setPadding(dp(10), 0, dp(4), 0);
        nc.addView(new ImageView(c), Ui.lp(dp(26), dp(26)));
        TextView t = Ui.single(Ui.text(c, "", 10, Ui.TEXT));
        t.setPadding(dp(10), 0, 0, 0);
        nc.addView(t, Ui.lpw(0, Ui.WRAP, 1));
        row.addView(nc);
        TextView pn = Ui.single(Ui.text(c, "", 10, Ui.MUTED));
        pn.setGravity(Gravity.CENTER_VERTICAL);
        pn.setPadding(dp(6), 0, dp(6), 0);
        row.addView(pn);
        TextView r = Ui.text(c, "", 10, Ui.TEXT);
        r.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        r.setPadding(dp(6), 0, dp(10), 0);
        row.addView(r);
        row.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Proc p = (Proc) row.getTag();
                if (!selected.remove(p.pid)) selected.add(p.pid);
                adapter.notifyDataSetChanged();
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                Proc p = (Proc) row.getTag();
                if (!selected.contains(p.pid)) {
                    selected.clear();
                    selected.add(p.pid);
                    adapter.notifyDataSetChanged();
                }
                menu(row);
                return true;
            }
        });
        return row;
    }

    private List<Proc> sel() {
        List<Proc> out = new ArrayList<Proc>();
        for (Proc p : procs) if (selected.contains(p.pid)) out.add(p);
        return out;
    }

    private void menu(View anchor) {
        final List<Proc> s = sel();
        if (s.isEmpty()) return;
        PopupMenu pm = new PopupMenu(c, anchor, Gravity.START);
        pm.getMenu().add(0, 1, 1, "Forcer l'arrêt");
        pm.getMenu().add(0, 2, 2, "Tuer le processus (kill -9)");
        final Pkg p0 = pkgMap().get(s.get(0).pkg);
        if (p0 != null) pm.getMenu().add(1, 3, 3, "Infos sur l'application…");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            public boolean onMenuItemClick(android.view.MenuItem it) {
                if (it.getItemId() == 1) forceStop(s);
                else if (it.getItemId() == 2) {
                    List<String> cmds = new ArrayList<String>();
                    for (Proc p : s) cmds.add("kill -9 " + p.pid);
                    run("kill -9", cmds);
                } else Dialogs.appInfo(m, p0);
                return true;
            }
        });
        pm.show();
    }

    private void run(String label, final List<String> cmds) {
        if (cmds.isEmpty() || !ctl.ready()) return;
        m.setStatus(label);
        final AdbDevice d = ctl.adb();
        ctl.submit(new Callable<Void>() {
            public Void call() {
                for (String cm : cmds) {
                    ctl.log(">> adb shell " + Html.esc(cm), "cmd");
                    AdbDevice.Result r = d.shell(cm, 20);
                    if (!r.text().isEmpty()) ctl.log(Html.esc(r.text()), r.ok() ? "out" : "err");
                }
                return null;
            }
        }, new Controller.Done<Void>() {
            public void done(Void r, Exception e) {
                h.postDelayed(new Runnable() {
                    public void run() {
                        refresh();
                    }
                }, 600);
            }
        });
    }

    void forceStop(List<Proc> list) {
        final List<String> pk = new ArrayList<String>();
        for (Proc p : list) if (!pk.contains(p.pkg)) pk.add(p.pkg);
        Collections.sort(pk);
        if (pk.isEmpty()) {
            m.toast("Sélectionne un ou plusieurs processus.");
            return;
        }
        final List<String> cmds = new ArrayList<String>();
        for (String x : pk) cmds.add("am force-stop " + Core.q(x));
        if (pk.contains("android") || pk.contains("com.android.systemui") || pk.contains("system_server")) {
            Dlg.confirm(m, "Attention", "La sélection contient un composant système critique. Forcer l'arrêt peut faire redémarrer l'interface. Continuer ?", new Dlg.Ok() {
                public void ok() {
                    run("Arrêt forcé : " + android.text.TextUtils.join(", ", pk), cmds);
                }
            });
            return;
        }
        run("Arrêt forcé : " + android.text.TextUtils.join(", ", pk), cmds);
    }
}

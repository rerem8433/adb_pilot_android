package fr.paradoxxx.adbpilot.ui;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import fr.paradoxxx.adbpilot.Config;
import fr.paradoxxx.adbpilot.Controller;
import fr.paradoxxx.adbpilot.Core;
import fr.paradoxxx.adbpilot.Html;
import fr.paradoxxx.adbpilot.MainActivity;
import fr.paradoxxx.adbpilot.adb.AdbDevice;
import fr.paradoxxx.adbpilot.adb.AdbStream;
import fr.paradoxxx.adbpilot.adb.Fastboot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

/** Console adb / fastboot, shell interactif, logcat (console_tab.py). */
public final class ConsoleTab extends Tab {
    private final TextView out;
    private final ScrollView sv;
    private final SpannableStringBuilder buf = new SpannableStringBuilder();
    private final EditText inp;
    private final TextView prefix;
    private final Ui.Btn bShell, bLogcat, bFastboot;
    private final Widgets.Combo fast;
    private String mode = "adb";
    private volatile AdbStream shell, logcat, running;
    private final List<String> history = new ArrayList<String>();
    private int hpos;
    private boolean busy;

    public ConsoleTab(MainActivity m) {
        super(m);
        LinearLayout lay = Ui.col(c);
        lay.setPadding(dp(6), dp(6), dp(6), dp(6));
        view = lay;
        LinearLayout top = Ui.row(c);
        top.setGravity(Gravity.TOP);
        sv = new ScrollView(c);
        sv.setBackgroundColor(Ui.CONSOLE);
        out = Ui.text(c, "", 10, 0xffe8e8e8);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextIsSelectable(true);
        out.setPadding(dp(8), dp(8), dp(8), dp(8));
        out.setLineSpacing(0, 1.1f);
        sv.addView(out);
        top.addView(sv, Ui.lpw(0, Ui.MATCH, 1));
        LinearLayout side = Ui.col(c);
        side.setPadding(dp(6), 0, 0, 0);
        bLogcat = Ui.button(c, "Logcat", "btn").checkable();
        bShell = Ui.button(c, "Shell", "btn").checkable();
        Ui.Btn[] bs = {Ui.button(c, "Copier\nle journal", "btn"), Ui.button(c, "Effacer\nla console", "btn"), bLogcat, bShell,
                Ui.button(c, "Partager\nle journal", "btn"), Ui.button(c, "Ctrl+C\n(stop)", "btn")};
        LinearLayout r = null;
        for (int i = 0; i < bs.length; i++) {
            if (i % 2 == 0) {
                r = Ui.row(c);
                side.addView(r);
            }
            bs[i].setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Ui.pt(8));
            bs[i].setPadding(dp(2), dp(2), dp(2), dp(2));
            r.addView(bs[i], Ui.lp(dp(84), dp(48)));
        }
        bs[0].setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("journal", buf.toString()));
                ConsoleTab.this.m.toast("Journal copié.", 2000);
            }
        });
        bs[1].setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                buf.clear();
                out.setText("");
            }
        });
        bLogcat.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleLogcat(bLogcat.isChecked());
            }
        });
        bShell.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                toggleShell(bShell.isChecked());
            }
        });
        bs[4].setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                share();
            }
        });
        bs[5].setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                interrupt();
            }
        });
        top.addView(side, Ui.lp(Ui.WRAP, Ui.MATCH));
        lay.addView(top, Ui.lpw(Ui.MATCH, 0, 1));

        LinearLayout row = Ui.row(c);
        row.setPadding(0, dp(6), 0, 0);
        prefix = Ui.bold(Ui.text(c, "adb:>", 10, Ui.TEXT));
        prefix.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        row.addView(prefix);
        inp = Ui.edit(c, "");
        inp.setTypeface(Typeface.MONOSPACE);
        inp.setBackground(Ui.box(0, 0, 0, 0));
        inp.setImeOptions(EditorInfo.IME_ACTION_SEND);
        inp.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            public boolean onEditorAction(TextView v, int id, KeyEvent e) {
                enter();
                return true;
            }
        });
        inp.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN || history.isEmpty()) return false;
                if (code == KeyEvent.KEYCODE_DPAD_UP) {
                    hpos = Math.max(0, hpos - 1);
                    inp.setText(history.get(hpos));
                    inp.setSelection(inp.length());
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                    hpos = Math.min(history.size(), hpos + 1);
                    inp.setText(hpos < history.size() ? history.get(hpos) : "");
                    return true;
                }
                return false;
            }
        });
        LinearLayout il = Ui.row(c);
        il.setBackground(Ui.box(0, 0, 0, 0));
        il.addView(inp, Ui.lpw(0, dp(40), 1));
        View line = new View(c);
        line.setBackgroundColor(Ui.BORDER);
        LinearLayout ic = Ui.col(c);
        ic.addView(il);
        ic.addView(line, Ui.lp(Ui.MATCH, 1));
        row.addView(ic, Ui.lpw(0, Ui.WRAP, 1));
        TextView x = Ui.text(c, "x", 10, Ui.TEXT);
        x.setPadding(dp(10), dp(6), dp(10), dp(6));
        x.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                inp.setText("");
            }
        });
        row.addView(x);
        Ui.Btn ent = Ui.button(c, "ENTRÉE", "btn");
        ent.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                enter();
            }
        });
        row.addView(ent, Ui.lp(dp(110), dp(42)));
        lay.addView(row);

        LinearLayout bot = Ui.row(c);
        bot.setPadding(0, dp(6), 0, 0);
        bFastboot = Ui.button(c, "fastboot", "btn").checkable();
        bFastboot.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, Ui.pt(8));
        bFastboot.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                fastbootToggled(bFastboot.isChecked());
            }
        });
        bot.addView(bFastboot, Ui.lp(Ui.WRAP, dp(30)));
        bot.addView(Ui.stretch(c));
        bot.addView(Ui.text(c, "Commandes rapides ", 10, Ui.TEXT));
        Ui.Btn add = Ui.button(c, "+", "btn");
        add.setPadding(0, 0, 0, 0);
        add.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String t = inp.getText().toString().trim();
                if (t.isEmpty()) return;
                List<String> l = Config.getList("fast_commands");
                if (!l.contains(t)) {
                    l.add(t);
                    Config.setList("fast_commands", l);
                    fillFast();
                }
            }
        });
        Ui.Btn del = Ui.button(c, "−", "btn");
        del.setPadding(0, 0, 0, 0);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                List<String> l = Config.getList("fast_commands");
                l.remove(fast.value());
                Config.setList("fast_commands", l);
                fillFast();
            }
        });
        bot.addView(add, Ui.lp(dp(32), dp(30)));
        LinearLayout.LayoutParams dl = Ui.lp(dp(32), dp(30));
        dl.leftMargin = dp(4);
        dl.rightMargin = dp(6);
        bot.addView(del, dl);
        fast = new Widgets.Combo(c);
        fast.onSelect(new Widgets.Combo.OnSelect() {
            public void selected(int i, String t) {
                inp.setText(t);
                inp.setSelection(inp.length());
                inp.requestFocus();
            }
        });
        fillFast();
        bot.addView(fast, Ui.lp(dp(320), dp(34)));
        lay.addView(bot);
        banner();
    }

    private void fillFast() {
        fast.items(Config.getList("fast_commands"));
        fast.select(-1);
    }

    private void banner() {
        append("### " + new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT).format(new Date()), "cmd");
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        append("### " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.HARDWARE + ") / RAM " + Math.round(mi.totalMem / 1073741824.0) + " Go", "cmd");
        append("### Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ") " + Build.SUPPORTED_ABIS[0], "cmd");
        append("### ADB Pilot " + MainActivity.VERSION + " · adb : protocole intégré (Java), scrcpy-server 3.3.4", "cmd");
        append("", "out");
    }

    // ------------------------------------------------------------------ sortie
    static int color(String level) {
        if ("cmd".equals(level)) return 0xffa9a9ad;
        if ("ok".equals(level)) return 0xff4cd964;
        if ("err".equals(level)) return 0xffff5c5c;
        if ("info".equals(level)) return 0xff7fb8ff;
        if ("logcat".equals(level)) return 0xffc8c8c8;
        return 0xffe8e8e8;
    }

    /** text : HTML échappé (comme la version PC). */
    public void append(String html, String level) {
        String t = html.replace("<br>", "\n").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&").replace("&nbsp;", " ");
        t = t.replaceAll("<[^>]+>", "");
        if ("cmd".equals(level) && t.startsWith(">>")) t = "\n" + t;
        boolean atEnd = sv.getScrollY() + sv.getHeight() >= out.getHeight() - dp(40);
        int start = buf.length();
        buf.append(t).append('\n');
        buf.setSpan(new ForegroundColorSpan(color(level)), start, buf.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (buf.length() > 400000) buf.delete(0, buf.length() - 300000);
        out.setText(buf);
        if (atEnd) sv.post(new Runnable() {
            public void run() {
                sv.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    private void post(final String s, final String level) {
        ctl.ui(new Runnable() {
            public void run() {
                append(Html.esc(s), level);
            }
        });
    }

    private void share() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, buf.toString());
        c.startActivity(Intent.createChooser(i, "Partager le journal"));
    }

    public void devicesChanged() {
        Controller.Dev d = ctl.current;
        if (shell != null && (d == null || !"device".equals(d.state))) {
            bShell.setChecked(false);
            toggleShell(false);
        }
    }

    @Override
    public void onDevice(Controller.Dev d) {
        if (d != null && "device".equals(d.state) && Config.getBool("auto_shell", false)) {
            m.showTab(this);
            if (!bShell.isChecked()) {
                bShell.setChecked(true);
                toggleShell(true);
            }
        }
    }

    // ------------------------------------------------------------------ saisie
    private void enter() {
        final String t = inp.getText().toString().trim();
        inp.setText("");
        if (!t.isEmpty() && (history.isEmpty() || !history.get(history.size() - 1).equals(t))) history.add(t);
        hpos = history.size();
        if ("shell".equals(mode)) {
            final AdbStream s = shell;
            if (s != null) {
                append("$ " + Html.esc(t), "cmd");
                ctl.run(new Runnable() {
                    public void run() {
                        try {
                            s.write((t + "\n").getBytes("UTF-8"));
                        } catch (Exception e) {
                            post("Shell : " + e.getMessage(), "err");
                        }
                    }
                });
            }
            return;
        }
        if (t.isEmpty()) return;
        if (busy) {
            append("Une commande est en cours (Ctrl+C pour l'arrêter).", "err");
            return;
        }
        final List<String[]> cmds = split(t);
        busy = true;
        ctl.run(new Runnable() {
            public void run() {
                boolean prevOk = true;
                try {
                    for (String[] cs : cmds) {
                        if ("&&".equals(cs[1]) && !prevOk) {
                            post("Commande suivante annulée (la précédente a échoué).", "err");
                            break;
                        }
                        prevOk = runOne(cs[0]);
                    }
                } finally {
                    ctl.ui(new Runnable() {
                        public void run() {
                            busy = false;
                        }
                    });
                }
            }
        });
    }

    /** Découpe « adb x && adb y ; fastboot z » (split_host_commands). */
    static List<String[]> split(String text) {
        List<String[]> out = new ArrayList<String[]>();
        StringBuilder b = new StringBuilder();
        String sep = null;
        char quote = 0;
        int i = 0, n = text.length();
        while (i < n) {
            char ch = text.charAt(i);
            if (quote != 0) {
                b.append(ch);
                if (ch == quote) quote = 0;
                i++;
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                b.append(ch);
                i++;
                continue;
            }
            boolean cut = false;
            for (String tok : new String[]{"&&", ";", "\n"}) {
                if (text.startsWith(tok, i)) {
                    String rest = text.substring(i + tok.length()).replaceAll("^[ \t]+", "");
                    if (rest.matches("(?s)^(adb|fastboot)\\b.*") || (tok.equals("\n") && !rest.trim().isEmpty())) {
                        if (b.toString().trim().length() > 0) out.add(new String[]{b.toString().trim(), sep});
                        b.setLength(0);
                        sep = tok.equals("\n") ? ";" : tok;
                        i += tok.length();
                        cut = true;
                        break;
                    }
                }
            }
            if (!cut) {
                b.append(ch);
                i++;
            }
        }
        if (b.toString().trim().length() > 0) out.add(new String[]{b.toString().trim(), sep});
        return out;
    }

    static List<String> args(String s) {
        List<String> out = new ArrayList<String>();
        Matcher m = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)").matcher(s);
        while (m.find()) out.add(m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3));
        return out;
    }

    /** Exécute une commande ; true si succès. */
    private boolean runOne(String raw) {
        String prog = "fastboot".equals(mode) ? "fastboot" : "adb";
        Matcher pm = Pattern.compile("^(adb|fastboot)\\b\\s*").matcher(raw);
        if (pm.find()) {
            prog = pm.group(1);
            raw = raw.substring(pm.end());
        }
        if ("fastboot".equals(prog)) return fastboot(raw);
        post(">> adb " + raw, "cmd");
        raw = raw.replaceAll("^-s\\s+\\S+\\s*", "");
        Matcher ms = Pattern.compile("^(shell|exec-out)\\b\\s*").matcher(raw);
        AdbDevice d = ctl.adb();
        if (ms.find()) {
            String remote = raw.substring(ms.end()).trim();
            if (remote.length() >= 2 && (remote.startsWith("\"") && remote.endsWith("\"") || remote.startsWith("'") && remote.endsWith("'")))
                remote = remote.substring(1, remote.length() - 1);
            if (remote.isEmpty()) {
                ctl.ui(new Runnable() {
                    public void run() {
                        bShell.setChecked(true);
                        toggleShell(true);
                    }
                });
                return true;
            }
            if (d == null) return noDevice();
            return stream((ms.group(1).equals("shell") ? "shell:" : "exec:") + remote, "out");
        }
        List<String> a = args(raw);
        if (a.isEmpty()) return true;
        String cmd = a.get(0);
        try {
            if (cmd.equals("devices")) {
                post("List of devices attached", "out");
                synchronized (ctl.devices) {
                    for (Controller.Dev x : ctl.devices)
                        post(x.serial + "\t" + x.state + (a.contains("-l") ? " model:" + x.model.replace(' ', '_') + (x.usb ? " usb" : " tcp") : ""), "out");
                }
                return true;
            }
            if (cmd.equals("version")) {
                post("ADB Pilot " + MainActivity.VERSION + " — protocole ADB intégré (A_VERSION 0x01000001)", "out");
                return true;
            }
            if (cmd.equals("connect")) {
                if (a.size() < 2) return err("usage : adb connect IP[:port]");
                ctl.connectIp(a.get(1), false);
                return true;
            }
            if (cmd.equals("disconnect")) {
                if (a.size() < 2) ctl.disconnectAllNetwork();
                else synchronized (ctl.devices) {
                    for (Controller.Dev x : new ArrayList<Controller.Dev>(ctl.devices))
                        if (x.serial.equals(a.get(1)) || x.serial.equals(a.get(1) + ":5555")) ctl.disconnect(x);
                }
                return true;
            }
            if (cmd.equals("kill-server") || cmd.equals("start-server")) {
                post("Pas de serveur adb sur la tablette : ADB Pilot parle directement aux appareils.", "info");
                return true;
            }
            if (cmd.equals("pair") || cmd.equals("mdns")) return err("débogage sans fil (appairage) non pris en charge dans cette version");
            if (d == null) return noDevice();
            if (cmd.equals("get-state")) {
                post(ctl.current.state, "out");
                return true;
            }
            if (cmd.equals("get-serialno")) {
                post(ctl.current.serial, "out");
                return true;
            }
            if (cmd.equals("reboot")) {
                d.reboot(a.size() > 1 ? a.get(1) : "");
                return true;
            }
            if (cmd.equals("reboot-bootloader")) {
                d.reboot("bootloader");
                return true;
            }
            if (cmd.equals("tcpip")) {
                AdbDevice.Result r = d.tcpip(a.size() > 1 ? Integer.parseInt(a.get(1)) : 5555);
                post(r.text(), r.ok() ? "out" : "err");
                return r.ok();
            }
            if (cmd.equals("usb") || cmd.equals("root") || cmd.equals("unroot") || cmd.equals("remount")
                    || cmd.equals("disable-verity") || cmd.equals("enable-verity")) {
                return stream(cmd + ":", "out");
            }
            if (cmd.equals("logcat")) {
                StringBuilder sb = new StringBuilder("logcat");
                for (int i = 1; i < a.size(); i++) sb.append(' ').append(Core.q(a.get(i)));
                return stream("shell:" + sb, "logcat");
            }
            if (cmd.equals("uninstall")) {
                StringBuilder sb = new StringBuilder("pm uninstall");
                for (int i = 1; i < a.size(); i++) sb.append(' ').append(Core.q(a.get(i)));
                return result(d.shell(sb.toString(), 60));
            }
            if (cmd.equals("install") || cmd.equals("install-multiple")) {
                List<String> flags = new ArrayList<String>();
                List<File> files = new ArrayList<File>();
                for (int i = 1; i < a.size(); i++) {
                    if (a.get(i).startsWith("-")) flags.add(a.get(i));
                    else files.add(local(a.get(i)));
                }
                for (File f : files) if (!f.isFile()) return err("fichier introuvable sur la tablette : " + f);
                if (files.isEmpty()) return err("usage : adb install [-r] [-g] [-d] FICHIER.apk");
                AdbDevice.Result r = files.size() == 1 && !files.get(0).getName().toLowerCase().endsWith(".apk")
                        ? Core.installFile(d, files.get(0), null, null) : d.install(files, flags, null);
                return result(r);
            }
            if (cmd.equals("push")) {
                if (a.size() < 3) return err("usage : adb push LOCAL DISTANT");
                File f = local(a.get(1));
                if (!f.isFile()) return err("fichier introuvable sur la tablette : " + f);
                String rem = a.get(2);
                if (rem.endsWith("/")) rem += f.getName();
                d.push(f, rem, null);
                post(f.getName() + " : 1 file pushed (" + f.length() + " bytes)", "ok");
                return true;
            }
            if (cmd.equals("pull")) {
                if (a.size() < 2) return err("usage : adb pull DISTANT [LOCAL]");
                String rem = a.get(1);
                File dst = a.size() > 2 ? local(a.get(2)) : new File(Config.userDir(), rem.substring(rem.lastIndexOf('/') + 1));
                if (dst.isDirectory()) dst = new File(dst, rem.substring(rem.lastIndexOf('/') + 1));
                FileOutputStream o = new FileOutputStream(dst);
                try {
                    d.pull(rem, o, null);
                } finally {
                    o.close();
                }
                post(rem + " : 1 file pulled → " + dst.getAbsolutePath(), "ok");
                return true;
            }
            return err("commande adb non prise en charge ici : " + cmd + " (essaie « adb shell … »)");
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        }
    }

    private File local(String p) {
        if (p.startsWith("/")) return new File(p);
        return new File(Config.userDir(), p);
    }

    private boolean result(AdbDevice.Result r) {
        if (!r.text().isEmpty()) post(r.text(), r.ok() ? "out" : "err");
        if (!r.ok()) post("(code de sortie " + r.rc + ")", "err");
        return r.ok();
    }

    private boolean err(String s) {
        post("[Erreur] : " + s, "err");
        return false;
    }

    private boolean noDevice() {
        return err("aucun appareil connecté");
    }

    /** Service en continu (shell:, exec:, logcat…) jusqu'à la fin ou Ctrl+C. */
    private boolean stream(String svc, String level) {
        AdbDevice d = ctl.adb();
        if (d == null) return noDevice();
        try {
            AdbStream s = d.openService(svc);
            running = s;
            byte[] b = new byte[16384];
            int n;
            StringBuilder pending = new StringBuilder();
            long last = System.currentTimeMillis();
            while ((n = s.read(b, 0, b.length)) >= 0) {
                pending.append(clean(new String(b, 0, n, "UTF-8")));
                long now = System.currentTimeMillis();
                if (now - last > 150 || pending.length() > 8000) {
                    flush(pending, level);
                    last = now;
                }
            }
            flush(pending, level);
            s.close();
            return true;
        } catch (Exception e) {
            return err(String.valueOf(e.getMessage()));
        } finally {
            running = null;
        }
    }

    private void flush(StringBuilder p, String level) {
        if (p.length() == 0) return;
        String s = p.toString();
        p.setLength(0);
        while (s.endsWith("\n")) s = s.substring(0, s.length() - 1);
        post(s, level);
    }

    static String clean(String s) {
        return s.replace("\r\n", "\n").replace("\r", "").replaceAll("\u001B\\[[0-9;?]*[A-Za-z]", "").replaceAll("\u001B\\][^\u0007]*\u0007", "");
    }

    private void interrupt() {
        if ("shell".equals(mode) && shell != null) {
            try {
                shell.write(new byte[]{3});
                append("^C", "info");
            } catch (Exception ignored) {
            }
        }
        AdbStream r = running;
        if (r != null) {
            r.close();
            append("Commande interrompue.", "info");
        }
    }

    // ------------------------------------------------------------------ shell interactif
    private void toggleShell(boolean on) {
        if (on) {
            if (!ctl.ready()) {
                append("[Erreur] : aucun appareil connecté", "err");
                bShell.setChecked(false);
                return;
            }
            bFastboot.setChecked(false);
            final AdbDevice d = ctl.adb();
            mode = "shell";
            prefix.setText("shell:>");
            append(">> adb shell  (session interactive : tape tes commandes, « exit » ou bouton Shell pour quitter)", "cmd");
            ctl.run(new Runnable() {
                public void run() {
                    try {
                        final AdbStream s = d.openShell();
                        shell = s;
                        s.write("echo \"$(getprop ro.product.model) | Android $(getprop ro.build.version.release) | $(id -un 2>/dev/null || whoami)\"\n".getBytes("UTF-8"));
                        byte[] b = new byte[16384];
                        int n;
                        while ((n = s.read(b, 0, b.length)) >= 0) {
                            String t = clean(new String(b, 0, n, "UTF-8"));
                            while (t.endsWith("\n")) t = t.substring(0, t.length() - 1);
                            if (!t.isEmpty()) post(t, "out");
                        }
                    } catch (Exception e) {
                        post("Shell : " + e.getMessage(), "err");
                    }
                    ctl.ui(new Runnable() {
                        public void run() {
                            if (shell != null) {
                                append("La session shell s'est terminée.", "info");
                                shell = null;
                                bShell.setChecked(false);
                                mode = bFastboot.isChecked() ? "fastboot" : "adb";
                                prefix.setText(mode + ":>");
                            }
                        }
                    });
                }
            });
            inp.requestFocus();
        } else {
            AdbStream s = shell;
            shell = null;
            if (s != null) s.close();
            mode = bFastboot.isChecked() ? "fastboot" : "adb";
            prefix.setText(mode + ":>");
            append("Shell fermé.", "info");
        }
    }

    private void fastbootToggled(boolean on) {
        if (on && bShell.isChecked()) {
            bShell.setChecked(false);
            toggleShell(false);
        }
        mode = on ? "fastboot" : (bShell.isChecked() ? "shell" : "adb");
        prefix.setText(mode + ":>");
        if (on && ctl.fastbootDevice() == null)
            append("Aucun appareil fastboot détecté : branche-le en USB OTG (mode bootloader) et accepte l'accès USB.", "info");
    }

    // ------------------------------------------------------------------ logcat
    private void toggleLogcat(boolean on) {
        if (on) {
            if (!ctl.ready()) {
                append("[Erreur] : aucun appareil connecté", "err");
                bLogcat.setChecked(false);
                return;
            }
            final AdbDevice d = ctl.adb();
            append(">> adb logcat (bouton Logcat pour arrêter)", "cmd");
            ctl.run(new Runnable() {
                public void run() {
                    try {
                        AdbStream s = d.openService("shell:logcat -v time -T 200");
                        logcat = s;
                        byte[] b = new byte[16384];
                        int n;
                        StringBuilder p = new StringBuilder();
                        long last = 0;
                        while ((n = s.read(b, 0, b.length)) >= 0) {
                            p.append(clean(new String(b, 0, n, "UTF-8")));
                            if (System.currentTimeMillis() - last > 200) {
                                flush(p, "logcat");
                                last = System.currentTimeMillis();
                            }
                        }
                        flush(p, "logcat");
                    } catch (Exception e) {
                        post("Logcat : " + e.getMessage(), "err");
                    }
                }
            });
        } else if (logcat != null) {
            logcat.close();
            logcat = null;
            append("Logcat arrêté.", "info");
        }
    }

    // ------------------------------------------------------------------ fastboot
    private boolean fastboot(String raw) {
        post(">> fastboot " + raw, "cmd");
        if (!bFastboot.isChecked()) ctl.ui(new Runnable() {
            public void run() {
                bFastboot.setChecked(true);
                fastbootToggled(true);
            }
        });
        List<String> a = args(raw);
        Controller.Dev dev = ctl.fastbootDevice();
        if (a.isEmpty()) return true;
        if (a.get(0).equals("devices")) {
            if (dev != null) post(dev.serial + "\tfastboot", "out");
            return true;
        }
        if (dev == null) return err("aucun appareil fastboot (USB OTG, mode bootloader)");
        Fastboot fb = dev.fastboot;
        Fastboot.Out o = new Fastboot.Out() {
            public void line(String s, boolean error) {
                post(s, error ? "err" : "out");
            }
        };
        try {
            String c0 = a.get(0);
            String v;
            if (c0.equals("getvar") && a.size() > 1) {
                v = fb.command("getvar:" + a.get(1), o);
                post(a.get(1) + ": " + v, "out");
            } else if (c0.equals("reboot")) {
                String t = a.size() > 1 ? a.get(1) : "";
                fb.command(t.isEmpty() ? "reboot" : "reboot-" + t, o);
                post("Rebooting" + (t.isEmpty() ? "" : " into " + t), "out");
            } else if (c0.equals("reboot-bootloader")) {
                fb.command("reboot-bootloader", o);
            } else if (c0.equals("continue")) {
                fb.command("continue", o);
            } else if (c0.equals("erase") && a.size() > 1) {
                fb.command("erase:" + a.get(1), o);
            } else if (c0.equals("set_active") && a.size() > 1) {
                fb.command("set_active:" + a.get(1), o);
            } else if ((c0.equals("oem") || c0.equals("flashing")) && a.size() > 1) {
                StringBuilder sb = new StringBuilder(c0);
                for (int i = 1; i < a.size(); i++) sb.append(' ').append(a.get(i));
                fb.command(sb.toString(), o);
            } else if (c0.equals("flash") && a.size() > 2) {
                File f = local(a.get(2));
                if (!f.isFile()) return err("fichier introuvable sur la tablette : " + f);
                post("Sending '" + a.get(1) + "' (" + (f.length() / 1024) + " KB)", "out");
                FileInputStream in = new FileInputStream(f);
                try {
                    fb.download(in, f.length(), o, null);
                } finally {
                    in.close();
                }
                post("Writing '" + a.get(1) + "'", "out");
                fb.command("flash:" + a.get(1), o);
            } else {
                return err("commande fastboot non prise en charge : " + raw);
            }
            post("OKAY", "ok");
            return true;
        } catch (Exception e) {
            return err("FAILED (" + e.getMessage() + ")");
        }
    }

    public void shutdown() {
        if (shell != null) shell.close();
        if (logcat != null) logcat.close();
        if (running != null) running.close();
    }
}

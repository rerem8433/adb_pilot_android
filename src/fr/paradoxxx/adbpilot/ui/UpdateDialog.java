package fr.paradoxxx.adbpilot.ui;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import fr.paradoxxx.adbpilot.Config;
import fr.paradoxxx.adbpilot.Controller;
import fr.paradoxxx.adbpilot.MainActivity;
import fr.paradoxxx.adbpilot.Updater;

import java.io.File;
import java.util.Locale;
import java.util.concurrent.Callable;

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

/** Fenêtre « Mise à jour disponible » (update_dialog.py). */
public final class UpdateDialog {

    /** Vérifie GitHub ; manual = message aussi quand tout est à jour ou en erreur. */
    public static void check(final MainActivity m, final boolean manual) {
        m.ctl.submit(new Callable<Updater.Release>() {
            public Updater.Release call() throws Exception {
                return Updater.check();
            }
        }, new Controller.Done<Updater.Release>() {
            public void done(Updater.Release r, Exception e) {
                if (e != null) {
                    if (manual) m.toast("Impossible de vérifier les mises à jour : " + e.getMessage());
                    return;
                }
                if (r == null) {
                    if (manual) m.toast("ADB Pilot est à jour (version " + MainActivity.VERSION + ").");
                    return;
                }
                if (!manual && r.version.equals(Config.getStr("update_skip", ""))) return;
                show(m, r);
            }
        });
    }

    static void show(final MainActivity m, final Updater.Release rel) {
        LinearLayout body = Ui.col(m);
        LinearLayout top = Ui.row(m);
        ImageView logo = new ImageView(m);
        logo.setImageBitmap(Ui.iconBitmap(m, "logo"));
        top.addView(logo, Ui.lp(dp(48), dp(48)));
        TextView t = Ui.html(m, "<b><big>ADB Pilot " + rel.version + " est disponible</big></b><br>"
                + "<font color='#a9a9ad'>Version installée : " + MainActivity.VERSION + "</font>", 10, Ui.TEXT);
        t.setPadding(dp(12), 0, 0, 0);
        top.addView(t, Ui.lpw(0, Ui.WRAP, 1));
        body.addView(top);
        TextView notes = Ui.text(m, rel.notes.isEmpty() ? "(pas de notes de version)" : rel.notes.replace("**", "").replace("## ", "").replace("# ", ""), 9, Ui.TEXT);
        notes.setTextIsSelectable(true);
        notes.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout nb = Ui.col(m);
        nb.setBackground(Ui.box(Ui.INPUT, Ui.BORDER, 0, 1));
        nb.addView(notes);
        LinearLayout.LayoutParams nl = Ui.lp(Ui.MATCH, Ui.WRAP);
        nl.topMargin = dp(10);
        body.addView(Dlg.scroll(nb, 280), nl);
        final TextView state = Ui.text(m, rel.assetUrl == null ? "Aucun APK dans cette release : ouvre la page de téléchargement."
                : String.format(Locale.ROOT, "Fichier : %s (%.1f Mo)", rel.assetName, rel.assetSize / 1048576.0), 9, Ui.MUTED);
        state.setPadding(0, dp(8), 0, dp(4));
        body.addView(state);
        final Widgets.Bar bar = new Widgets.Bar(m).style(Ui.ACCENT_LINE, Ui.INPUT, 0, true, false);
        bar.setVisibility(View.GONE);
        body.addView(bar, Ui.lp(Ui.MATCH, dp(8)));
        final Dialog d = Dlg.make(m, null, body, 600);
        d.setCancelable(false);
        LinearLayout b = Dlg.buttons(m);
        final Ui.Btn skip = Dlg.addButton(b, "Ignorer cette version", "btn", null, null);
        b.addView(Ui.stretch(m));
        final Ui.Btn later = Dlg.addButton(b, "Plus tard", "btn", null, null);
        final Ui.Btn go = Dlg.addButton(b, rel.assetUrl == null ? "Ouvrir la page" : "Télécharger et installer", "accent", null, null);
        body.addView(b);
        skip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Config.setStr("update_skip", rel.version);
                d.dismiss();
            }
        });
        later.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                d.dismiss();
            }
        });
        go.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (rel.assetUrl == null) {
                    m.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(rel.url)));
                    d.dismiss();
                    return;
                }
                if (Build.VERSION.SDK_INT >= 26 && !m.getPackageManager().canRequestPackageInstalls()) {
                    m.toast("Autorise « Installer des applis inconnues » pour ADB Pilot, puis touche à nouveau Télécharger.", 8000);
                    m.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + m.getPackageName())));
                    return;
                }
                skip.setEnabled(false);
                later.setEnabled(false);
                go.setEnabled(false);
                bar.setVisibility(View.VISIBLE);
                bar.setIndeterminate(true);
                state.setText("Téléchargement…");
                m.ctl.submit(new Callable<File>() {
                    public File call() throws Exception {
                        File f = Updater.download(rel, new Updater.Prog() {
                            long last;

                            public void on(final long n, final long tot) {
                                long now = System.currentTimeMillis();
                                if (now - last < 200) return;
                                last = now;
                                m.ctl.ui(new Runnable() {
                                    public void run() {
                                        if (tot > 0) {
                                            bar.setMax(tot);
                                            bar.setValue(n);
                                        }
                                        state.setText(String.format(Locale.ROOT, "Téléchargement… %.1f / %.1f Mo", n / 1048576.0, tot / 1048576.0));
                                    }
                                });
                            }
                        });
                        m.ctl.ui(new Runnable() {
                            public void run() {
                                bar.setIndeterminate(true);
                                state.setText("Installation… (confirme sur l'écran)");
                            }
                        });
                        Updater.install(m, f);
                        return f;
                    }
                }, new Controller.Done<File>() {
                    public void done(File f, Exception e) {
                        if (e != null) {
                            bar.setVisibility(View.GONE);
                            state.setText("Échec : " + e.getMessage());
                            skip.setEnabled(true);
                            later.setEnabled(true);
                            go.setEnabled(true);
                            return;
                        }
                        d.dismiss();
                    }
                });
            }
        });
        d.show();
    }
}

package fr.paradoxxx.adbpilot.ui;

import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import fr.paradoxxx.adbpilot.Config;
import fr.paradoxxx.adbpilot.MainActivity;
import fr.paradoxxx.adbpilot.MetaCache;
import fr.paradoxxx.adbpilot.Pkg;

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

/** Paramètres (settings_tab.py). */
public final class SettingsTab extends Tab {
    private TextView keyLbl;

    public SettingsTab(MainActivity m) {
        super(m);
        ScrollView sc = new ScrollView(c);
        LinearLayout body = Ui.row(c);
        body.setGravity(Gravity.TOP);
        body.setPadding(dp(12), dp(4), dp(12), dp(12));
        sc.addView(body);
        view = sc;
        LinearLayout c1 = Ui.col(c), c2 = Ui.col(c);
        body.addView(c1, Ui.lpw(0, Ui.WRAP, 1));
        body.addView(Ui.space(c, 12, 1));
        body.addView(c2, Ui.lpw(0, Ui.WRAP, 1));

        LinearLayout g = Ui.group(c, "Outils", c1);
        g.addView(Ui.html(c, "adb : <font color='#2fd06b'>protocole ADB intégré</font> (Wi-Fi et USB OTG, sans serveur adb)<br>"
                + "scrcpy : <font color='#2fd06b'>serveur v3.3.4 intégré</font> (décodage matériel de la tablette)<br>"
                + "fastboot : USB OTG uniquement", 9, Ui.TEXT));
        keyLbl = Ui.text(c, "", 8, Ui.MUTED);
        keyLbl.setPadding(0, dp(6), 0, 0);
        g.addView(keyLbl);

        g = Ui.group(c, "Liste des applications", c1);
        check(g, "Charger les noms et icônes (lecture des APK sur l'appareil)", "load_meta", true);
        LinearLayout r = Ui.row(c);
        r.addView(Ui.text(c, "Langue des noms : ", 10, Ui.TEXT));
        final String[] codes = {"fr", "en", ""};
        Widgets.Combo lang = new Widgets.Combo(c).items("Français", "Anglais", "Par défaut de l'APK");
        String cur = Config.getStr("label_lang", "fr");
        lang.select(cur.equals("en") ? 1 : cur.isEmpty() ? 2 : 0);
        lang.onSelect(new Widgets.Combo.OnSelect() {
            public void selected(int i, String t) {
                Config.setStr("label_lang", codes[i]);
            }
        });
        r.addView(lang, Ui.lpw(0, dp(36), 1));
        g.addView(r);
        Ui.Btn clr = Ui.button(c, "Vider le cache des noms/icônes", "btn");
        clr.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                MetaCache.clear();
                for (Pkg p : ctl.packages) {
                    p.label = null;
                    p.icon = null;
                    p.metaDone = false;
                }
                ctl.refreshPackages();
                SettingsTab.this.m.setStatus("Cache vidé, relecture des noms et icônes…");
            }
        });
        LinearLayout.LayoutParams cl = Ui.lp(Ui.MATCH, dp(40));
        cl.topMargin = dp(6);
        g.addView(clr, cl);

        g = Ui.group(c, "Installation / désinstallation", c1);
        check(g, "Accorder toutes les permissions à l'installation (-g)", "install_grant", true);
        check(g, "Remplacer si déjà installée (-r)", "install_replace", true);
        check(g, "Autoriser le retour à une version antérieure (-d)", "install_downgrade", false);
        check(g, "Applis système : garder les données à la désinstallation (-k)", "uninstall_keep_data", true);

        g = Ui.group(c, "Connexion", c1);
        check(g, "Se reconnecter à la dernière IP au démarrage", "auto_reconnect", true);
        check(g, "Ouvrir le shell dans la console dès la connexion", "auto_shell", false);
        check(g, "Détecter : analyser aussi 192.168.0.x, 192.168.1.x et 192.168.2.x", "scan_common_nets", true);
        field(g, "Réseaux en plus :", "scan_extra_nets", "", false).setHint("ex. 10.0.0.x, 192.168.50.0/24");

        g = Ui.group(c, "scrcpy", c2);
        field(g, "Débit vidéo :", "scrcpy_bitrate", "8M", false);
        field(g, "FPS max :", "scrcpy_max_fps", "", true).setHint("illimité");
        check(g, "Sans audio", "scrcpy_no_audio", false);
        check(g, "Éteindre l'écran de l'appareil", "scrcpy_turn_screen_off", false);
        check(g, "Garder l'appareil éveillé", "scrcpy_stay_awake", true);
        check(g, "Afficher les touches", "scrcpy_show_touches", false);
        LinearLayout cr = Ui.row(c);
        cr.addView(Ui.text(c, "Codec vidéo : ", 10, Ui.TEXT), Ui.lp(dp(130), Ui.WRAP));
        final String[] cods = {"h264", "h265"};
        Widgets.Combo codec = new Widgets.Combo(c).items("H.264 (compatible)", "H.265 (moins de débit)");
        codec.select("h265".equals(Config.getStr("scrcpy_codec", "h264")) ? 1 : 0);
        codec.onSelect(new Widgets.Combo.OnSelect() {
            public void selected(int i, String t) {
                Config.setStr("scrcpy_codec", cods[i]);
            }
        });
        cr.addView(codec, Ui.lpw(0, dp(36), 1));
        g.addView(cr);

        g = Ui.group(c, "Interface", c2);
        LinearLayout lr = Ui.row(c);
        lr.addView(Ui.text(c, "Langue : ", 10, Ui.TEXT), Ui.lp(dp(130), Ui.WRAP));
        lr.addView(new Widgets.Combo(c).items("Français"), Ui.lpw(0, dp(36), 1));
        g.addView(lr);
        LinearLayout tr = Ui.row(c);
        tr.addView(Ui.text(c, "Thème : ", 10, Ui.TEXT), Ui.lp(dp(130), Ui.WRAP));
        tr.addView(new Widgets.Combo(c).items("Sombre"), Ui.lpw(0, dp(36), 1));
        tr.setPadding(0, dp(4), 0, 0);
        g.addView(tr);
        LinearLayout fr = Ui.row(c);
        fr.addView(Ui.text(c, "Taille du texte : ", 10, Ui.TEXT), Ui.lp(dp(130), Ui.WRAP));
        fr.setPadding(0, dp(4), 0, 0);
        final String[] scales = {"90", "100", "115", "130"};
        Widgets.Combo fs = new Widgets.Combo(c).items("Petite", "Normale", "Grande", "Très grande");
        int idx = java.util.Arrays.asList(scales).indexOf(String.valueOf(Config.getInt("font_scale", 100)));
        fs.select(idx < 0 ? 1 : idx);
        fs.onSelect(new Widgets.Combo.OnSelect() {
            public void selected(int i, String t) {
                Config.setInt("font_scale", Integer.parseInt(scales[i]));
                SettingsTab.this.m.toast("Taille appliquée au prochain démarrage d'ADB Pilot.", 4000);
            }
        });
        fr.addView(fs, Ui.lpw(0, dp(36), 1));
        g.addView(fr);
        field(g, "Captures d'écran :", "screens_dir", "", false).setHint(Config.userDir("images").getAbsolutePath());
        field(g, "APK à installer :", "install_dir", "", false).setHint(Config.userDir("install").getAbsolutePath());

        g = Ui.group(c, "Mises à jour", c2);
        check(g, "Vérifier les mises à jour au démarrage (GitHub)", "update_check", true);
        Ui.Btn up = Ui.button(c, "Vérifier maintenant (version " + MainActivity.VERSION + ")", "btn");
        up.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                UpdateDialog.check(SettingsTab.this.m, true);
            }
        });
        LinearLayout.LayoutParams ul = Ui.lp(Ui.MATCH, dp(40));
        ul.topMargin = dp(6);
        g.addView(up, ul);
    }

    @Override
    public void onShown() {
        keyLbl.setText("Clé ADB de la tablette (empreinte affichée par Android lors de l'autorisation) :\n" + ctl.keyFingerprint());
    }

    private Widgets.Check check(LinearLayout g, String text, final String key, boolean def) {
        Widgets.Check cb = new Widgets.Check(c, text);
        cb.setChecked(Config.getBool(key, def));
        cb.onChange(new Widgets.OnChange() {
            public void changed(boolean on) {
                Config.setBool(key, on);
            }
        });
        g.addView(cb);
        return cb;
    }

    private EditText field(LinearLayout g, String label, final String key, String def, boolean number) {
        LinearLayout r = Ui.row(c);
        r.setPadding(0, dp(3), 0, dp(3));
        r.addView(Ui.text(c, label, 10, Ui.TEXT), Ui.lp(dp(130), Ui.WRAP));
        EditText e = Ui.edit(c, "");
        if (number) e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(Config.getStr(key, def));
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int cc) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int cc) {
            }

            public void afterTextChanged(Editable ed) {
                Config.setStr(key, ed.toString().trim());
            }
        });
        r.addView(e, Ui.lpw(0, dp(36), 1));
        g.addView(r);
        return e;
    }
}

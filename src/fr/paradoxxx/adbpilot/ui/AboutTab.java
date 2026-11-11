package fr.paradoxxx.adbpilot.ui;

import android.text.method.LinkMovementMethod;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import fr.paradoxxx.adbpilot.Config;
import fr.paradoxxx.adbpilot.Html;
import fr.paradoxxx.adbpilot.MainActivity;

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

/** À propos. */
public final class AboutTab extends Tab {
    public AboutTab(MainActivity m) {
        super(m);
        ScrollView sv = new ScrollView(c);
        LinearLayout lay = Ui.col(c);
        lay.setPadding(dp(Ui.phone ? 14 : 30), dp(Ui.phone ? 14 : 24), dp(Ui.phone ? 14 : 30), dp(24));
        sv.addView(lay);
        view = sv;
        String h = "<h2>ADB Pilot " + MainActivity.VERSION + "</h2>"
                + "<p><big>Développé par <b>ParadoxXx Squad</b><br>"
                + "<a href='https://github.com/rerem8433/adb_pilot_android'>https://github.com/rerem8433/adb_pilot_android</a></big></p>"
                + "<p>Gestionnaire d'appareils Android via ADB pour Linux, Windows, macOS et maintenant Android (tablette et smartphone), natif.<br>"
                + "Version PC (Linux, Windows, macOS) : <a href='https://github.com/rerem8433/adb_pilot'>github.com/rerem8433/adb_pilot</a></p>"
                + "<p>• Liste des applications avec noms, icônes et versions, filtres, recherche<br>"
                + "• Désactiver / activer / désinstaller (utilisateur 0) / restaurer, forcer l'arrêt, effacer les données ou le cache, sauvegarder les fichiers d'installation<br>"
                + "• Bibliothèque d'APK : APK / split APK / XAPK / APKS / APKM<br>"
                + "• Assistant debloat (listes debloat0-3, protection automatique des téléphones), presets, permissions<br>"
                + "• Gestionnaire de processus, console adb / fastboot, shell, logcat<br>"
                + "• Captures, touches, scrcpy intégré (enregistrement), Wi-Fi / USB OTG</p>"
                + "<p><b>Dossiers</b> : " + Html.esc(Config.userDir().getAbsolutePath()) + "<br>"
                + "&nbsp;&nbsp;images · videos · install · apps · store · presets</p>"
                + "<p>Composants tiers : <a href='https://github.com/Genymobile/scrcpy'>scrcpy-server</a> (Apache-2.0), "
                + "descriptions <a href='https://github.com/Universal-Debloater-Alliance/universal-android-debloater-next-generation'>UAD-ng</a> (GPL-3.0).</p>"
                + "<p>Licence : GPL-3.0. Projet indépendant, sans lien avec ADB AppControl.</p>";
        TextView t = Ui.html(c, h, 10, Ui.TEXT);
        t.setMovementMethod(LinkMovementMethod.getInstance());
        lay.addView(t);
        TextView n = Ui.html(c, "⚠&nbsp; " + Dialogs.targetNoticeHtml(), 10, Ui.TEXT);
        n.setBackground(Ui.box(0x22ff9f1c, Ui.ORANGE, 6, 1));
        n.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams nl = Ui.lp(Ui.MATCH, Ui.WRAP);
        nl.topMargin = dp(10);
        lay.addView(n, nl);
        TextView w = Ui.bold(Ui.text(c, "⚠ Version Android de TEST : pas encore validée sur du matériel réel. "
                + "Le débogage sans fil d'Android 11+ (appairage par code) n'est pas encore pris en charge : utilise le port 5555.", 10, Ui.ORANGE));
        w.setPadding(0, dp(12), 0, 0);
        lay.addView(w);
    }
}

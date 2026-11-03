package fr.paradoxxx.adbpilot.ui;

import android.content.Context;
import android.view.View;

import fr.paradoxxx.adbpilot.Controller;
import fr.paradoxxx.adbpilot.MainActivity;
import fr.paradoxxx.adbpilot.Pkg;

import java.util.List;
import java.util.Map;

/** Onglet de la fenêtre principale. */
public abstract class Tab {
    protected final MainActivity m;
    protected final Controller ctl;
    protected final Context c;
    public View view;

    protected Tab(MainActivity m) {
        this.m = m;
        this.ctl = m.ctl;
        this.c = m;
    }

    public void onShown() {
    }

    public void onHidden() {
    }

    public void onDevice(Controller.Dev d) {
    }

    public void onPackages(List<Pkg> p) {
    }

    public void onMeta(Pkg p) {
    }

    public void onInfo(Map<String, String> info, Map<String, Object> specs) {
    }

    public void setSearch(String s) {
    }

    public void onLog(String html, String level) {
    }

    protected boolean need() {
        if (!ctl.ready()) {
            m.toast("Aucun appareil connecté.");
            return false;
        }
        return true;
    }
}

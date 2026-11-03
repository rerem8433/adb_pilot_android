package fr.paradoxxx.adbpilot;

import android.graphics.Bitmap;

/** Paquet installé sur l'appareil (équivalent de adbcore.Package). */
public final class Pkg {
    public String name;
    public String path = "";
    public long size, apkSize, baseSize;
    public long dataSize = -1;   // -1 = inconnu
    public boolean system, disabled, deleted;
    public String label, version;
    public Bitmap icon;
    public boolean metaDone;

    public Pkg(String name) {
        this.name = name;
    }

    public String display() {
        return label != null && !label.isEmpty() ? label : name;
    }
}

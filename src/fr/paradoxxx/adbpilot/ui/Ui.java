package fr.paradoxxx.adbpilot.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.Html;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/** Thème « sombre » de la version PC (style.py) et petits widgets réutilisables. */
public final class Ui {
    public static final int HEADER = 0xff1b1b1c, BG = 0xff2b2b2d, PANEL = 0xff323235, ROW = 0xff38383b,
            ROW_ALT = 0xff343437, SEL = 0xff5d6676, TEXT = 0xfff0f0f0, MUTED = 0xffa9a9ad, BORDER = 0xff4a4a4e,
            BTN = 0xff3a3a3e, BTN_HOVER = 0xff47474c, ACCENT = 0xff24448f, ACCENT_LINE = 0xff4a74d6,
            ACCENT_HOVER = 0xff2d55b0, TITLE = 0xff7fb8ff, INPUT = 0xff232325, TAB = 0xff3a3a3e,
            TAB_SEL = 0xfff2f2f2, CONSOLE = 0xff1e1e20, GREEN = 0xff2fd06b, RED = 0xffff5c5c, ORANGE = 0xffffb84d,
            YELLOW = 0xffe6c22e, DISABLED_TXT = 0xff8a8a8e;
    public static float density = 1;
    public static float fontScale = 1;

    public static void init(Context c) {
        density = c.getResources().getDisplayMetrics().density;
    }

    public static int dp(float v) {
        return Math.round(v * density);
    }

    /** Taille de police : points Qt -> sp (10 pt = 13 sp). */
    public static float pt(float p) {
        return p * 1.3f * fontScale;
    }

    // ------------------------------------------------------------------ fonds
    public static GradientDrawable box(int fill, int stroke, float radiusDp, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        if (stroke != 0 && strokeDp > 0) g.setStroke(Math.max(1, dp(strokeDp)), stroke);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    public static StateListDrawable states(Drawable normal, Drawable pressed, Drawable checked, Drawable disabled) {
        StateListDrawable s = new StateListDrawable();
        if (disabled != null) s.addState(new int[]{-android.R.attr.state_enabled}, disabled);
        if (pressed != null) s.addState(new int[]{android.R.attr.state_pressed}, pressed);
        if (checked != null) {
            s.addState(new int[]{android.R.attr.state_selected}, checked);
            s.addState(new int[]{android.R.attr.state_activated}, checked);
        }
        s.addState(new int[]{}, normal);
        return s;
    }

    // ------------------------------------------------------------------ textes
    public static TextView text(Context c, CharSequence s, float pt, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, pt(pt));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView html(Context c, String html, float pt, int color) {
        TextView t = text(c, fromHtml(html), pt, color);
        t.setLinkTextColor(0xff4da3ff);
        return t;
    }

    @SuppressWarnings("deprecation")
    public static CharSequence fromHtml(String h) {
        if (android.os.Build.VERSION.SDK_INT >= 24) return Html.fromHtml(h, Html.FROM_HTML_MODE_LEGACY);
        return Html.fromHtml(h);
    }

    public static TextView bold(TextView t) {
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    public static TextView single(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    // ------------------------------------------------------------------ icônes
    private static final Map<String, Bitmap> icons = new HashMap<String, Bitmap>();

    public static Bitmap iconBitmap(Context c, String name) {
        Bitmap b = icons.get(name);
        if (b == null) {
            try {
                b = BitmapFactory.decodeStream(c.getAssets().open("icons/" + name + ".png"));
            } catch (Exception e) {
                b = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
            }
            icons.put(name, b);
        }
        return b;
    }

    public static Drawable icon(Context c, String name, int color) {
        BitmapDrawable d = new BitmapDrawable(c.getResources(), iconBitmap(c, name));
        if (color != 0) d.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        return d;
    }

    public static ImageView image(Context c, String name, int color, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(icon(c, name, color));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    /** Icône « Android » de remplacement (comme style.icon("app", "#3ddc84")). */
    public static Drawable placeholder(Context c) {
        return new BitmapDrawable(c.getResources(), iconBitmap(c, "app"));
    }

    /** Pastille lettre colorée (Store). */
    public static Bitmap letterIcon(String name, int size) {
        int[] pal = {0xffe5533d, 0xff2f80ed, 0xff27ae60, 0xff9b51e0, 0xfff2994a, 0xff00a3a3, 0xffd63384, 0xff5c6bc0};
        int sum = 0;
        for (char ch : name.toCharArray()) sum += ch;
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(pal[sum % pal.length]);
        cv.drawRoundRect(new RectF(0, 0, size, size), size * 0.24f, size * 0.24f, p);
        p.setColor(Color.WHITE);
        p.setTextSize(size * 0.5f);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        cv.drawText(name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase(), size / 2f, size / 2f - (fm.ascent + fm.descent) / 2, p);
        return b;
    }

    public static Bitmap rounded(Bitmap src, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        Path path = new Path();
        path.addRoundRect(new RectF(0, 0, size, size), size * 0.22f, size * 0.22f, Path.Direction.CW);
        cv.clipPath(path);
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        cv.drawBitmap(src, null, new RectF(0, 0, size, size), p);
        return b;
    }

    // ------------------------------------------------------------------ boutons
    /** Styles : btn, accent, go, scan, tcpip, chip, card, soft, debloat, ghost, flat. */
    public static Btn button(Context c, String text, String style) {
        Btn b = new Btn(c, style);
        b.setText(text);
        return b;
    }

    public static final class Btn extends TextView {
        private boolean checkable;
        private final String style;

        public Btn(Context c, String style) {
            super(c);
            this.style = style;
            setGravity(Gravity.CENTER);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, pt(10));
            setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{MUTED, TEXT}));
            setPadding(dp(10), dp(6), dp(10), dp(6));
            setClickable(true);
            setFocusable(true);
            applyStyle();
        }

        private void applyStyle() {
            float r = 2;
            if ("accent".equals(style)) {
                setBackground(states(box(ACCENT, ACCENT_LINE, r, 1), box(ACCENT_HOVER, ACCENT_LINE, r, 1), null,
                        box(0xff3b4660, 0xff3b4660, r, 1)));
                setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{0xff9aa3b8, Color.WHITE}));
                setTypeface(Typeface.DEFAULT_BOLD);
            } else if ("go".equals(style)) {
                setBackground(states(box(ACCENT, ACCENT_LINE, 3, 1), box(ACCENT_HOVER, ACCENT_LINE, 3, 1), null, box(SEL, SEL, 3, 1)));
                setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{0xffdddddd, Color.WHITE}));
                setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
                setTextSize(TypedValue.COMPLEX_UNIT_SP, pt(11));
                setPadding(0, 0, 0, 0);
            } else if ("scan".equals(style)) {
                setBackground(states(box(0x1affffff, ACCENT_LINE, 3, 1), box(0x594a74d6, ACCENT_LINE, 3, 1), null, null));
                setTypeface(Typeface.DEFAULT_BOLD);
                setPadding(dp(10), 0, dp(10), 0);
            } else if ("tcpip".equals(style)) {
                setBackground(states(box(0x14ffffff, 0xff666666, 0, 1), box(0x2effffff, 0xff666666, 0, 1), null,
                        box(0x14ffffff, 0xff444444, 0, 1)));
                setTextSize(TypedValue.COMPLEX_UNIT_SP, pt(8));
                setPadding(dp(6), dp(3), dp(6), dp(3));
                setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{0xff8a8a8e, TEXT}));
            } else if ("chip".equals(style)) {
                setBackground(states(box(0, BORDER, 15, 1), box(BTN_HOVER, BORDER, 15, 1), box(ACCENT, ACCENT_LINE, 15, 1), null));
                setPadding(dp(14), dp(5), dp(14), dp(5));
            } else if ("card".equals(style)) {
                setBackground(states(box(ACCENT, 0, 15, 0), box(ACCENT_HOVER, 0, 15, 0), null, box(SEL, 0, 15, 0)));
                setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}}, new int[]{0xffdddddd, Color.WHITE}));
                setTypeface(Typeface.DEFAULT_BOLD);
                setPadding(dp(16), dp(6), dp(16), dp(6));
            } else if ("soft".equals(style)) {
                setBackground(states(box(0xff9db8f0, 0xff6f92dc, 0, 1), box(0xffb5caf5, 0xff6f92dc, 0, 1), null, null));
                setTextColor(0xff0e1d40);
            } else if ("debloat".equals(style)) {
                setBackground(states(box(0xff2c2c2f, BORDER, 0, 1), box(BTN_HOVER, BORDER, 0, 1), null, null));
            } else if ("ghost".equals(style)) {
                setBackground(states(box(0, BORDER, 14, 1), box(BTN_HOVER, ACCENT_LINE, 14, 1), null, null));
            } else if ("flat".equals(style)) {
                setBackground(states(box(0, 0, 4, 0), box(0x1fffffff, 0, 4, 0), null, null));
                setPadding(dp(3), dp(3), dp(3), dp(3));
            } else if ("dlg".equals(style)) {
                setBackground(states(box(BTN, 0xff5a5a60, 8, 1), box(BTN_HOVER, 0xff5a5a60, 8, 1), null, null));
            } else {
                setBackground(states(box(BTN, BORDER, r, 1), box(SEL, BORDER, r, 1), box(SEL, BORDER, r, 1), null));
            }
        }

        public Btn checkable() {
            checkable = true;
            return this;
        }

        public void setChecked(boolean on) {
            setSelected(on);
            if ("chip".equals(style)) {
                setTextColor(on ? Color.WHITE : TEXT);
                setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            }
        }

        public boolean isChecked() {
            return isSelected();
        }

        @Override
        public boolean performClick() {
            if (checkable) setChecked(!isSelected());
            return super.performClick();
        }

        public Btn icon(Drawable d, int sizeDp) {
            d.setBounds(0, 0, dp(sizeDp), dp(sizeDp));
            setCompoundDrawables(d, null, null, null);
            setCompoundDrawablePadding(dp(6));
            return this;
        }
    }

    /** Bouton icône transparent (en-tête). */
    public static ImageView iconButton(Context c, String name, int color, int sizeDp) {
        ImageView v = new ImageView(c);
        v.setImageDrawable(icon(c, name, color));
        v.setPadding(dp(4), dp(4), dp(4), dp(4));
        v.setBackground(states(box(0, 0, 4, 0), box(0x1fffffff, 0, 4, 0), null, null));
        v.setClickable(true);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp + 8), dp(sizeDp + 8)));
        return v;
    }

    // ------------------------------------------------------------------ saisie
    public static EditText edit(Context c, String hint) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, pt(10));
        e.setSingleLine(true);
        e.setBackground(box(INPUT, BORDER, 0, 1));
        e.setPadding(dp(6), dp(5), dp(6), dp(5));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        return e;
    }

    // ------------------------------------------------------------------ mise en page
    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpw(int w, int h, float weight) {
        return new LinearLayout.LayoutParams(w, h, weight);
    }

    public static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT, WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    public static View space(Context c, int wDp, int hDp) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(wDp), dp(hDp)));
        return v;
    }

    public static View stretch(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1));
        return v;
    }

    public static View vstretch(Context c) {
        View v = new View(c);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, 0, 1));
        return v;
    }

    public static FrameLayout.LayoutParams flp(int w, int h, int gravity) {
        return new FrameLayout.LayoutParams(w, h, gravity);
    }

    /** Cadre type QGroupBox : titre gris + bordure. */
    public static LinearLayout group(Context c, String title, LinearLayout parent) {
        TextView t = text(c, title, 10, MUTED);
        t.setPadding(dp(8), dp(10), 0, dp(2));
        parent.addView(t);
        LinearLayout box = col(c);
        box.setBackground(box(0, BORDER, 0, 1));
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        parent.addView(box, lp(MATCH, WRAP));
        return box;
    }
}

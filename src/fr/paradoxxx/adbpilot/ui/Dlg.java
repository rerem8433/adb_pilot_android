package fr.paradoxxx.adbpilot.ui;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

/** Boîtes de dialogue sombres (QMessageBox / QDialog de la version PC). */
public final class Dlg {
    public interface Ok {
        void ok();
    }

    public interface Text {
        void ok(String s);
    }

    public interface Pick {
        void pick(int i);
    }

    public static Dialog make(Context c, String title, View body, int widthDp) {
        final Dialog d = new Dialog(c);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = Ui.col(c);
        root.setBackground(Ui.box(Ui.BG, Ui.BORDER, 8, 1));
        root.setPadding(dp(18), dp(14), dp(18), dp(12));
        if (title != null) {
            TextView t = Ui.bold(Ui.text(c, title, 12, Ui.TEXT));
            t.setPadding(0, 0, 0, dp(10));
            root.addView(t);
        }
        root.addView(body, Ui.lp(Ui.MATCH, Ui.WRAP));
        d.setContentView(root);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.width = dp(widthDp);
            w.setAttributes(lp);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return d;
    }

    public static LinearLayout buttons(Context c) {
        LinearLayout r = Ui.row(c);
        r.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(12), 0, 0);
        return r;
    }

    public static Ui.Btn addButton(LinearLayout row, String text, String style, final Dialog d, final Ok ok) {
        Ui.Btn b = Ui.button(row.getContext(), text, style);
        b.setMinWidth(dp(100));
        LinearLayout.LayoutParams lp = Ui.lp(Ui.WRAP, dp(40));
        lp.leftMargin = dp(8);
        row.addView(b, lp);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (d != null) d.dismiss();
                if (ok != null) ok.ok();
            }
        });
        return b;
    }

    public static ScrollView scroll(View v, int maxHeightDp) {
        final int max = dp(maxHeightDp);
        ScrollView s = new ScrollView(v.getContext()) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST));
            }
        };
        s.addView(v);
        return s;
    }

    public static void message(Context c, String title, String msg) {
        LinearLayout body = Ui.col(c);
        TextView t = Ui.text(c, msg, 10, Ui.TEXT);
        t.setLineSpacing(0, 1.15f);
        body.addView(scroll(t, 420));
        Dialog d = make(c, title, body, 520);
        LinearLayout b = buttons(c);
        addButton(b, "OK", "btn", d, null);
        body.addView(b);
        d.show();
    }

    /** Question Oui / Non (QMessageBox.question). */
    public static void confirm(Context c, String title, String msg, final Ok yes) {
        LinearLayout body = Ui.col(c);
        TextView t = Ui.text(c, msg, 10, Ui.TEXT);
        t.setLineSpacing(0, 1.15f);
        body.addView(scroll(t, 420));
        Dialog d = make(c, title, body, 560);
        LinearLayout b = buttons(c);
        addButton(b, "Non", "btn", d, null);
        addButton(b, "Oui", "accent", d, yes);
        body.addView(b);
        d.show();
    }

    public static void input(Context c, String title, String label, String value, final Text ok) {
        LinearLayout body = Ui.col(c);
        if (label != null) {
            TextView t = Ui.text(c, label, 10, Ui.TEXT);
            t.setPadding(0, 0, 0, dp(6));
            body.addView(t);
        }
        final EditText e = Ui.edit(c, "");
        e.setText(value);
        body.addView(e, Ui.lp(Ui.MATCH, dp(40)));
        final Dialog d = make(c, title, body, 480);
        LinearLayout b = buttons(c);
        addButton(b, "Annuler", "btn", d, null);
        addButton(b, "OK", "accent", d, new Ok() {
            public void ok() {
                ok.ok(e.getText().toString().trim());
            }
        });
        body.addView(b);
        d.show();
    }

    public static void list(Context c, String title, List<String> items, final Pick pick) {
        LinearLayout body = Ui.col(c);
        final Dialog[] dd = new Dialog[1];
        LinearLayout lst = Ui.col(c);
        for (int i = 0; i < items.size(); i++) {
            final int idx = i;
            TextView t = Ui.text(c, items.get(i), 10, Ui.TEXT);
            t.setPadding(dp(10), dp(12), dp(10), dp(12));
            t.setBackground(Ui.states(Ui.box(i % 2 == 0 ? Ui.ROW : Ui.ROW_ALT, 0, 0, 0), Ui.box(Ui.SEL, 0, 0, 0), null, null));
            t.setClickable(true);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    dd[0].dismiss();
                    pick.pick(idx);
                }
            });
            lst.addView(t, Ui.lp(Ui.MATCH, Ui.WRAP));
        }
        if (items.isEmpty()) lst.addView(Ui.text(c, "(vide)", 10, Ui.MUTED));
        body.addView(scroll(lst, 420));
        dd[0] = make(c, title, body, 520);
        LinearLayout b = buttons(c);
        addButton(b, "Annuler", "btn", dd[0], null);
        body.addView(b);
        dd[0].show();
    }

    public static ViewGroup.LayoutParams wrap() {
        return new ViewGroup.LayoutParams(Ui.WRAP, Ui.WRAP);
    }
}

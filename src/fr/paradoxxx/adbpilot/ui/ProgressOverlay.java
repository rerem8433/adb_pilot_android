package fr.paradoxxx.adbpilot.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

/** Voile + carte de progression avec bouton Annuler (style A de la version PC). */
public final class ProgressOverlay extends FrameLayout {
    private static final int ROWS = 4;
    private final LinearLayout card;
    private final TextView title, sub, count, pct;
    private final Widgets.Bar bar;
    private final List<LinearLayout> rows = new ArrayList<LinearLayout>();
    private final Ui.Btn btn;
    private final Map<String, Object[]> items = new HashMap<String, Object[]>();
    private final List<String> order = new ArrayList<String>();
    private int done, total = 1, errors;
    private boolean cancelled;
    private Runnable onCancel;
    private final Handler h = new Handler(Looper.getMainLooper());
    public int topOffset = 0;
    private final Paint veil = new Paint();

    public ProgressOverlay(Context c) {
        super(c);
        setWillNotDraw(false);
        veil.setColor(0xa50a0a0c);
        setVisibility(GONE);
        setClickable(true);
        card = Ui.col(c);
        card.setBackground(Ui.box(0xff2f2f33, Ui.ACCENT_LINE, 16, 1.5f));
        card.setPadding(dp(24), dp(20), dp(24), dp(18));
        card.setElevation(dp(16));
        LinearLayout top = Ui.row(c);
        top.setGravity(Gravity.TOP);
        ImageView logo = new ImageView(c);
        logo.setImageBitmap(Ui.iconBitmap(c, "logo"));
        top.addView(logo, Ui.lp(dp(40), dp(40)));
        LinearLayout tc = Ui.col(c);
        tc.setPadding(dp(14), 0, 0, 0);
        title = Ui.bold(Ui.text(c, "", 14, Ui.TEXT));
        sub = Ui.text(c, "", 9, Ui.MUTED);
        sub.setPadding(0, dp(3), 0, 0);
        tc.addView(title);
        tc.addView(sub);
        top.addView(tc, Ui.lpw(0, Ui.WRAP, 1));
        card.addView(top);
        bar = new Widgets.Bar(c).style(Ui.ACCENT_LINE, Ui.INPUT, 0, true, true);
        LinearLayout.LayoutParams bl = Ui.lp(Ui.MATCH, dp(10));
        bl.topMargin = dp(12);
        card.addView(bar, bl);
        LinearLayout cnt = Ui.row(c);
        count = Ui.bold(Ui.text(c, "", 10, Ui.TEXT));
        pct = Ui.bold(Ui.text(c, "", 10, Ui.TEXT));
        cnt.addView(count);
        cnt.addView(Ui.stretch(c));
        cnt.addView(pct);
        cnt.setPadding(0, dp(8), 0, dp(6));
        card.addView(cnt);
        for (int i = 0; i < ROWS; i++) {
            LinearLayout r = Ui.row(c);
            TextView st = Ui.bold(Ui.text(c, "", 10, Ui.MUTED));
            st.setGravity(Gravity.CENTER);
            r.addView(st, Ui.lp(dp(16), Ui.WRAP));
            ImageView ic = new ImageView(c);
            LinearLayout.LayoutParams il = Ui.lp(dp(24), dp(24));
            il.leftMargin = dp(8);
            il.rightMargin = dp(8);
            r.addView(ic, il);
            TextView tx = Ui.single(Ui.text(c, "", 10, 0xffdcdcdc));
            r.addView(tx, Ui.lpw(0, Ui.WRAP, 1));
            r.setVisibility(GONE);
            card.addView(r, Ui.lp(Ui.MATCH, dp(30)));
            rows.add(r);
        }
        LinearLayout bot = Ui.row(c);
        bot.addView(Ui.stretch(c));
        btn = Ui.button(c, "Annuler", "dlg");
        btn.setOnClickListener(new OnClickListener() {
            public void onClick(View v) {
                button();
            }
        });
        bot.addView(btn, Ui.lp(dp(130), dp(36)));
        bot.setPadding(0, dp(8), 0, 0);
        card.addView(bot);
        if (Ui.phone) card.setPadding(dp(16), dp(16), dp(16), dp(14));
        addView(card, new LayoutParams(dp(520), Ui.WRAP, Gravity.CENTER));
    }

    @Override
    protected void onMeasure(int wm, int hm) {
        LayoutParams lp = (LayoutParams) card.getLayoutParams();
        int w = Math.min(dp(520), MeasureSpec.getSize(wm) - dp(Ui.phone ? 16 : 32));
        if (lp.width != w && w > 0) lp.width = w;
        super.onMeasure(wm, hm);
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawRect(0, topOffset, getWidth(), getHeight(), veil);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        int ch = card.getMeasuredHeight(), cw = card.getMeasuredWidth();
        int x = (getWidth() - cw) / 2;
        int y = topOffset + Math.max(dp(10), (getHeight() - topOffset - ch) / 2);
        card.layout(x, y, x + cw, y + ch);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        return true;
    }

    public boolean isShown2() {
        return getVisibility() == VISIBLE;
    }

    public void start(String t, String subtitle, int tot, Runnable cancel) {
        h.removeCallbacksAndMessages(null);
        items.clear();
        order.clear();
        done = errors = 0;
        total = Math.max(1, tot);
        cancelled = false;
        onCancel = cancel;
        title.setText(t);
        sub.setText(subtitle);
        btn.setText("Annuler");
        btn.setEnabled(true);
        refresh();
        setVisibility(VISIBLE);
        bringToFront();
    }

    public void setOnCancel(Runnable r) {
        onCancel = r;
    }

    public void item(String key, String label, Bitmap icon, String state, String detail) {
        if (!items.containsKey(key)) order.add(key);
        items.put(key, new Object[]{label, icon, state, detail == null ? "" : detail});
        if ("err".equals(state)) errors++;
        refresh();
    }

    public void setDone(int n) {
        done = Math.min(total, n);
        refresh();
    }

    public void finish(String t) {
        onCancel = null;
        if (cancelled) title.setText("Opération annulée");
        else if (t != null) title.setText(t);
        if (errors > 0) {
            sub.setText(errors + " échec(s) — détails dans la Console");
            btn.setText("Fermer");
            btn.setEnabled(true);
            List<String> errs = new ArrayList<String>(), others = new ArrayList<String>();
            for (String k : order) {
                if ("err".equals(items.get(k)[2])) errs.add(k);
                else others.add(k);
            }
            order.clear();
            order.addAll(others);
            order.addAll(errs);
            refresh();
        } else {
            if (!cancelled) setDone(total);
            h.postDelayed(new Runnable() {
                public void run() {
                    close();
                }
            }, 700);
        }
    }

    public void close() {
        h.removeCallbacksAndMessages(null);
        setVisibility(GONE);
    }

    private void button() {
        if (onCancel == null) {
            close();
            return;
        }
        cancelled = true;
        btn.setEnabled(false);
        btn.setText("Annulation…");
        Runnable r = onCancel;
        onCancel = null;
        r.run();
    }

    private void refresh() {
        bar.setMax(total);
        bar.setValue(done);
        count.setText(done + " / " + total);
        pct.setText((100 * done / total) + " %");
        int from = Math.max(0, order.size() - ROWS);
        for (int i = 0; i < ROWS; i++) {
            LinearLayout r = rows.get(i);
            int k = from + i;
            if (k >= order.size()) {
                r.setVisibility(GONE);
                continue;
            }
            Object[] it = items.get(order.get(k));
            String state = (String) it[2];
            TextView st = (TextView) r.getChildAt(0);
            ImageView ic = (ImageView) r.getChildAt(1);
            TextView tx = (TextView) r.getChildAt(2);
            String sym = "·";
            int col = 0xff8a8a8e;
            if ("run".equals(state)) {
                sym = "⟳";
                col = 0xff7fa4ff;
            } else if ("ok".equals(state)) {
                sym = "✔";
                col = Ui.GREEN;
            } else if ("err".equals(state)) {
                sym = "✖";
                col = Ui.RED;
            } else if ("skip".equals(state)) sym = "–";
            st.setText(sym);
            st.setTextColor(col);
            Bitmap b = (Bitmap) it[1];
            if (b != null) ic.setImageBitmap(b);
            else ic.setImageDrawable(Ui.icon(getContext(), "app", 0xff5d6676));
            String detail = (String) it[3];
            tx.setText(it[0] + (TextUtils.isEmpty(detail) ? "" : " — " + detail));
            tx.setTextColor("err".equals(state) ? 0xffff9b9b : 0xffdcdcdc);
            r.setVisibility(VISIBLE);
        }
        requestLayout();
    }
}

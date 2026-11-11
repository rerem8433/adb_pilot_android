package fr.paradoxxx.adbpilot.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.ListView;

import fr.paradoxxx.adbpilot.Config;

import java.util.HashMap;
import java.util.Map;

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

/** En-tête de colonnes (tri + largeur réglable au doigt, mémorisée) et liste à hauteur de ligne adaptative. */
public final class Table {

    public static final class Cols {
        public final String key;
        public final String[] titles;
        public final int[] px;
        final boolean[] fixed;
        public final int[] gravity;
        public int sortCol = -1;
        public boolean sortAsc = true;
        /** Colonne qui prend la place restante (par défaut la dernière). */
        public int fill = -1;

        public Cols fill(int i) {
            fill = i;
            return this;
        }

        int fillIdx() {
            return fill < 0 ? px.length - 1 : fill;
        }

        /** Largeur de la colonne i pour une largeur totale donnée. */
        float width(int i, float total) {
            if (i != fillIdx()) return px[i];
            float w = total;
            for (int k = 0; k < px.length; k++) if (k != i) w -= px[k];
            return Math.max(dp(40), w);
        }

        public Cols(String key, String[] titles, int[] widthsDp, boolean[] fixed, int[] gravity) {
            this.key = key;
            this.titles = titles;
            this.fixed = fixed;
            this.gravity = gravity;
            px = new int[titles.length];
            Map<String, String> saved = key == null ? new HashMap<String, String>() : Config.getMap(key);
            for (int i = 0; i < titles.length; i++) {
                int w = dp(widthsDp[i]);
                String s = saved.get(String.valueOf(i));
                if (s != null && !fixed[i]) {
                    try {
                        w = Integer.parseInt(s);
                    } catch (NumberFormatException ignored) {
                    }
                }
                px[i] = w;
            }
        }

        void save() {
            if (key == null) return;
            Map<String, String> m = new HashMap<String, String>();
            for (int i = 0; i < px.length; i++) if (i != fillIdx()) m.put(String.valueOf(i), String.valueOf(px[i]));
            Config.setMap(key, m);
        }

        /** Applique les largeurs aux cellules d'une ligne (la dernière colonne remplit le reste). */
        public void apply(LinearLayout row) {
            int n = Math.min(row.getChildCount(), px.length);
            for (int i = 0; i < n; i++) {
                View v = row.getChildAt(i);
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
                if (lp == null) lp = new LinearLayout.LayoutParams(0, Ui.MATCH);
                if (i == fillIdx()) {
                    lp.width = 0;
                    lp.weight = 1;
                } else {
                    lp.width = px[i];
                    lp.weight = 0;
                }
                lp.height = Ui.MATCH;
                v.setLayoutParams(lp);
            }
        }
    }

    public interface HeaderListener {
        void onSort(int col);

        void onResized();
    }

    public static final class Header extends View {
        private final Cols cols;
        private final HeaderListener l;
        private final TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int dragCol = -1, dragSign = 1;
        private float downX, startW;
        private boolean moved;
        public int leftPad = 0;

        public Header(Context c, Cols cols, HeaderListener l) {
            super(c);
            this.cols = cols;
            this.l = l;
            tp.setTextSize(Ui.pt(8) * getResources().getDisplayMetrics().scaledDensity);
            setBackgroundColor(Ui.PANEL);
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(MeasureSpec.getSize(w), dp(28));
        }

        private float cw(int i) {
            return cols.width(i, getWidth() - leftPad);
        }

        private float colX(int i) {
            float x = leftPad;
            for (int k = 0; k < i; k++) x += cw(k);
            return x;
        }

        @Override
        protected void onDraw(Canvas c) {
            int h = getHeight();
            float x = leftPad;
            for (int i = 0; i < cols.titles.length; i++) {
                float w = cw(i);
                tp.setColor(Ui.MUTED);
                String t = TextUtils.ellipsize(cols.titles[i], tp, Math.max(0, w - dp(18)), TextUtils.TruncateAt.END).toString();
                float tw = tp.measureText(t);
                c.drawText(t, x + (w - tw) / 2, h / 2f - (tp.ascent() + tp.descent()) / 2, tp);
                if (i == cols.sortCol) {
                    p.setColor(Ui.MUTED);
                    p.setStyle(Paint.Style.FILL);
                    Path tri = new Path();
                    float cx = x + w - dp(10), cy = h / 2f;
                    float s = dp(4);
                    if (cols.sortAsc) {
                        tri.moveTo(cx - s, cy + s / 2);
                        tri.lineTo(cx + s, cy + s / 2);
                        tri.lineTo(cx, cy - s / 2);
                    } else {
                        tri.moveTo(cx - s, cy - s / 2);
                        tri.lineTo(cx + s, cy - s / 2);
                        tri.lineTo(cx, cy + s / 2);
                    }
                    tri.close();
                    c.drawPath(tri, p);
                }
                if (i < cols.titles.length - 1) {
                    p.setColor(Ui.BORDER);
                    p.setStrokeWidth(Math.max(1, dp(1)));
                    c.drawLine(x + w - 1, dp(4), x + w - 1, h - dp(4), p);
                }
                x += w;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = x;
                    moved = false;
                    dragCol = -1;
                    for (int i = 0; i < cols.titles.length - 1; i++) {
                        float edge = colX(i + 1);
                        if (Math.abs(x - edge) >= dp(14)) continue;
                        if (i != cols.fillIdx() && !cols.fixed[i]) {
                            dragCol = i;
                            dragSign = 1;
                        } else if (i == cols.fillIdx() && i + 1 < cols.titles.length && !cols.fixed[i + 1]) {
                            dragCol = i + 1;  // bord droit de la colonne extensible : on règle sa voisine
                            dragSign = -1;
                        }
                        if (dragCol >= 0) {
                            startW = cols.px[dragCol];
                            break;
                        }
                    }
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.abs(x - downX) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) moved = true;
                    if (dragCol >= 0 && moved) {
                        cols.px[dragCol] = (int) Math.max(dp(36), startW + dragSign * (x - downX));
                        invalidate();
                        l.onResized();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (dragCol >= 0 && moved) {
                        cols.save();
                    } else if (!moved) {
                        float cx = leftPad;
                        for (int i = 0; i < cols.titles.length; i++) {
                            float w = cw(i);
                            if (x >= cx && x < cx + w) {
                                if (cols.sortCol == i) cols.sortAsc = !cols.sortAsc;
                                else {
                                    cols.sortCol = i;
                                    cols.sortAsc = true;
                                }
                                invalidate();
                                l.onSort(i);
                                break;
                            }
                            cx += w;
                        }
                    }
                    dragCol = -1;
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    dragCol = -1;
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }

    /** Liste dont la hauteur des lignes s'adapte pour en afficher ~n (comme style.fit_rows). */
    public static final class List extends ListView {
        public int rowHeight = dp(44);
        public int iconSize = dp(30);
        private final int n, minH, maxIcon;
        public Runnable onRowSize;

        public List(Context c, int n, int minDp, int maxIconDp) {
            super(c);
            this.n = n;
            this.minH = dp(minDp);
            this.maxIcon = dp(maxIconDp);
            setDivider(new ColorDrawable(Ui.BG));
            setDividerHeight(Math.max(1, dp(1)));
            setBackgroundColor(Ui.ROW);
            setSelector(new ColorDrawable(0));
            setCacheColorHint(0);
            setFastScrollEnabled(true);
            setVerticalScrollBarEnabled(true);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            int rh = Math.max(minH, h / n);
            if (rh != rowHeight) {
                rowHeight = rh;
                iconSize = Math.max(dp(24), Math.min(maxIcon, rh - dp(14)));
                post(new Runnable() {
                    public void run() {
                        if (onRowSize != null) onRowSize.run();
                    }
                });
            }
        }
    }

    public static int rowColor(int pos, boolean selected) {
        if (selected) return Ui.SEL;
        return pos % 2 == 0 ? Ui.ROW : Ui.ROW_ALT;
    }

    public static Typeface italic() {
        return Typeface.create(Typeface.DEFAULT, Typeface.ITALIC);
    }
}

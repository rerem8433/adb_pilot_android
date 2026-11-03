package fr.paradoxxx.adbpilot.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListPopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

/** Case à cocher, bouton radio, liste déroulante, barre de progression (rendu identique à la version PC). */
public final class Widgets {

    public interface OnChange {
        void changed(boolean on);
    }

    // ------------------------------------------------------------------ case à cocher / radio
    public static class Check extends LinearLayout {
        final Box box;
        public final TextView label;
        private OnChange listener;

        public Check(Context c, String text, boolean radio) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            box = new Box(c, radio);
            addView(box, new LayoutParams(dp(18), dp(18)));
            label = Ui.text(c, text, 10, Ui.TEXT);
            label.setPadding(dp(6), 0, 0, 0);
            if (text != null && !text.isEmpty()) addView(label);
            setClickable(true);
            setPadding(0, dp(4), dp(4), dp(4));
            setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    if (box.radio && box.on) return;
                    setChecked(!box.on, true);
                }
            });
        }

        public Check(Context c, String text) {
            this(c, text, false);
        }

        public boolean isChecked() {
            return box.on;
        }

        public void setChecked(boolean on) {
            setChecked(on, false);
        }

        public void setChecked(boolean on, boolean notify) {
            if (box.on == on) return;
            box.on = on;
            box.invalidate();
            if (notify && listener != null) listener.changed(on);
        }

        public Check onChange(OnChange l) {
            listener = l;
            return this;
        }

        @Override
        public void setEnabled(boolean e) {
            super.setEnabled(e);
            label.setTextColor(e ? Ui.TEXT : Ui.MUTED);
            box.setAlpha(e ? 1f : 0.5f);
        }
    }

    /** Petite case seule (colonne de tableau). */
    public static class Box extends View {
        boolean on;
        final boolean radio;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tick = new Path();

        public Box(Context c, boolean radio) {
            super(c);
            this.radio = radio;
        }

        public void set(boolean v) {
            if (on != v) {
                on = v;
                invalidate();
            }
        }

        public boolean get() {
            return on;
        }

        @Override
        protected void onDraw(Canvas cv) {
            float s = Math.min(getWidth(), getHeight());
            float bs = dp(15);
            float x = (getWidth() - bs) / 2f, y = (getHeight() - bs) / 2f;
            RectF r = new RectF(x, y, x + bs, y + bs);
            p.setStyle(Paint.Style.FILL);
            p.setColor(on ? Ui.ACCENT : Ui.INPUT);
            if (radio) cv.drawOval(r, p);
            else cv.drawRoundRect(r, dp(2), dp(2), p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Math.max(1, dp(1)));
            p.setColor(on ? Ui.ACCENT_LINE : 0xff8a8a90);
            if (radio) cv.drawOval(r, p);
            else cv.drawRoundRect(r, dp(2), dp(2), p);
            if (on) {
                if (radio) {
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.WHITE);
                    cv.drawCircle(r.centerX(), r.centerY(), bs * 0.22f, p);
                } else {
                    p.setColor(Color.WHITE);
                    p.setStrokeWidth(bs * 0.14f);
                    p.setStrokeCap(Paint.Cap.ROUND);
                    p.setStrokeJoin(Paint.Join.ROUND);
                    tick.reset();
                    tick.moveTo(x + bs * 0.22f, y + bs * 0.53f);
                    tick.lineTo(x + bs * 0.41f, y + bs * 0.72f);
                    tick.lineTo(x + bs * 0.78f, y + bs * 0.28f);
                    cv.drawPath(tick, p);
                }
            }
        }
    }

    /** Groupe de boutons radio exclusifs. */
    public static final class RadioGroup {
        public interface OnPick {
            void picked(int index);
        }

        public final List<Check> items = new ArrayList<Check>();
        private OnPick listener;
        private int current = -1;

        public Check add(Context c, String text) {
            final Check ch = new Check(c, text, true);
            final int idx = items.size();
            items.add(ch);
            ch.onChange(new OnChange() {
                public void changed(boolean on) {
                    if (on) select(idx, true);
                }
            });
            return ch;
        }

        public void select(int idx, boolean notify) {
            current = idx;
            for (int i = 0; i < items.size(); i++) items.get(i).setChecked(i == idx);
            if (notify && listener != null) listener.picked(idx);
        }

        public int selected() {
            return current;
        }

        public RadioGroup onPick(OnPick l) {
            listener = l;
            return this;
        }
    }

    // ------------------------------------------------------------------ liste déroulante
    public static class Combo extends TextView {
        public interface OnSelect {
            void selected(int index, String text);
        }

        private final List<String> items = new ArrayList<String>();
        private int index = -1;
        private OnSelect listener;

        public Combo(Context c) {
            super(c);
            setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.pt(10));
            setTextColor(Ui.TEXT);
            setSingleLine(true);
            setEllipsize(TextUtils.TruncateAt.END);
            setGravity(Gravity.CENTER_VERTICAL);
            setBackground(Ui.states(Ui.box(Ui.INPUT, Ui.BORDER, 0, 1), Ui.box(Ui.BTN_HOVER, Ui.BORDER, 0, 1), null, null));
            setPadding(dp(6), dp(5), dp(6), dp(5));
            Drawable arrow = Ui.icon(c, "down", Ui.MUTED);
            arrow.setBounds(0, 0, dp(12), dp(12));
            setCompoundDrawables(null, null, arrow, null);
            setCompoundDrawablePadding(dp(4));
            setClickable(true);
            setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    popup();
                }
            });
        }

        public Combo items(List<String> l) {
            items.clear();
            items.addAll(l);
            if (index >= items.size()) index = items.isEmpty() ? -1 : 0;
            refresh();
            return this;
        }

        public Combo items(String... l) {
            List<String> x = new ArrayList<String>();
            for (String s : l) x.add(s);
            return items(x);
        }

        public void select(int i) {
            index = i;
            refresh();
        }

        public void selectText(String t) {
            int i = items.indexOf(t);
            if (i >= 0) select(i);
        }

        public int index() {
            return index;
        }

        public String value() {
            return index >= 0 && index < items.size() ? items.get(index) : "";
        }

        public Combo onSelect(OnSelect l) {
            listener = l;
            return this;
        }

        private void refresh() {
            setText(index >= 0 && index < items.size() ? items.get(index) : "");
        }

        void popup() {
            if (items.isEmpty()) return;
            final ListPopupWindow p = new ListPopupWindow(getContext());
            p.setAnchorView(this);
            p.setModal(true);
            p.setBackgroundDrawable(Ui.box(Ui.PANEL, Ui.BORDER, 0, 1));
            p.setAdapter(new ArrayAdapter<String>(getContext(), 0, items) {
                @Override
                public View getView(int pos, View cv, ViewGroup parent) {
                    TextView t = cv instanceof TextView ? (TextView) cv : Ui.text(getContext(), "", 10, Ui.TEXT);
                    t.setText(getItem(pos));
                    t.setPadding(dp(10), dp(10), dp(10), dp(10));
                    t.setBackgroundColor(pos == index ? Ui.SEL : 0);
                    return t;
                }
            });
            p.setWidth(Math.max(getWidth(), dp(160)));
            p.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                    index = pos;
                    refresh();
                    p.dismiss();
                    if (listener != null) listener.selected(pos, items.get(pos));
                }
            });
            p.show();
        }
    }

    /** Liste déroulante éditable (Port : Auto / 5555 …). */
    public static LinearLayout editableCombo(final Context c, final EditText e, final String... values) {
        LinearLayout l = Ui.row(c);
        l.setBackground(Ui.box(Ui.INPUT, Ui.BORDER, 0, 1));
        e.setBackground(null);
        l.addView(e, Ui.lpw(0, Ui.WRAP, 1));
        final android.widget.ImageView arrow = Ui.iconButton(c, "down", Ui.MUTED, 12);
        l.addView(arrow);
        arrow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                final ListPopupWindow p = new ListPopupWindow(c);
                p.setAnchorView((View) arrow.getParent());
                p.setModal(true);
                p.setBackgroundDrawable(Ui.box(Ui.PANEL, Ui.BORDER, 0, 1));
                final List<String> items = new ArrayList<String>();
                for (String s : values) items.add(s);
                p.setAdapter(new ArrayAdapter<String>(c, 0, items) {
                    @Override
                    public View getView(int pos, View cv, ViewGroup parent) {
                        TextView t = Ui.text(c, getItem(pos), 10, Ui.TEXT);
                        t.setPadding(dp(10), dp(10), dp(10), dp(10));
                        return t;
                    }
                });
                p.setWidth(dp(120));
                p.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                    public void onItemClick(AdapterView<?> parent, View view, int pos, long id) {
                        e.setText(items.get(pos));
                        p.dismiss();
                    }
                });
                p.show();
            }
        });
        return l;
    }

    // ------------------------------------------------------------------ barre de progression
    public static class Bar extends View {
        private long max = 100, value = 0;
        private boolean indeterminate, rounded, gradient;
        private int fill = Ui.ACCENT_LINE, bg = Ui.INPUT, border = Ui.BORDER;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private ValueAnimator anim;
        private float phase;

        public Bar(Context c) {
            super(c);
        }

        public Bar style(int fill, int bg, int border, boolean rounded, boolean gradient) {
            this.fill = fill;
            this.bg = bg;
            this.border = border;
            this.rounded = rounded;
            this.gradient = gradient;
            invalidate();
            return this;
        }

        public void setMax(long m) {
            max = Math.max(1, m);
            setIndeterminate(false);
            invalidate();
        }

        public void setValue(long v) {
            value = v;
            invalidate();
        }

        public void setIndeterminate(boolean on) {
            if (on == indeterminate) return;
            indeterminate = on;
            if (on) {
                anim = ValueAnimator.ofFloat(0, 1);
                anim.setDuration(1200);
                anim.setRepeatCount(ValueAnimator.INFINITE);
                anim.setInterpolator(new LinearInterpolator());
                anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                    public void onAnimationUpdate(ValueAnimator a) {
                        phase = (Float) a.getAnimatedValue();
                        invalidate();
                    }
                });
                anim.start();
            } else if (anim != null) {
                anim.cancel();
                anim = null;
            }
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (anim != null) anim.cancel();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), r = rounded ? h / 2 : 0;
            p.setShader(null);
            p.setStyle(Paint.Style.FILL);
            p.setColor(bg);
            c.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
            float x0, x1;
            if (indeterminate) {
                float seg = w * 0.3f;
                x0 = -seg + (w + seg) * phase;
                x1 = x0 + seg;
            } else {
                x0 = 0;
                x1 = w * Math.min(1f, value / (float) max);
            }
            x0 = Math.max(0, x0);
            x1 = Math.min(w, x1);
            if (x1 > x0) {
                if (gradient) p.setShader(new LinearGradient(0, 0, w, 0, Ui.ACCENT_LINE, 0xff7fa4ff, Shader.TileMode.CLAMP));
                else p.setColor(fill);
                c.drawRoundRect(new RectF(x0, 0, x1, h), r, r, p);
                p.setShader(null);
            }
            if (border != 0) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(1);
                p.setColor(border);
                c.drawRoundRect(new RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), r, r, p);
            }
        }
    }
}

package fr.paradoxxx.adbpilot;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import fr.paradoxxx.adbpilot.adb.AdbDevice;
import fr.paradoxxx.adbpilot.adb.AdbStream;
import fr.paradoxxx.adbpilot.ui.Ui;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static fr.paradoxxx.adbpilot.ui.Ui.dp;

/** Fenêtre scrcpy intégrée : serveur scrcpy v3.3.4 sur l'appareil, décodage matériel sur la tablette. */
public final class ScrcpyActivity extends Activity implements SurfaceHolder.Callback {
    static final String VERSION = "3.3.4";
    static final String SERVER = "/data/local/tmp/scrcpy-server.jar";

    private AdbDevice dev;
    private Controller ctl;
    private Fit fit;
    private SurfaceView surface;
    private TextView status;
    private EditText kb;
    private volatile boolean closed;
    private AdbStream server, video, audio, control;
    private final ExecutorService ctrlExec = Executors.newSingleThreadExecutor();
    private volatile int frameW, frameH;
    private int maxSize;
    private boolean record;
    private MediaMuxer muxer;
    private int muxTrack = -1;
    private boolean muxStarted;
    private File recFile;
    private String label;

    /** Conteneur qui garde le rapport largeur/hauteur de l'image. */
    final class Fit extends FrameLayout {
        Fit(Context c) {
            super(c);
        }

        @Override
        protected void onLayout(boolean ch, int l, int t, int r, int b) {
            int w = r - l, h = b - t;
            int fw = frameW > 0 ? frameW : 16, fh = frameH > 0 ? frameH : 9;
            float s = Math.min((float) w / fw, (float) h / fh);
            int vw = Math.round(fw * s), vh = Math.round(fh * s);
            int x = (w - vw) / 2, y = (h - vh) / 2;
            for (int i = 0; i < getChildCount(); i++) {
                View v = getChildAt(i);
                if (v == surface) {
                    v.measure(MeasureSpec.makeMeasureSpec(vw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(vh, MeasureSpec.EXACTLY));
                    v.layout(x, y, x + vw, y + vh);
                } else {
                    v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
                }
            }
        }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Config.init(this);
        Ui.init(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        ctl = Controller.instance;
        if (ctl == null || !ctl.ready()) {
            finish();
            return;
        }
        dev = ctl.adb();
        label = ctl.current.label();
        maxSize = getIntent().getIntExtra("max_size", 1080);
        record = getIntent().getBooleanExtra("record", false);

        LinearLayout root = Ui.row(this);
        root.setBackgroundColor(Color.BLACK);
        fit = new Fit(this);
        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        fit.addView(surface);
        status = Ui.text(this, "Connexion à " + label + "…", 10, 0xffdddddd);
        status.setPadding(dp(12), dp(10), dp(12), dp(10));
        status.setBackground(Ui.box(0xcc141416, Ui.ACCENT_LINE, 6, 1));
        FrameLayout overlay = new FrameLayout(this);
        overlay.addView(fit, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        overlay.addView(status, new FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.TOP | Gravity.START));
        kb = new EditText(this);
        kb.setAlpha(0f);
        kb.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        kb.setText(" ");
        kb.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int bb, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int bb, int c) {
            }

            public void afterTextChanged(Editable e) {
                String t = e.toString();
                if (t.equals(" ")) return;
                if (t.isEmpty()) key(67);
                else if (t.startsWith(" ")) text(t.substring(1));
                else text(t);
                kb.removeTextChangedListener(this);
                kb.setText(" ");
                kb.setSelection(1);
                kb.addTextChangedListener(this);
            }
        });
        kb.setOnKeyListener(new View.OnKeyListener() {
            public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return true;
                if (code == KeyEvent.KEYCODE_DEL) key(67);
                else if (code == KeyEvent.KEYCODE_ENTER) key(66);
                else return false;
                return true;
            }
        });
        overlay.addView(kb, new FrameLayout.LayoutParams(dp(2), dp(2), Gravity.BOTTOM));
        root.addView(overlay, Ui.lpw(0, Ui.MATCH, 1));

        LinearLayout bar = Ui.col(this);
        bar.setBackgroundColor(0xff1b1b1c);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        bar.setPadding(dp(4), dp(8), dp(4), dp(8));
        Object[][] bt = {{"back", 4}, {"home", 3}, {"recents", 187}, {null, 0}, {"plus", 24}, {"minus", 25}, {"mute", 164},
                {null, 0}, {"menu", 82}, {"power", 26}, {"terminal", -1}, {"camera", -3}, {null, 0}, {"left", -2}};
        for (Object[] x : bt) {
            if (x[0] == null) {
                bar.addView(Ui.vstretch(this));
                continue;
            }
            final int code = (Integer) x[1];
            ImageView iv = Ui.iconButton(this, (String) x[0], code == 26 ? 0xffef5a5a : (code == -2 ? 0xffa9a9ad : 0xffffffff), 26);
            iv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (code == -1) toggleKeyboard();
                    else if (code == -2) finish();
                    else if (code == -3) screenshot();
                    else key(code);
                }
            });
            LinearLayout.LayoutParams lp = Ui.lp(dp(46), dp(46));
            lp.topMargin = dp(2);
            bar.addView(iv, lp);
        }
        root.addView(bar, Ui.lp(dp(56), Ui.MATCH));
        setContentView(root);
        surface.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                touch(v, e);
                return true;
            }
        });
    }

    // ------------------------------------------------------------------ cycle de vie
    private boolean started;

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        if (started) return;
        started = true;
        new Thread(new Runnable() {
            public void run() {
                session(surface.getHolder());
            }
        }, "scrcpy").start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        shutdown();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations()) finish();
    }

    @Override
    protected void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private synchronized void shutdown() {
        if (closed) return;
        closed = true;
        for (AdbStream s : new AdbStream[]{video, audio, control, server}) if (s != null) s.close();
        ctrlExec.shutdownNow();
    }

    private void setStatus(final String s, final boolean hide) {
        runOnUiThread(new Runnable() {
            public void run() {
                status.setText(s);
                status.setVisibility(hide ? View.GONE : View.VISIBLE);
            }
        });
    }

    private void fail(final String msg) {
        if (closed) return;
        ctl.log("[Erreur] : scrcpy : " + Html.esc(msg), "err");
        setStatus("scrcpy : " + msg, false);
    }

    // ------------------------------------------------------------------ session
    static long bitrate(String s) {
        s = s == null ? "" : s.trim().toUpperCase(Locale.ROOT);
        try {
            if (s.endsWith("M")) return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1000000);
            if (s.endsWith("K")) return (long) (Double.parseDouble(s.substring(0, s.length() - 1)) * 1000);
            if (!s.isEmpty()) return Long.parseLong(s);
        } catch (NumberFormatException ignored) {
        }
        return 8000000;
    }

    private void session(SurfaceHolder holder) {
        try {
            byte[] jar = Core.readAll(getAssets().open("scrcpy-server.jar"));
            long[] st = dev.stat(SERVER);
            if (st == null || st[1] != jar.length) {
                setStatus("Envoi du serveur scrcpy…", false);
                dev.push(new java.io.ByteArrayInputStream(jar), jar.length, SERVER, 0644, null);
            }
            int sdk = 0;
            try {
                sdk = Integer.parseInt(String.valueOf(ctl.lastSpecs.get("sdk")));
            } catch (Exception ignored) {
            }
            boolean wantAudio = !Config.getBool("scrcpy_no_audio", false) && sdk >= 30;
            int scid = new Random().nextInt(0x7fffffff);
            String sc = String.format(Locale.ROOT, "%08x", scid);
            StringBuilder cmd = new StringBuilder("CLASSPATH=" + SERVER + " app_process / com.genymobile.scrcpy.Server " + VERSION);
            cmd.append(" scid=").append(sc).append(" log_level=info tunnel_forward=true control=true cleanup=true");
            cmd.append(" video_codec=").append(Config.getStr("scrcpy_codec", "h264"));
            cmd.append(" max_size=").append(maxSize);
            cmd.append(" video_bit_rate=").append(bitrate(Config.getStr("scrcpy_bitrate", "8M")));
            String fps = Config.getStr("scrcpy_max_fps", "");
            if (fps.matches("\\d+") && !fps.equals("0")) cmd.append(" max_fps=").append(fps);
            cmd.append(" audio=").append(wantAudio);
            if (wantAudio) cmd.append(" audio_codec=raw");
            if (Config.getBool("scrcpy_stay_awake", true)) cmd.append(" stay_awake=true");
            if (Config.getBool("scrcpy_show_touches", false)) cmd.append(" show_touches=true");
            ctl.log(">> scrcpy-server " + VERSION + " (" + Html.esc(label) + ", " + maxSize + " px)", "cmd");
            server = dev.openExec(cmd.toString());
            new Thread(new Runnable() {
                public void run() {
                    try {
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = server.read(b, 0, b.length)) >= 0) {
                            String s = new String(b, 0, n, "UTF-8").trim();
                            if (!s.isEmpty()) ctl.log(Html.esc(s), "out");
                        }
                    } catch (IOException ignored) {
                    }
                    if (!closed) runOnUiThread(new Runnable() {
                        public void run() {
                            ctl.log("scrcpy terminé", "info");
                        }
                    });
                }
            }).start();
            String sock = "localabstract:scrcpy_" + sc;
            video = openRetry(sock);
            if (video == null) throw new IOException("le serveur scrcpy ne répond pas (voir la Console)");
            byte[] one = new byte[1];
            video.readFully(one, 0, 1);
            if (wantAudio) audio = dev.openService(sock);
            control = dev.openService(sock);
            byte[] name = new byte[64];
            video.readFully(name, 0, 64);
            byte[] meta = new byte[12];
            video.readFully(meta, 0, 12);
            ByteBuffer mb = ByteBuffer.wrap(meta);
            int codecId = mb.getInt();
            frameW = mb.getInt();
            frameH = mb.getInt();
            if (codecId == 0 || codecId == 1) throw new IOException("encodage vidéo impossible sur l'appareil");
            relayout();
            setStatus("", true);
            if (audio != null) startAudio();
            startControlReader();
            if (Config.getBool("scrcpy_turn_screen_off", false)) send(new byte[]{10, 0});
            if (record) startRecord(codecId);
            decode(holder, codecId);
        } catch (Exception e) {
            fail(String.valueOf(e.getMessage()));
        } finally {
            stopRecord();
        }
    }

    private AdbStream openRetry(String sock) throws InterruptedException {
        for (int i = 0; i < 60 && !closed; i++) {
            try {
                return dev.openService(sock);
            } catch (IOException e) {
                Thread.sleep(100);
            }
        }
        return null;
    }

    private void relayout() {
        runOnUiThread(new Runnable() {
            public void run() {
                fit.requestLayout();
            }
        });
    }

    // ------------------------------------------------------------------ vidéo
    private static String mime(int id) {
        if (id == 0x68323635) return MediaFormat.MIMETYPE_VIDEO_HEVC;
        if (id == 0x00617631) return "video/av01";
        return MediaFormat.MIMETYPE_VIDEO_AVC;
    }

    private MediaCodec newDecoder(String mime, SurfaceHolder holder) throws IOException {
        MediaFormat f = MediaFormat.createVideoFormat(mime, Math.max(frameW, 16), Math.max(frameH, 16));
        MediaCodec c = MediaCodec.createDecoderByType(mime);
        c.configure(f, holder.getSurface(), null, 0);
        c.start();
        return c;
    }

    private void decode(SurfaceHolder holder, int codecId) throws IOException {
        String mime = mime(codecId);
        MediaCodec codec = newDecoder(mime, holder);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        byte[] head = new byte[12];
        byte[] lastConfig = null;
        boolean hadFrame = false;
        try {
            while (!closed) {
                video.readFully(head, 0, 12);
                ByteBuffer hb = ByteBuffer.wrap(head);
                long ptsFlags = hb.getLong();
                int size = hb.getInt();
                byte[] data = new byte[size];
                video.readFully(data, 0, size);
                boolean config = (ptsFlags & (1L << 63)) != 0;
                boolean keyFrame = (ptsFlags & (1L << 62)) != 0;
                long pts = ptsFlags & 0x3FFFFFFFFFFFFFFFL;
                if (config) {
                    if (hadFrame && lastConfig != null && !Arrays.equals(lastConfig, data)) {
                        // rotation / changement de taille : nouveau décodeur
                        codec.stop();
                        codec.release();
                        codec = newDecoder(mime, holder);
                        if (muxer != null) stopRecord();
                    }
                    lastConfig = data;
                    if (muxer != null && !muxStarted) muxConfig(data, codecId);
                } else {
                    hadFrame = true;
                    if (muxer != null && muxStarted) muxWrite(data, pts, keyFrame);
                }
                int idx;
                while ((idx = codec.dequeueInputBuffer(10000)) < 0) {
                    drain(codec, info);
                    if (closed) return;
                }
                ByteBuffer in = codec.getInputBuffer(idx);
                in.clear();
                in.put(data);
                codec.queueInputBuffer(idx, 0, size, config ? 0 : pts, config ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0);
                drain(codec, info);
            }
        } finally {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            codec.release();
        }
    }

    private void drain(MediaCodec codec, MediaCodec.BufferInfo info) {
        while (true) {
            int o = codec.dequeueOutputBuffer(info, 0);
            if (o >= 0) codec.releaseOutputBuffer(o, true);
            else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                int w = f.getInteger(MediaFormat.KEY_WIDTH), h = f.getInteger(MediaFormat.KEY_HEIGHT);
                if (f.containsKey("crop-right") && f.containsKey("crop-left")) w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
                if (f.containsKey("crop-bottom") && f.containsKey("crop-top")) h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
                if (w != frameW || h != frameH) {
                    frameW = w;
                    frameH = h;
                    relayout();
                }
            } else break;
        }
    }

    // ------------------------------------------------------------------ enregistrement (MP4, vidéo seule)
    private void startRecord(int codecId) {
        try {
            String dn = label.replaceAll("[^\\w.\\-]+", "_");
            recFile = new File(Config.userDir("videos"), dn + "_scrcpy_" + new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.ROOT).format(new Date()) + ".mp4");
            muxer = new MediaMuxer(recFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        } catch (Exception e) {
            ctl.log("[Erreur] : enregistrement : " + Html.esc(String.valueOf(e.getMessage())), "err");
            muxer = null;
        }
    }

    private static int nextStart(byte[] d, int from) {
        for (int i = from; i + 3 < d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && (d[i + 2] == 1 || (d[i + 2] == 0 && d[i + 3] == 1))) return i;
        }
        return -1;
    }

    private void muxConfig(byte[] cfg, int codecId) {
        try {
            String mime = mime(codecId);
            MediaFormat f = MediaFormat.createVideoFormat(mime, frameW, frameH);
            if (mime.equals(MediaFormat.MIMETYPE_VIDEO_AVC)) {
                int a = nextStart(cfg, 0), b = nextStart(cfg, a + 3);
                if (a >= 0 && b > a) {
                    f.setByteBuffer("csd-0", ByteBuffer.wrap(Arrays.copyOfRange(cfg, a, b)));
                    f.setByteBuffer("csd-1", ByteBuffer.wrap(Arrays.copyOfRange(cfg, b, cfg.length)));
                } else f.setByteBuffer("csd-0", ByteBuffer.wrap(cfg));
            } else f.setByteBuffer("csd-0", ByteBuffer.wrap(cfg));
            muxTrack = muxer.addTrack(f);
            muxer.start();
            muxStarted = true;
            ctl.log("Enregistrement : " + Html.esc(recFile.getAbsolutePath()), "info");
        } catch (Exception e) {
            ctl.log("[Erreur] : enregistrement : " + Html.esc(String.valueOf(e.getMessage())), "err");
            stopRecord();
        }
    }

    private long firstPts = -1;
    private boolean gotKey;

    private void muxWrite(byte[] d, long pts, boolean key) {
        if (!gotKey && !key) return;
        gotKey = true;
        if (firstPts < 0) firstPts = pts;
        MediaCodec.BufferInfo bi = new MediaCodec.BufferInfo();
        bi.set(0, d.length, pts - firstPts, key ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
        try {
            muxer.writeSampleData(muxTrack, ByteBuffer.wrap(d), bi);
        } catch (Exception ignored) {
        }
    }

    private synchronized void stopRecord() {
        if (muxer == null) return;
        MediaMuxer m = muxer;
        muxer = null;
        try {
            if (muxStarted) m.stop();
            m.release();
        } catch (Exception ignored) {
        }
        if (muxStarted && recFile != null && recFile.length() > 0) {
            final String p = recFile.getAbsolutePath();
            ctl.log("[Succès] : Vidéo enregistrée : " + Html.esc(p), "ok");
        }
        muxStarted = false;
    }

    // ------------------------------------------------------------------ audio (PCM brut 48 kHz stéréo)
    private void startAudio() {
        new Thread(new Runnable() {
            public void run() {
                AudioTrack at = null;
                try {
                    byte[] id = new byte[4];
                    audio.readFully(id, 0, 4);
                    int codec = ByteBuffer.wrap(id).getInt();
                    if (codec == 0 || codec == 1) {
                        ctl.log("Audio indisponible sur cet appareil (vidéo seule).", "info");
                        return;
                    }
                    int min = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
                    at = new AudioTrack(AudioManager.STREAM_MUSIC, 48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(min * 2, 48000), AudioTrack.MODE_STREAM);
                    at.play();
                    byte[] head = new byte[12];
                    while (!closed) {
                        audio.readFully(head, 0, 12);
                        ByteBuffer hb = ByteBuffer.wrap(head);
                        long pf = hb.getLong();
                        int size = hb.getInt();
                        byte[] d = new byte[size];
                        audio.readFully(d, 0, size);
                        if ((pf & (1L << 63)) != 0) continue;
                        at.write(d, 0, size);
                    }
                } catch (Exception ignored) {
                } finally {
                    if (at != null) {
                        try {
                            at.stop();
                        } catch (Exception ignored) {
                        }
                        at.release();
                    }
                }
            }
        }, "scrcpy-audio").start();
    }

    // ------------------------------------------------------------------ contrôle
    private void startControlReader() {
        new Thread(new Runnable() {
            public void run() {
                byte[] b = new byte[4096];
                try {
                    while (!closed && control.read(b, 0, b.length) >= 0) {
                        // messages de l'appareil (presse-papiers…) : ignorés
                    }
                } catch (IOException ignored) {
                }
                if (!closed) runOnUiThread(new Runnable() {
                    public void run() {
                        finish();
                    }
                });
            }
        }, "scrcpy-ctrl").start();
    }

    private void send(final byte[] msg) {
        final AdbStream c = control;
        if (c == null || closed) return;
        try {
            ctrlExec.submit(new Runnable() {
                public void run() {
                    try {
                        c.write(msg);
                    } catch (IOException ignored) {
                    }
                }
            });
        } catch (Exception ignored) {
        }
    }

    void key(int code) {
        for (int action = 0; action <= 1; action++) {
            ByteBuffer b = ByteBuffer.allocate(14).order(ByteOrder.BIG_ENDIAN);
            b.put((byte) 0).put((byte) action).putInt(code).putInt(0).putInt(0);
            send(b.array());
        }
    }

    void text(String t) {
        try {
            byte[] u = t.getBytes("UTF-8");
            if (u.length > 300) u = Arrays.copyOf(u, 300);
            ByteBuffer b = ByteBuffer.allocate(5 + u.length);
            b.put((byte) 1).putInt(u.length).put(u);
            send(b.array());
        } catch (Exception ignored) {
        }
    }

    private void touchMsg(int action, long pointerId, float x, float y, float pressure) {
        int w = frameW, h = frameH;
        if (w <= 0 || h <= 0) return;
        ByteBuffer b = ByteBuffer.allocate(32);
        b.put((byte) 2).put((byte) action).putLong(pointerId);
        b.putInt(Math.max(0, Math.min(w - 1, Math.round(x)))).putInt(Math.max(0, Math.min(h - 1, Math.round(y))));
        b.putShort((short) w).putShort((short) h);
        b.putShort((short) (pressure >= 1f ? 0xffff : (int) (pressure * 0x10000)));
        b.putInt(0).putInt(0);
        send(b.array());
    }

    private void touch(View v, MotionEvent e) {
        if (v.getWidth() == 0) return;
        float sx = (float) frameW / v.getWidth(), sy = (float) frameH / v.getHeight();
        int act = e.getActionMasked();
        if (act == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < e.getPointerCount(); i++)
                touchMsg(2, e.getPointerId(i), e.getX(i) * sx, e.getY(i) * sy, 1f);
            return;
        }
        int i = e.getActionIndex();
        if (act == MotionEvent.ACTION_DOWN || act == MotionEvent.ACTION_POINTER_DOWN)
            touchMsg(0, e.getPointerId(i), e.getX(i) * sx, e.getY(i) * sy, 1f);
        else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_POINTER_UP)
            touchMsg(1, e.getPointerId(i), e.getX(i) * sx, e.getY(i) * sy, 0f);
        else if (act == MotionEvent.ACTION_CANCEL)
            for (int k = 0; k < e.getPointerCount(); k++) touchMsg(1, e.getPointerId(k), e.getX(k) * sx, e.getY(k) * sy, 0f);
    }

    private void toggleKeyboard() {
        kb.requestFocus();
        InputMethodManager im = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        im.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
    }

    private void screenshot() {
        final AdbDevice d = dev;
        new Thread(new Runnable() {
            public void run() {
                try {
                    byte[] data = d.execOut("screencap -p", 30);
                    File f = new File(Config.userDir("images"), label.replaceAll("[^\\w.\\-]+", "_") + "_"
                            + new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.ROOT).format(new Date()) + ".png");
                    java.io.FileOutputStream o = new java.io.FileOutputStream(f);
                    o.write(data);
                    o.close();
                    ctl.log("[Succès] : Capture enregistrée : " + Html.esc(f.getAbsolutePath()), "ok");
                    setStatus("Capture enregistrée : " + f.getName(), false);
                    Thread.sleep(2500);
                    setStatus("", true);
                } catch (Exception e) {
                    fail("capture impossible");
                }
            }
        }).start();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_UP) key(4);
            return true;
        }
        if (getCurrentFocus() == kb) return super.dispatchKeyEvent(e);
        if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN || e.isSystem())
            return super.dispatchKeyEvent(e);
        ByteBuffer b = ByteBuffer.allocate(14);
        b.put((byte) 0).put((byte) (e.getAction() == KeyEvent.ACTION_DOWN ? 0 : 1)).putInt(code).putInt(e.getRepeatCount()).putInt(e.getMetaState());
        send(b.array());
        return true;
    }
}

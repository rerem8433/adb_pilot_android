package fr.paradoxxx.adbpilot.adb;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Protocole ADB côté « hôte » : poignée de main, authentification RSA, multiplexage des flux. */
public final class AdbConnection {
    static final int A_SYNC = 0x434e5953, A_CNXN = 0x4e584e43, A_OPEN = 0x4e45504f, A_OKAY = 0x59414b4f,
            A_CLSE = 0x45534c43, A_WRTE = 0x45545257, A_AUTH = 0x48545541, A_STLS = 0x534c5453;
    static final int A_VERSION = 0x01000001;
    static final int MAX_PAYLOAD = 256 * 1024;
    static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    static final Charset UTF8 = Charset.forName("UTF-8");

    public interface Listener {
        /** La clé a été envoyée : l'utilisateur doit accepter sur l'écran de l'appareil. */
        void onAuthPrompt();

        void onClosed(AdbConnection c, String reason);
    }

    private final Transport t;
    private final Object writeLock = new Object();
    private final Map<Integer, AdbStream> streams = new ConcurrentHashMap<Integer, AdbStream>();
    private final AtomicInteger nextId = new AtomicInteger(1);
    private volatile boolean closed;
    private int maxData = 4096;
    private String banner = "";
    private final Map<String, String> props = new HashMap<String, String>();
    private final java.util.Set<String> features = new java.util.HashSet<String>();
    private Listener listener;
    private Thread reader;

    public AdbConnection(Transport t) {
        this.t = t;
    }

    public Transport transport() {
        return t;
    }

    public int maxData() {
        return maxData;
    }

    public boolean hasFeature(String f) {
        return features.contains(f);
    }

    public String prop(String k) {
        String v = props.get(k);
        return v == null ? "" : v;
    }

    public boolean isClosed() {
        return closed;
    }

    // ------------------------------------------------------------------ messages
    void send(int cmd, int a0, int a1, byte[] data, int off, int len) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (int i = 0; i < len; i++) sum += data[off + i] & 0xff;
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(~cmd);
        synchronized (writeLock) {
            if (closed) throw new IOException("connexion fermée");
            t.write(h.array(), 0, 24);
            if (len > 0) {
                t.write(data, off, len);
                t.endOfMessage(len);
            }
        }
    }

    void send(int cmd, int a0, int a1, byte[] data) throws IOException {
        send(cmd, a0, a1, data == null ? new byte[0] : data, 0, data == null ? 0 : data.length);
    }

    private static final class Msg {
        int cmd, a0, a1;
        byte[] data;
    }

    private Msg read() throws IOException {
        byte[] h = new byte[24];
        t.readFully(h, 0, 24);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.a0 = b.getInt();
        m.a1 = b.getInt();
        int len = b.getInt();
        b.getInt();
        int magic = b.getInt();
        if (magic != ~m.cmd) throw new IOException("message ADB invalide");
        if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("taille ADB invalide");
        m.data = new byte[len];
        if (len > 0) t.readFully(m.data, 0, len);
        return m;
    }

    // ------------------------------------------------------------------ connexion
    /**
     * Poignée de main. Bloque jusqu'à CNXN (ou exception). timeoutMs couvre aussi l'attente de
     * l'acceptation de la clé sur l'écran de l'appareil.
     */
    public void connect(AdbCrypto crypto, Listener l, final int timeoutMs) throws Exception {
        this.listener = l;
        final boolean[] finished = {false};
        Thread watchdog = new Thread(new Runnable() {
            public void run() {
                try {
                    Thread.sleep(timeoutMs);
                } catch (InterruptedException e) {
                    return;
                }
                synchronized (finished) {
                    if (!finished[0]) t.close();
                }
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            byte[] hostBanner = "host::features=shell_v2,cmd,stat_v2,ls_v2,fixed_push_mkdir\0".getBytes(UTF8);
            send(A_CNXN, A_VERSION, MAX_PAYLOAD, hostBanner);
            boolean sentSig = false, sentKey = false;
            while (true) {
                Msg m;
                try {
                    m = read();
                } catch (IOException e) {
                    if (sentKey) throw new IOException("autorisation non accordée sur l'appareil (délai dépassé)");
                    throw e;
                }
                if (m.cmd == A_CNXN) {
                    maxData = Math.max(1024, Math.min(m.a1, MAX_PAYLOAD));
                    parseBanner(new String(m.data, UTF8));
                    break;
                } else if (m.cmd == A_AUTH && m.a0 == AUTH_TOKEN) {
                    if (!sentSig) {
                        sentSig = true;
                        send(A_AUTH, AUTH_SIGNATURE, 0, crypto.sign(m.data));
                    } else if (!sentKey) {
                        sentKey = true;
                        send(A_AUTH, AUTH_RSAPUBLICKEY, 0, crypto.publicKeyPayload());
                        if (l != null) l.onAuthPrompt();
                    }
                } else if (m.cmd == A_STLS) {
                    throw new IOException("débogage sans fil (TLS) non pris en charge : utilise le port 5555 (adb tcpip)");
                }
            }
        } finally {
            synchronized (finished) {
                finished[0] = true;
            }
            watchdog.interrupt();
        }
        if (t instanceof Transport.Tcp) ((Transport.Tcp) t).setReadTimeout(0);
        reader = new Thread(new Runnable() {
            public void run() {
                loop();
            }
        }, "adb-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void parseBanner(String s) {
        banner = s;
        int i = s.indexOf("::");
        String rest = i >= 0 ? s.substring(i + 2) : s;
        for (String part : rest.split(";")) {
            int e = part.indexOf('=');
            if (e <= 0) continue;
            String k = part.substring(0, e).trim(), v = part.substring(e + 1).replace("\0", "").trim();
            props.put(k, v);
            if ("features".equals(k)) {
                for (String f : v.split(",")) features.add(f.trim());
            }
        }
    }

    private void loop() {
        String reason = "connexion perdue";
        try {
            while (!closed) {
                Msg m = read();
                AdbStream s;
                switch (m.cmd) {
                    case A_OKAY:
                        s = streams.get(m.a1);
                        if (s != null) s.onOkay(m.a0);
                        break;
                    case A_WRTE:
                        s = streams.get(m.a1);
                        if (s != null) {
                            s.onData(m.data);
                            send(A_OKAY, m.a1, m.a0, null);
                        } else {
                            send(A_CLSE, 0, m.a0, null);
                        }
                        break;
                    case A_CLSE:
                        s = streams.remove(m.a1);
                        if (s != null) s.onRemoteClose();
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            if (e.getMessage() != null) reason = e.getMessage();
        }
        close(reason);
    }

    public void close(String reason) {
        if (closed) return;
        closed = true;
        t.close();
        for (AdbStream s : streams.values()) s.onRemoteClose();
        streams.clear();
        if (listener != null) listener.onClosed(this, reason);
    }

    // ------------------------------------------------------------------ flux
    public AdbStream open(String destination) throws IOException {
        if (closed) throw new IOException("appareil déconnecté");
        int id = nextId.getAndIncrement();
        AdbStream s = new AdbStream(this, id);
        streams.put(id, s);
        byte[] d = (destination + "\0").getBytes(UTF8);
        send(A_OPEN, id, 0, d);
        if (!s.waitOpen(15000)) {
            streams.remove(id);
            throw new IOException("service refusé par l'appareil : " + destination.split(":")[0]);
        }
        return s;
    }

    void forget(int localId) {
        streams.remove(localId);
    }
}

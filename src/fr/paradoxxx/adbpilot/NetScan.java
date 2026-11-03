package fr.paradoxxx.adbpilot;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Détection des appareils Android sur le réseau local (port ADB, bannière, mDNS, DNS inverse). */
public final class NetScan {
    public static final class Found {
        public String ip;
        public int port = 0;
        public String adb = "";   // open | auth | tls | ""
        public String name = "", model = "";
        public final List<String> details = new ArrayList<String>();
        public final Set<String> sources = new LinkedHashSet<String>();

        public boolean connectable() {
            return port > 0;
        }
    }

    public interface Progress {
        void on(int n, int total, List<Found> found);

        boolean stopped();
    }

    static final String[] MDNS = {"_adb-tls-connect._tcp.local", "_adb._tcp.local", "_googlecast._tcp.local",
            "_androidtvremote2._tcp.local"};

    /** Préfixes /24 des interfaces de la tablette (ex. "192.168.1."). */
    public static List<String> localNetworks() {
        List<String> out = new ArrayList<String>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    String pre = ip.substring(0, ip.lastIndexOf('.') + 1);
                    if (!out.contains(pre)) out.add(pre);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static boolean portOpen(String ip, int port, int timeout) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), timeout);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** ("open", props) / ("auth", {}) / ("", {}) — aucune clé n'est envoyée. */
    static String banner(String ip, int port, Map<String, String> props) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(ip, port), 1500);
            s.setSoTimeout(1500);
            byte[] data = "host::\0".getBytes("UTF-8");
            ByteBuffer h = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
            int sum = 0;
            for (byte b : data) sum += b & 0xff;
            h.putInt(0x4E584E43).putInt(0x01000001).putInt(256 * 1024).putInt(data.length).putInt(sum).putInt(~0x4E584E43);
            h.put(data);
            OutputStream o = s.getOutputStream();
            o.write(h.array());
            o.flush();
            InputStream in = s.getInputStream();
            byte[] head = new byte[24];
            int off = 0;
            while (off < 24) {
                int n = in.read(head, off, 24 - off);
                if (n < 0) return "";
                off += n;
            }
            ByteBuffer hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            int cmd = hb.getInt();
            hb.getInt();
            hb.getInt();
            int len = hb.getInt();
            if (cmd == 0x48545541) return "auth";
            if (cmd != 0x4E584E43) return "";
            byte[] p = new byte[Math.min(len, 65536)];
            off = 0;
            while (off < p.length) {
                int n = in.read(p, off, p.length - off);
                if (n < 0) break;
                off += n;
            }
            String payload = new String(p, 0, off, "UTF-8").replace("\0", "");
            int i = payload.indexOf("::");
            for (String part : (i >= 0 ? payload.substring(i + 2) : payload).split(";")) {
                int e = part.indexOf('=');
                if (e > 0) props.put(part.substring(0, e).trim(), part.substring(e + 1).trim());
            }
            return "open";
        } catch (Exception e) {
            return "";
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ mDNS
    static byte[] qname(String n) throws Exception {
        java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
        for (String p : n.split("\\.")) {
            byte[] x = p.getBytes("UTF-8");
            b.write(x.length);
            b.write(x);
        }
        b.write(0);
        return b.toByteArray();
    }

    static Object[] readName(byte[] d, int off, int depth) {
        List<String> labels = new ArrayList<String>();
        while (off < d.length) {
            int ln = d[off] & 0xff;
            if (ln == 0) return new Object[]{join(labels), off + 1};
            if ((ln & 0xC0) == 0xC0) {
                int ptr = ((ln & 0x3F) << 8) | (d[off + 1] & 0xff);
                if (depth < 10) labels.add((String) readName(d, ptr, depth + 1)[0]);
                return new Object[]{join(labels), off + 2};
            }
            try {
                labels.add(new String(d, off + 1, ln, "UTF-8"));
            } catch (Exception e) {
                labels.add("?");
            }
            off += 1 + ln;
        }
        return new Object[]{join(labels), off};
    }

    static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) {
            if (s == null || s.isEmpty()) continue;
            if (sb.length() > 0) sb.append('.');
            sb.append(s);
        }
        return sb.toString();
    }

    static List<Map<String, Object>> mdnsBrowse(int timeoutMs) {
        List<Object[]> recs = new ArrayList<Object[]>();
        MulticastSocket s = null;
        try {
            s = new MulticastSocket();
            s.setTimeToLive(255);
            s.setSoTimeout(200);
            java.io.ByteArrayOutputStream q = new java.io.ByteArrayOutputStream();
            ByteBuffer h = ByteBuffer.allocate(12);
            h.putShort((short) 0).putShort((short) 0).putShort((short) MDNS.length).putShort((short) 0).putShort((short) 0).putShort((short) 0);
            q.write(h.array());
            for (String svc : MDNS) {
                q.write(qname(svc));
                q.write(new byte[]{0, 12, (byte) 0x80, 1});
            }
            byte[] qb = q.toByteArray();
            s.send(new DatagramPacket(qb, qb.length, InetAddress.getByName("224.0.0.251"), 5353));
            long end = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[9000];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    s.receive(p);
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                byte[] d = new byte[p.getLength()];
                System.arraycopy(buf, 0, d, 0, d.length);
                parse(d, recs);
            }
        } catch (Exception ignored) {
        } finally {
            if (s != null) s.close();
        }
        Map<String, Object[]> srv = new HashMap<String, Object[]>();
        Map<String, Map<String, String>> txt = new HashMap<String, Map<String, String>>();
        Map<String, String> a = new HashMap<String, String>();
        List<String[]> ptr = new ArrayList<String[]>();
        for (Object[] r : recs) {
            String t = (String) r[0], n = (String) r[1];
            if (t.equals("PTR")) ptr.add(new String[]{n, (String) r[2]});
            else if (t.equals("SRV")) srv.put(n, (Object[]) r[2]);
            else if (t.equals("A")) a.put(n, (String) r[2]);
            else if (t.equals("TXT")) {
                @SuppressWarnings("unchecked") Map<String, String> m = (Map<String, String>) r[2];
                txt.put(n, m);
            }
        }
        List<Map<String, Object>> out = new ArrayList<Map<String, Object>>();
        for (String[] pr : ptr) {
            boolean known = false;
            for (String m : MDNS) if (m.equals(pr[0])) known = true;
            if (!known) continue;
            Object[] sv = srv.get(pr[1]);
            String host = sv == null ? "" : (String) sv[0];
            Map<String, Object> e = new HashMap<String, Object>();
            e.put("service", pr[0]);
            String inst = pr[1];
            e.put("instance", inst.endsWith(pr[0]) ? inst.substring(0, Math.max(0, inst.length() - pr[0].length() - 1)) : inst);
            e.put("ip", a.containsKey(host) ? a.get(host) : "");
            e.put("port", sv == null ? 0 : sv[1]);
            e.put("txt", txt.containsKey(inst) ? txt.get(inst) : new HashMap<String, String>());
            out.add(e);
        }
        return out;
    }

    static void parse(byte[] d, List<Object[]> out) {
        try {
            ByteBuffer b = ByteBuffer.wrap(d);
            b.getShort();
            b.getShort();
            int qd = b.getShort() & 0xffff, an = b.getShort() & 0xffff, ns = b.getShort() & 0xffff, ar = b.getShort() & 0xffff;
            int off = 12;
            for (int i = 0; i < qd; i++) off = (Integer) readName(d, off, 0)[1] + 4;
            for (int i = 0; i < an + ns + ar; i++) {
                Object[] nm = readName(d, off, 0);
                String name = (String) nm[0];
                off = (Integer) nm[1];
                ByteBuffer r = ByteBuffer.wrap(d, off, 10);
                int type = r.getShort() & 0xffff;
                r.getShort();
                r.getInt();
                int rdlen = r.getShort() & 0xffff;
                off += 10;
                if (type == 12) out.add(new Object[]{"PTR", name, readName(d, off, 0)[0]});
                else if (type == 33) {
                    int port = ((d[off + 4] & 0xff) << 8) | (d[off + 5] & 0xff);
                    out.add(new Object[]{"SRV", name, new Object[]{readName(d, off + 6, 0)[0], port}});
                } else if (type == 16) {
                    Map<String, String> t = new HashMap<String, String>();
                    int p = 0;
                    while (p < rdlen) {
                        int ln = d[off + p] & 0xff;
                        String kv = new String(d, off + p + 1, ln, "UTF-8");
                        int e = kv.indexOf('=');
                        if (e > 0) t.put(kv.substring(0, e), kv.substring(e + 1));
                        p += 1 + ln;
                    }
                    out.add(new Object[]{"TXT", name, t});
                } else if (type == 1 && rdlen == 4) {
                    out.add(new Object[]{"A", name, (d[off] & 0xff) + "." + (d[off + 1] & 0xff) + "." + (d[off + 2] & 0xff) + "." + (d[off + 3] & 0xff)});
                }
                off += rdlen;
            }
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ scan
    public static List<Found> scan(int port, final Map<String, String> known, final Progress pg) {
        List<String> nets = localNetworks();
        final List<String> hosts = new ArrayList<String>();
        for (String n : nets) for (int i = 1; i < 255; i++) hosts.add(n + i);
        final int[] ports = port == 5555 ? new int[]{5555} : new int[]{port, 5555};
        final Map<String, Found> found = new ConcurrentHashMap<String, Found>();
        final int total = hosts.size() + 1;
        final AtomicInteger done = new AtomicInteger();
        ExecutorService ex = Executors.newFixedThreadPool(96);
        Future<List<Map<String, Object>>> mdns = ex.submit(new java.util.concurrent.Callable<List<Map<String, Object>>>() {
            public List<Map<String, Object>> call() {
                return mdnsBrowse(2500);
            }
        });
        List<Future<?>> fs = new ArrayList<Future<?>>();
        for (final String ip : hosts) {
            fs.add(ex.submit(new Runnable() {
                public void run() {
                    if (pg.stopped()) return;
                    for (int p : ports) {
                        if (portOpen(ip, p, 600)) {
                            Found f = new Found();
                            f.ip = ip;
                            f.port = p;
                            f.adb = "auth";
                            found.put(ip, f);
                            break;
                        }
                    }
                    int n = done.incrementAndGet();
                    if (n % 8 == 0) pg.on(n, total, new ArrayList<Found>(found.values()));
                }
            }));
        }
        for (Future<?> f : fs) {
            try {
                f.get();
            } catch (Exception ignored) {
            }
        }
        List<Map<String, Object>> services;
        try {
            services = mdns.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            services = new ArrayList<Map<String, Object>>();
        }
        for (Map<String, Object> sv : services) {
            String ip = (String) sv.get("ip");
            if (ip == null || ip.isEmpty()) continue;
            Found f = found.get(ip);
            if (f == null) {
                f = new Found();
                f.ip = ip;
                found.put(ip, f);
            }
            String svc = (String) sv.get("service");
            @SuppressWarnings("unchecked") Map<String, String> txt = (Map<String, String>) sv.get("txt");
            int sport = (Integer) sv.get("port");
            if (svc.startsWith("_googlecast")) {
                String nm = txt.containsKey("fn") ? txt.get("fn") : (String) sv.get("instance");
                String md = txt.containsKey("md") ? txt.get("md") : "";
                f.details.add("Google Cast" + (md.isEmpty() ? "" : " · " + md));
                if (f.name.isEmpty()) f.name = nm;
                f.sources.add("cast");
            } else if (svc.startsWith("_androidtvremote2")) {
                if (f.name.isEmpty()) f.name = (String) sv.get("instance");
                f.sources.add("atv");
            } else if (svc.startsWith("_adb-tls-connect") && sport > 0) {
                if (f.port == 0) {
                    f.port = sport;
                    f.adb = "tls";
                }
                f.sources.add("adb-tls");
            } else if (svc.startsWith("_adb._tcp") && sport > 0 && f.port == 0) {
                f.port = sport;
                f.adb = "auth";
            }
        }
        List<Future<?>> en = new ArrayList<Future<?>>();
        for (final Found f : found.values()) {
            en.add(ex.submit(new Runnable() {
                public void run() {
                    if (f.port > 0 && f.adb.equals("auth")) {
                        Map<String, String> props = new HashMap<String, String>();
                        if ("open".equals(banner(f.ip, f.port, props))) {
                            f.adb = "open";
                            String model = props.containsKey("ro.product.model") ? props.get("ro.product.model")
                                    : (props.containsKey("ro.product.name") ? props.get("ro.product.name") : "");
                            f.model = model.replace("_", " ").trim();
                            String dev = props.get("ro.product.device");
                            if (dev != null && !f.model.contains(dev)) f.details.add(0, dev);
                        }
                    }
                    try {
                        String host = InetAddress.getByName(f.ip).getCanonicalHostName();
                        if (host != null && !host.equals(f.ip)) {
                            f.details.add(host.split("\\.")[0]);
                            f.sources.add("dns");
                        }
                    } catch (Exception ignored) {
                    }
                    String hist = known.get(f.ip);
                    if (!f.model.isEmpty()) f.name = f.model;
                    else if (hist != null) f.name = hist;
                    else if (f.name.isEmpty())
                        f.name = f.sources.contains("dns") && !f.details.isEmpty() ? f.details.get(f.details.size() - 1) : f.ip;
                }
            }));
        }
        for (Future<?> f : en) {
            try {
                f.get(10, TimeUnit.SECONDS);
            } catch (Exception ignored) {
            }
        }
        ex.shutdownNow();
        List<Found> out = new ArrayList<Found>(found.values());
        Collections.sort(out, new Comparator<Found>() {
            public int compare(Found a, Found b) {
                int oa = order(a.adb), ob = order(b.adb);
                if (oa != ob) return oa - ob;
                return ipKey(a.ip) < ipKey(b.ip) ? -1 : (ipKey(a.ip) == ipKey(b.ip) ? 0 : 1);
            }
        });
        pg.on(total, total, out);
        return out;
    }

    static int order(String s) {
        return "open".equals(s) ? 0 : "auth".equals(s) ? 1 : "tls".equals(s) ? 2 : 3;
    }

    public static long ipKey(String ip) {
        long k = 0;
        for (String p : ip.split("\\.")) {
            try {
                k = k * 256 + Integer.parseInt(p);
            } catch (NumberFormatException e) {
                k = k * 256;
            }
        }
        return k;
    }
}

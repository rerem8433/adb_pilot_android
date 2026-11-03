package fr.paradoxxx.adbpilot.adb;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/** Protocole fastboot sur USB (OTG). */
public final class Fastboot {
    public interface Out {
        void line(String s, boolean error);
    }

    private final UsbDeviceConnection conn;
    private final UsbInterface iface;
    private final UsbEndpoint in, out;
    public final String serial;
    private static final Charset ASCII = Charset.forName("US-ASCII");

    public static UsbInterface findInterface(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface f = d.getInterface(i);
            if (f.getInterfaceClass() == 0xff && f.getInterfaceSubclass() == 0x42 && f.getInterfaceProtocol() == 0x03)
                return f;
        }
        return null;
    }

    public Fastboot(UsbManager mgr, UsbDevice dev, UsbInterface iface) throws IOException {
        this.iface = iface;
        UsbEndpoint ei = null, eo = null;
        for (int i = 0; i < iface.getEndpointCount(); i++) {
            UsbEndpoint e = iface.getEndpoint(i);
            if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (e.getDirection() == UsbConstants.USB_DIR_IN) ei = e;
            else eo = e;
        }
        if (ei == null || eo == null) throw new IOException("interface fastboot invalide");
        in = ei;
        out = eo;
        conn = mgr.openDevice(dev);
        if (conn == null) throw new IOException("ouverture USB refusée");
        if (!conn.claimInterface(iface, true)) {
            conn.close();
            throw new IOException("interface USB occupée");
        }
        String s = null;
        try {
            s = conn.getSerial();
        } catch (Throwable ignored) {
        }
        serial = s == null ? dev.getDeviceName() : s;
    }

    public void close() {
        try {
            conn.releaseInterface(iface);
        } catch (Throwable ignored) {
        }
        conn.close();
    }

    private void send(byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = Math.min(b.length - off, 16384);
            byte[] c = b;
            if (off != 0 || n != b.length) {
                c = new byte[n];
                System.arraycopy(b, off, c, 0, n);
            }
            int r = conn.bulkTransfer(out, c, n, 10000);
            if (r < 0) throw new IOException("écriture USB impossible");
            off += r;
        }
    }

    private String recv(int timeout) throws IOException {
        byte[] b = new byte[256];
        int n = conn.bulkTransfer(in, b, b.length, timeout);
        if (n < 0) throw new IOException("pas de réponse de l'appareil");
        return new String(b, 0, n, ASCII);
    }

    /** Envoie une commande ; renvoie la valeur OKAY. Lève une exception sur FAIL. */
    public String command(String cmd, Out o) throws IOException {
        send(cmd.getBytes(ASCII));
        return response(o, 0);
    }

    private String response(Out o, long dataSize) throws IOException {
        while (true) {
            String r = recv(120000);
            if (r.length() < 4) continue;
            String tag = r.substring(0, 4), v = r.substring(4);
            if ("INFO".equals(tag) || "TEXT".equals(tag)) {
                if (o != null) o.line("(bootloader) " + v, false);
            } else if ("OKAY".equals(tag)) {
                return v;
            } else if ("FAIL".equals(tag)) {
                throw new IOException(v);
            } else if ("DATA".equals(tag)) {
                return "DATA" + v;
            }
        }
    }

    public void download(InputStream data, long size, Out o, AdbDevice.Progress pg) throws IOException {
        send(String.format("download:%08x", size).getBytes(ASCII));
        String r = response(o, size);
        if (!r.startsWith("DATA")) throw new IOException("download refusé");
        byte[] buf = new byte[16384];
        long done = 0;
        int n;
        while ((n = data.read(buf)) > 0) {
            byte[] c = buf;
            if (n != buf.length) {
                c = new byte[n];
                System.arraycopy(buf, 0, c, 0, n);
            }
            int w = conn.bulkTransfer(out, c, n, 30000);
            if (w < 0) throw new IOException("envoi interrompu");
            done += n;
            if (pg != null) pg.onProgress(done, size);
        }
        response(o, 0);
    }
}

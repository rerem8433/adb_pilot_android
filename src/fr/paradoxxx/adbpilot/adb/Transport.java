package fr.paradoxxx.adbpilot.adb;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Liaison brute avec l'appareil : TCP (Wi-Fi) ou USB (OTG). */
public abstract class Transport {
    public abstract void write(byte[] data, int off, int len) throws IOException;

    public abstract void readFully(byte[] buf, int off, int len) throws IOException;

    public abstract void close();

    public abstract boolean isUsb();

    /** USB : un paquet vide quand la charge est un multiple de la taille de paquet USB. */
    public void endOfMessage(int payloadLen) throws IOException {
    }

    // ------------------------------------------------------------------ TCP
    public static final class Tcp extends Transport {
        private final Socket s;
        private final InputStream in;
        private final OutputStream out;

        public Tcp(String host, int port, int timeoutMs) throws IOException {
            s = fr.paradoxxx.adbpilot.NetScan.newSocket();
            s.setTcpNoDelay(true);
            s.setKeepAlive(true);
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            in = s.getInputStream();
            out = s.getOutputStream();
        }

        public void setReadTimeout(int ms) {
            try {
                s.setSoTimeout(ms);
            } catch (IOException ignored) {
            }
        }

        @Override
        public void write(byte[] data, int off, int len) throws IOException {
            out.write(data, off, len);
            out.flush();
        }

        @Override
        public void readFully(byte[] buf, int off, int len) throws IOException {
            while (len > 0) {
                int n = in.read(buf, off, len);
                if (n < 0) throw new IOException("connexion fermée");
                off += n;
                len -= n;
            }
        }

        @Override
        public void close() {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public boolean isUsb() {
            return false;
        }
    }

    // ------------------------------------------------------------------ USB
    public static final class Usb extends Transport {
        private final UsbDeviceConnection conn;
        private final UsbInterface iface;
        private final UsbEndpoint epIn, epOut;
        private final int maxPacket;
        private volatile boolean closed;

        public Usb(UsbManager mgr, UsbDevice dev, UsbInterface iface) throws IOException {
            this.iface = iface;
            UsbEndpoint ei = null, eo = null;
            for (int i = 0; i < iface.getEndpointCount(); i++) {
                UsbEndpoint e = iface.getEndpoint(i);
                if (e.getType() != android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                if (e.getDirection() == android.hardware.usb.UsbConstants.USB_DIR_IN) ei = e;
                else eo = e;
            }
            if (ei == null || eo == null) throw new IOException("points d'accès USB introuvables");
            epIn = ei;
            epOut = eo;
            maxPacket = eo.getMaxPacketSize();
            conn = mgr.openDevice(dev);
            if (conn == null) throw new IOException("ouverture USB refusée");
            if (!conn.claimInterface(iface, true)) {
                conn.close();
                throw new IOException("interface USB occupée");
            }
        }

        public UsbDeviceConnection connection() {
            return conn;
        }

        @Override
        public void write(byte[] data, int off, int len) throws IOException {
            while (len > 0) {
                int chunk = Math.min(len, 16384);
                byte[] b = data;
                if (off != 0) {
                    b = new byte[chunk];
                    System.arraycopy(data, off, b, 0, chunk);
                }
                int n = conn.bulkTransfer(epOut, b, chunk, 5000);
                if (n < 0) throw new IOException("écriture USB impossible");
                off += n;
                len -= n;
            }
        }

        @Override
        public void endOfMessage(int payloadLen) throws IOException {
            if (payloadLen > 0 && maxPacket > 0 && payloadLen % maxPacket == 0) {
                conn.bulkTransfer(epOut, new byte[0], 0, 1000);
            }
        }

        @Override
        public void readFully(byte[] buf, int off, int len) throws IOException {
            byte[] tmp = null;
            while (len > 0) {
                if (closed) throw new IOException("USB fermé");
                int chunk = Math.min(len, 16384);
                int n;
                if (off == 0) {
                    n = conn.bulkTransfer(epIn, buf, chunk, 0);
                } else {
                    if (tmp == null || tmp.length < chunk) tmp = new byte[chunk];
                    n = conn.bulkTransfer(epIn, tmp, chunk, 0);
                    if (n > 0) System.arraycopy(tmp, 0, buf, off, n);
                }
                if (n < 0) throw new IOException("lecture USB impossible (appareil débranché ?)");
                off += n;
                len -= n;
            }
        }

        @Override
        public void close() {
            closed = true;
            try {
                conn.releaseInterface(iface);
            } catch (Throwable ignored) {
            }
            try {
                conn.close();
            } catch (Throwable ignored) {
            }
        }

        @Override
        public boolean isUsb() {
            return true;
        }
    }
}

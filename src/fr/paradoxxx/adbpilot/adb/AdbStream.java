package fr.paradoxxx.adbpilot.adb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Un flux ADB (shell, sync, exec, socket…) avec contrôle de flux OKAY. */
public final class AdbStream {
    private static final byte[] EOF = new byte[0];
    private final AdbConnection c;
    final int localId;
    private volatile int remoteId;
    private final Object lock = new Object();
    private boolean opened, refused, writeReady, remoteClosed, localClosed;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<byte[]>();
    private byte[] cur;
    private int curPos;
    private boolean eof;

    AdbStream(AdbConnection c, int localId) {
        this.c = c;
        this.localId = localId;
    }

    boolean waitOpen(long ms) {
        long end = System.currentTimeMillis() + ms;
        synchronized (lock) {
            while (!opened && !refused && !remoteClosed) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) return false;
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    return false;
                }
            }
            return opened;
        }
    }

    void onOkay(int remote) {
        synchronized (lock) {
            remoteId = remote;
            opened = true;
            writeReady = true;
            lock.notifyAll();
        }
    }

    void onData(byte[] d) {
        if (d.length > 0) queue.add(d);
    }

    void onRemoteClose() {
        synchronized (lock) {
            if (!opened) refused = true;
            remoteClosed = true;
            lock.notifyAll();
        }
        queue.add(EOF);
    }

    public boolean isClosed() {
        return remoteClosed || localClosed;
    }

    // ------------------------------------------------------------------ écriture
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    public void write(byte[] b, int off, int len) throws IOException {
        int max = c.maxData();
        while (len > 0) {
            int n = Math.min(len, max);
            synchronized (lock) {
                while (!writeReady && !remoteClosed && !localClosed) {
                    try {
                        lock.wait(30000);
                    } catch (InterruptedException e) {
                        throw new IOException("interrompu");
                    }
                }
                if (remoteClosed || localClosed) throw new IOException("flux fermé");
                writeReady = false;
            }
            c.send(AdbConnection.A_WRTE, localId, remoteId, b, off, n);
            off += n;
            len -= n;
        }
    }

    // ------------------------------------------------------------------ lecture
    /** Lit au plus len octets ; -1 en fin de flux. */
    public int read(byte[] b, int off, int len) throws IOException {
        if (eof) return -1;
        if (cur == null || curPos >= cur.length) {
            try {
                cur = queue.take();
            } catch (InterruptedException e) {
                throw new IOException("interrompu");
            }
            curPos = 0;
            if (cur == EOF) {
                eof = true;
                queue.add(EOF);
                return -1;
            }
        }
        int n = Math.min(len, cur.length - curPos);
        System.arraycopy(cur, curPos, b, off, n);
        curPos += n;
        return n;
    }

    /** Lecture avec délai (null si rien dans le délai). */
    public byte[] poll(long ms) throws IOException {
        if (eof) return null;
        if (cur != null && curPos < cur.length) {
            byte[] r = new byte[cur.length - curPos];
            System.arraycopy(cur, curPos, r, 0, r.length);
            cur = null;
            return r;
        }
        try {
            byte[] d = queue.poll(ms, TimeUnit.MILLISECONDS);
            if (d == EOF) {
                eof = true;
                queue.add(EOF);
                return null;
            }
            return d == null ? new byte[0] : d;
        } catch (InterruptedException e) {
            throw new IOException("interrompu");
        }
    }

    public boolean atEof() {
        return eof;
    }

    public void readFully(byte[] b, int off, int len) throws IOException {
        while (len > 0) {
            int n = read(b, off, len);
            if (n < 0) throw new IOException("fin de flux inattendue");
            off += n;
            len -= n;
        }
    }

    public byte[] readAll() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = read(buf, 0, buf.length)) >= 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    public InputStream inputStream() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                byte[] one = new byte[1];
                int n = AdbStream.this.read(one, 0, 1);
                return n < 0 ? -1 : one[0] & 0xff;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                return AdbStream.this.read(b, off, len);
            }

            @Override
            public void close() {
                AdbStream.this.close();
            }
        };
    }

    public OutputStream outputStream() {
        return new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                AdbStream.this.write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                AdbStream.this.write(b, off, len);
            }

            @Override
            public void close() {
                AdbStream.this.close();
            }
        };
    }

    public void close() {
        boolean send;
        synchronized (lock) {
            send = !localClosed && opened && !remoteClosed;
            localClosed = true;
            lock.notifyAll();
        }
        if (send) {
            try {
                c.send(AdbConnection.A_CLSE, localId, remoteId, null);
            } catch (IOException ignored) {
            }
        }
        c.forget(localId);
        queue.add(EOF);
    }
}

package WayFarMap.client.map.export;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Writes a PNG row by row, so a picture far bigger than the memory (a whole map at full detail) can be saved: only
 * the previous row is kept. RGBA, 8 bits per channel.
 */
final class PngStream implements Closeable {

    private static final byte[] SIGNATURE = { (byte) 137, 80, 78, 71, 13, 10, 26, 10 };
    private static final int CHUNK = 1 << 16;

    private final DataOutputStream out;
    private final int width;
    private final IdatStream idat = new IdatStream();
    private final DeflaterOutputStream deflated;
    private final Deflater deflater = new Deflater(6);
    private byte[] previous, current;
    private final byte[] filtered;
    private int rows;
    private final int height;

    PngStream(OutputStream target, int width, int height) throws IOException {
        this.out = new DataOutputStream(new BufferedOutputStream(target, CHUNK));
        this.width = width;
        this.height = height;
        out.write(SIGNATURE);
        byte[] header = new byte[13];
        putInt(header, 0, width);
        putInt(header, 4, height);
        header[8] = 8; // bits per channel
        header[9] = 6; // RGBA
        chunk("IHDR", header, header.length);
        deflated = new DeflaterOutputStream(idat, deflater, CHUNK);
        previous = new byte[width * 4];
        current = new byte[width * 4];
        filtered = new byte[width * 4 + 1];
    }

    /** Adds the next row: {@code width} ARGB pixels from the offset. */
    void row(int[] argb, int offset) throws IOException {
        for (int x = 0; x < width; x++) {
            int c = argb[offset + x];
            int i = x * 4;
            current[i] = (byte) (c >> 16);
            current[i + 1] = (byte) (c >> 8);
            current[i + 2] = (byte) c;
            current[i + 3] = (byte) (c >>> 24);
        }
        // "Up" filter: the difference to the row above packs well for maps, where rows look alike.
        filtered[0] = 2;
        for (int i = 0; i < current.length; i++) {
            filtered[i + 1] = (byte) (current[i] - previous[i]);
        }
        deflated.write(filtered);
        byte[] swap = previous;
        previous = current;
        current = swap;
        rows++;
    }

    @Override
    public void close() throws IOException {
        try {
            if (rows == height) {
                deflated.finish();
                idat.flushChunk();
                chunk("IEND", new byte[0], 0);
            }
            out.flush();
        } finally {
            deflater.end();
            out.close();
        }
    }

    private void chunk(String type, byte[] data, int length) throws IOException {
        byte[] name = type.getBytes("US-ASCII");
        out.writeInt(length);
        out.write(name);
        out.write(data, 0, length);
        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data, 0, length);
        out.writeInt((int) crc.getValue());
    }

    private static void putInt(byte[] b, int at, int v) {
        b[at] = (byte) (v >>> 24);
        b[at + 1] = (byte) (v >>> 16);
        b[at + 2] = (byte) (v >>> 8);
        b[at + 3] = (byte) v;
    }

    /** The compressed rows, cut into IDAT chunks. */
    private final class IdatStream extends OutputStream {

        private final byte[] buffer = new byte[CHUNK];
        private int length;

        @Override
        public void write(int b) throws IOException {
            buffer[length++] = (byte) b;
            if (length == buffer.length) {
                flushChunk();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            while (len > 0) {
                int n = Math.min(len, buffer.length - length);
                System.arraycopy(b, off, buffer, length, n);
                length += n;
                off += n;
                len -= n;
                if (length == buffer.length) {
                    flushChunk();
                }
            }
        }

        @Override
        public void flush() throws IOException {
            // Chunks are written when full or at the end, not on every flush of the compressor.
        }

        void flushChunk() throws IOException {
            if (length > 0) {
                chunk("IDAT", buffer, length);
                length = 0;
            }
        }
    }
}

package eu.dariolucia.jsatdecode.source.fmcomms2;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/* Java socket implementation of the legacy IIOD protocol documented by libiio
   v0.26 iiod-client.c and retained by main/iiod/parser.y. No BINARY negotiation,
   ZSTD, libiio binding, JNI, JNA or native dependency. One owner serializes all
   transactions; another thread may ONLY close the socket to cancel blocking I/O.
   Numeric lines, mask lines and binary bodies share one buffered input: never
   put a Reader in front of binary sample data. See docs/fmcomms2-source.md. */
final class IiodClient implements AutoCloseable {
    private final Socket socket = new Socket();
    private InputStream input;
    private OutputStream output;
    private final byte[] lineBuffer = new byte[4096];

    void connect(Fmcomms2Settings settings) throws IOException {
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(settings.readTimeout());
        socket.connect(new InetSocketAddress(settings.host(), settings.port()), settings.connectTimeout());
        input = new BufferedInputStream(socket.getInputStream(), 65536);
        output = new BufferedOutputStream(socket.getOutputStream(), 4096);
    }

    static final class RemoteException extends IOException {
        final int errno;

        RemoteException(String command, int code) {
            super("IIOD " + command + " failed with errno " + (-code));
            errno = -code;
        }
    }

    int command(String command) throws IOException {
        send(command);
        return result(command);
    }

    private void send(String command) throws IOException {
        output.write((command + "\r\n").getBytes(StandardCharsets.US_ASCII));
        output.flush();
    }

    String line() throws IOException {
        byte[] bytes = lineBuffer;
        int size = 0;
        while (true) {
            int b = input.read();
            if (b < 0) throw new EOFException("IIOD disconnected inside response line");
            if (b == '\n') return new String(bytes, 0, size, StandardCharsets.US_ASCII).strip();
            if (size == bytes.length) throw new IOException("IIOD response line exceeds 4096 bytes");
            if (b > 127) throw new IOException("Non-ASCII IIOD response line");
            bytes[size++] = (byte) b;
        }
    }

    private int result(String command) throws IOException {
        for (int empty = 0; empty < 8; empty++) {
            String line = line();
            if (line.isEmpty()) continue;
            int value;
            try {
                value = Integer.parseInt(line);
            } catch (NumberFormatException e) {
                throw new IOException("Invalid IIOD integer response to " + command + ": " + line, e);
            }
            if (value < 0) throw new RemoteException(command, value);
            return value;
        }
        throw new IOException("Too many empty IIOD response lines");
    }

    byte[] contextXml() throws IOException {
        return response("PRINT", 2 * 1024 * 1024);
    }

    byte[] response(String command, int maximum) throws IOException {
        int size = command(command);
        if (size > maximum) throw new IOException("IIOD response exceeds bound: " + command);
        byte[] data = new byte[size];
        readFully(data, 0, size);
        if (input.read() != '\n') throw new IOException("Missing IIOD response body newline");
        return data;
    }

    void write(String attribute, byte[] value) throws IOException {
        output.write(("WRITE " + attribute + " " + value.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(value);
        output.flush();
        if (result("WRITE " + attribute) != value.length)
            throw new IOException("Short IIOD attribute write: " + attribute);
    }

    void write(String attribute, String value) throws IOException {
        // libiio's string attribute writes include the terminating NUL, not LF.
        write(attribute, (value + '\0').getBytes(StandardCharsets.US_ASCII));
    }

    long readLong(String attribute) throws IOException {
        String text = readText(attribute);
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new IOException("Invalid IIOD numeric attribute " + attribute + ": " + text, e);
        }
    }

    String readText(String attribute) throws IOException {
        return new String(response("READ " + attribute, 4096), StandardCharsets.US_ASCII).replace("\0", "").strip();
    }

    int readBuffer(String device, String expectedMask, byte[] bytes) throws IOException {
        send("READBUF " + device + " " + bytes.length);
        int used = 0;
        boolean first = true;
        while (used < bytes.length) {
            int count = result("READBUF");
            if (count == 0) break;
            if (count > bytes.length - used) throw new IOException("IIOD returned more sample bytes than requested");
            if (first) {
                String mask = line();
                if (!mask.equalsIgnoreCase(expectedMask))
                    throw new IOException("IIOD channel mask changed: expected " + expectedMask + ", got " + mask + "; another client may have enabled channels");
                first = false;
            }
            readFully(bytes, used, count);
            used += count;
        }
        if (used == 0) throw new IOException("IIOD READBUF made no progress");
        return used;
    }

    private void readFully(byte[] bytes, int offset, int count) throws IOException {
        int end = offset + count;
        while (offset < end) {
            int read = input.read(bytes, offset, end - offset);
            if (read < 0) throw new EOFException("IIOD disconnected inside binary body");
            offset += read;
        }
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) { /* cancellation must remain nonblocking */ }
    }
}

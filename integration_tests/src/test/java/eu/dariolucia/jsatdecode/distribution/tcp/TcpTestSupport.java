package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpMessage;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpMessageDecoder;
import java.io.DataInputStream;
import java.io.EOFException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;

final class TcpTestSupport {
    private TcpTestSupport() { }
    static int port() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }
    static TcpMessage read(Socket socket) throws Exception {
        var input = new DataInputStream(socket.getInputStream());
        byte[] header = input.readNBytes(8);
        if (header.length != 8) throw new EOFException("Incomplete JTDP header");
        if ((header[0] & 255) != 0xA5) throw new AssertionError("Wrong magic");
        long length = Integer.toUnsignedLong(ByteBuffer.wrap(header).getInt(4));
        if (length < 8 || length > 1_048_576) throw new AssertionError("Invalid wire length: " + length);
        byte[] body = input.readNBytes((int) length - 8);
        if (body.length != length - 8) throw new EOFException("Incomplete JTDP payload");
        byte[] message = new byte[(int) length];
        System.arraycopy(header, 0, message, 0, 8);
        System.arraycopy(body, 0, message, 8, body.length);
        return TcpMessageDecoder.decodeSingle(message);
    }
}

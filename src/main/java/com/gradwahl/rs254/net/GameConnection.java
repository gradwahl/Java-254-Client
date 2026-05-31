package com.gradwahl.rs254.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

public final class GameConnection implements AutoCloseable {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private GameConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
    }

    public static GameConnection open(String host, int port) throws IOException {
        System.out.println("[net] Connecting to game TCP " + host + ":" + port + " ...");
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 10_000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(20_000);
        System.out.println("[net] TCP connected");
        return new GameConnection(socket);
    }

    public void send(byte[] data) throws IOException {
        out.write(data);
        out.flush();
    }

    public int read() throws IOException {
        int b = in.read();
        if (b == -1) throw new IOException("Connection closed");
        return b;
    }

    public byte[] readBytes(int len) throws IOException {
        byte[] out = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(out, off, len - off);
            if (n == -1) throw new IOException("Connection closed");
            off += n;
        }
        return out;
    }

    @Override
    public void close() {
        try { socket.close(); } catch (IOException ignored) {}
    }
}

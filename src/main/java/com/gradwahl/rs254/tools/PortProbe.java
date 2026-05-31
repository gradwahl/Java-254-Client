package com.gradwahl.rs254.tools;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class PortProbe {
    private static final int[] PORTS = {80, 8080, 8888, 43594, 43595, 43500, 45099, 43501};

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "localhost";
        for (int port : PORTS) {
            probe(host, port);
        }
    }

    private static void probe(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1200);
            socket.setSoTimeout(1200);

            OutputStream out = socket.getOutputStream();
            out.write(("GET /crc HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            InputStream in = socket.getInputStream();
            byte[] buf = new byte[16];
            int read = in.read(buf);

            if (read <= 0) {
                System.out.println(port + " OPEN but sent no HTTP response. This is probably NOT the web /crc port.");
                return;
            }

            String head = new String(buf, 0, read, StandardCharsets.ISO_8859_1).replace("\r", "\\r").replace("\n", "\\n");
            if (head.startsWith("HTTP/1.")) {
                System.out.println(port + " HTTP response: " + head);
            } else {
                System.out.println(port + " OPEN but first bytes are not HTTP: " + head);
            }
        } catch (Exception ex) {
            System.out.println(port + " closed/unreachable: " + ex.getClass().getSimpleName());
        }
    }
}

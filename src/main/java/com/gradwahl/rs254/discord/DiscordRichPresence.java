package com.gradwahl.rs254.discord;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public final class DiscordRichPresence implements Closeable {
    private static final int OP_HANDSHAKE = 0;
    private static final int OP_FRAME = 1;
    private static final int OP_CLOSE = 2;

    private final String appId;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "discord-rpc");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger nonce = new AtomicInteger();

    private RandomAccessFile pipe;
    private long startTime;
    private boolean connected;

    public DiscordRichPresence(String appId) {
        this.appId = appId;
    }

    public void connect() {
        worker.execute(() -> {
            disconnectNow();
            startTime = System.currentTimeMillis() / 1000L;
            for (int i = 0; i < 10; i++) {
                RandomAccessFile candidate = null;
                try {
                    candidate = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
                    writePacket(candidate, OP_HANDSHAKE, "{\"v\":1,\"client_id\":\"" + escape(appId) + "\"}");
                    readPacket(candidate);
                    pipe = candidate;
                    connected = true;
                    return;
                } catch (IOException ignored) {
                    if (candidate != null) {
                        try {
                            candidate.close();
                        } catch (IOException ignoredClose) {
                        }
                    }
                }
            }
            System.err.println("[discord] could not connect to Discord IPC (is Discord running?)");
        });
    }

    public void updateActivity(String details, String state) {
        worker.execute(() -> {
            if (!connected || pipe == null) return;
            String activity = "{\"details\":\"" + escape(details) + "\""
                    + ",\"state\":\"" + escape(state) + "\""
                    + ",\"timestamps\":{\"start\":" + startTime + "}"
                    + ",\"assets\":{\"large_image\":\"logo\",\"large_text\":\"LostCity RSPS\"}}";
            String payload = "{\"cmd\":\"SET_ACTIVITY\""
                    + ",\"args\":{\"pid\":" + ProcessHandle.current().pid() + ",\"activity\":" + activity + "}"
                    + ",\"nonce\":\"" + nonce.incrementAndGet() + "\"}";
            try {
                writePacket(pipe, OP_FRAME, payload);
                readPacket(pipe);
            } catch (IOException e) {
                disconnectNow();
            }
        });
    }

    public void disconnect() {
        worker.execute(this::disconnectNow);
    }

    private void disconnectNow() {
        RandomAccessFile f = pipe;
        pipe = null;
        connected = false;
        if (f == null) return;
        try {
            writePacket(f, OP_CLOSE, "{}");
        } catch (IOException ignored) {
        }
        try {
            f.close();
        } catch (IOException ignored) {
        }
    }

    private static void writePacket(RandomAccessFile f, int opcode, String payload) throws IOException {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(opcode);
        header.putInt(data.length);
        f.write(header.array());
        f.write(data);
    }

    private static void readPacket(RandomAccessFile f) throws IOException {
        byte[] header = new byte[8];
        f.readFully(header);
        int length = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (length > 0 && length <= 65_535) {
            byte[] body = new byte[length];
            f.readFully(body);
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public void close() {
        disconnect();
        worker.shutdownNow();
    }
}

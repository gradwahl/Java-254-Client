package com.gradwahl.rs254.cache;

import com.gradwahl.rs254.ClientConfig;
import com.gradwahl.rs254.io.PacketBuffer;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public final class RemoteCache {
    private final ClientConfig config;
    private final HttpClient client = HttpClient.newHttpClient();

    public RemoteCache(ClientConfig config) {
        this.config = config;
    }

    private static final java.util.Random RNG = new java.util.Random();

    public int[] fetchCrcs() throws IOException, InterruptedException {
        try {
            int suffix = (int) (RNG.nextDouble() * 9.9999999E7);
            String path = "/crc" + suffix;
            System.out.println("Trying " + config.httpBaseUri() + path);
            byte[] data = fetch(path);
            if (data.length < 36) {
                throw new IOException("/crc returned " + data.length + " bytes, expected 36");
            }
            System.out.println("Loaded CRCs from HTTP.");
            return decodeCrcs(data);
        } catch (Exception httpError) {
            System.out.println("HTTP /crc failed: " + httpError.getClass().getSimpleName() + ": " + httpError.getMessage());
            DiskCache disk = new DiskCache(config.cacheDir());
            if (!disk.exists()) {
                if (httpError instanceof IOException e) throw e;
                if (httpError instanceof InterruptedException e) throw e;
                throw new IOException("HTTP /crc failed and no local cache exists at " + config.cacheDir(), httpError);
            }
            System.out.println("Using local cache CRCs from " + config.cacheDir());
            return disk.crcsForIndex0();
        }
    }

    private static int[] decodeCrcs(byte[] data) {
        PacketBuffer buf = new PacketBuffer(data);
        int[] crcs = new int[9];
        for (int i = 0; i < crcs.length; i++) crcs[i] = buf.g4();
        return crcs;
    }

    public byte[] fetch(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.httpBaseUri() + path)).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IOException(path + " returned HTTP " + response.statusCode());
        }
        return response.body();
    }
}

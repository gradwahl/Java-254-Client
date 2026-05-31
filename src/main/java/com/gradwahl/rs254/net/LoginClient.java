package com.gradwahl.rs254.net;

import com.gradwahl.rs254.ClientConfig;
import com.gradwahl.rs254.cache.DiskCache;
import com.gradwahl.rs254.cache.RemoteCache;
import com.gradwahl.rs254.io.IsaacCipher;
import com.gradwahl.rs254.io.PacketBuffer;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Random;

public final class LoginClient {
    private static final BigInteger RSA_EXPONENT = new BigInteger("58778699976184461502525193738213253649000149147835990136706041084440742975821");
    private static final BigInteger RSA_MODULUS  = new BigInteger("7162900525229798032761816791230527296329313291232324290237849263501208207972894053929065636522363163621000728841182238772712427862772219676577293600221789");

    private final ClientConfig config;
    private final Random random = new Random();

    public LoginClient(ClientConfig config) {
        this.config = config;
    }

    /**
     * Performs the full login handshake. Returns a live {@link GameSession} on
     * success (response code 2). Throws an IOException with a human-readable
     * message for all other response codes.
     */
    public GameSession login(String username, String password, boolean reconnecting) throws Exception {
        int[] crcs = new RemoteCache(config).fetchCrcs();
        int uid = new DiskCache(config.cacheDir()).getUid();

        GameConnection connection = GameConnection.open(config.host(), config.gamePort());
        boolean success = false;
        try {
            long userhash    = usernameHash(username);
            int  loginServer = (int) ((userhash >>> 16) & 0x1f);

            PacketBuffer handshake = new PacketBuffer(2);
            handshake.p1(14);
            handshake.p1(loginServer);
            connection.send(handshake.bytes());

            connection.readBytes(8); // server sends 8 zero bytes
            int response = connection.read();
            if (response != 0) {
                throw new IOException("Handshake rejected: response=" + response);
            }

            long serverSeed = new PacketBuffer(connection.readBytes(8)).g8();
            int[] seed = {
                random.nextInt(99_999_999),
                random.nextInt(99_999_999),
                (int) (serverSeed >>> 32),
                (int) serverSeed
            };

            PacketBuffer rsa = new PacketBuffer(256);
            rsa.p1(10);
            for (int value : seed) rsa.p4(value);
            rsa.p4(uid);
            rsa.pjstr(username);
            rsa.pjstr(password);
            rsa.rsaenc(RSA_MODULUS, RSA_EXPONENT);

            PacketBuffer login = new PacketBuffer(1 + 1 + 1 + 1 + 36 + rsa.pos);
            login.p1(reconnecting ? 18 : 16);
            login.p1(rsa.pos + 36 + 1 + 1);
            login.p1(config.revision());
            login.p1(0); // low memory flag
            for (int crc : crcs) login.p4(crc);
            login.pdata(rsa.data, 0, rsa.pos);

            IsaacCipher outbound = new IsaacCipher(seed.clone());
            int[] inboundSeed = seed.clone();
            for (int i = 0; i < inboundSeed.length; i++) inboundSeed[i] += 50;
            IsaacCipher inbound = new IsaacCipher(inboundSeed);

            connection.send(login.bytes());
            response = connection.read();

            if (response != 2) {
                throw new IOException(loginResponseMessage(response));
            }

            connection.read(); // staff level (unused for now)
            connection.read(); // mouse tracking flag (unused for now)

            System.out.println("[login] Logged in successfully");
            success = true;
            return new GameSession(connection, inbound, outbound);
        } finally {
            if (!success) connection.close();
        }
    }

    private static String loginResponseMessage(int code) {
        return switch (code) {
            case 3  -> "Invalid username or password";
            case 4  -> "Account disabled";
            case 5  -> "Account already logged in";
            case 6  -> "Client out of date";
            case 7  -> "World is full";
            case 8  -> "Login server offline";
            case 9  -> "Too many login attempts";
            case 10 -> "Bad session ID";
            case 11 -> "Login server rejected session";
            case 14 -> "Login attempts exceeded";
            case 15 -> "Members account required";
            case 16 -> "Too many login attempts (IP)";
            case 18 -> "Standing in a members-only area";
            default -> "Login failed (response=" + code + ")";
        };
    }

    private static long usernameHash(String username) {
        String clean = username.toLowerCase().trim();
        long hash = 0L;
        for (int i = 0; i < clean.length() && i < 12; i++) {
            char c = clean.charAt(i);
            hash *= 37L;
            if (c >= 'a' && c <= 'z') hash += 1 + c - 'a';
            else if (c >= '0' && c <= '9') hash += 27 + c - '0';
        }
        while (hash % 37L == 0L && hash != 0L) hash /= 37L;
        return hash;
    }
}

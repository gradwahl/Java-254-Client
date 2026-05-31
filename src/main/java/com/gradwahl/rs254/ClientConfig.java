package com.gradwahl.rs254;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ClientConfig(String host, int httpPort, int gamePort, boolean secure, int revision, String cacheDir, String dbPath) {

    private static final String CONFIG_FILE = "client.json";

    // Relative path within a server install that contains the database
    private static final String DB_RELATIVE = "lostcity-client" + File.separator + "db.sqlite";

    public static ClientConfig load() {
        File configFile = resolveConfigFile();
        boolean firstRun = !configFile.exists();

        if (firstRun) {
            String detectedDb = detectDbPath(configFile.getParentFile());
            String defaultConfig =
                "{\n" +
                "  \"web_host\": \"localhost\",\n" +
                "  \"web_port\": 80,\n" +
                "  \"game_port\": 43594,\n" +
                "  \"db_path\": \"" + detectedDb.replace("\\", "\\\\") + "\"\n" +
                "}\n";
            try {
                Files.writeString(configFile.toPath(), defaultConfig, StandardCharsets.UTF_8);
                System.out.println("[Config] Created default config: " + configFile.getAbsolutePath());
                if (!detectedDb.isEmpty()) {
                    System.out.println("[Config] Auto-detected db_path: " + detectedDb);
                } else {
                    System.out.println("[Config] Could not auto-detect db_path — set it manually in client.json");
                }
                System.out.println("[Config] Edit it to configure your connection, then restart.");
            } catch (IOException e) {
                System.err.println("[Config] Warning: could not write " + configFile + ": " + e.getMessage());
            }
        } else {
            System.out.println("[Config] Loaded config from: " + configFile.getAbsolutePath());
        }

        return parseFile(configFile);
    }

    /**
     * Walks up from the starting directory (up to 5 levels), scanning all sibling
     * directories at each level for a {@code lostcity-client/db.sqlite} file.
     */
    private static String detectDbPath(File startDir) {
        File dir = startDir;
        for (int i = 0; i < 5 && dir != null; i++, dir = dir.getParentFile()) {
            File[] siblings = dir.listFiles(File::isDirectory);
            if (siblings == null) continue;
            for (File sibling : siblings) {
                File candidate = new File(sibling, DB_RELATIVE);
                if (candidate.isFile()) {
                    return candidate.getAbsolutePath();
                }
            }
        }
        return "";
    }

    private static File resolveConfigFile() {
        // Place config next to the JAR, falling back to the working directory
        try {
            File jar = new File(ClientConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (jar.isFile()) {
                return new File(jar.getParentFile(), CONFIG_FILE);
            }
        } catch (Exception ignored) {}
        return new File(CONFIG_FILE);
    }

    private static ClientConfig parseFile(File file) {
        String host = "localhost";
        int httpPort = 80;
        int gamePort = 43594;
        boolean secure = false;
        int revision = 254;
        String cacheDir = "cache";
        String dbPath = "";

        if (file.exists()) {
            try {
                String json = Files.readString(file.toPath(), StandardCharsets.UTF_8);
                host = readString(json, "web_host", host);
                httpPort = readInt(json, "web_port", httpPort);
                gamePort = readInt(json, "game_port", gamePort);
                dbPath = readString(json, "db_path", dbPath);
            } catch (IOException e) {
                System.err.println("[Config] Warning: could not read " + file + ": " + e.getMessage());
            }
        }

        // System properties (-Drs254.host=...) override file values
        host = System.getProperty("rs254.host", host);
        httpPort = Integer.getInteger("rs254.httpPort", Integer.getInteger("rs254.port", httpPort));
        gamePort = Integer.getInteger("rs254.gamePort", Integer.getInteger("rs254.wsPort", gamePort));
        secure = Boolean.getBoolean("rs254.secure");
        revision = Integer.getInteger("rs254.revision", revision);
        cacheDir = System.getProperty("rs254.cacheDir", cacheDir);
        dbPath = System.getProperty("rs254.dbPath", dbPath);

        return new ClientConfig(host, httpPort, gamePort, secure, revision, cacheDir, dbPath);
    }

    private static String readString(String json, String key, String defaultValue) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\\\\\"]|\\\\.)*)\"").matcher(json);
        if (m.find()) {
            // Unescape JSON backslash sequences (\\  and \")
            return m.group(1).replace("\\\\", "\\").replace("\\\"", "\"");
        }
        return defaultValue;
    }

    private static int readInt(String json, String key, int defaultValue) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {}
        }
        return defaultValue;
    }

    /** @deprecated Use {@link #load()} instead. */
    @Deprecated
    public static ClientConfig fromSystemProperties() {
        return load();
    }

    public String httpBaseUri() {
        String scheme = secure ? "https" : "http";
        if ((secure && httpPort == 443) || (!secure && httpPort == 80)) {
            return scheme + "://" + host;
        }
        return scheme + "://" + host + ":" + httpPort;
    }

    public String gameAddress() {
        return host + ":" + gamePort;
    }
}

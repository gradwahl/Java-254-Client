package com.gradwahl.rs254;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.zip.CRC32;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        setupErrorLogging();
        applyEarlyGraphicsProperties();
        relaunchJarWithOneGbHeapIfNeeded(args);
        com.gradwahl.rs254.update.ClientUpdater.ensureUpdaterExtracted();
        ClientDebugger.enable();

        prepareBundledCache();

        ClientConfig config = ClientConfig.load();
        applyConfig(config);

        // Default: node-id=10  port-offset=0  highmem  members  storeid=32
        // Override via args: java -jar client.jar 10 0 highmem members 32
        String[] clientArgs = args.length == 5 ? args : new String[]{"10", "0", "highmem", "members", "32"};
        jagex2.client.Client.main(clientArgs);
    }

    private static void relaunchJarWithOneGbHeapIfNeeded(String[] args) throws Exception {
        if (Boolean.getBoolean("rs254.heapRelaunched") || Runtime.getRuntime().maxMemory() >= 900L * 1024L * 1024L) {
            return;
        }
        File current = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (!current.isFile() || !current.getName().toLowerCase().endsWith(".jar")) {
            return;
        }

        String javaBin = new File(System.getProperty("java.home"), "bin" + File.separator + "javaw").getPath();
        if (!new File(javaBin + (isWindows() ? ".exe" : "")).exists()) {
            javaBin = new File(System.getProperty("java.home"), "bin" + File.separator + "java").getPath();
        }

        List<String> command = new ArrayList<>();
        command.add(javaBin);
        command.add("-Xmx1g");
        command.add("-Drs254.heapRelaunched=true");
        command.add("-Dsun.java2d.noddraw=true");
        command.add("--enable-native-access=ALL-UNNAMED");
        command.add("--add-opens");
        command.add("java.base/java.lang=ALL-UNNAMED");
        command.add("--add-opens");
        command.add("java.base/java.lang.reflect=ALL-UNNAMED");
        command.add("-jar");
        command.add(current.getPath());
        for (String arg : args) {
            command.add(arg);
        }
        new ProcessBuilder(command)
                .directory(current.getParentFile())
                .start();
        System.exit(0);
    }

    /** Cache files bundled in the JAR under /cache and extracted on first run. */
    private static final String[] BUNDLED_CACHE_FILES = {
        "main_file_cache.dat",
        "main_file_cache.idx0",
        "main_file_cache.idx1",
        "main_file_cache.idx2",
        "main_file_cache.idx3",
        "main_file_cache.idx4",
    };

    /**
     * Resolve a fixed cache directory next to the JAR (independent of the
     * working directory the client was launched from) and, on first run,
     * populate it from the cache bundled inside the JAR. This lets the client
     * render without a running on-demand download server.
     *
     * Both cache consumers are pointed at this directory: the legacy signlink
     * store reads {@code rs254.cache.dir}, the newer DiskCache/RemoteCache
     * layer reads {@code rs254.cacheDir}.
     */
    private static void prepareBundledCache() {
        File cacheDir = resolveCacheDir();
        String path = cacheDir.getAbsolutePath();
        if (System.getProperty("rs254.cache.dir") == null) {
            System.setProperty("rs254.cache.bootstrapManaged", "true");
            System.setProperty("rs254.cache.dir", path);
        }
        if (System.getProperty("rs254.cacheDir") == null) {
            System.setProperty("rs254.cacheDir", path);
        }

        if (hasCompleteCache(cacheDir)) {
            return; // a populated cache already exists — never clobber it
        }
        if (Main.class.getResource("/cache/main_file_cache.dat") == null) {
            return; // no cache bundled in this build — nothing to extract
        }
        if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
            System.err.println("[Cache] Could not create cache dir: " + cacheDir);
            return;
        }

        for (String name : BUNDLED_CACHE_FILES) {
            try (InputStream in = Main.class.getResourceAsStream("/cache/" + name)) {
                if (in == null) continue;
                Files.copy(in, new File(cacheDir, name).toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("[Cache] Failed to extract " + name + ": " + e.getMessage());
            }
        }
        System.out.println("[Cache] Extracted bundled cache to " + cacheDir);
    }

    private static File resolveCacheDir() {
        String override = System.getProperty("rs254.cache.dir");
        if (override != null && !override.isBlank()) {
            return new File(override);
        }

        try {
            File jar = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (jar.isFile()) {
                File besideJar = new File(jar.getParentFile(), "cache");
                if (canUseCacheDir(besideJar)) {
                    return besideJar;
                }
            }
        } catch (Exception ignored) {}

        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            File userCache = new File(home, ".progressive-java-client" + File.separator + "file_store_32");
            if (canUseCacheDir(userCache)) {
                return userCache;
            }
        }

        return new File("cache");
    }

    private static boolean hasCompleteCache(File cacheDir) {
        if (Boolean.getBoolean("rs254.cache.bootstrapManaged")
                && Main.class.getResource("/cache/main_file_cache.dat") != null) {
            return hasMatchingBundledCache(cacheDir);
        }
        for (String name : BUNDLED_CACHE_FILES) {
            File file = new File(cacheDir, name);
            if (!file.isFile() || file.length() == 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasMatchingBundledCache(File cacheDir) {
        for (String name : BUNDLED_CACHE_FILES) {
            File file = new File(cacheDir, name);
            if (!file.isFile() || !resourceMatchesFile("/cache/" + name, file)) {
                return false;
            }
        }
        return true;
    }

    private static boolean resourceMatchesFile(String resourceName, File file) {
        try (InputStream resource = Main.class.getResourceAsStream(resourceName);
             InputStream local = Files.newInputStream(file.toPath())) {
            if (resource == null || file.length() == 0) {
                return false;
            }
            CRC32 resourceCrc = new CRC32();
            CRC32 localCrc = new CRC32();
            byte[] resourceBuffer = new byte[8192];
            byte[] localBuffer = new byte[8192];
            int resourceRead;
            int localRead;
            do {
                resourceRead = resource.read(resourceBuffer);
                localRead = local.read(localBuffer);
                if (resourceRead != localRead) {
                    return false;
                }
                if (resourceRead > 0) {
                    resourceCrc.update(resourceBuffer, 0, resourceRead);
                    localCrc.update(localBuffer, 0, localRead);
                }
            } while (resourceRead != -1);
            return resourceCrc.getValue() == localCrc.getValue();
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean canUseCacheDir(File dir) {
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return false;
            }
            File test = File.createTempFile(".write_test", ".tmp", dir);
            return test.delete();
        } catch (IOException e) {
            return false;
        }
    }

    private static void applyConfig(ClientConfig config) {
        // Publish loaded values as system properties so downstream code can read them
        System.setProperty("rs254.host", config.host());
        System.setProperty("rs254.httpPort", String.valueOf(config.httpPort()));
        System.setProperty("rs254.gamePort", String.valueOf(config.gamePort()));
        if (!config.dbPath().isEmpty()) {
            System.setProperty("rs254.dbPath", config.dbPath());
        }
    }

    private static void applyEarlyGraphicsProperties() {
        // Must be set before any AWT/Java2D classes create a Windows DirectDraw pipeline.
        System.setProperty("sun.java2d.noddraw", "true");
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void setupErrorLogging() {
        ErrorLogOutputStream.ensureLogDir();
        System.setErr(new PrintStream(new ErrorLogOutputStream(System.err), true));
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("\nCRASH on thread [" + t.getName() + "] at " + LocalDateTime.now());
            e.printStackTrace(System.err);
            System.err.flush();
        });
    }

    private static final class ErrorLogOutputStream extends OutputStream {
        private static final DateTimeFormatter LOG_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

        private final PrintStream console;
        private OutputStream log;
        private boolean logUnavailable;

        private ErrorLogOutputStream(PrintStream console) {
            this.console = console;
        }

        private static void ensureLogDir() {
            resolveLogDir().mkdirs();
        }

        @Override
        public synchronized void write(int value) throws IOException {
            console.write(value);
            openLogIfNeeded();
            if (log != null) {
                log.write(value);
            }
        }

        @Override
        public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
            console.write(bytes, offset, length);
            openLogIfNeeded();
            if (log != null) {
                log.write(bytes, offset, length);
            }
        }

        @Override
        public synchronized void flush() throws IOException {
            console.flush();
            if (log != null) {
                log.flush();
            }
        }

        private void openLogIfNeeded() {
            if (log != null || logUnavailable) {
                return;
            }
            try {
                File logDir = resolveLogDir();
                String timestamp = LocalDateTime.now().format(LOG_TIMESTAMP);
                log = new FileOutputStream(new File(logDir, "error_" + timestamp + ".log"), true);
            } catch (IOException e) {
                logUnavailable = true;
                console.println("[Logging] Could not create error log: " + e.getMessage());
            }
        }

        private static File resolveLogDir() {
            File base;
            String configuredLogDir = System.getProperty("rs254.logDir");
            if (configuredLogDir != null && !configuredLogDir.isBlank()) {
                base = new File(configuredLogDir);
            } else {
                try {
                    File jar = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                    base = jar.isFile() ? new File(jar.getParentFile(), "logs") : new File("logs");
                } catch (Exception ignored) {
                    base = new File("logs");
                }
            }
            base.mkdirs();
            return base;
        }
    }
}

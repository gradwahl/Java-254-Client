package com.gradwahl.rs254;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        setupErrorLogging();

        ClientConfig config = ClientConfig.load();
        applyConfig(config);

        // Default: node-id=10  port-offset=0  highmem  members  storeid=32
        // Override via args: java -jar client.jar 10 0 highmem members 32
        String[] clientArgs = args.length == 5 ? args : new String[]{"10", "0", "highmem", "members", "32"};
        jagex2.client.Client.main(clientArgs);
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

    private static void setupErrorLogging() {
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

        private File resolveLogDir() {
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

package com.gradwahl.rs254;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        setupCrashLogging();
        ClientDebugger.enable();

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

    private static void setupCrashLogging() {
        try {
            File logDir = new File(System.getProperty("user.home"), ".rs254");
            logDir.mkdirs();
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            File logFile = new File(logDir, "crash_" + timestamp + ".log");
            PrintStream log = new PrintStream(new FileOutputStream(logFile, true), true);
            System.setErr(log);
            System.err.println("Session started: " + LocalDateTime.now());
            System.err.println("Java: " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
            System.err.println("OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
            System.err.println("---");
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                System.err.println("\nCRASH on thread [" + t.getName() + "] at " + LocalDateTime.now());
                e.printStackTrace(System.err);
                System.err.flush();
            });
            System.out.println("Crash log: " + logFile.getAbsolutePath());
        } catch (Exception e) {
            // If logging setup fails, fall back to a simple handler
            Thread.setDefaultUncaughtExceptionHandler((t, ex) -> {
                System.err.println("UNCAUGHT on thread " + t.getName() + ": " + ex);
                ex.printStackTrace(System.err);
            });
        }
    }
}

package com.gradwahl.rs254;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            System.err.println("UNCAUGHT on thread " + t.getName() + ": " + e);
            e.printStackTrace(System.err);
        });
        // Default: node-id=10  port-offset=0  highmem  members  storeid=32
        // Override via args: java -jar client.jar 10 0 highmem members 32
        String[] clientArgs = args.length == 5 ? args : new String[]{"10", "0", "highmem", "members", "32"};
        jagex2.client.Client.main(clientArgs);
    }
}

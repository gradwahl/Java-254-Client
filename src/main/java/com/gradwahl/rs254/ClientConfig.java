package com.gradwahl.rs254;

public record ClientConfig(String host, int httpPort, int gamePort, boolean secure, int revision, String cacheDir) {
    public static ClientConfig fromSystemProperties() {
        String host = System.getProperty("rs254.host", "localhost");
        int httpPort = Integer.getInteger("rs254.httpPort", Integer.getInteger("rs254.port", 80));
        int gamePort = Integer.getInteger("rs254.gamePort", Integer.getInteger("rs254.wsPort", 43594));
        boolean secure = Boolean.getBoolean("rs254.secure");
        int revision = Integer.getInteger("rs254.revision", 254);
        String cacheDir = System.getProperty("rs254.cacheDir", "cache");
        return new ClientConfig(host, httpPort, gamePort, secure, revision, cacheDir);
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

package com.gradwahl.rs254;

public record ClientConfig(String host, int httpPort, int gamePort, boolean secure, int revision, boolean websocket) {
    public static ClientConfig fromSystemProperties() {
        String host = System.getProperty("rs254.host", "localhost");
        int httpPort = Integer.getInteger("rs254.httpPort", Integer.getInteger("rs254.webPort", 80));
        int gamePort = Integer.getInteger("rs254.gamePort", Integer.getInteger("rs254.nodePort", 43594));
        boolean secure = Boolean.getBoolean("rs254.secure");
        int revision = Integer.getInteger("rs254.revision", 254);
        boolean websocket = Boolean.getBoolean("rs254.websocket");
        return new ClientConfig(host, httpPort, gamePort, secure, revision, websocket);
    }

    public String httpBaseUri() {
        return (secure ? "https" : "http") + "://" + host + ":" + httpPort;
    }

    public String gameEndpoint() {
        if (websocket) {
            return (secure ? "wss" : "ws") + "://" + host + ":" + gamePort + "/";
        }
        return host + ":" + gamePort + " TCP";
    }
}

package com.gradwahl.rs254;

import com.gradwahl.rs254.net.GameSession;
import com.gradwahl.rs254.net.LoginClient;

import javax.swing.JFrame;

public final class ClientFrame extends JFrame {
    private final GameCanvas canvas;
    private final ClientConfig config;
    private volatile GameSession session;

    public ClientFrame(ClientConfig config) {
        super("Java 254 Client");
        this.config = config;
        this.canvas = new GameCanvas(config, this::sessionStatus, this::startLogin);

        add(canvas);
        pack();
        setResizable(false);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
    }

    public void start() {
        canvas.start();
        canvas.requestFocusInWindow();
    }

    private String sessionStatus() {
        GameSession s = session;
        return s != null ? s.status() : "";
    }

    private void startLogin(String username, String password) {
        GameSession old = session;
        if (old != null) { old.close(); session = null; }

        Thread t = new Thread(() -> {
            try {
                GameSession s = new LoginClient(config).login(username, password, false);
                session = s;
                canvas.enterGame();
                s.start();
            } catch (Exception ex) {
                // Show error on the title screen briefly, then let them retry
                canvas.showLoginError(ex.getMessage());
                ex.printStackTrace();
            }
        }, "login-thread");
        t.setDaemon(true);
        t.start();
    }
}

package com.gradwahl.rs254;

import com.gradwahl.rs254.net.GameSession;
import com.gradwahl.rs254.net.LoginClient;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import java.awt.image.BufferedImage;
import java.io.InputStream;

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
        loadIcon();
    }

    private void loadIcon() {
        try (InputStream is = ClientFrame.class.getResourceAsStream("/icon.ico")) {
            if (is == null) return;
            BufferedImage img = loadIco(is);
            if (img != null) setIconImage(img);
        } catch (Exception ignored) {}
    }

    private static BufferedImage loadIco(InputStream is) throws Exception {
        byte[] data = is.readAllBytes();
        if (data.length < 6) return null;
        int count = (data[4] & 0xFF) | ((data[5] & 0xFF) << 8);
        int bestW = -1, bestOff = 0, bestLen = 0;
        for (int i = 0; i < count; i++) {
            int base = 6 + i * 16;
            if (base + 16 > data.length) break;
            int w   = data[base] & 0xFF;
            if (w == 0) w = 256;
            int sz  = icoInt(data, base + 8);
            int off = icoInt(data, base + 12);
            if (w > bestW) { bestW = w; bestOff = off; bestLen = sz; }
        }
        if (bestW < 0) return null;
        return ImageIO.read(new java.io.ByteArrayInputStream(data, bestOff, bestLen));
    }

    private static int icoInt(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
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

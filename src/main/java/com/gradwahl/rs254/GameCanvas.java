package com.gradwahl.rs254;

import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public final class GameCanvas extends Canvas implements Runnable {
    public static final int WIDTH  = 765;
    public static final int HEIGHT = 503;

    private enum State { LOADING, TITLE, LOGGING_IN, IN_GAME, ERROR }

    // Input field rectangles — positioned over the titlebox sprite
    private static final int FIELD_W    = 250;
    private static final int FIELD_H    = 15;
    private static final int FIELD_X    = TitleScreen.FIELD_X;
    private static final int USER_Y     = TitleScreen.USERNAME_Y;
    private static final int PASS_Y     = TitleScreen.PASSWORD_Y;
    private static final int BTN_X      = TitleScreen.BTN_X;
    private static final int BTN_Y      = TitleScreen.BTN_Y;
    private static final int BTN_W      = 147;
    private static final int BTN_H      = 41;

    private static final Rectangle R_USER = new Rectangle(FIELD_X, USER_Y,  FIELD_W, FIELD_H);
    private static final Rectangle R_PASS = new Rectangle(FIELD_X, PASS_Y,  FIELD_W, FIELD_H);
    private static final Rectangle R_BTN  = new Rectangle(BTN_X,   BTN_Y,   BTN_W,   BTN_H);

    private final ClientConfig             config;
    private final Supplier<String>         liveStatus;
    private final BiConsumer<String,String> loginAction;

    private final TitleScreen titleScreen = new TitleScreen();

    private final StringBuilder username = new StringBuilder("Callum");
    private final StringBuilder password = new StringBuilder(); // never pre-fill — always typed
    private boolean focusUser = true;

    private volatile State  state      = State.LOADING;
    private volatile String statusLine = "Loading...";
    private volatile boolean running;
    private Thread thread;

    // Off-screen buffer: TYPE_INT_ARGB so alpha compositing works correctly
    // before blitting to the (potentially hardware-accelerated) BufferStrategy surface.
    private final BufferedImage renderBuffer =
            new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);

    public GameCanvas(ClientConfig config,
                      Supplier<String> liveStatus,
                      BiConsumer<String,String> loginAction) {
        this.config      = config;
        this.liveStatus  = liveStatus;
        this.loginAction = loginAction;

        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setIgnoreRepaint(true);
        setFocusable(true);

        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { onMouse(e); }
        });
        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { onKeyPressed(e); }
            @Override public void keyTyped  (KeyEvent e) { onKeyTyped(e);   }
        });
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        thread  = new Thread(this, "game-loop");
        thread.setDaemon(true);
        thread.start();

        // Load title screen assets off the game-loop thread
        Thread loader = new Thread(() -> {
            titleScreen.load(config);
            if (titleScreen.isLoaded()) {
                state = State.TITLE;
            } else {
                statusLine = "Cache error: " + titleScreen.loadError();
                state = State.TITLE; // show fallback title anyway
            }
        }, "asset-loader");
        loader.setDaemon(true);
        loader.start();
    }

    public void setStatus(String msg)  { statusLine = msg; }
    public void enterGame()            { state = State.IN_GAME; }
    public void enterTitle()           { state = State.TITLE; statusLine = ""; }
    public void showError(String msg)  { statusLine = msg; state = State.ERROR; }
    /** Show an error message but stay on the title screen so the user can retry. */
    public void showLoginError(String msg) { statusLine = msg; state = State.TITLE; }

    // -------------------------------------------------------------------------

    private void onMouse(MouseEvent e) {
        if (state != State.TITLE) return;
        int x = e.getX(), y = e.getY();
        if (R_USER.contains(x, y)) { focusUser = true;  return; }
        if (R_PASS.contains(x, y)) { focusUser = false; return; }
        if (R_BTN.contains(x, y))  { submitLogin(); }
    }

    private void onKeyPressed(KeyEvent e) {
        if (state != State.TITLE) return;
        int code = e.getKeyCode();
        if (code == KeyEvent.VK_TAB) {
            focusUser = !focusUser;
        } else if (code == KeyEvent.VK_BACK_SPACE) {
            StringBuilder buf = focusUser ? username : password;
            if (!buf.isEmpty()) buf.deleteCharAt(buf.length() - 1);
        } else if (code == KeyEvent.VK_ENTER) {
            submitLogin();
        }
    }

    private void onKeyTyped(KeyEvent e) {
        if (state != State.TITLE) return;
        char c = e.getKeyChar();
        if (c < 32 || c > 126) return;
        if (focusUser && username.length() < 12) username.append(c);
        else if (!focusUser && password.length() < 20) password.append(c);
    }

    private void submitLogin() {
        if (username.isEmpty()) return;
        state = State.LOGGING_IN;
        statusLine = "Connecting...";
        loginAction.accept(username.toString(), password.toString());
    }

    // -------------------------------------------------------------------------

    @Override
    public void run() {
        createBufferStrategy(2);
        long last        = System.nanoTime();
        double nsPerTick = 1_000_000_000.0 / 50.0;
        double accum     = 0.0;

        while (running) {
            long now = System.nanoTime();
            accum += (now - last) / nsPerTick;
            last   = now;
            while (accum >= 1.0) { tick(); accum -= 1.0; }
            render();
            Thread.onSpinWait();
        }
    }

    private void tick() { /* game-state update hook */ }

    private void render() {
        ClientDebugger.onRenderStart();
        // Draw everything into an off-screen BufferedImage first.
        // This guarantees correct ARGB alpha compositing (BGRA images with
        // transparent pixels) regardless of the hardware surface backing the
        // BufferStrategy (DirectDraw / Vulkan / OpenGL) on Windows.
        Graphics2D rg = renderBuffer.createGraphics();
        try {
            rg.setColor(Color.BLACK);
            rg.fillRect(0, 0, WIDTH, HEIGHT);
            switch (state) {
                case LOADING    -> renderCentre(rg, "Loading...");
                case TITLE      -> renderTitle(rg);
                case LOGGING_IN -> renderLoggingIn(rg);
                case IN_GAME    -> renderInGame(rg);
                case ERROR      -> renderCentre(rg, "Error: " + statusLine);
            }
        } finally {
            rg.dispose();
        }

        // Blit the composited image to the hardware surface.
        BufferStrategy bs = getBufferStrategy();
        Graphics g = bs.getDrawGraphics();
        try {
            g.drawImage(renderBuffer, 0, 0, null);
        } finally {
            g.dispose();
        }
        bs.show();
    }

    // -- title screen ---------------------------------------------------------

    private void renderTitle(Graphics g) {
        // 1. RS background + logo + login box sprites
        titleScreen.renderBackground(g);

        // 2. Username input field (drawn over the titlebox)
        Font inputFont = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        drawField(g, R_USER, username.toString(), false, focusUser,  inputFont);
        drawField(g, R_PASS, password.toString(), true,  !focusUser, inputFont);

        // 3. Error/status message below the login box
        if (!statusLine.isEmpty()) {
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(new Color(0xff4444));
            FontMetrics fm = g.getFontMetrics();
            int mx = TitleScreen.BOX_X + 180 - fm.stringWidth(statusLine) / 2;
            g.drawString(statusLine, mx, TitleScreen.BOX_Y + 195);
        }

        // 4. Fallback: if assets didn't load, draw a legible overlay
        if (!titleScreen.isLoaded()) {
            renderFallbackTitle(g);
        }
    }

    private void drawField(Graphics g, Rectangle r, String text,
                           boolean mask, boolean active, Font font) {
        // Subtle transparent overlay so text is visible over any background
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(r.x, r.y, r.width, r.height);
        if (active) {
            g.setColor(new Color(0xff, 0xcc, 0x00, 180));
            g.drawRect(r.x, r.y, r.width, r.height);
        }
        g.setFont(font);
        g.setColor(Color.WHITE);
        String display = mask ? "*".repeat(text.length()) : text;
        long tick = System.currentTimeMillis() / 500;
        if (active && (tick & 1) == 0) display += "|";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(display, r.x + 3, r.y + r.height / 2 + fm.getAscent() / 2 - 1);
    }

    private void renderFallbackTitle(Graphics g) {
        Font tf = new Font(Font.SERIF, Font.BOLD, 28);
        g.setFont(tf); g.setColor(new Color(0xcc9900));
        String t = "RuneScape";
        FontMetrics fm = g.getFontMetrics();
        g.drawString(t, WIDTH/2 - fm.stringWidth(t)/2, HEIGHT/2 - 80);

        Font lf = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        g.setFont(lf); g.setColor(Color.LIGHT_GRAY);
        g.drawString("Username:", FIELD_X, USER_Y - 4);
        g.drawString("Password:", FIELD_X, PASS_Y - 4);

        g.setColor(new Color(0x555555));
        g.fillRect(BTN_X, BTN_Y, BTN_W, BTN_H);
        g.setColor(new Color(0x888888));
        g.drawRect(BTN_X, BTN_Y, BTN_W, BTN_H);
        Font bf = new Font(Font.SANS_SERIF, Font.BOLD, 13);
        g.setFont(bf); g.setColor(Color.WHITE);
        fm = g.getFontMetrics();
        g.drawString("Login", BTN_X + (BTN_W - fm.stringWidth("Login"))/2,
                     BTN_Y + BTN_H/2 + fm.getAscent()/2 - 1);
    }

    // -- other states ---------------------------------------------------------

    private void renderLoggingIn(Graphics g) {
        titleScreen.renderBackground(g);
        renderCentreOnBox(g, statusLine);
    }

    private void renderInGame(Graphics g) {
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        g.setColor(Color.WHITE);
        g.drawString(liveStatus.get(), 10, 20);
    }

    private void renderCentre(Graphics g, String msg) {
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        g.setColor(Color.WHITE);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(msg, WIDTH/2 - fm.stringWidth(msg)/2, HEIGHT/2);
    }

    private void renderCentreOnBox(Graphics g, String msg) {
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        g.setColor(Color.WHITE);
        FontMetrics fm = g.getFontMetrics();
        int bx = TitleScreen.BOX_X + 180;
        int by = TitleScreen.BOX_Y + 100;
        g.drawString(msg, bx - fm.stringWidth(msg)/2, by);
    }
}

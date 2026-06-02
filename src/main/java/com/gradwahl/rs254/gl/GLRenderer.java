package com.gradwahl.rs254.gl;

import jagex2.client.GameShell;
import jagex2.client.Client;
import jagex2.graphics.Pix3D;
import jagex2.graphics.PixMap;
import jagex2.graphics.TriangleRenderer;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.imageio.ImageIO;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL33.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Batching OpenGL renderer that replaces Pix3D's software rasteriser.
 *
 * Vertex layout (10 floats):  x, y, r, g, b, u, v, type, z, alpha
 *   type == 0  →  coloured (flat / gouraud): use (r,g,b), ignore u,v
 *   type == 1  →  textured: sample uTex at (u,v), modulate by (r,g,b)
 *
 * Threading: init() must be called before any other method.  All methods
 * must be called from the same thread that called init() (GLFW requirement
 * on Windows, and OpenGL context requirement everywhere).
 */
public final class GLRenderer implements TriangleRenderer {

    // -------------------------------------------------------------------------
    // constants
    // -------------------------------------------------------------------------

    private static final int FLOATS_PER_VERT = 10;
    private static final int MAX_TRIS        = 32_768;
    private static final int MAX_VERTS       = MAX_TRIS * 3;
    private static final int SIDEBAR_PANEL_W = 180;
    private static final int SIDEBAR_RAIL_W  = 20;
    private static final int SIDEBAR_ROW_H   = 34;
    private static final int SIDEBAR_TABS    = 6;

    private static final java.awt.Font INTER_FONT;
    private static final java.awt.Font INTER_MEDIUM;
    static {
        java.awt.Font reg;
        try (InputStream is = GLRenderer.class.getResourceAsStream("/Inter-Regular.ttf")) {
            reg = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, is);
        } catch (Exception e) {
            reg = new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 12);
        }
        INTER_FONT = reg;

        java.awt.Font med;
        try (InputStream is = GLRenderer.class.getResourceAsStream("/Inter-Medium.ttf")) {
            med = java.awt.Font.createFont(java.awt.Font.TRUETYPE_FONT, is);
        } catch (Exception e) {
            med = reg;
        }
        INTER_MEDIUM = med;
    }

    // Short skill names matching Client's statXP[] order
    private static final String[] SKILL_SHORT = {
        "ATK", "DEF", "STR", "HP",  "RNG", "PRA", "MAG",
        "COK", "WC",  "FLT", "FSH", "FM",  "CRA", "SMI",
        "MIN", "HER", "AGI", "THI", "SLA", "RC",  "TOT"
    };

    // Hiscores skill selector labels — index 0 = Overall, 1-19 = API skill IDs
    private static final String[] HSCORE_SKILL_LABEL = {
        "Overall",   "Attack",   "Defence",    "Strength", "Hitpoints",
        "Ranged",    "Prayer",   "Magic",      "Cooking",  "Woodcutting",
        "Fletching", "Fishing",  "Firemaking", "Crafting", "Smithing",
        "Mining",    "Herblore", "Agility",    "Thieving", "Runecrafting"
    };

    // LostHQ guide categories
    private static final String[] LOSTHQ_ITEMS = {
        "QUEST GUIDES",
        "NPC DATABASE",
        "ITEM DATABASE",
        "SPECIAL GUIDES",
        "TREASURE TRAILS",
        "SKILL GUIDES"
    };

    private static final String UI_VERT_SRC = """
            #version 330 core
            layout(location=0) in vec2 aPos;
            layout(location=1) in vec2 aUV;
            uniform float uUMin;
            uniform float uUMax;
            out vec2 vUV;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vUV = vec2(uUMin + aUV.x * (uUMax - uUMin), aUV.y);
            }
            """;

    private static final String UI_FRAG_SRC = """
            #version 330 core
            in vec2 vUV;
            uniform sampler2D uUI;
            out vec4 fragColor;
            void main() {
                vec4 c = texture(uUI, vUV);
                // Alpha == 0 means this pixel was never written (or cleared) — show 3D scene.
                if (c.a == 0.0) discard;
                fragColor = vec4(c.rgb, 1.0);
            }
            """;

    private static final String VERT_SRC = """
            #version 330 core
            layout(location=0) in vec2  aPos;
            layout(location=1) in vec3  aColor;
            layout(location=2) in vec2  aUV;
            layout(location=3) in float aType;
            layout(location=4) in float aZ;
            layout(location=5) in float aAlpha;

            uniform vec2 uScreen;

            out vec3  vColor;
            out vec2  vUV;
            flat out int vType;
            out float vAlpha;

            void main() {
                // RS screen-space → NDC XY: (0,0) top-left → (-1,1)
                vec2 ndc = vec2(
                    aPos.x / uScreen.x *  2.0 - 1.0,
                    aPos.y / uScreen.y * -2.0 + 1.0
                );
                // Depth buffer disabled — rely on RS2's painter's algorithm sort.
                gl_Position = vec4(ndc, 0.0, 1.0);
                vColor = aColor;
                vUV    = aUV;
                vType  = int(aType);
                vAlpha = aAlpha;
            }
            """;

    private static final String FRAG_SRC = """
            #version 330 core
            in  vec3 vColor;
            in  vec2 vUV;
            flat in int vType;
            in float vAlpha;

            uniform sampler2D uTex;

            out vec4 fragColor;

            void main() {
                if (vType == 1) {
                    vec4 t = texture(uTex, vUV);
                    if (t.a == 0.0) discard;          // RS2 transparent (palette 0)
                    fragColor = t * vec4(vColor, 1.0); // texture × lighting
                } else {
                    fragColor = vec4(vColor, vAlpha);
                }
            }
            """;

    // -------------------------------------------------------------------------
    // state
    // -------------------------------------------------------------------------

    private final int screenW;
    private final int screenH;
    private final int maxUiW;
    private int       windowW;
    private int       windowH;

    private long     window;
    private int      vao, vbo, prog;
    private int      uScreen, uTex;

    private final FloatBuffer buf =
            MemoryUtil.memAllocFloat(MAX_VERTS * FLOATS_PER_VERT);
    private int vertCount;

    private final int[] gpuTex   = new int[50];   // OpenGL texture IDs per slot
    private int         currentTexId = -1;         // texture bound for current batch

    // UI overlay pass
    private int     uiProg, uiQuadVao, uiQuadVbo, uiTex, uiTexLoc, uiUMinLoc, uiUMaxLoc;
    private IntBuffer uiDirectBuf;  // direct (off-heap) buffer for glTexSubImage2D

    // Native-resolution sidebar — rendered via Java2D at physical screen pixels
    private java.awt.image.BufferedImage sidebarNativeBuf;
    private java.nio.IntBuffer           sidebarNativeDirect;
    private int                          sidebarNativeTex;
    private int                          sidebarNativeW, sidebarNativeH;
    private java.awt.Graphics2D          sg;  // set during drawSidebar(), null otherwise

    private static final java.awt.Font UI_FONT_BODY = INTER_MEDIUM.deriveFont(9.5f);
    private static final java.awt.Font UI_FONT_HEAD = INTER_FONT.deriveFont(12f);
    private static final java.awt.Font UI_FONT_TINY = INTER_MEDIUM.deriveFont(7f);

    // RuneLite-style client sidebar
    private boolean sidebarOpen;
    private int     sidebarTab;
    private boolean sidebarGpuEnabled   = true;
    private boolean sidebarFpsEnabled   = true;
    private boolean sidebarRoofsEnabled = true;
    private boolean settingsFullscreen  = false;
    private boolean settingsShiftClick  = false;
    private boolean settingsDiscordRp   = false;

    // XP session tracking — updated by Client when XP packets arrive
    public static final long[] xpSessionGains = new long[25];
    // When true, Client renders floating XP drops on the viewport
    public static boolean xpScreenEnabled = false;
    // Player info for Hiscores panel (populated by Client)
    public static String playerName      = "";
    public static int    playerTotalLevel = 0;

    // Hiscores panel state
    private int             hiscoresSkill   = 0;   // 0=overall, 1-19=API skill ID
    private volatile String[] hiscoresNames  = new String[0];
    private volatile int[]    hiscoresLevels = new int[0];
    private long            hiscoresFetchAt = 0;
    private volatile boolean hiscoresFetching = false;
    private final java.util.concurrent.ExecutorService hiscoresFetcher =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "hiscores-fetch");
                t.setDaemon(true);
                return t;
            });

    // Only intercept Pix3D calls when rendering to the main game viewport, not icon buffers.
    public static int[] viewportPixels = null;
    // Outside the client's 24-bit RGB range. Item sprites use RGB value 1
    // for their dark outline, so 1 cannot safely represent transparency.
    public static final int UI_TRANSPARENT_SENTINEL = 0x01000000;
    // Viewport draw position in uiBuffer (set from Client after areaViewport is created).
    public static int vpDrawX = 4, vpDrawY = 4, vpW = 512, vpH = 334;

    // Input routing
    private GameShell shell;
    private boolean   middleMouseDragging;
    private int       middleMouseX;
    private int       middleMouseY;
    private int       cursorX;
    private int       cursorY;

    // Debug metrics overlay
    private boolean statsOverlayVisible;
    private long    metricsSampleAt = System.nanoTime();
    private int     sampledFrames;
    private int     sampledTicks;
    private int     displayFps;
    private int     displayTps;

    // -------------------------------------------------------------------------
    // construction
    // -------------------------------------------------------------------------

    public GLRenderer(int screenW, int screenH) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.maxUiW = screenW + SIDEBAR_PANEL_W + SIDEBAR_RAIL_W;
        this.windowW = screenW + SIDEBAR_RAIL_W;
        this.windowH = screenH;
    }

    // -------------------------------------------------------------------------
    // lifecycle
    // -------------------------------------------------------------------------

    private long tryCreateWindow(int w, int h) {
        // Attempt 1: OpenGL 3.3 core profile (preferred)
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE,   GLFW_FALSE);
        long win = glfwCreateWindow(w, h, "RS254 - OpenGL", NULL, NULL);
        if (win != NULL) return win;

        // Attempt 2: OpenGL 3.3 compatibility profile (some older/VM drivers)
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_COMPAT_PROFILE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE,   GLFW_FALSE);
        win = glfwCreateWindow(w, h, "RS254 - OpenGL", NULL, NULL);
        if (win != NULL) { System.err.println("[GL] Using compatibility profile fallback"); }
        return win;
    }

    /** Create the GLFW window and initialise OpenGL. Call once before use. */
    public void init() {
        glfwSetErrorCallback((error, description) ->
            System.err.println("[GLFW ERROR] " + error + ": " + org.lwjgl.glfw.GLFWErrorCallback.getDescription(description)));
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");

        // Query the primary monitor's content (DPI) scale so the initial window
        // size produces a framebuffer close to native RS2 physical pixel dimensions.
        // Without this, a 175% DPI display would create a 1388×880 framebuffer for
        // a 793×503 logical window, upscaling everything and making UI elements huge.
        glfwDefaultWindowHints();
        float[] xscale = {1f}, yscale = {1f};
        long primaryMonitor = glfwGetPrimaryMonitor();
        if (primaryMonitor != NULL) {
            glfwGetMonitorContentScale(primaryMonitor, xscale, yscale);
        }
        int initW = Math.max(1, Math.round(windowW / xscale[0]));
        int initH = Math.max(1, Math.round(screenH / yscale[0]));

        window = tryCreateWindow(initW, initH);
        if (window == NULL) throw new RuntimeException(
            "GLFW window creation failed — OpenGL 3.3 is required.\n" +
            "Update your GPU drivers, or on a VM enable 3D acceleration.\n" +
            "On Windows without a GPU, install Mesa (opengl32.dll) and re-run.");
        setWindowIcon();

        glfwMakeContextCurrent(window);
        glfwSwapInterval(1);
        GL.createCapabilities();

        setupVAO();
        prog    = buildProgram();
        uScreen = glGetUniformLocation(prog, "uScreen");
        uTex    = glGetUniformLocation(prog, "uTex");

        glUseProgram(prog);
        glUniform2f(uScreen, screenW, screenH);
        glUniform1i(uTex, 0);

        glClearColor(0f, 0f, 0f, 1f);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

        setupUIPass();
        setupCallbacks();
        updateOutputViewport();
        glfwShowWindow(window);
    }

    private void setWindowIcon() {
        try (InputStream is = GLRenderer.class.getResourceAsStream("/icon.ico")) {
            if (is == null) return;
            BufferedImage img = loadIco(is);
            if (img == null) return;
            int w = img.getWidth(), h = img.getHeight();
            int[] rgb = img.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
            try {
                for (int px : rgb) {
                    buf.put((byte) ((px >> 16) & 0xFF))
                       .put((byte) ((px >> 8)  & 0xFF))
                       .put((byte)  (px         & 0xFF))
                       .put((byte) ((px >> 24) & 0xFF));
                }
                buf.flip();
                try (GLFWImage.Buffer icons = GLFWImage.malloc(1)) {
                    icons.position(0).width(w).height(h).pixels(buf);
                    glfwSetWindowIcon(window, icons);
                }
            } finally {
                MemoryUtil.memFree(buf);
            }
        } catch (Exception e) {
            System.err.println("[Icon] " + e.getMessage());
        }
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

    /** Attach a GameShell so GLFW input events are forwarded to the game. */
    public void setGameShell(GameShell gs) {
        this.shell = gs;
    }

    @Override
    public boolean shouldClose() {
        return glfwWindowShouldClose(window);
    }

    @Override
    public void beginFrame() {
        beginFrame(true);
    }

    public void beginFrame(boolean clearViewport) {
        beginFrame(clearViewport, true);
    }

    public void beginFrame(boolean clearViewport, boolean clearScene) {
        glfwPollEvents();
        if (clearScene) {
            glClear(GL_COLOR_BUFFER_BIT);
        }
        buf.clear();
        vertCount    = 0;
        currentTexId = -1;
        // Clear the viewport region so the 3D GL scene shows through each frame.
        // Non-viewport UI (sidebars, chat) persists and redraws only when RS2 says so.
        if (clearViewport && PixMap.uiBuffer != null) {
            for (int row = 0; row < vpH; row++) {
                int off = (vpDrawY + row) * PixMap.uiWidth + vpDrawX;
                java.util.Arrays.fill(PixMap.uiBuffer, off, off + vpW, 0);
            }
        }
    }

    @Override
    public void endFrame() {
        flushBatch();
        drawUIOverlay();
        sampledFrames++;
        updateMetrics();
        drawStatsOverlay();
        glfwSwapBuffers(window);
    }

    public void recordTick() {
        sampledTicks++;
    }

    public void destroy() {
        flushBatch();
        glDeleteBuffers(vbo);
        glDeleteVertexArrays(vao);
        glDeleteProgram(prog);
        glDeleteBuffers(uiQuadVbo);
        glDeleteVertexArrays(uiQuadVao);
        glDeleteProgram(uiProg);
        glDeleteTextures(uiTex);
        if (sidebarNativeTex != 0) glDeleteTextures(sidebarNativeTex);
        for (int t : gpuTex) if (t != 0) glDeleteTextures(t);
        MemoryUtil.memFree(buf);
        if (uiDirectBuf      != null) MemoryUtil.memFree(uiDirectBuf);
        if (sidebarNativeDirect != null) MemoryUtil.memFree(sidebarNativeDirect);
        hiscoresFetcher.shutdownNow();
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);
        glfwTerminate();
    }

    // -------------------------------------------------------------------------
    // TriangleRenderer implementation
    // -------------------------------------------------------------------------

    @Override
    public void addFlat(int x0, int y0, int x1, int y1, int x2, int y2, int rgb, int trans, int z0, int z1, int z2) {
        if (currentTexId != -1) { flushBatch(); currentTexId = -1; }
        ensureCapacity();
        float r = ch(rgb >> 16), g = ch(rgb >> 8), b = ch(rgb);
        float alpha = legacyAlpha(trans);
        putVertex(x0, y0, r, g, b, 0, 0, 0, z0, alpha);
        putVertex(x1, y1, r, g, b, 0, 0, 0, z1, alpha);
        putVertex(x2, y2, r, g, b, 0, 0, 0, z2, alpha);
        vertCount += 3;
    }

    @Override
    public void addGouraud(int x0, int y0, int x1, int y1, int x2, int y2,
                            int hsl0, int hsl1, int hsl2, int trans, int z0, int z1, int z2) {
        if (currentTexId != -1) { flushBatch(); currentTexId = -1; }
        ensureCapacity();
        int c0 = Pix3D.colourTable[hsl0];
        int c1 = Pix3D.colourTable[hsl1];
        int c2 = Pix3D.colourTable[hsl2];
        float alpha = legacyAlpha(trans);
        putVertex(x0, y0, ch(c0 >> 16), ch(c0 >> 8), ch(c0), 0, 0, 0, z0, alpha);
        putVertex(x1, y1, ch(c1 >> 16), ch(c1 >> 8), ch(c1), 0, 0, 0, z1, alpha);
        putVertex(x2, y2, ch(c2 >> 16), ch(c2 >> 8), ch(c2), 0, 0, 0, z2, alpha);
        vertCount += 3;
    }

    @Override
    public void addTextured(int x0, int y0, int x1, int y1, int x2, int y2,
                             float u0, float v0, float u1, float v1, float u2, float v2,
                             int hsl0, int hsl1, int hsl2, int texId, int z0, int z1, int z2) {
        if (texId != currentTexId) {
            flushBatch();
            bindTexture(texId);
            currentTexId = texId;
        }
        ensureCapacity();

        // RS2 texture lighting: texel >>> (hsl >> 6).
        // hsl >> 6 gives 0,1,2,3 for [full, half, quarter, eighth] brightness.
        float b0 = texBrightness(hsl0);
        float b1 = texBrightness(hsl1);
        float b2 = texBrightness(hsl2);
        putVertex(x0, y0, b0, b0, b0, u0, v0, 1, z0, 1);
        putVertex(x1, y1, b1, b1, b1, u1, v1, 1, z1, 1);
        putVertex(x2, y2, b2, b2, b2, u2, v2, 1, z2, 1);
        vertCount += 3;
    }

    // -------------------------------------------------------------------------
    // texture management
    // -------------------------------------------------------------------------

    /** Upload (or re-upload) one game texture slot to the GPU. */
    public void uploadTexture(int texId) {
        if (Pix3D.textures[texId] == null || Pix3D.texturePalette[texId] == null) return;
        int[] texels = Pix3D.getTexels(texId);
        int   size   = Pix3D.lowMem ? 64 : 128;

        if (gpuTex[texId] == 0) gpuTex[texId] = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, gpuTex[texId]);

        ByteBuffer rgba = MemoryUtil.memAlloc(size * size * 4);
        try {
            for (int i = 0; i < size * size; i++) {
                int c = texels[i];
                rgba.put((byte) (c >> 16));           // R
                rgba.put((byte) (c >>  8));           // G
                rgba.put((byte)  c);                  // B
                rgba.put(c == 0 ? (byte) 0 : (byte) -1); // A: 0 = transparent
            }
            rgba.flip();
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, size, size, 0,
                         GL_RGBA, GL_UNSIGNED_BYTE, rgba);
        } finally {
            MemoryUtil.memFree(rgba);
        }

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
    }

    // -------------------------------------------------------------------------
    // internals
    // -------------------------------------------------------------------------

    private void bindTexture(int texId) {
        if (gpuTex[texId] == 0) uploadTexture(texId);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, gpuTex[texId]);
    }

    private void ensureCapacity() {
        if (vertCount + 3 > MAX_VERTS) flushBatch();
    }

    private void flushBatch() {
        if (vertCount == 0) return;
        buf.flip();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, buf);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLES, 0, vertCount);
        buf.clear();
        vertCount = 0;
    }

    private void putVertex(int x, int y, float r, float g, float b,
                            float u, float v, float type, float z, float alpha) {
        buf.put(x).put(y).put(r).put(g).put(b).put(u).put(v).put(type).put(z).put(alpha);
    }

    /** Extract a normalised [0,1] channel from a packed int (shifts the byte to position 0). */
    private static float ch(int packed) {
        return (packed & 0xFF) / 255f;
    }

    /** Convert Pix3D's destination weight into the equivalent OpenGL source alpha. */
    private static float legacyAlpha(int trans) {
        return (256 - trans) / 256f;
    }

    /**
     * Convert an RS2 texture lighting value to a [0,1] brightness factor.
     * RS2 applies lighting as: texel >>> (hsl >> 6), giving 4 levels: 1, ½, ¼, ⅛.
     * Values with shift > 7 produce essentially black; we cap there to avoid overflow.
     */
    private static float texBrightness(int hsl) {
        int shift = hsl >> 6;
        if (shift <= 0)  return 1f;
        if (shift >= 7)  return 1f / 128f;
        return 1f / (1 << shift);
    }

    // -------------------------------------------------------------------------
    // UI overlay pass
    // -------------------------------------------------------------------------

    private void setupUIPass() {
        // Allocate the CPU-side UI buffer that PixMap.draw() writes into.
        PixMap.uiBuffer = new int[maxUiW * screenH];
        PixMap.uiWidth  = maxUiW;
        PixMap.uiHeight = screenH;
        // Direct (off-heap) copy buffer for glTexSubImage2D — LWJGL requires direct buffers.
        uiDirectBuf = MemoryUtil.memAllocInt(maxUiW * screenH);

        // Fullscreen quad: two triangles covering NDC [-1,1].
        // UV Y is flipped because RS has Y=0 at top, OpenGL NDC has Y=1 at top.
        float[] quad = {
            -1f, -1f,  0f, 1f,
            -1f,  1f,  0f, 0f,
             1f,  1f,  1f, 0f,
            -1f, -1f,  0f, 1f,
             1f,  1f,  1f, 0f,
             1f, -1f,  1f, 1f,
        };
        uiQuadVao = glGenVertexArrays();
        uiQuadVbo = glGenBuffers();
        glBindVertexArray(uiQuadVao);
        glBindBuffer(GL_ARRAY_BUFFER, uiQuadVbo);
        glBufferData(GL_ARRAY_BUFFER, quad, GL_STATIC_DRAW);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 4 * Float.BYTES, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);

        uiProg = buildProgram(UI_VERT_SRC, UI_FRAG_SRC);
        uiTexLoc  = glGetUniformLocation(uiProg, "uUI");
        uiUMinLoc = glGetUniformLocation(uiProg, "uUMin");
        uiUMaxLoc = glGetUniformLocation(uiProg, "uUMax");

        // Create the 2D overlay texture (BGRA so IntBuffer maps straight).
        uiTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, uiTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, maxUiW, screenH, 0,
                     GL_BGRA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        sidebarNativeTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, sidebarNativeTex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    }

    private void drawUIOverlay() {
        if (PixMap.uiBuffer == null) return;

        // Upload game UI pixels. drawSidebar() no longer touches uiBuffer, so no backup needed.
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, uiTex);
        uiDirectBuf.clear();
        uiDirectBuf.put(PixMap.uiBuffer, 0, maxUiW * screenH);
        uiDirectBuf.flip();
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, maxUiW, screenH,
                        GL_BGRA, GL_UNSIGNED_BYTE, uiDirectBuf);

        glUseProgram(uiProg);
        glUniform1i(uiTexLoc, 0);
        glBindVertexArray(uiQuadVao);

        int[] fw = new int[1], fh = new int[1];
        glfwGetFramebufferSize(window, fw, fh);
        int sidebarLogW = SIDEBAR_RAIL_W + (sidebarOpen ? SIDEBAR_PANEL_W : 0);

        if (sidebarInsideWindow()) {
            double scale  = Math.min((double) fw[0] / (screenW + sidebarLogW),
                                     (double) fh[0] / screenH);
            int gameW    = Math.max(1, (int) Math.round(screenW    * scale));
            int gameH    = Math.max(1, (int) Math.round(screenH    * scale));
            int sidebarW = Math.max(1, (int) Math.round(sidebarLogW * scale));
            int gameX    = sidebarOpen ? 0 : (fw[0] - gameW - sidebarW) / 2;
            int vertOff  = (fh[0] - gameH) / 2;

            // Pass 1: game UI
            glUniform1f(uiUMinLoc, 0f);
            glUniform1f(uiUMaxLoc, (float) screenW / maxUiW);
            glViewport(gameX, vertOff, gameW, gameH);
            glDrawArrays(GL_TRIANGLES, 0, 6);

            // Pass 2: native-resolution sidebar
            drawSidebarNative(fw[0] - sidebarW, vertOff, sidebarW, gameH, scale);
        } else {
            // Windowed 1:1 mode — compute actual DPI scale from framebuffer vs logical size
            double scale  = (fw[0] > 0) ? (double) fw[0] / (screenW + sidebarLogW) : 1.0;
            int gameW    = Math.max(1, (int) Math.round(screenW    * scale));
            int gameH    = Math.max(1, (int) Math.round(screenH    * scale));
            int sidebarW = Math.max(1, (int) Math.round(sidebarLogW * scale));

            glUniform1f(uiUMinLoc, 0f);
            glUniform1f(uiUMaxLoc, (float) screenW / maxUiW);
            glViewport(0, 0, gameW, gameH);
            glDrawArrays(GL_TRIANGLES, 0, 6);

            drawSidebarNative(gameW, 0, sidebarW, gameH, scale);
        }

        glUseProgram(prog);
        updateOutputViewport();
    }

    private void drawSidebarNative(int physX, int physY, int physW, int physH, double scale) {
        // Reallocate Java2D buffer and GL texture storage whenever the physical size changes.
        if (physW != sidebarNativeW || physH != sidebarNativeH) {
            if (sidebarNativeBuf != null) sidebarNativeBuf.flush();
            if (sidebarNativeDirect != null) MemoryUtil.memFree(sidebarNativeDirect);
            sidebarNativeBuf    = new java.awt.image.BufferedImage(physW, physH,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            sidebarNativeDirect = MemoryUtil.memAllocInt(physW * physH);
            sidebarNativeW = physW; sidebarNativeH = physH;
            glBindTexture(GL_TEXTURE_2D, sidebarNativeTex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, physW, physH, 0,
                         GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        }

        // Render sidebar into native buffer at physical resolution via Java2D.
        sg = sidebarNativeBuf.createGraphics();
        try {
            sg.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                                java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            sg.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                                java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            sg.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS,
                                java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            sg.setBackground(new java.awt.Color(0, 0, 0, 0));
            sg.clearRect(0, 0, physW, physH);
            // Map logical sidebar coordinates (x origin = screenW) to physical pixels.
            sg.scale(scale, scale);
            sg.translate(-screenW, 0);
            drawSidebar();
        } finally {
            sg.dispose();
            sg = null;
        }

        // Upload Java2D pixels (TYPE_INT_ARGB = BGRA in little-endian memory) to GL.
        int[] pixels = ((java.awt.image.DataBufferInt)
                sidebarNativeBuf.getRaster().getDataBuffer()).getData();
        sidebarNativeDirect.clear();
        sidebarNativeDirect.put(pixels);
        sidebarNativeDirect.flip();
        glBindTexture(GL_TEXTURE_2D, sidebarNativeTex);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, physW, physH,
                        GL_BGRA, GL_UNSIGNED_BYTE, sidebarNativeDirect);

        // Draw native sidebar texture in its physical viewport — exactly 1:1 pixels.
        glUniform1f(uiUMinLoc, 0f);
        glUniform1f(uiUMaxLoc, 1f);
        glViewport(physX, physY, physW, physH);
        glDrawArrays(GL_TRIANGLES, 0, 6);

        // Restore the game UI texture for any subsequent passes.
        glBindTexture(GL_TEXTURE_2D, uiTex);
    }

    private void drawSidebar() {
        int railX  = sidebarRailX();
        int panelX = sidebarPanelX();
        if (sidebarOpen) {
            fillUiRect(panelX, 0, SIDEBAR_PANEL_W, screenH, 0xFF262626);
            fillUiRect(panelX + SIDEBAR_PANEL_W - 1, 0, 1, screenH, 0xFF363636);
            drawUiText(sidebarTitle(), panelX + 12, 17, 2, 0xFFDCDCDC);
            drawUiText("X", panelX + SIDEBAR_PANEL_W - 18, 17, 2, 0xFF999999);
            fillUiRect(panelX, 42, SIDEBAR_PANEL_W, 1, 0xFF363636);
            drawSidebarPanel(panelX);
        }

        fillUiRect(railX, 0, SIDEBAR_RAIL_W, screenH, 0xFF1B1B1B);
        fillUiRect(railX, 0, 1, screenH, 0xFF363636);
        for (int index = 0; index < SIDEBAR_TABS - 1; index++) {
            int y      = index * SIDEBAR_ROW_H;
            boolean active = sidebarOpen && sidebarTab == index;
            if (active) {
                fillUiRect(railX + 1, y, SIDEBAR_RAIL_W - 1, SIDEBAR_ROW_H, 0xFF3F3523);
                fillUiRect(railX + 1, y, 3, SIDEBAR_ROW_H, 0xFFE89E14);
            }
            // 8×8 icon at scale 1, centred in 20×34 cell
            drawIconScaled(index, railX + 6, y + 13, 1, active ? 0xFFE89E14 : 0xFFDCDCDC);
        }
        // Settings icon (tab 5) pinned to the bottom of the rail
        int settingsY      = screenH - SIDEBAR_ROW_H;
        boolean settingsActive = sidebarOpen && sidebarTab == 5;
        if (settingsActive) {
            fillUiRect(railX + 1, settingsY, SIDEBAR_RAIL_W - 1, SIDEBAR_ROW_H, 0xFF3F3523);
            fillUiRect(railX + 1, settingsY, 3, SIDEBAR_ROW_H, 0xFFE89E14);
        }
        drawIconScaled(5, railX + 6, settingsY + 13, 1, settingsActive ? 0xFFE89E14 : 0xFFDCDCDC);
    }

    private void drawSidebarPanel(int x) {
        switch (sidebarTab) {
            case 0 -> drawHiscoresPanel(x);
            case 1 -> drawXpScreenPanel(x);
            case 2 -> drawXpTrackerPanel(x);
            case 3 -> drawWorldMapPanel(x);
            case 4 -> drawLostHqPanel(x);
            case 5 -> drawSettingsPanel(x);
        }
    }

    private String sidebarTitle() {
        return switch (sidebarTab) {
            case 0 -> "HISCORES";
            case 1 -> "XP SCREEN";
            case 2 -> "XP TRACKER";
            case 3 -> "WORLD MAP";
            case 4 -> "LOSTHQ";
            default -> "SETTINGS";
        };
    }

    private void drawHiscoresPanel(int x) {
        // Auto-fetch when panel opens or data is stale (30s)
        long now = System.currentTimeMillis();
        if (!hiscoresFetching && (hiscoresNames.length == 0 || now - hiscoresFetchAt > 30_000L)) {
            fetchHiscores(hiscoresSkill);
        }

        // Skill selector buttons — 2 per row, 10 rows, full skill names at tiny size
        for (int i = 0; i < HSCORE_SKILL_LABEL.length; i++) {
            int col = i % 2;
            int row = i / 2;
            int bx  = x + 10 + col * 82;
            int by  = 52 + row * 13;
            boolean sel = (i == hiscoresSkill);
            fillUiRect(bx, by, 78, 11, sel ? 0xFF3F3523 : 0xFF2A2A2A);
            if (sel) fillUiRect(bx, by, 78, 1, 0xFFE89E14);
            drawUiText(HSCORE_SKILL_LABEL[i], bx + 4, by + 2, 0, sel ? 0xFFE89E14 : 0xFF999999);
        }

        int afterButtons = 52 + 10 * 13 + 3;  // y just below last button row
        fillUiRect(x + 10, afterButtons, SIDEBAR_PANEL_W - 20, 1, 0xFF363636);

        // Column headers
        int headerY = afterButtons + 5;
        drawUiText("RK",   x + 10,  headerY, 1, 0xFF666666);
        drawUiText("NAME", x + 28,  headerY, 1, 0xFF666666);
        drawUiText("LVL",  x + 140, headerY, 1, 0xFF666666);
        fillUiRect(x + 10, headerY + 11, SIDEBAR_PANEL_W - 20, 1, 0xFF363636);

        // Leaderboard rows
        String[] names  = hiscoresNames;
        int[]    levels = hiscoresLevels;
        int rowY = headerY + 13;
        if (names.length == 0) {
            String msg = hiscoresFetching ? "LOADING..." : "NO DATA";
            drawUiText(msg, x + 10, rowY, 1, 0xFF666666);
        } else {
            int maxRows = (screenH - rowY) / 12;
            for (int i = 0; i < names.length && i < maxRows; i++) {
                int col = (i == 0) ? 0xFFFFD700 : 0xFFDCDCDC;
                drawUiText(String.valueOf(i + 1), x + 10, rowY, 1, col);
                String name = names[i].toUpperCase();
                if (name.length() > 14) name = name.substring(0, 14);
                drawUiText(name, x + 28, rowY, 1, col);
                drawUiText(String.valueOf(levels[i]), x + 140, rowY, 1, col);
                rowY += 12;
            }
        }
    }

    private void drawXpScreenPanel(int x) {
        drawUiText("SHOW XP GAINS", x + 16, 56, 1, 0xFFE89E14);
        fillUiRect(x + 16, 70, SIDEBAR_PANEL_W - 32, 1, 0xFF363636);
        drawUiText("DISPLAYS FLOATING XP", x + 16, 86, 1, 0xFF999999);
        drawUiText("TEXT ON SCREEN WHEN", x + 16, 100, 1, 0xFF999999);
        drawUiText("EXPERIENCE IS GAINED.", x + 16, 114, 1, 0xFF999999);
        fillUiRect(x + 16, 130, SIDEBAR_PANEL_W - 32, 1, 0xFF363636);
        drawToggleRow(x, 140, "XP ON SCREEN", xpScreenEnabled);
    }

    private void drawXpTrackerPanel(int x) {
        // Reset button (top-right of panel)
        fillUiRect(x + SIDEBAR_PANEL_W - 58, 52, 46, 16, 0xFF3A3A3A);
        fillUiRect(x + SIDEBAR_PANEL_W - 58, 52, 46, 1, 0xFF555555);
        fillUiRect(x + SIDEBAR_PANEL_W - 58, 67, 46, 1, 0xFF555555);
        drawUiText("RESET", x + SIDEBAR_PANEL_W - 50, 57, 1, 0xFFDCDCDC);
        // Column headers
        drawUiText("SKILL", x + 16, 57, 1, 0xFF999999);
        drawUiText("XP GAINED", x + 70, 57, 1, 0xFF999999);
        fillUiRect(x + 16, 70, SIDEBAR_PANEL_W - 32, 1, 0xFF363636);
        // Rows
        boolean hasXp = false;
        for (long v : xpSessionGains) if (v > 0) { hasXp = true; break; }
        if (!hasXp) {
            drawUiText("NO XP GAINED", x + 16, 90,  1, 0xFF999999);
            drawUiText("THIS SESSION.",  x + 16, 106, 1, 0xFF999999);
        } else {
            int rowY = 82;
            for (int i = 0; i < SKILL_SHORT.length && rowY < screenH - 14; i++) {
                if (xpSessionGains[i] > 0) {
                    drawUiText(SKILL_SHORT[i], x + 16, rowY, 1, 0xFFDCDCDC);
                    drawUiText("+" + xpSessionGains[i], x + 70, rowY, 1, 0xFF80FF80);
                    rowY += 14;
                }
            }
        }
    }

    private void drawWorldMapPanel(int x) {
        drawUiText("WORLD MAP", x + 16, 56, 1, 0xFFE89E14);
        fillUiRect(x + 16, 70, SIDEBAR_PANEL_W - 32, 1, 0xFF363636);
        drawUiText("CONNECT TO A WORLD", x + 16, 90, 1, 0xFF999999);
        drawUiText("TO TRACK YOUR", x + 16, 106, 1, 0xFF999999);
        drawUiText("LOCATION ON THE", x + 16, 122, 1, 0xFF999999);
        drawUiText("WORLD MAP.", x + 16, 138, 1, 0xFF999999);
    }

    private void drawLostHqPanel(int x) {
        drawUiText("LOSTHQ TOOLS", x + 16, 56, 1, 0xFFE89E14);
        fillUiRect(x + 16, 70, SIDEBAR_PANEL_W - 32, 1, 0xFF363636);
        for (int i = 0; i < LOSTHQ_ITEMS.length; i++) {
            int itemY = 82 + i * 24;
            fillUiRect(x + 12, itemY, SIDEBAR_PANEL_W - 24, 18, 0xFF1F1F1F);
            fillUiRect(x + 12, itemY, SIDEBAR_PANEL_W - 24, 1, 0xFF363636);
            drawUiText(LOSTHQ_ITEMS[i], x + 20, itemY + 6, 1, 0xFFDCDCDC);
        }
    }

    private void drawSettingsPanel(int x) {
        drawUiText("CLIENT SETTINGS", x + 16, 56, 1, 0xFFE89E14);
        drawToggleRow(x, 72,  "GPU RENDERING",   sidebarGpuEnabled);
        drawToggleRow(x, 116, "SHOW FPS",         sidebarFpsEnabled);
        drawToggleRow(x, 160, "SHOW ROOFS",       sidebarRoofsEnabled);
        drawToggleRow(x, 204, "FULLSCREEN",        settingsFullscreen);
        drawToggleRow(x, 248, "SHIFT CLICK",       settingsShiftClick);
        drawToggleRow(x, 292, "DISCORD RP",        settingsDiscordRp);
    }

    private void drawPluginRow(int x, int y, String name, String description, boolean enabled) {
        drawUiText(name, x + 16, y, 2, 0xFFDCDCDC);
        drawUiText(description, x + 16, y + 20, 1, 0xFF999999);
        drawToggle(x + SIDEBAR_PANEL_W - 48, y + 2, enabled);
    }

    private void fetchHiscores(int skill) {
        if (hiscoresFetching) return;
        hiscoresFetching = true;
        hiscoresFetchAt  = System.currentTimeMillis();
        String urlStr = "http://localhost:3000/api/hiscores?page=0&skill="
                + (skill == 0 ? "overall" : String.valueOf(skill));
        hiscoresFetcher.execute(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(5000);
                try (InputStream in = conn.getInputStream()) {
                    String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    parseHiscores(json);
                } finally {
                    conn.disconnect();
                }
            } catch (Exception ignored) {
            } finally {
                hiscoresFetching = false;
            }
        });
    }

    private void parseHiscores(String json) {
        List<String>  names  = new ArrayList<>();
        List<Integer> levels = new ArrayList<>();
        int pos = 0;
        while (true) {
            int objStart = json.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) break;
            String obj   = json.substring(objStart, objEnd + 1);
            String name  = jsonString(obj, "username");
            int    level = jsonInt(obj, "level");
            if (name != null && level >= 0) {
                names.add(name);
                levels.add(level);
            }
            pos = objEnd + 1;
        }
        hiscoresNames  = names.toArray(new String[0]);
        hiscoresLevels = levels.stream().mapToInt(Integer::intValue).toArray();
    }

    private static String jsonString(String obj, String key) {
        String tag = "\"" + key + "\":\"";
        int i = obj.indexOf(tag);
        if (i < 0) return null;
        i += tag.length();
        int end = obj.indexOf('"', i);
        return end < 0 ? null : obj.substring(i, end);
    }

    private static int jsonInt(String obj, String key) {
        String tag = "\"" + key + "\":";
        int i = obj.indexOf(tag);
        if (i < 0) return -1;
        i += tag.length();
        int end = i;
        while (end < obj.length() && (Character.isDigit(obj.charAt(end)) || obj.charAt(end) == '-')) end++;
        try { return Integer.parseInt(obj.substring(i, end)); } catch (NumberFormatException e) { return -1; }
    }

    private void drawToggleRow(int x, int y, String text, boolean enabled) {
        drawUiText(text, x + 16, y + 16, 1, 0xFFDCDCDC);
        drawToggle(x + SIDEBAR_PANEL_W - 48, y + 9, enabled);
        fillUiRect(x + 16, y + 42, SIDEBAR_PANEL_W - 32, 1, 0xFF363636);
    }

    private void drawMetric(int x, int y, String name, String value) {
        drawUiText(name, x + 16, y, 1, 0xFF999999);
        drawUiText(value, x + 98, y, 1, 0xFFDCDCDC);
    }

    private void drawToggle(int x, int y, boolean enabled) {
        fillUiRect(x, y, 32, 16, enabled ? 0xFFE09107 : 0xFF505050);
        fillUiRect(enabled ? x + 18 : x + 2, y + 2, 12, 12, enabled ? 0xFFFFC143 : 0xFFA5A5A5);
    }

    /**
     * Draw one of the 6 sidebar rail icons (8×8 bitmask, bit 7 = leftmost column).
     * At scale 2 this produces a 16×16 pixel icon.
     *
     * Icon legend (tab index):
     *   0 = Crown      → Hiscores
     *   1 = Eye        → XP on-screen toggle
     *   2 = Bar chart  → XP Tracker
     *   3 = Globe      → World Map
     *   4 = Grid/table → LostHQ Tools
     *   5 = Gear       → Settings
     */
    private static int[] iconBits(int id) {
        return switch (id) {
            case 0 -> new int[]{0x81, 0xA9, 0xFF, 0x7E, 0x7E, 0x3C, 0x7E, 0xFF}; // crown
            case 1 -> new int[]{0x00, 0x3C, 0x7E, 0xDB, 0xFF, 0x7E, 0x3C, 0x00}; // eye
            case 2 -> new int[]{0x00, 0x03, 0x03, 0x0F, 0x0F, 0x6F, 0x6F, 0x6F}; // rising bars
            case 3 -> new int[]{0x3C, 0x42, 0xBD, 0xFF, 0xBD, 0x42, 0x3C, 0x00}; // globe
            case 4 -> new int[]{0xFF, 0x92, 0x92, 0xFF, 0x92, 0x92, 0xFF, 0x00}; // grid/table
            case 5 -> new int[]{0x3C, 0x7E, 0xE7, 0x42, 0x42, 0xE7, 0x7E, 0x3C}; // gear
            default -> new int[8];
        };
    }

    private void drawIconScaled(int id, int x, int y, int scale, int color) {
        int[] bits = iconBits(id);
        for (int row = 0; row < bits.length; row++) {
            for (int col = 0; col < 8; col++) {
                if ((bits[row] & (1 << (7 - col))) != 0) {
                    fillUiRect(x + col * scale, y + row * scale, scale, scale, color);
                }
            }
        }
    }

    /** Reset all per-session XP counters. Called from the XP Tracker reset button. */
    public static void resetXpSession() {
        java.util.Arrays.fill(xpSessionGains, 0L);
    }

    private void fillUiRect(int x, int y, int width, int height, int argb) {
        int a = (argb >> 24) & 0xFF, r = (argb >> 16) & 0xFF,
            g = (argb >> 8)  & 0xFF, b =  argb        & 0xFF;
        sg.setColor(new java.awt.Color(r, g, b, a));
        sg.fillRect(x, y, width, height);
    }

    private void drawUiText(String text, int x, int y, int scale, int argb) {
        if (text == null || text.isEmpty()) return;
        java.awt.Font font = (scale >= 2) ? UI_FONT_HEAD : (scale == 0) ? UI_FONT_TINY : UI_FONT_BODY;
        sg.setFont(font);
        java.awt.FontMetrics fm = sg.getFontMetrics(font);
        int a = (argb >> 24) & 0xFF, r = (argb >> 16) & 0xFF,
            g = (argb >> 8)  & 0xFF, b =  argb        & 0xFF;
        sg.setColor(new java.awt.Color(r, g, b, a));
        sg.drawString(text, x, y + fm.getAscent());
    }

    private void updateMetrics() {
        long now = System.nanoTime();
        long elapsed = now - metricsSampleAt;
        if (elapsed < 1_000_000_000L) return;
        displayFps = (int) Math.round(sampledFrames * 1_000_000_000.0 / elapsed);
        displayTps = (int) Math.round(sampledTicks * 1_000_000_000.0 / elapsed);
        sampledFrames = 0;
        sampledTicks = 0;
        metricsSampleAt = now;
    }

    private void drawStatsOverlay() {
        if (!statsOverlayVisible) return;
        long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                / (1024L * 1024L);
        String[] lines = {
                "TPS:" + displayTps,
                "FPS:" + displayFps,
                "MEM:" + usedMb + "MB"
        };
        int maxChars = 0;
        for (String line : lines) {
            maxChars = Math.max(maxChars, line.length());
        }
        int x = vpDrawX + vpW - maxChars * 6 * 2 - 7;
        int y = vpDrawY + 4;
        for (String line : lines) {
            drawText(line, x + 1, y + 1, 2, 0x000000);
            drawText(line, x, y, 2, 0xFFFF00);
            y += 13;
        }
        flushBatch();
    }

    private void drawText(String text, int x, int y, int scale, int rgb) {
        int cursor = x;
        for (int i = 0; i < text.length(); i++) {
            drawGlyph(text.charAt(i), cursor, y, scale, rgb);
            cursor += 6 * scale;
        }
    }

    private void drawGlyph(char ch, int x, int y, int scale, int rgb) {
        int[] rows = glyph(ch);
        for (int row = 0; row < rows.length; row++) {
            for (int col = 0; col < 5; col++) {
                if ((rows[row] & (1 << (4 - col))) != 0) {
                    addOverlayRect(x + col * scale, y + row * scale, scale, scale, rgb);
                }
            }
        }
    }

    private void addOverlayRect(int x, int y, int width, int height, int rgb) {
        if (vertCount + 6 > MAX_VERTS) flushBatch();
        float r = ch(rgb >> 16), g = ch(rgb >> 8), b = ch(rgb);
        putVertex(x,         y,          r, g, b, 0, 0, 0, 0, 1);
        putVertex(x + width, y,          r, g, b, 0, 0, 0, 0, 1);
        putVertex(x + width, y + height, r, g, b, 0, 0, 0, 0, 1);
        putVertex(x,         y,          r, g, b, 0, 0, 0, 0, 1);
        putVertex(x + width, y + height, r, g, b, 0, 0, 0, 0, 1);
        putVertex(x,         y + height, r, g, b, 0, 0, 0, 0, 1);
        vertCount += 6;
    }

    // 5-wide × 7-tall bitmap font. Each int is a row bitmask: bit4=leftmost col.
    private static int[] glyph(char ch) {
        return switch (ch) {
            case '0' -> new int[]{14, 17, 17, 17, 17, 17, 14};
            case '1' -> new int[]{ 4, 12,  4,  4,  4,  4, 31};
            case '2' -> new int[]{14, 17,  1,  6,  8, 16, 31};
            case '3' -> new int[]{14, 17,  1,  6,  1, 17, 14};
            case '4' -> new int[]{ 2,  6, 10, 18, 31,  2,  2};
            case '5' -> new int[]{31, 16, 16, 30,  1, 17, 14};
            case '6' -> new int[]{14, 17, 16, 30, 17, 17, 14};
            case '7' -> new int[]{31,  1,  2,  4,  8,  8,  8};
            case '8' -> new int[]{14, 17, 17, 14, 17, 17, 14};
            case '9' -> new int[]{14, 17, 17, 15,  1, 17, 14};
            case 'A' -> new int[]{14, 17, 17, 31, 17, 17, 17};
            case 'B' -> new int[]{30, 17, 17, 30, 17, 17, 30};
            case 'C' -> new int[]{14, 17, 16, 16, 16, 17, 14};
            case 'D' -> new int[]{30, 17, 17, 17, 17, 17, 30};
            case 'E' -> new int[]{31, 16, 16, 30, 16, 16, 31};
            case 'F' -> new int[]{31, 16, 16, 30, 16, 16, 16};
            case 'G' -> new int[]{14, 17, 16, 19, 17, 17, 14};
            case 'H' -> new int[]{17, 17, 17, 31, 17, 17, 17};
            case 'I' -> new int[]{31,  4,  4,  4,  4,  4, 31};
            case 'J' -> new int[]{31,  1,  1,  1,  1, 17, 14};
            case 'K' -> new int[]{17, 18, 20, 24, 20, 18, 17};
            case 'L' -> new int[]{16, 16, 16, 16, 16, 16, 31};
            case 'M' -> new int[]{17, 27, 21, 17, 17, 17, 17};
            case 'N' -> new int[]{17, 25, 21, 19, 17, 17, 17};
            case 'O' -> new int[]{14, 17, 17, 17, 17, 17, 14};
            case 'P' -> new int[]{30, 17, 17, 30, 16, 16, 16};
            case 'Q' -> new int[]{14, 17, 17, 17, 21, 19, 15};
            case 'R' -> new int[]{30, 17, 17, 30, 20, 18, 17};
            case 'S' -> new int[]{14, 17, 16, 14,  1, 17, 14};
            case 'T' -> new int[]{31,  4,  4,  4,  4,  4,  4};
            case 'U' -> new int[]{17, 17, 17, 17, 17, 17, 14};
            case 'V' -> new int[]{17, 17, 17, 17, 17, 10,  4};
            case 'W' -> new int[]{17, 17, 17, 21, 27, 17, 17};
            case 'X' -> new int[]{17, 17, 10,  4, 10, 17, 17};
            case 'Y' -> new int[]{17, 17, 10,  4,  4,  4,  4};
            case 'Z' -> new int[]{31,  1,  2,  4,  8, 16, 31};
            case '.' -> new int[]{ 0,  0,  0,  0,  0,  6,  6};
            case ':' -> new int[]{ 0,  6,  6,  0,  6,  6,  0};
            case ' ' -> new int[]{ 0,  0,  0,  0,  0,  0,  0};
            case '+' -> new int[]{ 0,  0,  4, 14,  4,  0,  0};
            case '-' -> new int[]{ 0,  0,  0, 14,  0,  0,  0};
            default  -> new int[]{ 0,  0,  0,  0,  0,  0,  0};
        };
    }

    // -------------------------------------------------------------------------
    // GLFW input → GameShell routing
    // -------------------------------------------------------------------------

    private void setupCallbacks() {
        glfwSetCursorPosCallback(window, (win, x, y) -> {
            if (shell == null) return;
            shell.idleCycles = 0;
            int mouseX = toLogicalX(x);
            int mouseY = toLogicalY(y);
            cursorX = mouseX;
            cursorY = mouseY;
            if (isSidebarX(mouseX)) {
                shell.mouseX = -1;
                shell.mouseY = -1;
                return;
            }
            if (middleMouseDragging && shell instanceof Client) {
                ((Client) shell).rotateOrbitCamera(mouseX - middleMouseX, mouseY - middleMouseY);
                middleMouseX = mouseX;
                middleMouseY = mouseY;
            }
            shell.mouseX = mouseX;
            shell.mouseY = mouseY;
        });

        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (shell == null) return;
            shell.idleCycles = 0;
            if (action == GLFW_PRESS && isSidebarX(cursorX)) {
                clickSidebar(cursorX, cursorY);
                return;
            }
            if (button == GLFW_MOUSE_BUTTON_MIDDLE) {
                middleMouseDragging = action == GLFW_PRESS;
                middleMouseX = shell.mouseX;
                middleMouseY = shell.mouseY;
                return;
            }
            int btn = (button == GLFW_MOUSE_BUTTON_RIGHT) ? 2 : 1;
            if (action == GLFW_PRESS) {
                shell.nextMouseClickButton = btn;
                shell.nextMouseClickX      = shell.mouseX;
                shell.nextMouseClickY      = shell.mouseY;
                shell.nextMouseClickTime   = System.currentTimeMillis();
                shell.mouseButton          = btn;
            } else if (action == GLFW_RELEASE) {
                shell.mouseButton = 0;
            }
        });

        glfwSetFramebufferSizeCallback(window, (win, width, height) ->
                updateOutputViewport(width, height));
        glfwSetWindowSizeCallback(window, (win, width, height) -> {
            windowW = width;
            windowH = height;
        });

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_GRAVE_ACCENT) {
                if (action == GLFW_PRESS) statsOverlayVisible = !statsOverlayVisible;
                return;
            }
            if (shell == null) return;
            shell.idleCycles = 0;
            int gk = glfwToGameKey(key);
            if (gk < 0) return;
            if (action == GLFW_PRESS || action == GLFW_REPEAT) {
                if (gk < 128)  shell.actionKey[gk] = 1;
                if (gk > 4) {
                    shell.keyQueue[shell.keyQueueWritePos] = gk;
                    shell.keyQueueWritePos = (shell.keyQueueWritePos + 1) & 0x7F;
                }
            } else if (action == GLFW_RELEASE) {
                if (gk < 128) shell.actionKey[gk] = 0;
            }
        });

        // Printable characters (letters, digits, symbols).
        glfwSetCharCallback(window, (win, codepoint) -> {
            if (codepoint == '`') return;
            if (shell == null || codepoint < 32 || codepoint >= 128) return;
            shell.idleCycles = 0;
            shell.keyQueue[shell.keyQueueWritePos] = codepoint;
            shell.keyQueueWritePos = (shell.keyQueueWritePos + 1) & 0x7F;
        });
    }

    private void updateOutputViewport() {
        int[] width = new int[1];
        int[] height = new int[1];
        glfwGetFramebufferSize(window, width, height);
        updateOutputViewport(width[0], height[0]);
    }

    private void updateOutputViewport(int width, int height) {
        if (width <= 0 || height <= 0) return;
        if (sidebarInsideWindow()) {
            int sidebarLogW = SIDEBAR_RAIL_W + (sidebarOpen ? SIDEBAR_PANEL_W : 0);
            double scale = Math.min((double) width / (screenW + sidebarLogW),
                                    (double) height / screenH);
            int gameW   = Math.max(1, (int) Math.round(screenW * scale));
            int gameH   = Math.max(1, (int) Math.round(screenH * scale));
            int sidebarW = Math.max(1, (int) Math.round(sidebarLogW * scale));
            int gameX   = sidebarOpen ? 0 : (width - gameW - sidebarW) / 2;
            glViewport(gameX, (height - gameH) / 2, gameW, gameH);
        } else {
            double scale = Math.min((double) width / outputW(), (double) height / screenH);
            int viewportW = Math.max(1, (int) Math.round(screenW * scale));
            int viewportH = Math.max(1, (int) Math.round(screenH * scale));
            int fullW     = Math.max(1, (int) Math.round(outputW() * scale));
            glViewport((width - fullW) / 2, (height - viewportH) / 2, viewportW, viewportH);
        }
    }

    private int toLogicalX(double x) {
        if (sidebarInsideWindow()) {
            int sidebarLogW = SIDEBAR_RAIL_W + (sidebarOpen ? SIDEBAR_PANEL_W : 0);
            double scale = Math.min((double) windowW / (screenW + sidebarLogW),
                                    (double) windowH / screenH);
            double sidebarPhysW     = sidebarLogW * scale;
            double sidebarPhysStart = windowW - sidebarPhysW;
            if (x >= sidebarPhysStart) {
                // cursor is over the sidebar at the right edge
                return screenW + (int) ((x - sidebarPhysStart) / scale);
            }
            // cursor is over the game canvas
            double gamePhysW    = screenW * scale;
            double gamePhysStart = sidebarOpen ? 0 : (sidebarPhysStart - gamePhysW) / 2.0;
            return (int) ((x - gamePhysStart) / scale);
        }
        double scale = Math.min((double) windowW / outputW(), (double) windowH / screenH);
        double left  = (windowW - outputW() * scale) / 2.0;
        return (int) ((x - left) / scale);
    }

    private int toLogicalY(double y) {
        double scale;
        if (sidebarInsideWindow()) {
            int sidebarLogW = SIDEBAR_RAIL_W + (sidebarOpen ? SIDEBAR_PANEL_W : 0);
            scale = Math.min((double) windowW / (screenW + sidebarLogW),
                             (double) windowH / screenH);
        } else {
            scale = Math.min((double) windowW / outputW(), (double) windowH / screenH);
        }
        double top = (windowH - screenH * scale) / 2.0;
        return (int) ((y - top) / scale);
    }

    private int outputW() {
        return screenW + SIDEBAR_RAIL_W + (sidebarOpen ? SIDEBAR_PANEL_W : 0);
    }

    private void setOutputViewport(int logicalWidth) {
        int[] width = new int[1];
        int[] height = new int[1];
        glfwGetFramebufferSize(window, width, height);
        double scale = Math.min((double) width[0] / logicalWidth, (double) height[0] / screenH);
        int viewportW = Math.max(1, (int) Math.round(logicalWidth * scale));
        int viewportH = Math.max(1, (int) Math.round(screenH * scale));
        glViewport(viewportX(width[0], viewportW), (height[0] - viewportH) / 2, viewportW, viewportH);
    }

    private void clickSidebar(int x, int y) {
        int railX = sidebarRailX();
        if (x >= railX) {
            int tab;
            if (y >= screenH - SIDEBAR_ROW_H) {
                tab = 5; // settings pinned at bottom
            } else {
                tab = y / SIDEBAR_ROW_H;
                if (tab < 0 || tab >= SIDEBAR_TABS - 1) return;
            }
            if (sidebarOpen && sidebarTab == tab) {
                sidebarOpen = false;
            } else {
                sidebarOpen = true;
                sidebarTab  = tab;
            }
            resizeForSidebar();
            updateOutputViewport();
            return;
        }
        if (!sidebarOpen) return;
        // Close button (top-right X in the panel header)
        if (x >= sidebarPanelX() + SIDEBAR_PANEL_W - 28 && y <= 42) {
            sidebarOpen = false;
            resizeForSidebar();
            updateOutputViewport();
            return;
        }
        // Per-tab click handling
        switch (sidebarTab) {
            case 0 -> { // Hiscores – skill selector buttons (2 per row, 10 rows, y=52..181)
                int px = sidebarPanelX();
                int relX = x - (px + 10);
                int relY = y - 52;
                int col = relX / 82;
                int row = relY / 13;
                if (row >= 0 && row < 10 && col >= 0 && col < 2) {
                    int skill = row * 2 + col;
                    if (skill < HSCORE_SKILL_LABEL.length && skill != hiscoresSkill) {
                        hiscoresSkill  = skill;
                        hiscoresNames  = new String[0];
                        hiscoresLevels = new int[0];
                        hiscoresFetchAt = 0; // force immediate re-fetch
                    }
                }
            }
            case 1 -> { // XP Screen Toggle
                if (y >= 140 && y < 184) xpScreenEnabled = !xpScreenEnabled;
            }
            case 2 -> { // XP Tracker – reset button
                int px = sidebarPanelX();
                if (x >= px + SIDEBAR_PANEL_W - 58 && y >= 52 && y < 68) {
                    resetXpSession();
                }
            }
            case 5 -> { // Settings toggles (each row is 44 px tall starting at y=72)
                if (y >= 72  && y < 116) sidebarGpuEnabled   = !sidebarGpuEnabled;
                if (y >= 116 && y < 160) sidebarFpsEnabled    = !sidebarFpsEnabled;
                if (y >= 160 && y < 204) sidebarRoofsEnabled  = !sidebarRoofsEnabled;
                if (y >= 204 && y < 248) toggleFullscreen();
                if (y >= 248 && y < 292) settingsShiftClick   = !settingsShiftClick;
                if (y >= 292 && y < 336) settingsDiscordRp    = !settingsDiscordRp;
            }
        }
    }

    private void toggleFullscreen() {
        settingsFullscreen = !settingsFullscreen;
        if (settingsFullscreen) {
            long monitor = glfwGetPrimaryMonitor();
            org.lwjgl.glfw.GLFWVidMode mode = glfwGetVideoMode(monitor);
            if (mode != null) {
                glfwSetWindowMonitor(window, monitor, 0, 0,
                        mode.width(), mode.height(), mode.refreshRate());
            }
        } else {
            glfwSetWindowMonitor(window, NULL, 100, 100, outputW(), screenH, GLFW_DONT_CARE);
        }
        updateOutputViewport();
    }

    private boolean sidebarInsideWindow() {
        return glfwGetWindowAttrib(window, GLFW_MAXIMIZED) == GLFW_TRUE;
    }

    private int sidebarRailX() {
        return screenW + (sidebarOpen ? SIDEBAR_PANEL_W : 0);
    }

    private int sidebarPanelX() {
        return screenW;
    }

    private boolean isSidebarX(int x) {
        if (x >= sidebarRailX()) return true;
        return sidebarOpen && x >= sidebarPanelX();
    }

    private void resizeForSidebar() {
        if (!sidebarInsideWindow()) {
            glfwSetWindowSize(window, outputW(), screenH);
        }
        updateOutputViewport();
    }

    private int viewportX(int framebufferW, int viewportW) {
        if (sidebarInsideWindow() && sidebarOpen) return 0;
        return (framebufferW - viewportW) / 2;
    }

    /** Map a GLFW key constant to the game's internal key code. */
    private static int glfwToGameKey(int k) {
        return switch (k) {
            case GLFW_KEY_LEFT         -> 1;
            case GLFW_KEY_RIGHT        -> 2;
            case GLFW_KEY_UP           -> 3;
            case GLFW_KEY_DOWN         -> 4;
            case GLFW_KEY_LEFT_CONTROL,
                 GLFW_KEY_RIGHT_CONTROL -> 5;
            case GLFW_KEY_BACKSPACE,
                 GLFW_KEY_DELETE        -> 8;
            case GLFW_KEY_TAB          -> 9;
            case GLFW_KEY_ENTER,
                 GLFW_KEY_KP_ENTER      -> 10;
            case GLFW_KEY_F1  -> 1008; case GLFW_KEY_F2  -> 1009;
            case GLFW_KEY_F3  -> 1010; case GLFW_KEY_F4  -> 1011;
            case GLFW_KEY_F5  -> 1012; case GLFW_KEY_F6  -> 1013;
            case GLFW_KEY_F7  -> 1014; case GLFW_KEY_F8  -> 1015;
            case GLFW_KEY_F9  -> 1016; case GLFW_KEY_F10 -> 1017;
            case GLFW_KEY_F11 -> 1018; case GLFW_KEY_F12 -> 1019;
            case GLFW_KEY_HOME      -> 1000;
            case GLFW_KEY_END       -> 1001;
            case GLFW_KEY_PAGE_UP   -> 1002;
            case GLFW_KEY_PAGE_DOWN -> 1003;
            default -> -1;
        };
    }

    private void setupVAO() {
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER,
                     (long) MAX_VERTS * FLOATS_PER_VERT * Float.BYTES,
                     GL_DYNAMIC_DRAW);

        int stride = FLOATS_PER_VERT * Float.BYTES;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0L);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 2L * Float.BYTES);
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(2, 2, GL_FLOAT, false, stride, 5L * Float.BYTES);
        glEnableVertexAttribArray(2);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 7L * Float.BYTES);
        glEnableVertexAttribArray(3);
        glVertexAttribPointer(4, 1, GL_FLOAT, false, stride, 8L * Float.BYTES);
        glEnableVertexAttribArray(4);
        glVertexAttribPointer(5, 1, GL_FLOAT, false, stride, 9L * Float.BYTES);
        glEnableVertexAttribArray(5);
    }

    private int buildProgram() {
        return buildProgram(VERT_SRC, FRAG_SRC);
    }

    private int buildProgram(String vertSrc, String fragSrc) {
        int vs = compileShader(GL_VERTEX_SHADER,   vertSrc);
        int fs = compileShader(GL_FRAGMENT_SHADER, fragSrc);
        int p  = glCreateProgram();
        glAttachShader(p, vs);
        glAttachShader(p, fs);
        glLinkProgram(p);
        if (glGetProgrami(p, GL_LINK_STATUS) == GL_FALSE)
            throw new RuntimeException("Shader link:\n" + glGetProgramInfoLog(p));
        glDeleteShader(vs);
        glDeleteShader(fs);
        return p;
    }

    private static int compileShader(int type, String src) {
        int id = glCreateShader(type);
        glShaderSource(id, src);
        glCompileShader(id);
        if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE)
            throw new RuntimeException("Shader compile:\n" + glGetShaderInfoLog(id));
        return id;
    }
}

package com.gradwahl.rs254.gl;

import jagex2.client.GameShell;
import jagex2.client.Client;
import jagex2.graphics.Pix3D;
import jagex2.graphics.PixMap;
import jagex2.graphics.TriangleRenderer;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

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

    private static final String UI_VERT_SRC = """
            #version 330 core
            layout(location=0) in vec2 aPos;
            layout(location=1) in vec2 aUV;
            out vec2 vUV;
            void main() {
                gl_Position = vec4(aPos, 0.0, 1.0);
                vUV = aUV;
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
    private int     uiProg, uiQuadVao, uiQuadVbo, uiTex, uiTexLoc;
    private IntBuffer uiDirectBuf;  // direct (off-heap) buffer for glTexSubImage2D

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
        this.windowW = screenW;
        this.windowH = screenH;
    }

    // -------------------------------------------------------------------------
    // lifecycle
    // -------------------------------------------------------------------------

    /** Create the GLFW window and initialise OpenGL. Call once before use. */
    public void init() {
        glfwSetErrorCallback((error, description) ->
            System.err.println("[GLFW ERROR] " + error + ": " + org.lwjgl.glfw.GLFWErrorCallback.getDescription(description)));
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE,   GLFW_FALSE);

        window = glfwCreateWindow(screenW, screenH, "RS254 - OpenGL", NULL, NULL);
        if (window == NULL) throw new RuntimeException("GLFW window creation failed");

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
        glfwPollEvents();
        glClear(GL_COLOR_BUFFER_BIT);
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
        for (int t : gpuTex) if (t != 0) glDeleteTextures(t);
        MemoryUtil.memFree(buf);
        if (uiDirectBuf != null) MemoryUtil.memFree(uiDirectBuf);
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
        PixMap.uiBuffer = new int[screenW * screenH];
        PixMap.uiWidth  = screenW;
        PixMap.uiHeight = screenH;
        // Direct (off-heap) copy buffer for glTexSubImage2D — LWJGL requires direct buffers.
        uiDirectBuf = MemoryUtil.memAllocInt(screenW * screenH);

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
        uiTexLoc = glGetUniformLocation(uiProg, "uUI");

        // Create the 2D overlay texture (BGRA so IntBuffer maps straight).
        uiTex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, uiTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, screenW, screenH, 0,
                     GL_BGRA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    }

    private void drawUIOverlay() {
        if (PixMap.uiBuffer == null) return;
        // Upload the current frame's UI pixels.
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, uiTex);
        uiDirectBuf.clear();
        uiDirectBuf.put(PixMap.uiBuffer, 0, screenW * screenH);
        uiDirectBuf.flip();
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, screenW, screenH,
                        GL_BGRA, GL_UNSIGNED_BYTE,
                        uiDirectBuf);
        glUseProgram(uiProg);
        glUniform1i(uiTexLoc, 0);
        glBindVertexArray(uiQuadVao);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        // Restore the 3D shader for the next frame.
        glUseProgram(prog);
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
        int x = vpDrawX + vpW - maxChars * 4 * 2 - 7;
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
            cursor += 4 * scale;
        }
    }

    private void drawGlyph(char ch, int x, int y, int scale, int rgb) {
        int[] rows = glyph(ch);
        for (int row = 0; row < rows.length; row++) {
            for (int col = 0; col < 3; col++) {
                if ((rows[row] & (1 << (2 - col))) != 0) {
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

    private static int[] glyph(char ch) {
        return switch (ch) {
            case '0' -> new int[]{7, 5, 5, 5, 7};
            case '1' -> new int[]{2, 6, 2, 2, 7};
            case '2' -> new int[]{7, 1, 7, 4, 7};
            case '3' -> new int[]{7, 1, 7, 1, 7};
            case '4' -> new int[]{5, 5, 7, 1, 1};
            case '5' -> new int[]{7, 4, 7, 1, 7};
            case '6' -> new int[]{7, 4, 7, 5, 7};
            case '7' -> new int[]{7, 1, 1, 1, 1};
            case '8' -> new int[]{7, 5, 7, 5, 7};
            case '9' -> new int[]{7, 5, 7, 1, 7};
            case 'B' -> new int[]{6, 5, 6, 5, 6};
            case 'E' -> new int[]{7, 4, 6, 4, 7};
            case 'F' -> new int[]{7, 4, 6, 4, 4};
            case 'M' -> new int[]{5, 7, 7, 5, 5};
            case 'P' -> new int[]{6, 5, 6, 4, 4};
            case 'S' -> new int[]{7, 4, 7, 1, 7};
            case 'T' -> new int[]{7, 2, 2, 2, 2};
            case ':' -> new int[]{0, 2, 0, 2, 0};
            default  -> new int[]{0, 0, 0, 0, 0};
        };
    }

    // -------------------------------------------------------------------------
    // GLFW input → GameShell routing
    // -------------------------------------------------------------------------

    private void setupCallbacks() {
        glfwSetCursorPosCallback(window, (win, x, y) -> {
            if (shell == null) return;
            int mouseX = toLogicalX(x);
            int mouseY = toLogicalY(y);
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
        double scale = Math.min((double) width / screenW, (double) height / screenH);
        int viewportW = Math.max(1, (int) Math.round(screenW * scale));
        int viewportH = Math.max(1, (int) Math.round(screenH * scale));
        glViewport((width - viewportW) / 2, (height - viewportH) / 2, viewportW, viewportH);
    }

    private int toLogicalX(double x) {
        double scale = Math.min((double) windowW / screenW, (double) windowH / screenH);
        double left = (windowW - screenW * scale) / 2.0;
        return (int) ((x - left) / scale);
    }

    private int toLogicalY(double y) {
        double scale = Math.min((double) windowW / screenW, (double) windowH / screenH);
        double top = (windowH - screenH * scale) / 2.0;
        return (int) ((y - top) / scale);
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

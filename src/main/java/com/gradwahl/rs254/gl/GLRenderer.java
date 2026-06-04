package com.gradwahl.rs254.gl;

import jagex2.client.GameShell;
import jagex2.client.Client;
import jagex2.graphics.Pix3D;
import jagex2.graphics.PixMap;
import jagex2.graphics.TriangleRenderer;
import com.gradwahl.rs254.ClientDebugger;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryUtil;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import javax.swing.JEditorPane;
import javax.swing.SwingUtilities;
import javax.swing.text.AttributeSet;
import javax.swing.text.DefaultCaret;
import javax.swing.text.Element;
import javax.swing.text.StyleConstants;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLDocument;
import javax.swing.text.html.HTMLEditorKit;

import com.gradwahl.rs254.ClientConfig;
import com.gradwahl.rs254.discord.DiscordRichPresence;

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
    private static final int SIDEBAR_PANEL_W = 320;
    private static final int SIDEBAR_RAIL_W  = 32;
    private static final int SIDEBAR_ROW_H   = 36;
    private static final int SIDEBAR_TABS    = 6;
    private static final int TAB_ICON_SIZE   = 22;
    private static final String DISCORD_APP_ID = "1507449981689270283";
    private static final String[] AFK_LABELS = {
            "90 Seconds", "2 Minutes", "5 Minutes", "10 Minutes", "30 Minutes", "Never"
    };
    private static final int[] AFK_CYCLES = {
            4_500, 6_000, 15_000, 30_000, 90_000, -1
    };
    private static final int LOSTHQ_READER_W    = SIDEBAR_PANEL_W - 8;
    private static final int LOSTHQ_READER_WIDE = 1280; // wide backing canvas for drag-panning occasional overflow
    private static final int HSCROLL_H          = 10;  // horizontal scrollbar height
    private static final int LOSTHQ_BODY_W       = LOSTHQ_READER_W - 12;
    private static final int LOSTHQ_QUEST_COMPLETE_IMAGE_W = LOSTHQ_READER_W;
    private static final int LOSTHQ_CONTENT_IMAGE_W = LOSTHQ_BODY_W - 4;
    private static final int[] XP_TABLE = buildXpTable();
    private static final java.util.Map<String, String[][]> SKILL_UNLOCKS = buildSkillUnlocks();
    private static java.util.Map<String, String[][]> buildSkillUnlocks() {
        java.util.Map<String, String[][]> m = new java.util.LinkedHashMap<>();
        m.put("attack", new String[][]{
            {"Bronze weapons",  "1"},  {"Iron weapons",    "1"},
            {"Steel weapons",   "5"},  {"Black weapons",  "10"},
            {"Mithril weapons","20"},  {"Adamant weapons","30"},
            {"Rune weapons",   "40"},  {"Dragon weapons", "60"},
        });
        m.put("strength", new String[][]{
            {"Chickens / Goblins",     "1"},  {"Barbarian Village",    "1"},
            {"Monks of Zamorak",      "15"},  {"Guards",              "20"},
            {"Hill Giants",           "30"},  {"Moss Giants",         "40"},
            {"Fire Giants",           "60"},  {"Greater Demons",      "70"},
            {"Black Demons",          "80"},
        });
        m.put("defence", new String[][]{
            {"Bronze armour",   "1"},  {"Iron armour",     "1"},
            {"Steel armour",    "5"},  {"Black armour",   "10"},
            {"Mithril armour", "20"},  {"Adamant armour", "30"},
            {"Rune armour",    "40"},  {"Dragon armour",  "60"},
        });
        m.put("hitpoints", new String[][]{
            {"Gained via combat XP", "1"},
            {"Hitpoints increase with every combat level", "1"},
        });
        m.put("ranged", new String[][]{
            {"Shortbow",         "1"},  {"Oak shortbow",    "5"},
            {"Willow shortbow", "20"},  {"Maple shortbow", "30"},
            {"Yew shortbow",    "40"},  {"Magic shortbow", "50"},
        });
        m.put("prayer", new String[][]{
            {"Thick Skin",            "1"},  {"Burst of Strength",     "4"},
            {"Clarity of Thought",    "7"},  {"Rock Skin",            "10"},
            {"Superhuman Strength",  "13"},  {"Improved Reflexes",    "16"},
            {"Rapid Restore",        "19"},  {"Rapid Heal",           "22"},
            {"Protect Item",         "25"},  {"Steel Skin",           "28"},
            {"Ultimate Strength",    "31"},  {"Incredible Reflexes",  "34"},
            {"Protect from Magic",   "37"},  {"Protect from Missiles","40"},
            {"Protect from Melee",   "43"},  {"Retribution",          "46"},
            {"Redemption",           "49"},  {"Smite",                "52"},
        });
        m.put("magic", new String[][]{
            {"Wind Strike",    "1"},  {"Water Strike",   "5"},
            {"Earth Strike",   "9"},  {"Fire Strike",   "13"},
            {"Wind Bolt",     "17"},  {"Water Bolt",    "23"},
            {"Earth Bolt",    "29"},  {"Fire Bolt",     "35"},
            {"Wind Blast",    "41"},  {"Superheat Item","43"},
            {"Water Blast",   "47"},  {"High Alchemy",  "55"},
            {"Earth Blast",   "53"},  {"Fire Blast",    "59"},
            {"Wind Wave",     "62"},  {"Water Wave",    "65"},
            {"Earth Wave",    "70"},  {"Fire Wave",     "75"},
        });
        m.put("cooking", new String[][]{
            {"Shrimps",    "1"},  {"Anchovies",  "1"},
            {"Sardine",    "1"},  {"Herring",    "5"},
            {"Mackerel",  "10"},  {"Trout",     "15"},
            {"Pike",      "20"},  {"Salmon",    "25"},
            {"Tuna",      "30"},  {"Lobster",   "40"},
            {"Bass",      "43"},  {"Swordfish", "45"},
            {"Shark",     "80"},
        });
        m.put("woodcutting", new String[][]{
            {"Logs",       "1"},  {"Oak logs",   "15"},
            {"Willow logs","30"},  {"Maple logs", "45"},
            {"Yew logs",  "60"},  {"Magic logs", "75"},
        });
        m.put("fletching", new String[][]{
            {"Arrow shafts",        "1"},  {"Shortbow (u)",         "5"},
            {"Longbow (u)",        "10"},  {"Oak shortbow (u)",     "20"},
            {"Oak longbow (u)",    "25"},  {"Willow shortbow (u)", "35"},
            {"Willow longbow (u)", "40"},  {"Maple shortbow (u)",  "50"},
            {"Maple longbow (u)",  "55"},  {"Yew shortbow (u)",    "65"},
            {"Yew longbow (u)",    "70"},  {"Magic shortbow (u)",  "80"},
            {"Magic longbow (u)",  "85"},
        });
        m.put("fishing", new String[][]{
            {"Shrimps",    "1"},  {"Sardine",    "5"},
            {"Herring",   "10"},  {"Anchovies", "15"},
            {"Trout",     "20"},  {"Pike",      "25"},
            {"Salmon",    "30"},  {"Tuna",      "35"},
            {"Lobster",   "40"},  {"Swordfish", "50"},
            {"Shark",     "76"},
        });
        m.put("firemaking", new String[][]{
            {"Logs",       "1"},  {"Oak logs",   "15"},
            {"Willow logs","30"},  {"Maple logs", "45"},
            {"Yew logs",  "60"},  {"Magic logs", "75"},
        });
        m.put("crafting", new String[][]{
            {"Leather gloves",        "1"},  {"Leather boots",        "7"},
            {"Leather cowl",          "9"},  {"Leather vambraces",   "11"},
            {"Leather body",         "14"},  {"Leather chaps",       "18"},
            {"Coif",                 "38"},  {"Studded body",        "41"},
            {"Studded chaps",        "44"},  {"Snakeskin bandana",   "48"},
            {"Snakeskin body",       "53"},  {"Green d'hide vambs",  "57"},
            {"Green d'hide body",    "63"},  {"Blue d'hide body",    "71"},
            {"Red d'hide body",      "77"},  {"Black d'hide body",   "84"},
        });
        m.put("smithing", new String[][]{
            {"Bronze bar",    "1"},  {"Iron bar",      "15"},
            {"Silver bar",   "20"},  {"Steel bar",     "30"},
            {"Gold bar",     "40"},  {"Mithril bar",   "50"},
            {"Adamantite bar","70"},  {"Runite bar",    "85"},
        });
        m.put("mining", new String[][]{
            {"Rune essence", "1"},  {"Copper ore",    "1"},
            {"Tin ore",       "1"},  {"Iron ore",     "15"},
            {"Silver ore",   "20"},  {"Coal",         "30"},
            {"Gold ore",     "40"},  {"Mithril ore",  "55"},
            {"Adamantite ore","70"},  {"Runite ore",   "85"},
        });
        m.put("herblore", new String[][]{
            {"Attack potion",  "1"},  {"Antipoison",     "5"},
            {"Strength potion","12"},  {"Restore potion","22"},
            {"Energy potion", "26"},  {"Defence potion","30"},
            {"Combat potion", "36"},  {"Prayer potion", "38"},
            {"Super attack",  "45"},  {"Fishing potion","50"},
            {"Super energy",  "52"},  {"Super strength","55"},
            {"Weapon poison", "60"},  {"Super defence", "66"},
            {"Antifire",      "69"},  {"Ranging potion","72"},
            {"Magic potion",  "76"},  {"Zamorak brew",  "78"},
            {"Saradomin brew","81"},
        });
        m.put("agility", new String[][]{
            {"Gnome Stronghold course", "1"},
            {"Barbarian Outpost course","35"},
            {"Ape Atoll course",        "48"},
            {"Wilderness course",       "52"},
            {"Advanced Gnome course",   "85"},
        });
        m.put("thieving", new String[][]{
            {"Men / Women",        "1"},  {"Farmers",         "10"},
            {"H.A.M. Members",    "15"},  {"Warriors",        "25"},
            {"Rogues",            "32"},  {"Master Farmers",  "38"},
            {"Guards",            "40"},  {"Fremennik Citizens","45"},
            {"Knights of Ardougne","55"},  {"Watchmen",        "65"},
            {"Paladins",          "70"},  {"Heroes",          "80"},
            {"Elves",             "85"},
        });
        m.put("runecrafting", new String[][]{
            {"Air runes",   "1"},  {"Mind runes",   "2"},
            {"Water runes", "5"},  {"Earth runes",  "9"},
            {"Fire runes", "14"},  {"Body runes",  "20"},
            {"Cosmic runes","27"},  {"Chaos runes", "35"},
            {"Nature runes","44"},  {"Law runes",   "54"},
            {"Death runes", "65"},  {"Blood runes", "77"},
        });
        return m;
    }
    private static int[] buildXpTable() {
        int[] xp = new int[100];
        for (int level = 2; level <= 99; level++) {
            long points = 0;
            for (int i = 1; i < level; i++) {
                points += (long) Math.floor(i + 300.0 * Math.pow(2.0, i / 7.0));
            }
            xp[level] = (int) (points / 4);
        }
        return xp;
    }

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
    private static final int HSCORE_SKILL_COLUMNS = 3;

    // LostHQ toolkit destinations, kept in the same order as the website menu.
    private static final String[] LOSTHQ_ITEMS = {
        "QUEST GUIDES",
        "SKILL GUIDES",
        "NPC DATABASE",
        "ITEM DATABASE",
        "SPECIAL GUIDES",
        "CALCULATORS",
        "TREASURE TRAIL REWARDS",
        "TREASURE TRAIL GUIDES"
    };
    private static final String[] LOSTHQ_URLS = {
        "https://2004.losthq.rs/?p=questguides",
        "https://2004.losthq.rs/?p=skillguides",
        "https://2004.losthq.rs/?p=npcdb",
        "https://2004.losthq.rs/?p=itemdb",
        "https://2004.losthq.rs/?p=specialguides",
        "https://2004.losthq.rs/?p=calculators",
        "https://2004.losthq.rs/?p=cluetables",
        "https://2004.losthq.rs/?p=clueguides"
    };
    private static final String[] LOSTHQ_ICON_URLS = {
        "https://2004.losthq.rs/img/questicon.png",
        "https://2004.losthq.rs/img/stats.png",
        "https://2004.losthq.rs/img/skeleton.png",
        "https://2004.losthq.rs/img/itemdb.png",
        "https://2004.losthq.rs/img/specialguides.png",
        "https://2004.losthq.rs/img/swordicon.png",
        "https://2004.losthq.rs/img/casket.png",
        "https://2004.losthq.rs/img/clueicon.png"
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
    private boolean  frameDrawable = true;
    private boolean  windowIconified;
    private int      framebufferW = 1;
    private int      framebufferH = 1;
    private int      restoreCooldownFrames;

    private final FloatBuffer buf =
            MemoryUtil.memAllocFloat(MAX_VERTS * FLOATS_PER_VERT);
    private int vertCount;

    private final int[] gpuTex   = new int[50];   // OpenGL texture IDs per slot
    private int         currentTexId = -1;         // texture bound for current batch

    // UI overlay pass
    private int     uiProg, uiQuadVao, uiQuadVbo, uiTex, uiTexLoc, uiUMinLoc, uiUMaxLoc;
    private IntBuffer uiDirectBuf;  // direct (off-heap) buffer for glTexSubImage2D

    // Tab icons — loaded once from resources, drawn in the sidebar rail
    private final BufferedImage[] tabIcons = new BufferedImage[SIDEBAR_TABS];
    private final BufferedImage[] worldMapKeyPages = new BufferedImage[2];

    // World map — region tiles are decoded lazily for the currently visible plane.
    @SuppressWarnings("unchecked")
    private final Map<String, BufferedImage>[] worldMapTiles = new Map[] {
            new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>()
    };
    private final Set<String> worldMapRegions = new HashSet<>();
    private boolean       worldMapLoaded = false;
    private float         mapZoom      = -1;  // -1 = needs init/load
    private float         mapPanX      = 0;
    private float         mapPanY      = 0;
    private boolean       mapDragging  = false;
    private float         mapDragLastX, mapDragLastY;
    private boolean       worldMapKeyVisible = false;
    private int           worldMapKeyPage    = 0;
    private boolean       worldMapFollowing  = false;
    private boolean       worldMapFullscreen = false;
    private volatile String  worldmapStatus  = null;
    // Tile coordinate origin (top-left of rendered image = tile X/Z of pixel 0,0)
    private static final int WM_ORIGIN_X = 1856;  // westernmost tile X  (chunk 29 * 64)
    private static final int WM_ORIGIN_Z = 1280;  // southernmost tile Z (chunk 20 * 64)
    private static final int WM_WIDTH    = 1728;  // tiles wide  (27 chunks * 64)
    private static final int WM_HEIGHT   = 9088;  // tiles tall (142 chunks * 64)
    private static final int WM_REGION_SIZE  = 64;
    private static final int WM_REGION_MIN_X = 29;
    private static final int WM_REGION_MAX_X = 55;
    private static final int WM_REGION_MIN_Z = 20;
    private static final int WM_REGION_MAX_Z = 161;
    private static final int WM_DETAIL_REGION_MIN_X = 36;
    private static final int WM_DETAIL_REGION_MAX_X = 55;
    private static final int WM_DETAIL_REGION_MIN_Z = 44;
    private static final int WM_DETAIL_REGION_MAX_Z = 62;
    private static final int WM_DEFAULT_TILE_X = 50 * WM_REGION_SIZE + WM_REGION_SIZE / 2;
    private static final int WM_DEFAULT_TILE_Z = 50 * WM_REGION_SIZE + WM_REGION_SIZE / 2;

    // Player world tile position — updated each frame by Client
    public static volatile int playerTileX = -1;
    public static volatile int playerTileZ = -1;
    public static volatile int playerPlane = 0;

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
    private static final Preferences SETTINGS_PREFS = Preferences.userNodeForPackage(GLRenderer.class);
    private static final DiscordRichPresence DISCORD_RPC = new DiscordRichPresence(DISCORD_APP_ID);
    public static volatile int afkTimeoutCycles = AFK_CYCLES[0];
    public static volatile boolean shiftKeyDown;
    public static volatile boolean settingShiftDropInventory;
    public static volatile boolean settingShiftTakeGround;
    public static volatile boolean settingShiftAttackNpc;
    public static volatile boolean settingShiftPickpocketNpc;
    public static volatile boolean settingShiftBankNpc;
    public static volatile boolean settingShiftUseQuicklyBankBooth;
    public static volatile boolean settingShiftExamineAnything;
    public static volatile boolean settingDiscordRichPresence;
    public static volatile boolean settingFps60Enabled;
    private static volatile long settingFps60SuppressedUntilMs;
    private boolean sidebarOpen;
    private int     sidebarTab;
    private boolean sidebarGpuEnabled   = true;
    private boolean sidebarFpsEnabled   = SETTINGS_PREFS.getBoolean("fps60", false);
    private boolean sidebarRoofsEnabled = true;
    private boolean settingsFullscreen  = false;
    private boolean settingsAfkDropdownOpen;
    private int     settingsAfkIndex    = SETTINGS_PREFS.getInt("afkIndex", 0);
    private final String clientVersionText = "Version: " + ClientConfig.currentVersionLabel();

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
    private final java.util.concurrent.ExecutorService lostHqLauncher =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "losthq-launcher");
                t.setDaemon(true);
                return t;
            });
    private volatile String lostHqStatus = "SELECT A LOSTHQ TOOL";
    private final BufferedImage[] lostHqIcons = new BufferedImage[LOSTHQ_ITEMS.length];
    private volatile boolean lostHqIconsLoading;
    private volatile JEditorPane lostHqPage;
    private volatile URI lostHqPageUri;
    private volatile String lostHqHtml;
    // Click-to-zoom overlay for quest-reward parchment images.
    // When non-null, a full-screen modal shows the image at high resolution;
    // any mouse click dismisses it.
    private volatile BufferedImage lostHqZoomImage;
    private final Set<Integer> lostHqCompletedSteps = new HashSet<>();
    private final Object lostHqProgressLock = new Object();
    private boolean lostHqProgressRefreshScheduled;
    private long lostHqProgressRevision;
    private long lostHqProgressPageId;
    private volatile int lostHqScrollY;
    private volatile int lostHqScrollX;
    private volatile int lostHqContentW = LOSTHQ_READER_W;
    private boolean lostHqDragging;
    private boolean lostHqDragMoved;
    private int     lostHqDragLastY;
    private int     lostHqDragLastX;
    private int     lostHqPressX, lostHqPressY;
    private boolean lostHqHScrollDrag;   // dragging the horizontal scrollbar thumb
    private int     lostHqHScrollAncX;   // mouseX when thumb drag started
    private int     lostHqHScrollAncV;   // lostHqScrollX when thumb drag started

    // Native search panel (NPC DB / Item DB) ─ no JEditorPane, drawn directly
    private record SearchEntry(int id, String name, String extra, String desc) {}
    private volatile List<SearchEntry> lostHqNpcData;
    private volatile List<SearchEntry> lostHqItemData;
    private List<SearchEntry>          lostHqSearchResults = List.of();
    private String                     lostHqSearchQuery   = "";
    private boolean                    lostHqSearchFocused;
    private boolean                    lostHqSearchIsNpc;  // true = NPC DB, false = Item DB

    // Native skill calculator panel ─ no JEditorPane, drawn directly
    private String  lostHqCalcSkill         = null;
    private int     lostHqCalcFocus         = 0;  // 0=none, 1=currentXp, 2=goalLevel
    private String  lostHqCalcCurrentXpStr  = "0";
    private String  lostHqCalcGoalLvlStr    = "2";

    // Navigation history ─ stack of URIs visited before the current page (null = main menu)
    private final java.util.Deque<URI> lostHqHistory = new java.util.LinkedList<>();

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
        loadSettings();
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
        restoreSceneGlState();

        setupUIPass();
        setupCallbacks();
        updateWindowSizeLimits();
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
        gs.setFramerate(50);
    }

    @Override
    public boolean shouldClose() {
        return glfwWindowShouldClose(window);
    }

    /**
     * Poll events and report whether the OS has removed the drawable surface.
     * Windows commonly reports a 0x0 framebuffer while minimized; several GL
     * drivers crash hard if we keep uploading/drawing/swap-buffering then.
     */
    public boolean isRenderPaused() {
        glfwPollEvents();
        if (window == NULL || windowIconified || glfwGetWindowAttrib(window, GLFW_ICONIFIED) == GLFW_TRUE) {
            ClientDebugger.onRenderPauseState(true, "iconified", framebufferW, framebufferH);
            sleepWhileRenderPaused();
            return true;
        }
        int[] fw = new int[1], fh = new int[1];
        glfwGetFramebufferSize(window, fw, fh);
        framebufferW = fw[0];
        framebufferH = fh[0];
        boolean paused = framebufferW <= 0 || framebufferH <= 0;
        ClientDebugger.onRenderPauseState(paused, paused ? "zero-framebuffer" : "drawable",
                framebufferW, framebufferH);
        if (paused) {
            sleepWhileRenderPaused();
        }
        return paused;
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
        frameDrawable = !isRenderPaused();
        if (!frameDrawable) {
            return;
        }
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
        if (!frameDrawable) {
            return;
        }
        flushBatch();
        if (restoreCooldownFrames > 0) {
            restoreCooldownFrames--;
            glfwSwapBuffers(window);
            return;
        }
        drawUIOverlay();
        sampledFrames++;
        updateMetrics();
        drawStatsOverlay();
        glfwSwapBuffers(window);
    }

    private void sleepWhileRenderPaused() {
        try {
            Thread.sleep(50L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean isFrameDrawable() {
        return frameDrawable;
    }

    public boolean shouldSuppressInterpolation() {
        return !isHighFpsEffectiveEnabled();
    }

    private void beginRestoreCooldown() {
        restoreCooldownFrames = Math.max(restoreCooldownFrames, 30);
        settingFps60SuppressedUntilMs = Math.max(settingFps60SuppressedUntilMs,
                System.currentTimeMillis() + 1000L);
        restoreSceneGlState();
    }

    public static boolean isHighFpsEffectiveEnabled() {
        return settingFps60Enabled && System.currentTimeMillis() >= settingFps60SuppressedUntilMs;
    }

    private void restoreSceneGlState() {
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glDisable(GL_SCISSOR_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        glActiveTexture(GL_TEXTURE0);
        glUseProgram(prog);
        glUniform2f(uScreen, screenW, screenH);
        glUniform1i(uTex, 0);
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
        DISCORD_RPC.disconnect();
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
            boolean transparent = Pix3D.textureTranslucent[texId];
            for (int i = 0; i < size * size; i++) {
                int c = texels[i];
                rgba.put((byte) (c >> 16));           // R
                rgba.put((byte) (c >>  8));           // G
                rgba.put((byte)  c);                  // B
                rgba.put(transparent && c == 0 ? (byte) 0 : (byte) -1);
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
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        loadTabIcons();
    }

    private void drawUIOverlay() {
        if (PixMap.uiBuffer == null) return;

        // Draw the quest-reward zoom overlay into uiBuffer BEFORE upload so the
        // overlay appears on the same frame the user clicked. It writes only to
        // the 3D viewport area, leaving the chatbox / inventory / sidebar alone.
        drawLostHqZoomOverlay();

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
        if (worldMapFullscreen) {
            drawWorldMapFullscreenNative(fw[0], fh[0]);
            glUseProgram(prog);
            updateOutputViewport();
            return;
        }
        int sidebarLogW = sidebarLogicalW();

        if (sidebarInsideWindow()) {
            double scale  = insideGameScale(fw[0], fh[0]);
            int gameW    = Math.max(1, (int) Math.round(screenW    * scale));
            int gameH    = Math.max(1, (int) Math.round(screenH    * scale));
            int gameX    = insideGameX(fw[0], gameW);
            int sidebarW = insideSidebarW(fw[0], gameX, gameW, sidebarLogW, scale);
            int vertOff  = (fh[0] - gameH) / 2;

            glUniform1f(uiUMinLoc, 0f);
            glUniform1f(uiUMaxLoc, (float) screenW / maxUiW);
            glViewport(gameX, vertOff, gameW, gameH);
            glDrawArrays(GL_TRIANGLES, 0, 6);

            if (sidebarW > 0) {
                double logicalStartX = screenW + sidebarLogW - sidebarW / scale;
                drawSidebarNative(fw[0] - sidebarW, vertOff, sidebarW, gameH, scale, logicalStartX);
            }
        } else {
            // Windowed 1:1 mode — compute actual DPI scale from framebuffer vs logical size
            double scale   = (fw[0] > 0) ? (double) fw[0] / (screenW + sidebarLogW) : 1.0;
            int gameW      = Math.max(1, (int) Math.round(screenW    * scale));
            int gameH      = Math.max(1, (int) Math.round(screenH    * scale));
            int sidebarW   = Math.max(1, (int) Math.round(sidebarLogW * scale));
            // Top-align: if the window is taller than content, spare space goes below
            int vertOff    = fh[0] - gameH;

            glUniform1f(uiUMinLoc, 0f);
            glUniform1f(uiUMaxLoc, (float) screenW / maxUiW);
            glViewport(0, vertOff, gameW, gameH);
            glDrawArrays(GL_TRIANGLES, 0, 6);

            drawSidebarNative(gameW, vertOff, sidebarW, gameH, scale, screenW);
        }

        glUseProgram(prog);
        updateOutputViewport();
    }

    private void drawSidebarNative(int physX, int physY, int physW, int physH, double scale,
                                   double logicalStartX) {
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
            // Map the visible logical sidebar slice to physical pixels. In maximized
            // mode the slice is clipped at the game edge instead of shrinking the game.
            sg.scale(scale, scale);
            sg.translate(-logicalStartX, 0);
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

    private void drawWorldMapFullscreenNative(int physW, int physH) {
        if (physW != sidebarNativeW || physH != sidebarNativeH) {
            if (sidebarNativeBuf != null) sidebarNativeBuf.flush();
            if (sidebarNativeDirect != null) MemoryUtil.memFree(sidebarNativeDirect);
            sidebarNativeBuf = new BufferedImage(physW, physH, BufferedImage.TYPE_INT_ARGB);
            sidebarNativeDirect = MemoryUtil.memAllocInt(physW * physH);
            sidebarNativeW = physW;
            sidebarNativeH = physH;
            glBindTexture(GL_TEXTURE_2D, sidebarNativeTex);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, physW, physH, 0,
                    GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        }

        sg = sidebarNativeBuf.createGraphics();
        try {
            sg.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            sg.setBackground(new java.awt.Color(0, 0, 0, 0));
            sg.clearRect(0, 0, physW, physH);
            sg.scale((double) physW / screenW, (double) physH / screenH);
           fillUiRect(0, 0, screenW, screenH, 0xFF262626);
           drawUiTextVerticallyCentered("WORLD MAP", 12, 11, 20, 2, 0xFFDCDCDC);
           drawWorldMapHeaderControls(0, screenW);
           drawSidebarHeaderCloseButton(screenW);
            fillUiRect(0, 42, screenW, 1, 0xFF363636);
            drawWorldMapView(0, 43, screenW, screenH - 43);
        } finally {
            sg.dispose();
            sg = null;
        }

        int[] pixels = ((java.awt.image.DataBufferInt)
                sidebarNativeBuf.getRaster().getDataBuffer()).getData();
        sidebarNativeDirect.clear();
        sidebarNativeDirect.put(pixels);
        sidebarNativeDirect.flip();
        glBindTexture(GL_TEXTURE_2D, sidebarNativeTex);
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, physW, physH,
                GL_BGRA, GL_UNSIGNED_BYTE, sidebarNativeDirect);
        glUniform1f(uiUMinLoc, 0f);
        glUniform1f(uiUMaxLoc, 1f);
        glViewport(0, 0, physW, physH);
        glDrawArrays(GL_TRIANGLES, 0, 6);
        glBindTexture(GL_TEXTURE_2D, uiTex);
    }

    private void loadTabIcons() {
        String[] files = {"highscores", "floating_xp", "xp_tracker", "world_map", "guides_tools", "settings"};
        for (int i = 0; i < files.length; i++) {
            try (InputStream is = GLRenderer.class.getResourceAsStream("/sideicons/" + files[i] + ".png")) {
                if (is == null) continue;
                tabIcons[i] = ImageIO.read(is);
            } catch (Exception e) {
                System.err.println("[sideicons] failed to load " + files[i] + ": " + e.getMessage());
            }
        }
        for (int i = 0; i < worldMapKeyPages.length; i++) {
            try (InputStream is = GLRenderer.class.getResourceAsStream("/maps/254/key/page_" + i + ".png")) {
                if (is != null) worldMapKeyPages[i] = ImageIO.read(is);
            } catch (Exception e) {
                System.err.println("[worldmap] failed to load key page " + i + ": " + e.getMessage());
            }
        }
    }

    private void loadWorldmapTiles() {
        worldmapStatus = "Loading map index...";
        try (InputStream in = getClass().getResourceAsStream("/maps/254/regions.csv")) {
            if (in == null) throw new IllegalStateException("maps/254/regions.csv missing");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                reader.readLine(); // header
                while ((line = reader.readLine()) != null) {
                    String[] columns = line.split(",");
                    if (columns.length >= 3) worldMapRegions.add(columns[1] + "_" + columns[2]);
                }
            }
            for (int regionX = WM_DETAIL_REGION_MIN_X; regionX <= WM_DETAIL_REGION_MAX_X; regionX++) {
                for (int regionZ = WM_DETAIL_REGION_MIN_Z; regionZ <= WM_DETAIL_REGION_MAX_Z; regionZ++) {
                    worldMapRegions.add(regionX + "_" + regionZ);
                }
            }
            worldMapLoaded = true;
            mapZoom = -1;
            worldmapStatus = null;
            System.out.println("[worldmap] indexed " + worldMapRegions.size() + " tiled regions");
        } catch (Exception e) {
            worldmapStatus = "Failed: " + e.getMessage();
            System.err.println("[worldmap] " + e);
        }
    }

    private BufferedImage loadWorldmapTile(int plane, int regionX, int regionZ) {
        String key = regionX + "_" + regionZ;
        if (!worldMapRegions.contains(key)) return null;
        Map<String, BufferedImage> cache = worldMapTiles[plane];
        if (cache.containsKey(key)) return cache.get(key);

        BufferedImage tile = null;
        String path = "/maps/254/plane_" + plane + "/" + key + ".png";
        try (InputStream in = getClass().getResourceAsStream(path)) {
            if (in != null) {
                BufferedImage source = ImageIO.read(in);
                tile = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < source.getHeight(); y++) {
                    for (int x = 0; x < source.getWidth(); x++) {
                        int rgb = source.getRGB(x, y) & 0xFFFFFF;
                        int red = rgb >> 16 & 0xFF;
                        int green = rgb >> 8 & 0xFF;
                        int blue = rgb & 0xFF;
                        boolean transparentKey = red > 180 && green < 100 && blue > 180;
                        tile.setRGB(x, y, transparentKey ? 0 : 0xFF000000 | rgb);
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[worldmap] Failed to load " + path + ": " + e.getMessage());
        }
        cache.put(key, tile);
        return tile;
    }

    private void drawTabIcon(int id, int cx, int cy, boolean active) {
        BufferedImage img = id >= 0 && id < tabIcons.length ? tabIcons[id] : null;
        if (img != null) {
            int x = cx - TAB_ICON_SIZE / 2;
            int y = cy - TAB_ICON_SIZE / 2;
            boolean glow = id == 1 && xpScreenEnabled;
            if (glow) {
                drawIconAlphaGlow(img, x, y, TAB_ICON_SIZE, TAB_ICON_SIZE);
            }
            java.awt.Composite prev = sg.getComposite();
            sg.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, active || glow ? 1.0f : 0.55f));
            sg.drawImage(img, x, y, TAB_ICON_SIZE, TAB_ICON_SIZE, null);
            sg.setComposite(prev);
        } else {
            drawIconScaled(id, cx - 4, cy - 4, 1, active ? 0xFFE89E14 : 0xFFDCDCDC);
        }
    }

    private void drawIconAlphaGlow(BufferedImage img, int x, int y, int w, int h) {
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(img, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }

        BufferedImage yellowMask = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        BufferedImage whiteMask = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int alpha = (scaled.getRGB(px, py) >>> 24) & 0xFF;
                if (alpha == 0) continue;
                yellowMask.setRGB(px, py, ((alpha * 190 / 255) << 24) | 0x73738B);
                whiteMask.setRGB(px, py, ((alpha * 150 / 255) << 24) | 0xFFFFFF);
            }
        }

        java.awt.Composite prev = sg.getComposite();
        try {
            sg.setComposite(java.awt.AlphaComposite.SrcOver);
            for (int r = 4; r >= 1; r--) {
                float opacity = 0.12f + (4 - r) * 0.05f;
                sg.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, opacity));
                sg.drawImage(yellowMask, x - r, y, w, h, null);
                sg.drawImage(yellowMask, x + r, y, w, h, null);
                sg.drawImage(yellowMask, x, y - r, w, h, null);
                sg.drawImage(yellowMask, x, y + r, w, h, null);
                sg.drawImage(yellowMask, x - r, y - r, w, h, null);
                sg.drawImage(yellowMask, x + r, y - r, w, h, null);
                sg.drawImage(yellowMask, x - r, y + r, w, h, null);
                sg.drawImage(yellowMask, x + r, y + r, w, h, null);
            }
            sg.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.55f));
            sg.drawImage(whiteMask, x - 1, y, w, h, null);
            sg.drawImage(whiteMask, x + 1, y, w, h, null);
            sg.drawImage(whiteMask, x, y - 1, w, h, null);
            sg.drawImage(whiteMask, x, y + 1, w, h, null);
        } finally {
            sg.setComposite(prev);
        }
    }

    private void drawSidebar() {
        int railX  = sidebarRailX();
        int panelX = sidebarPanelX();
        int panelW = sidebarPanelW();
        if (sidebarOpen) {
            fillUiRect(panelX, 0, panelW, screenH, 0xFF262626);
            fillUiRect(panelX + panelW - 1, 0, 1, screenH, 0xFF363636);
            if (sidebarTab == 3) {
                drawUiTextVerticallyCentered(sidebarTitle(), panelX + 4, 11, 20, 0, 0xFFDCDCDC);
            } else {
                drawUiText(sidebarTitle(), panelX + 12, 17, 2, 0xFFDCDCDC);
            }
            if (sidebarTab == 3) drawWorldMapHeaderControls(panelX, panelW);
            drawSidebarHeaderCloseButton(panelX + panelW);
            fillUiRect(panelX, 42, panelW, 1, 0xFF363636);
            drawSidebarPanel(panelX);
        }

        fillUiRect(railX, 0, SIDEBAR_RAIL_W, screenH, 0xFF1B1B1B);
        fillUiRect(railX, 0, 1, screenH, 0xFF363636);
        for (int index = 0; index < SIDEBAR_TABS - 1; index++) {
            int y      = index * SIDEBAR_ROW_H;
            boolean active = index != 1 && sidebarOpen && sidebarTab == index;
            if (active) {
                fillUiRect(railX + 1, y, SIDEBAR_RAIL_W - 1, SIDEBAR_ROW_H, 0xFF3F3523);
                fillUiRect(railX + 1, y, 3, SIDEBAR_ROW_H, 0xFFE89E14);
            }
            int cx = railX + SIDEBAR_RAIL_W / 2;
            int cy = y + SIDEBAR_ROW_H / 2;
            drawTabIcon(index, cx, cy, active);
        }
        // Settings icon (tab 5) pinned to the bottom of the rail
        int settingsY      = screenH - SIDEBAR_ROW_H;
        boolean settingsActive = sidebarOpen && sidebarTab == 5;
        if (settingsActive) {
            fillUiRect(railX + 1, settingsY, SIDEBAR_RAIL_W - 1, SIDEBAR_ROW_H, 0xFF3F3523);
            fillUiRect(railX + 1, settingsY, 3, SIDEBAR_ROW_H, 0xFFE89E14);
        }
        drawTabIcon(5, railX + SIDEBAR_RAIL_W / 2, settingsY + SIDEBAR_ROW_H / 2, settingsActive);
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
            case 4 -> "Guides/Tools";
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
        int panelW = sidebarPanelW();
        int columns = HSCORE_SKILL_COLUMNS;
        int buttonW = (panelW - 20 - (columns - 1) * 4) / columns;
        int buttonRows = (HSCORE_SKILL_LABEL.length + columns - 1) / columns;
        for (int i = 0; i < HSCORE_SKILL_LABEL.length; i++) {
            int col = i % columns;
            int row = i / columns;
            int bx  = x + 10 + col * (buttonW + 4);
            int by  = 52 + row * 13;
            boolean sel = (i == hiscoresSkill);
            fillUiRect(bx, by, buttonW, 11, sel ? 0xFF3F3523 : 0xFF2A2A2A);
            if (sel) fillUiRect(bx, by, buttonW, 1, 0xFFE89E14);
            drawUiTextFittedFull(HSCORE_SKILL_LABEL[i], bx + 4, by + 2,
                    buttonW - 8, 0, sel ? 0xFFE89E14 : 0xFF999999);
        }

        int afterButtons = 52 + buttonRows * 13 + 3;
        fillUiRect(x + 10, afterButtons, panelW - 20, 1, 0xFF363636);

        // Column headers
        int headerY = afterButtons + 5;
        drawUiText("RK",   x + 10,  headerY, 1, 0xFF666666);
        drawUiText("NAME", x + 28,  headerY, 1, 0xFF666666);
        drawUiText("LVL",  x + 140, headerY, 1, 0xFF666666);
        fillUiRect(x + 10, headerY + 15, panelW - 20, 1, 0xFF363636);

        // Leaderboard rows
        String[] names  = hiscoresNames;
        int[]    levels = hiscoresLevels;
        int rowY = headerY + 18;
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
        int panelW = sidebarPanelW();
        drawUiText("SHOW XP GAINS", x + 16, 56, 1, 0xFFE89E14);
        fillUiRect(x + 16, 70, panelW - 32, 1, 0xFF363636);
        drawUiText("DISPLAYS FLOATING XP", x + 16, 86, 1, 0xFF999999);
        drawUiText("TEXT ON SCREEN WHEN", x + 16, 100, 1, 0xFF999999);
        drawUiText("EXPERIENCE IS GAINED.", x + 16, 114, 1, 0xFF999999);
        fillUiRect(x + 16, 130, panelW - 32, 1, 0xFF363636);
        drawToggleRow(x, 140, "XP ON SCREEN", xpScreenEnabled);
    }

    private void drawXpTrackerPanel(int x) {
        int panelW = sidebarPanelW();
        // Reset button — 4-sided border, text centred inside
        int rbx = x + panelW - 58, rby = 52, rbw = 46, rbh = 22;
        fillUiRect(rbx,           rby,           rbw, rbh, 0xFF3A3A3A); // background
        fillUiRect(rbx,           rby,           rbw, 1,   0xFF666666); // top
        fillUiRect(rbx,           rby + rbh - 1, rbw, 1,   0xFF666666); // bottom
        fillUiRect(rbx,           rby,           1,   rbh, 0xFF666666); // left
        fillUiRect(rbx + rbw - 1, rby,           1,   rbh, 0xFF666666); // right
        drawUiText("RESET", rbx + 9, rby + 5, 1, 0xFFDCDCDC);
        // Column headers sit below the reset button so narrow panels do not overlap.
        drawUiText("SKILL",     x + 16, rby + rbh + 10, 1, 0xFF999999);
        drawUiText("XP GAINED", x + 70, rby + rbh + 10, 1, 0xFF999999);
        fillUiRect(x + 16, rby + rbh + 25, panelW - 32, 1, 0xFF363636);
        // Rows
        boolean hasXp = false;
        for (long v : xpSessionGains) if (v > 0) { hasXp = true; break; }
        if (!hasXp) {
            drawUiText("NO XP GAINED", x + 16, 110,  1, 0xFF999999);
            drawUiText("THIS SESSION.",  x + 16, 126, 1, 0xFF999999);
        } else {
            int rowY = 104;
            for (int i = 0; i < SKILL_SHORT.length && rowY < screenH - 14; i++) {
                if (xpSessionGains[i] > 0) {
                    drawUiText(SKILL_SHORT[i], x + 16, rowY, 1, 0xFFDCDCDC);
                    drawUiText("+" + xpSessionGains[i], x + 70, rowY, 1, 0xFF80FF80);
                    rowY += 14;
                }
            }
        }
    }

    private int worldMapViewX() {
        return worldMapFullscreen ? 0 : sidebarPanelX();
    }

    private int worldMapViewY() {
        return 43;
    }

    private int worldMapViewW() {
        return worldMapFullscreen ? screenW : sidebarPanelW();
    }

    private int worldMapViewH() {
        return screenH - worldMapViewY();
    }

    private void zoomMapAround(float factor, int pivotX, int pivotY) {
        float oldZoom = mapZoom;
        mapZoom = Math.max(0.03f, Math.min(12f, mapZoom * factor));
        float ratio = mapZoom / oldZoom;
        float cx = pivotX - worldMapViewX();
        float cy = pivotY - worldMapViewY();
        mapPanX = cx - (cx - mapPanX) * ratio;
        mapPanY = cy - (cy - mapPanY) * ratio;
    }

    private void drawWorldMapHeaderControls(int panelX, int panelW) {
        int right = panelX + panelW;
        int keyX = right - (worldMapFullscreen ? 75 : 129);
        drawWorldMapHeaderButton(keyX, 11, 29, "KEY", worldMapKeyVisible);
        if (!worldMapFullscreen) {
            drawWorldMapHeaderButton(right - 98, 11, 52, worldMapFollowing ? "UNFOLLOW" : "FOLLOW",
                    worldMapFollowing);
        }
        drawWorldMapHeaderSquareButton(right - 44, 11);
    }

    private void drawWorldMapHeaderButton(int x, int y, int width, String label, boolean active) {
        fillUiRect(x, y, width, 20, active ? 0xFF4B3D26 : 0xFF333333);
        fillUiRect(x, y, width, 1, active ? 0xFFE89E14 : 0xFF555555);
        fillUiRect(x, y + 19, width, 1, 0xFF1B1B1B);
        drawUiTextCentered(label, x, y, width, 20, 0, active ? 0xFFE89E14 : 0xFFDCDCDC);
    }

    private void drawWorldMapHeaderSquareButton(int x, int y) {
        fillUiRect(x, y, 20, 20, 0xFF333333);
        fillUiRect(x, y, 20, 1, 0xFF555555);
        fillUiRect(x, y + 19, 20, 1, 0xFF1B1B1B);
        fillUiRect(x + 5, y + 5, 10, 10, 0xFF999999);
        fillUiRect(x + 6, y + 6, 8, 8, 0xFF262626);
    }

    private void drawSidebarHeaderCloseButton(int right) {
        int x = right - 22;
        int y = 11;
        fillUiRect(x, y, 20, 20, 0xFF333333);
        fillUiRect(x, y, 20, 1, 0xFF555555);
        fillUiRect(x, y + 19, 20, 1, 0xFF1B1B1B);
        drawUiTextCentered("X", x, y, 20, 20, 0, 0xFFDCDCDC);
    }

    private void centreMapOnTile(int tileX, int tileZ, int viewW, int viewH) {
        float px = tileX - WM_ORIGIN_X;
        float py = (WM_ORIGIN_Z + WM_HEIGHT - 1) - tileZ;
        mapPanX = viewW / 2f - px * mapZoom;
        mapPanY = viewH / 2f - py * mapZoom;
    }

    private void drawWorldMapKey(int vx, int vy, int vw, int vh) {
        fillUiRect(vx, vy, vw, vh, 0xFF777777);
        BufferedImage page = worldMapKeyPages[worldMapKeyPage];
        if (page != null) {
            sg.drawImage(page, vx + (vw - page.getWidth()) / 2, vy + 4, null);
        }
        int footerY = vy + vh - 18;
        fillUiRect(vx, footerY, vw, 18, 0xFF262626);
        drawUiText("<", vx + 8, footerY + 4, 1, 0xFFDCDCDC);
        drawUiText((worldMapKeyPage + 1) + " / " + worldMapKeyPages.length, vx + vw / 2 - 12,
                footerY + 4, 0, 0xFFDCDCDC);
        drawUiText(">", vx + vw - 16, footerY + 4, 1, 0xFFDCDCDC);
    }

    private int worldMapKeyOverlayWidth() {
        return 144;
    }

    private void drawWorldMapKeyOverlay(int vx, int vy, int vh) {
        int width = worldMapKeyOverlayWidth();
        BufferedImage page = worldMapKeyPages[worldMapKeyPage];
        int height = Math.min(vh, (page != null ? page.getHeight() : 434) + 26);
        fillUiRect(vx, vy, width, height, 0xFF777777);
        if (page != null) {
            sg.drawImage(page, vx + (width - page.getWidth()) / 2, vy + 4, null);
        }
        int footerY = vy + height - 18;
        fillUiRect(vx, footerY, width, 18, 0xFF262626);
        drawUiText("<", vx + 8, footerY + 4, 1, 0xFFDCDCDC);
        drawUiText((worldMapKeyPage + 1) + " / " + worldMapKeyPages.length, vx + width / 2 - 12,
                footerY + 4, 0, 0xFFDCDCDC);
        drawUiText(">", vx + width - 16, footerY + 4, 1, 0xFFDCDCDC);
    }

    private void drawWorldMapPanel(int panelX) {
        drawWorldMapView(panelX, 43, sidebarPanelW(), screenH - 43);
    }

    private void drawWorldMapView(int vx, int vy, int vw, int vh) {

        // Trigger load on first open
        if (!worldMapLoaded && worldmapStatus == null) {
            loadWorldmapTiles();
        }

        // Show loading / error status
        if (worldmapStatus != null) {
            drawUiText(worldmapStatus, vx + 8, vy + 20, 0, 0xFF888888);
            return;
        }
        if (!worldMapLoaded) return;

        // First render after load: show crisp 1:1 terrain, centred on the player or mainland.
        if (mapZoom < 0) {
            mapZoom = 1f;
            int centreTileX = playerTileX >= 0 ? playerTileX : WM_DEFAULT_TILE_X;
            int centreTileZ = playerTileZ >= 0 ? playerTileZ : WM_DEFAULT_TILE_Z;
            centreMapOnTile(centreTileX, centreTileZ, vw, vh);
        }
        if (worldMapFollowing && playerTileX >= 0 && playerTileZ >= 0) {
            centreMapOnTile(playerTileX, playerTileZ, vw, vh);
        }
        if (worldMapKeyVisible && !worldMapFullscreen) {
            drawWorldMapKey(vx, vy, vw, vh);
            return;
        }

        // Clip to panel
        java.awt.Shape prevClip = sg.getClip();
        sg.setClip(vx, vy, vw, vh);

        // Efficient src/dst rect rendering — only processes visible pixels
        float invZ = 1f / mapZoom;
        int sx1 = Math.max(0, (int)((-mapPanX) * invZ));
        int sy1 = Math.max(0, (int)((-mapPanY) * invZ));
        int sx2 = Math.min(WM_WIDTH, (int)((vw - mapPanX) * invZ) + 1);
        int sy2 = Math.min(WM_HEIGHT, (int)((vh - mapPanY) * invZ) + 1);
        if (sx1 < sx2 && sy1 < sy2) {
            sg.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                                java.awt.RenderingHints.VALUE_RENDER_SPEED);
            sg.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                                java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            int minRegionX = WM_REGION_MIN_X + sx1 / WM_REGION_SIZE;
            int maxRegionX = WM_REGION_MIN_X + (sx2 - 1) / WM_REGION_SIZE;
            int maxRegionZ = WM_REGION_MAX_Z - sy1 / WM_REGION_SIZE;
            int minRegionZ = WM_REGION_MAX_Z - (sy2 - 1) / WM_REGION_SIZE;
            int plane = Math.max(0, Math.min(3, playerPlane));
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                    BufferedImage tile = loadWorldmapTile(plane, regionX, regionZ);
                    if (tile == null) continue;
                    int imgX = (regionX - WM_REGION_MIN_X) * WM_REGION_SIZE;
                    int imgY = (WM_REGION_MAX_Z - regionZ) * WM_REGION_SIZE;
                    int dx1 = vx + Math.round(mapPanX + imgX * mapZoom);
                    int dy1 = vy + Math.round(mapPanY + imgY * mapZoom);
                    int dx2 = vx + Math.round(mapPanX + (imgX + WM_REGION_SIZE) * mapZoom);
                    int dy2 = vy + Math.round(mapPanY + (imgY + WM_REGION_SIZE) * mapZoom);
                    sg.drawImage(tile, dx1, dy1, dx2 - dx1, dy2 - dy1, null);
                }
            }
            sg.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                                java.awt.RenderingHints.VALUE_RENDER_QUALITY);
        }

        // Player dot — tile coords scaled to image pixel space
        if (playerTileX >= 0 && playerTileZ >= 0) {
            float imgX = playerTileX - WM_ORIGIN_X;
            float imgY = (WM_ORIGIN_Z + WM_HEIGHT - 1) - playerTileZ;
            float dotX = vx + mapPanX + imgX * mapZoom;
            float dotY = vy + mapPanY + imgY * mapZoom;
            if (dotX >= vx && dotX < vx + vw && dotY >= vy && dotY < vy + vh) {
                sg.setColor(new java.awt.Color(0xFF, 0xC8, 0x00));
                sg.fillOval((int) dotX - 4, (int) dotY - 4, 8, 8);
                sg.setColor(java.awt.Color.BLACK);
                sg.setStroke(new java.awt.BasicStroke(1.2f));
                sg.drawOval((int) dotX - 4, (int) dotY - 4, 8, 8);
                sg.setStroke(new java.awt.BasicStroke(1f));
            }
        }

        sg.setClip(prevClip);

        // Zoom buttons
        int btnX = vx + vw - 20, btnY = vy + vh - 38;
        fillUiRect(btnX, btnY,      18, 17, 0xCC1B1B1B);
        fillUiRect(btnX, btnY + 19, 18, 17, 0xCC1B1B1B);
        drawUiText("+", btnX + 4, btnY + 2,      1, 0xFFDCDCDC);
        drawUiText("-", btnX + 5, btnY + 2 + 19, 1, 0xFFDCDCDC);
        drawUiText("PLANE " + Math.max(0, Math.min(3, playerPlane)), vx + 8, vy + vh - 18, 0, 0xFF999999);
        if (worldMapKeyVisible && worldMapFullscreen) {
            drawWorldMapKeyOverlay(vx + 8, vy + 4, vh - 8);
        }
    }

    private void drawLostHqPanel(int x) {
        if (lostHqPage != null || lostHqPageUri != null) {
            if (isSearchPageUri(lostHqPageUri)) {
                drawLostHqSearchPage(x);
                return;
            }
            if (isSkillCalcUri(lostHqPageUri)) {
                drawLostHqSkillCalcPage(x);
                return;
            }
            drawLostHqPage(x);
            return;
        }
        loadLostHqIcons();
        int panelW = sidebarPanelW();
        int itemStartY = 52;
        int iconSize   = 24;
        int itemStep   = Math.max(42, (screenH - itemStartY - 8) / LOSTHQ_ITEMS.length);
        for (int i = 0; i < LOSTHQ_ITEMS.length; i++) {
            int itemY   = itemStartY + i * itemStep;
            int centerX = x + panelW / 2;
            boolean hovered = cursorX >= x + 4 && cursorX < x + panelW - 4
                    && cursorY >= itemY && cursorY < itemY + itemStep;
            BufferedImage icon = lostHqIcons[i];
            if (icon != null) {
                sg.drawImage(icon, centerX - iconSize / 2, itemY + 2, iconSize, iconSize, null);
            } else {
                fillUiRect(centerX - 6, itemY + 6, 12, 12, hovered ? 0xFFE89E14 : 0xFF555555);
            }
            drawUiTextCentered(LOSTHQ_ITEMS[i], x + 4, itemY + iconSize + 6,
                    panelW - 8, 12, 0, hovered ? 0xFFE89E14 : 0xFFDCDCDC);
        }
    }

    private void loadLostHqIcons() {
        if (lostHqIconsLoading) return;
        for (BufferedImage icon : lostHqIcons) if (icon != null) return;
        lostHqIconsLoading = true;
        lostHqLauncher.execute(() -> {
            for (int i = 0; i < LOSTHQ_ICON_URLS.length; i++) {
                String iconName = LOSTHQ_ICON_URLS[i].substring(LOSTHQ_ICON_URLS[i].lastIndexOf('/') + 1);
                try (InputStream res = GLRenderer.class.getResourceAsStream("/losthq/img/" + iconName)) {
                    if (res != null) {
                        lostHqIcons[i] = ImageIO.read(res);
                        continue;
                    }
                } catch (Exception ignored) {}
                try {
                    lostHqIcons[i] = ImageIO.read(new URL(LOSTHQ_ICON_URLS[i]));
                } catch (Exception ignored) {}
            }
            lostHqIconsLoading = false;
        });
    }

    private void openLostHqPage(int item) {
        if (item < 0 || item >= LOSTHQ_URLS.length) return;
        lostHqHistory.clear();  // fresh session from the root menu
        openLostHqPage(URI.create(LOSTHQ_URLS[item]));
    }

    private static boolean isSearchPageUri(URI uri) {
        if (uri == null) return false;
        String q = uri.getRawQuery();
        return q != null && (q.contains("p=npcdb") || q.contains("p=itemdb"));
    }

    private static boolean isSkillCalcUri(URI uri) {
        if (uri == null) return false;
        String q = uri.getRawQuery();
        return q != null && q.contains("p=calculators") && q.contains("skill=");
    }

    private static String skillCalcSkillName(URI uri) {
        String q = uri == null ? null : uri.getQuery();
        if (q == null) return null;
        for (String part : q.split("&")) {
            if (part.startsWith("skill=")) return part.substring(6);
        }
        return null;
    }

    // Forward navigation: push current page to history, then load the new page.
    private void openLostHqPage(URI uri) {
        lostHqHistory.offerFirst(lostHqPageUri);
        if (lostHqHistory.size() > 20) lostHqHistory.pollLast();
        loadLostHqPage(uri);
    }

    // Internal loader used by both forward navigation and BACK — never touches history.
    private void loadLostHqPage(URI uri) {
        lostHqPage = null;
        lostHqPageUri = uri;
        lostHqHtml = null;
        resetLostHqProgress();
        lostHqScrollY = 0;
        lostHqScrollX = 0;
        lostHqContentW = LOSTHQ_READER_W;
        lostHqSearchQuery = "";
        lostHqSearchFocused = false;
        lostHqSearchResults = List.of();
        lostHqCalcSkill = null;
        lostHqCalcFocus = 0;
        lostHqStatus = null;
        if (uri == null) {
            lostHqStatus = "SELECT A LOSTHQ TOOL";
            return;
        }
        // NPC/item DB use the native search panel — no JEditorPane needed.
        if (isSearchPageUri(uri)) {
            String q = uri.getRawQuery();
            lostHqSearchIsNpc = q != null && q.contains("p=npcdb");
            // Load data in background so the UI stays responsive.
            lostHqLauncher.execute(() -> {
                if (lostHqSearchIsNpc && lostHqNpcData == null)
                    lostHqNpcData = loadSearchData("/losthq/npc_data.tsv");
                else if (!lostHqSearchIsNpc && lostHqItemData == null)
                    lostHqItemData = loadSearchData("/losthq/item_data.tsv");
            });
            return;
        }
        // Skill calculators use a native panel — no HTML/JS needed.
        if (isSkillCalcUri(uri)) {
            lostHqCalcSkill        = skillCalcSkillName(uri);
            lostHqCalcFocus        = 0;
            lostHqCalcCurrentXpStr = "0";
            lostHqCalcGoalLvlStr   = "2";
            return;
        }
        lostHqStatus = "LOADING PAGE...";
        lostHqLauncher.execute(() -> {
            try {
                String html;
                // Prefer bundled HTML — it has local fixes (column widths, link text,
                // image sizes) applied to fit the narrow sidebar panel.  Fall back to
                // the live site only if the page isn't bundled.
                try {
                    html = loadBundledHtml(uri);
                } catch (Exception bundledEx) {
                    html = fetchLostHqHtml(uri);
                }
                String finalHtml = html;
                // Use classpath base so relative image src attributes (img/...) load
                // from the bundled JAR resources.  Link navigation still uses lostHqPageUri
                // (the original https:// URI) via pageUri.resolve() in clickLostHqPage.
                URL classpathBase = GLRenderer.class.getResource("/losthq/");
                URL baseUrl = (classpathBase != null) ? classpathBase : uri.toURL();
                SwingUtilities.invokeLater(() -> {
                    // Disable caret — it's a read-only pane and DefaultCaret.repaintNewCaret
                    // triggers a layout pass on the EDT that hits JDK BoxView/FlowView bugs.
                    lostHqHtml = finalHtml;
                    lostHqPage = createLostHqPage(decorateLostHqProgressSteps(finalHtml), baseUrl);
                    lostHqStatus = null;
                });
            } catch (Exception e) {
                lostHqPage = null;
                lostHqPageUri = null;
                lostHqStatus = "COULD NOT LOAD PAGE";
            }
        });
    }

    private String fetchLostHqHtml(URI uri) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setConnectTimeout(3000);
        conn.setReadTimeout(5000);
        conn.setRequestProperty("User-Agent", "Progressive-Java-Client");
        try (InputStream in = conn.getInputStream()) {
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            html = html.replaceFirst("(?is)<body[^>]*>", "<body>");
            html = html.replaceAll("(?is)<link[^>]*rel=\"stylesheet\"[^>]*>", "");
            html = html.replaceAll("(?is)<script[^>]*>.*?</script>", "");
            html = html.replaceFirst("(?is)<a href=\"/\"><img[^>]*losthq\\.png[^>]*></a>", "");
            html = html.replaceAll("(?is)<div class=\"main-menu e\">.*?</div>", "");
            html = html.replaceAll("(?is)<div class=\"top-border\"></div>", "");
            html = html.replaceAll("(?is)<div class=\"left-border\"></div>", "");
            html = html.replaceAll("(?is)<div class=\"right-border\"></div>", "");
            html = html.replaceAll("(?is)<div class=\"bottom-border\"></div>", "");
            html = html.replaceAll("(?is)<div style=\"text-align:center;[^>]*>.*?site-options-container.*?</div>\\s*</div>", "");
            // Strip elements JEditorPane's CSS engine can't hide with display:none
            html = html.replaceAll("(?is)<[^>]+id=\"scrollToTop\"[^>]*>.*?</[a-z]+>", "");
            html = html.replaceAll("(?is)<div[^>]+class=\"[^\"]*img-modal[^\"]*\"[^>]*>.*?</div>", "");
            html = html.replaceAll("(?is)<img[^>]+class=\"[^\"]*narrowscroll-(?:top|bottom)[^\"]*\"[^>]*>", "");
            // Rewrite absolute /img/... paths to relative so they resolve from the classpath base
            html = html.replaceAll("(?i)(src=[\"'])/img/", "$1img/");
            return prepareLostHqHtml(html);
        } finally {
            conn.disconnect();
        }
    }

    private static String prepareLostHqHtml(String html) {
        String prepared = normalizeLostHqInlineWidths(html);
        prepared = normalizeLostHqLinkSpacing(prepared);
        prepared = normalizeLostHqImageWidths(prepared);
        prepared = normalizeLostHqCanvases(prepared);
        prepared = injectXpTable(prepared);
        prepared = normalizeLostHqTables(prepared);
        prepared = wrapLostHqTextNodes(prepared);
        prepared = wrapLostHqTableCells(prepared);
        prepared = breakLostHqLongLinkRuns(prepared);
        return injectCompactCss(prepared);
    }

    private static String injectCompactCss(String html) {
        // Swing's HTMLEditorKit only honours pixel (px) values for width — it ignores
        // percentage widths and !important.  Use LOSTHQ_READER_W as the pixel reference
        // so tables are constrained to the same width as the JEditorPane setSize() call,
        // allowing the table layout engine to distribute column widths and wrap text.
        int w = LOSTHQ_READER_W;
        String compactCss = "<style>\n"
            + "body { width: " + w + "px; margin: 0; padding: 2px 0; background: #111; color: #ddd;"
            + " font-family: Arial, sans-serif; font-size: 12px; line-height: 1.5; }\n"
            + "div.body, .main-content, .main-page, .quest-container, .row,"
            + " .stone-box, #narrowscroll, .narrowscroll-bg, .narrowscroll-bgimg,"
            + " .narrowscroll-content { width: " + LOSTHQ_BODY_W + "px; margin: 0; padding: 2px; display: block; }\n"
            + ".quest-column { background: #2a2a2a; border: 1px solid #444; margin: 4px 0; padding: 4px; }\n"
            + "img, canvas { display: block; margin: 3px 0; }\n"
            + "img.quest-complete { margin-left: -6px; margin-right: -6px; }\n"
            + "table { width: " + LOSTHQ_BODY_W + "px; margin: 3px 0; border-collapse: collapse; }\n"
            + "td, th { padding: 2px 4px; font-size: 11px; }\n"
            + "th { color: #ffd700; font-weight: bold; border-bottom: 1px solid #444; }\n"
            + "tr:nth-child(even) td { background: #1a1a1a; }\n"
            + "h1, h2, h3 { font-size: 13px; margin: 6px 0 3px; color: #ff981f; font-weight: bold; }\n"
            + "ul { margin: 3px 0; padding-left: 14px; }\n"
            + "a { color: #90c040; text-decoration: none; }\n"
            + ".quest-header { color: #ff981f; font-weight: bold; }\n"
            + ".quest-entry { margin: 1px 0; }\n"
            + ".main-menu, #site-options-container, #scrollToTop, .img-modal { display: none; }\n"
            + "</style>";
        return html.replaceFirst("(?is)</head>", compactCss + "</head>");
    }

    private static String normalizeLostHqImageWidths(String html) {
        // Swing's HTML renderer handles percentage image widths inconsistently.
        // Pin quest-complete banners to a fixed pixel width that fills the panel,
        // and tag them with class="quest-complete" so the CSS negative-margin rule
        // lets them escape the body's 6px horizontal padding.
        String tagged = html.replaceAll(
                "(?i)(<img\\b(?![^>]*\\bclass=)[^>]*src=[\"'][^\"']*questimages/quest_complete/[^\"']*[\"'])",
                "$1 class=\"quest-complete\"");
        tagged = tagged.replaceAll(
                "(?i)(<img\\b[^>]*src=[\"'][^\"']*questimages/quest_complete/[^\"']*[\"'][^>]*\\bwidth=[\"'])\\d+%([\"'])",
                "$1" + LOSTHQ_QUEST_COMPLETE_IMAGE_W + "$2");
        return clampLostHqImageWidths(tagged);
    }

    private static String normalizeLostHqCanvases(String html) {
        boolean skillGuidesIndex = html.contains("content=\"https://2004.losthq.rs/?p=skillguides\"");
        boolean calculatorsIndex = html.contains("content=\"https://2004.losthq.rs/?p=calculators\"");
        Matcher matcher = Pattern.compile("(?is)<canvas\\b([^>]*)>.*?</canvas>").matcher(html);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) {
            String attributes = matcher.group(1);
            String itemName = lostHqAttribute(attributes, "itemname");
            if (itemName == null) {
                String replacement = "skillTree".equals(lostHqAttribute(attributes, "skills"))
                        ? (calculatorsIndex ? lostHqSkillCalcGrid() : skillGuidesIndex ? lostHqSkillGuideLinks() : "")
                        : "";
                matcher.appendReplacement(normalized, Matcher.quoteReplacement(replacement));
                continue;
            }
            // Canvases without show-label are sprite-only display elements (e.g. smithing table
            // quantity indicators). Emit nothing so narrow columns don't overflow with item text.
            if (lostHqAttribute(attributes, "show-label") == null
                    && lostHqAttribute(attributes, "name-replace") == null) {
                matcher.appendReplacement(normalized, "");
                continue;
            }
            String label = lostHqAttribute(attributes, "name-replace");
            if (label == null) label = humanizeLostHqItemName(itemName);
            String amount = lostHqAttribute(attributes, "amount");
            if (amount != null && lostHqAttribute(attributes, "name-replace") == null) {
                label = amount + " " + label;
            }
            String append = lostHqAttribute(attributes, "name-append");
            if (append != null) label += append;
            matcher.appendReplacement(normalized, Matcher.quoteReplacement(label));
        }
        matcher.appendTail(normalized);
        return normalized.toString();
    }

    private static String lostHqSkillGuideLinks() {
        return """
                <div class="quest-column">
                <div class="quest-entry"><a href="?p=skillguides&amp;skill=cooking">Cooking</a></div>
                <div class="quest-entry"><a href="?p=skillguides&amp;skill=crafting">Crafting</a></div>
                <div class="quest-entry"><a href="?p=skillguides&amp;skill=firemaking">Firemaking</a></div>
                <div class="quest-entry"><a href="?p=skillguides&amp;skill=fletching">Fletching</a></div>
                <div class="quest-entry"><a href="?p=skillguides&amp;skill=magic">Magic</a></div>
                <div class="quest-entry"><a href="?p=skillguides&amp;skill=mining">Mining</a></div>
                <div class="quest-entry"><a href="?p=skillguides&amp;skill=runecraft">Runecraft</a></div>
                <div class="quest-entry"><a href="?p=skillguides&amp;skill=smithing">Smithing</a></div>
                <div class="quest-entry"><a href="?p=skillguides&amp;skill=woodcutting">Woodcutting</a></div>
                </div>
                """;
    }

    // RS2004 skill tree layout (3 columns), matching the stat panel order
    private static final String[][] SKILL_CALC_GRID = {
        {"attack",   "hitpoints", "mining"},
        {"strength", "agility",   "smithing"},
        {"defence",  "herblore",  "fishing"},
        {"ranged",   "thieving",  "cooking"},
        {"prayer",   "crafting",  "firemaking"},
        {"magic",    "fletching", "woodcutting"},
        {"runecrafting", null,    null}
    };

    private static String lostHqSkillCalcGrid() {
        StringBuilder sb = new StringBuilder();
        sb.append("<table style='border-collapse:collapse;margin:4px 0;'>");
        for (String[] row : SKILL_CALC_GRID) {
            sb.append("<tr>");
            for (String skill : row) {
                sb.append("<td style='padding:3px;text-align:center;'>");
                if (skill != null) {
                    java.net.URL iconUrl = GLRenderer.class.getResource("/skillicons/" + skill + ".png");
                    sb.append("<a href='?p=calculators&amp;skill=").append(skill).append("'>");
                    if (iconUrl != null) {
                        sb.append("<img src='").append(iconUrl)
                          .append("' width='32' height='32' alt='").append(skill).append("'>");
                    } else {
                        String label = Character.toUpperCase(skill.charAt(0)) + skill.substring(1);
                        sb.append(label);
                    }
                    sb.append("</a>");
                }
                sb.append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private static String injectXpTable(String html) {
        if (!html.contains("xp-table")) return html;
        return html.replaceFirst("(?i)<div[^>]+class=[\"']xp-table[\"'][^>]*>\\s*</div>",
                Matcher.quoteReplacement(buildXpTableHtml()));
    }

    private static String buildXpTableHtml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<br><table><tr><th>Level</th><th>XP Required</th><th>Level</th><th>XP Required</th></tr>");
        for (int level = 2; level <= 50; level++) {
            int paired = level + 49;
            sb.append("<tr><td>").append(level).append("</td><td>")
              .append(String.format("%,d", XP_TABLE[level])).append("</td><td>")
              .append(paired).append("</td><td>")
              .append(String.format("%,d", XP_TABLE[paired])).append("</td></tr>");
        }
        sb.append("</table>");
        return sb.toString();
    }

    private static String lostHqAttribute(String attributes, String name) {
        Matcher matcher = Pattern.compile("(?i)\\b" + Pattern.quote(name)
                + "\\s*=\\s*([\"'])(.*?)\\1").matcher(attributes);
        return matcher.find() ? matcher.group(2) : null;
    }

    private static String humanizeLostHqItemName(String itemName) {
        String label = itemName.replace('_', ' ').trim();
        if (label.isEmpty()) return "";
        return Character.toUpperCase(label.charAt(0)) + label.substring(1);
    }

    private String decorateLostHqProgressSteps(String html) {
        Set<Integer> completedSteps;
        synchronized (lostHqProgressLock) {
            completedSteps = new HashSet<>(lostHqCompletedSteps);
        }
        Matcher matcher = Pattern.compile(
                "(?is)<div\\s+data-progress(?:\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+))?\\s*>(.*?)</div>")
                .matcher(html);
        StringBuffer decorated = new StringBuffer();
        int step = 0;
        while (matcher.find()) {
            boolean checked = completedSteps.contains(step);
            String content = matcher.group(1);
            String replacement = "<div><a href=\"progress:" + step + "\">"
                    + (checked ? "[x]" : "[ ]") + "</a> "
                    + (checked ? "<strike>" + content + "</strike>" : content)
                    + "</div>";
            matcher.appendReplacement(decorated, Matcher.quoteReplacement(replacement));
            step++;
        }
        matcher.appendTail(decorated);
        return decorated.toString();
    }

    private void toggleLostHqProgressStep(String href) {
        JEditorPane page = lostHqPage;
        if (lostHqHtml == null || page == null) return;
        try {
            int step = Integer.parseInt(href.substring("progress:".length()));
            long pageId;
            synchronized (lostHqProgressLock) {
                if (!lostHqCompletedSteps.add(step)) lostHqCompletedSteps.remove(step);
                lostHqProgressRevision++;
                if (lostHqProgressRefreshScheduled) return;
                lostHqProgressRefreshScheduled = true;
                pageId = lostHqProgressPageId;
            }
            scheduleLostHqProgressRefresh(page, pageId);
        } catch (RuntimeException ignored) {
        }
    }

    private void scheduleLostHqProgressRefresh(JEditorPane page, long pageId) {
        SwingUtilities.invokeLater(() -> {
            String html = lostHqHtml;
            long renderedRevision;
            synchronized (lostHqProgressLock) {
                if (pageId != lostHqProgressPageId || page != lostHqPage || html == null) return;
                renderedRevision = lostHqProgressRevision;
            }
            JEditorPane refreshedPage = page;
            try {
                HTMLDocument currentDoc = (HTMLDocument) page.getDocument();
                JEditorPane replacement = createLostHqPage(
                        decorateLostHqProgressSteps(html), currentDoc.getBase());
                synchronized (lostHqProgressLock) {
                    if (pageId != lostHqProgressPageId || page != lostHqPage) return;
                    lostHqPage = replacement;
                    refreshedPage = replacement;
                }
            } catch (RuntimeException ignored) {
                // JDK HTML layout bugs should not leave the refresh gate locked.
            }

            boolean refreshAgain = false;
            synchronized (lostHqProgressLock) {
                if (pageId != lostHqProgressPageId || refreshedPage != lostHqPage) return;
                lostHqProgressRefreshScheduled = false;
                if (renderedRevision != lostHqProgressRevision) {
                    lostHqProgressRefreshScheduled = true;
                    refreshAgain = true;
                }
            }
            if (refreshAgain) scheduleLostHqProgressRefresh(refreshedPage, pageId);
        });
    }

    private static JEditorPane createLostHqPage(String html, URL baseUrl) {
        JEditorPane page = new JEditorPane();
        page.setEditable(false);
        // Match the body background so any overflow canvas area (between the body
        // width and LOSTHQ_READER_WIDE) renders dark instead of the default white.
        page.setBackground(new java.awt.Color(0x111111));
        page.setOpaque(true);
        // Disable caret: this read-only pane does not need caret repaint layout passes.
        DefaultCaret silentCaret = new DefaultCaret();
        silentCaret.setUpdatePolicy(DefaultCaret.NEVER_UPDATE);
        page.setCaret(silentCaret);
        HTMLEditorKit kit = new HTMLEditorKit();
        HTMLDocument doc = (HTMLDocument) kit.createDefaultDocument();
        doc.setAsynchronousLoadPriority(-1);
        doc.setBase(baseUrl);
        doc.putProperty("IgnoreCharsetDirective", Boolean.TRUE);
        page.setEditorKit(kit);
        page.setDocument(doc);
        page.setText(html);
        page.setSize(LOSTHQ_READER_W, Short.MAX_VALUE);
        return page;
    }

    private void resetLostHqProgress() {
        synchronized (lostHqProgressLock) {
            lostHqCompletedSteps.clear();
            lostHqProgressRevision++;
            lostHqProgressPageId++;
            lostHqProgressRefreshScheduled = false;
        }
    }

    private static String wrapLostHqTextNodes(String html) {
        int body = html.toLowerCase().indexOf("<body");
        int bodyEnd = body < 0 ? -1 : html.indexOf('>', body);
        if (bodyEnd < 0) return html;
        String head = html.substring(0, bodyEnd + 1);
        Matcher matcher = Pattern.compile(">([^<]+)<").matcher(html.substring(bodyEnd + 1));
        StringBuffer wrapped = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(wrapped, Matcher.quoteReplacement(">"
                    + wrapLostHqTextNode(matcher.group(1)) + "<"));
        }
        matcher.appendTail(wrapped);
        return head + wrapped;
    }

    private static String wrapLostHqTextNode(String text) {
        // 32 chars is the conservative cap for a 312-px body at 11–12 px Arial.
        // Worst-case (all caps / quoted phrases) ~9 px per glyph: 32 × 9 = 288 px,
        // which fits comfortably even when this text node is concatenated with a
        // preceding short fragment (e.g. preamble before an <i>quoted phrase</i>).
        return wrapLostHqTextNode(text, 32);
    }

    private static String wrapLostHqTextNode(String text, int wrapCol) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) return text;
        boolean leadingSpace = Character.isWhitespace(text.charAt(0));
        boolean trailingSpace = Character.isWhitespace(text.charAt(text.length() - 1));
        StringBuilder wrapped = new StringBuilder();
        if (leadingSpace) wrapped.append(normalized.length() > 28 ? "<br>" : " ");
        int column = 0;
        for (String word : normalized.split(" ")) {
            if (column > 0 && column + 1 + word.length() > wrapCol) {
                wrapped.append("<br>");
                column = 0;
            } else if (column > 0) {
                wrapped.append(' ');
                column++;
            }
            wrapped.append(word);
            column += word.length();
        }
        if (trailingSpace) wrapped.append(' ');
        return wrapped.toString();
    }

    private static String clampLostHqImageWidths(String html) {
        Matcher matcher = Pattern.compile("(?is)<img\\b([^>]*)>").matcher(html);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String attrs = matcher.group(1);
            Matcher width = Pattern.compile("(?i)\\bwidth\\s*=\\s*([\"']?)(\\d+|\\d+%)\\1").matcher(attrs);
            if (width.find()) {
                String value = width.group(2);
                int imageW = value.endsWith("%")
                        ? Math.max(1, LOSTHQ_CONTENT_IMAGE_W * Integer.parseInt(value.substring(0, value.length() - 1)) / 100)
                        : Math.min(Integer.parseInt(value), LOSTHQ_CONTENT_IMAGE_W);
                String replacement = "width=\"" + imageW + "\"";
                attrs = width.replaceFirst(Matcher.quoteReplacement(replacement));
            } else {
                String src = lostHqAttribute(attrs, "src");
                if (src != null && src.contains("questimages/")) {
                    int imageW = src.contains("quest_complete") ? LOSTHQ_QUEST_COMPLETE_IMAGE_W : LOSTHQ_CONTENT_IMAGE_W;
                    attrs += " width=\"" + imageW + "\"";
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement("<img" + attrs + ">"));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String normalizeLostHqInlineWidths(String html) {
        Matcher matcher = Pattern.compile("(?is)\\sstyle\\s*=\\s*([\"'])(.*?)\\1").matcher(html);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String style = matcher.group(2)
                    .replaceAll("(?i)(?:^|;)\\s*(?:min-|max-)?width\\s*:\\s*[^;]+;?", ";")
                    .replaceAll(";{2,}", ";")
                    .replaceAll("^\\s*;|;\\s*$", "")
                    .trim();
            String replacement = style.isEmpty() ? "" : " style=" + matcher.group(1) + style + matcher.group(1);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String normalizeLostHqLinkSpacing(String html) {
        String spaced = html.replaceAll("(?i)</a>(?=\\S)", "</a> ");
        spaced = spaced.replaceAll("(?i)(?<=\\S)(<a\\b)", " $1");
        spaced = spaced.replaceAll("(?i)</a>\\s+(and|or)\\s+(<a\\b)", "</a><br>$1 $2");
        spaced = spaced.replaceAll("(?i)</a>\\s+([.,;:!?])", "</a>$1");
        return spaced;
    }

    private static String breakLostHqLongLinkRuns(String html) {
        Matcher matcher = Pattern.compile("(?is)([^<>\\r\\n]{16,})\\s+(<a\\b[^>]*>[^<]{4,}</a>)").matcher(html);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String prefix = matcher.group(1);
            int lastBreak = Math.max(prefix.toLowerCase().lastIndexOf("<br>"), prefix.lastIndexOf('>'));
            String visibleRun = prefix.substring(lastBreak + 1).replaceAll("&[^;]+;", "x").trim();
            String replacement = visibleRun.length() >= 18
                    ? prefix + "<br>" + matcher.group(2)
                    : matcher.group(0);
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    // Swing's HTMLEditorKit respects HTML table/column width attributes more than
    // CSS. Normalize scraped fixed-width layouts so every table can fit the reader.
    private static String normalizeLostHqTables(String html) {
        String normalized = html.replaceAll("(?i)(<table\\b[^>]*?)\\swidth\\s*=\\s*([\"'])?\\d+%?\\2", "$1");
        normalized = normalized.replaceAll("(?i)(<table\\b[^>]*?)\\sstyle\\s*=\\s*([\"'])[^\"']*?\\2", "$1");
        normalized = normalized.replaceAll("(?i)(<col\\b[^>]*?)\\swidth\\s*=\\s*([\"'])?\\d+%?\\2", "$1");
        normalized = normalized.replaceAll("(?i)<table\\b(?![^>]*\\bwidth=)",
                "<table width=\"" + LOSTHQ_BODY_W + "\"");
        return normalized;
    }

    // Rewraps text nodes inside <td>/<th> cells at a column-proportional width.
    // Swing's table layout uses natural content width and won't wrap text in cells
    // unless <br> tags are present, so long location strings in multi-column tables
    // would otherwise overflow the panel horizontally.
    private static String wrapLostHqTableCells(String html) {
        Matcher tableMatcher = Pattern.compile(
                "(?is)(<table\\b[^>]*>)(.*?)(</table\\s*>)").matcher(html);
        StringBuffer out = new StringBuffer();
        while (tableMatcher.find()) {
            String tableBody = tableMatcher.group(2);
            int cols = countFirstRowColumns(tableBody);
            // Approximate chars per line that fit in an equal-width column at 11px Arial.
            // Body content width ≈ 292px; each char ≈ 6px.
            int wrapCol = Math.max(10, 292 / cols / 6);
            String wrappedBody = rewrapTableCellText(tableBody, wrapCol);
            tableMatcher.appendReplacement(out, Matcher.quoteReplacement(
                    tableMatcher.group(1) + wrappedBody + tableMatcher.group(3)));
        }
        tableMatcher.appendTail(out);
        return out.toString();
    }

    private static int countFirstRowColumns(String tableBody) {
        // <col> elements directly encode the column count; prefer them over row parsing.
        Matcher colMatcher = Pattern.compile("(?i)<col\\b").matcher(tableBody);
        int colCount = 0;
        while (colMatcher.find()) colCount++;
        if (colCount > 0) return colCount;
        // Fall back to counting cells in the first row, expanding colspan values.
        Matcher rowMatcher = Pattern.compile("(?is)<tr\\b[^>]*>(.*?)</tr\\s*>").matcher(tableBody);
        if (!rowMatcher.find()) return 1;
        Matcher cellMatcher = Pattern.compile("(?i)<t[dh]\\b([^>]*)>").matcher(rowMatcher.group(1));
        int count = 0;
        while (cellMatcher.find()) {
            Matcher cs = Pattern.compile("(?i)\\bcolspan=[\"']?(\\d+)").matcher(cellMatcher.group(1));
            count += cs.find() ? Integer.parseInt(cs.group(1)) : 1;
        }
        return count > 0 ? count : 1;
    }

    private static String rewrapTableCellText(String tableBody, int wrapCol) {
        Matcher cellMatcher = Pattern.compile(
                "(?is)(<t[dh]\\b[^>]*>)(.*?)(</t[dh]\\s*>)").matcher(tableBody);
        StringBuffer sb = new StringBuffer();
        while (cellMatcher.find()) {
            String rewrapped = rewrapTextNodes(cellMatcher.group(2), wrapCol);
            cellMatcher.appendReplacement(sb, Matcher.quoteReplacement(
                    cellMatcher.group(1) + rewrapped + cellMatcher.group(3)));
        }
        cellMatcher.appendTail(sb);
        return sb.toString();
    }

    private static String rewrapTextNodes(String html, int wrapCol) {
        Matcher m = Pattern.compile(">([^<]+)<").matcher(html);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    ">" + wrapLostHqTextNode(m.group(1), wrapCol) + "<"));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Mirrors the Python scraper's uri_to_filename — must stay in sync with cache/scrape_losthq.py. */
    private static String uriToResourceFilename(URI uri) {
        String query = uri.getQuery();
        if (query == null || query.isEmpty()) return "index.html";
        TreeMap<String, List<String>> params = new TreeMap<>();
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            params.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> e : params.entrySet()) {
            for (String val : e.getValue()) {
                if (sb.length() > 0) sb.append('_');
                sb.append(e.getKey()).append('_').append(val.replaceAll("[^a-zA-Z0-9_-]", "_"));
            }
        }
        return sb + ".html";
    }

    private String loadBundledHtml(URI uri) throws Exception {
        String filename = uriToResourceFilename(uri);
        InputStream in = GLRenderer.class.getResourceAsStream("/losthq/" + filename);
        if (in == null) throw new FileNotFoundException("No bundled page: " + filename);
        try (in) {
            String html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Older scraped resources contain a fixed-width reader stylesheet.
            // Remove it before injecting the responsive CSS so Swing does not
            // keep laying out narrow maximized panels at the legacy 312px width.
            html = html.replaceFirst("(?is)<style>.*?</style>", "");
            return prepareLostHqHtml(html);
        }
    }

    private static List<SearchEntry> loadSearchData(String resource) {
        try (InputStream in = GLRenderer.class.getResourceAsStream(resource)) {
            if (in == null) return List.of();
            String[] lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\r?\\n");
            List<SearchEntry> out = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                String[] c = lines[i].split("\t", 4);
                if (c.length < 2) continue;
                int id = -1;
                try { id = Integer.parseInt(c[0].trim()); } catch (NumberFormatException ignored) {}
                out.add(new SearchEntry(id, c[1],
                        c.length > 2 ? c[2] : "",
                        c.length > 3 ? c[3] : ""));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void updateLostHqSearch() {
        List<SearchEntry> data = lostHqSearchIsNpc ? lostHqNpcData : lostHqItemData;
        if (data == null) { lostHqSearchResults = List.of(); return; }
        String q = lostHqSearchQuery.toLowerCase().trim();
        if (q.isEmpty()) { lostHqSearchResults = List.of(); return; }
        List<SearchEntry> results = new ArrayList<>();
        // Exact ID match first
        try {
            int id = Integer.parseInt(q);
            for (SearchEntry e : data) if (e.id() == id) { results.add(e); break; }
        } catch (NumberFormatException ignored) {}
        // Name/description substring matches
        for (SearchEntry e : data) {
            if (results.size() >= 20) break;
            if (!results.contains(e) && e.name().toLowerCase().contains(q))
                results.add(e);
        }
        lostHqSearchResults = results;
    }

    private static final int SEARCH_ROW_H = 28;

    private void drawLostHqSearchPage(int x) {
        int panelW = sidebarPanelW();
        int pageW  = panelW - 8;

        // BACK button (same position as the HTML page back button)
        fillUiRect(x + 4, 48, pageW, 18, 0xFF333333);
        drawUiTextCentered("<  BACK", x + 4, 48, pageW, 18, 1, 0xFFE89E14);

        // Search box
        int sbY = 70;
        boolean focused = lostHqSearchFocused;
        fillUiRect(x + 4, sbY, pageW, 20, focused ? 0xFF2A2A3A : 0xFF1E1E2A);
        fillUiRect(x + 4, sbY, 2, 20, 0xFF4477CC);
        String cursor = focused && (System.currentTimeMillis() / 500 % 2 == 0) ? "_" : "";
        String display = lostHqSearchQuery.isEmpty() && !focused
                ? "Search by name or ID..."
                : lostHqSearchQuery + cursor;
        int textColor = lostHqSearchQuery.isEmpty() && !focused ? 0xFF555566 : 0xFFDDDDEE;
        drawUiText(display, x + 10, sbY + 3, 1, textColor);

        // Loading indicator
        List<SearchEntry> data = lostHqSearchIsNpc ? lostHqNpcData : lostHqItemData;
        int resultsY = sbY + 24;
        if (data == null) {
            drawUiText("Loading data...", x + 8, resultsY + 4, 1, 0xFF777777);
            return;
        }

        // Hint / results
        if (lostHqSearchQuery.isEmpty()) {
            String hint = "Search " + (lostHqSearchIsNpc ? "1,284 NPCs" : "3,883 items")
                    + " by name or ID number.";
            drawUiText(hint, x + 8, resultsY + 4, 0, 0xFF666677);
            return;
        }
        if (lostHqSearchResults.isEmpty()) {
            drawUiText("No matches for: " + lostHqSearchQuery, x + 8, resultsY + 4, 1, 0xFF666677);
            return;
        }

        int ry = resultsY;
        for (SearchEntry e : lostHqSearchResults) {
            if (ry + SEARCH_ROW_H > screenH) break;
            fillUiRect(x + 4, ry, pageW, SEARCH_ROW_H - 2, 0xFF1C1C28);
            // ID badge
            String idStr = "[" + e.id() + "]";
            drawUiText(idStr, x + 8, ry + 2, 0, 0xFF4477CC);
            // Name
            int nameX = x + 8 + sg.getFontMetrics(uiFont(0)).stringWidth(idStr) + 4;
            drawUiText(e.name(), nameX, ry + 2, 1, 0xFFE89E14);
            // Extra (level/HP or Members)
            if (!e.extra().isEmpty()) {
                drawUiText(e.extra(), x + panelW - 8 - sg.getFontMetrics(uiFont(0)).stringWidth(e.extra()),
                        ry + 2, 0, 0xFF888899);
            }
            // Description
            if (!e.desc().isEmpty()) {
                String desc = e.desc();
                if (sg.getFontMetrics(uiFont(0)).stringWidth(desc) > pageW - 8)
                    desc = e.desc().substring(0, Math.min(e.desc().length(),
                            (pageW - 8) / 6)) + "…";
                drawUiText(desc, x + 8, ry + 15, 0, 0xFF888899);
            }
            ry += SEARCH_ROW_H;
        }
    }

    private static int xpToLevel(int xp) {
        for (int level = 98; level >= 1; level--) {
            if (xp >= XP_TABLE[level + 1]) return level + 1;
        }
        return 1;
    }

    private void drawLostHqSkillCalcPage(int x) {
        int panelW = sidebarPanelW();
        int pageW  = panelW - 8;

        // BACK button
        fillUiRect(x + 4, 48, pageW, 18, 0xFF333333);
        drawUiTextCentered("<  BACK", x + 4, 48, pageW, 18, 1, 0xFFE89E14);

        // Title
        String skill = lostHqCalcSkill != null ? lostHqCalcSkill : "skill";
        String title = Character.toUpperCase(skill.charAt(0)) + skill.substring(1) + " Calculator";
        drawUiTextCentered(title, x + 4, 70, pageW, 16, 2, 0xFFE89E14);

        int currentXp    = 0;
        int goalLevel    = 2;
        try { currentXp = Math.max(0, Integer.parseInt(lostHqCalcCurrentXpStr)); } catch (NumberFormatException ignored) {}
        try { goalLevel  = Math.max(1, Math.min(99, Integer.parseInt(lostHqCalcGoalLvlStr))); } catch (NumberFormatException ignored) {}
        int currentLevel = xpToLevel(currentXp);
        int goalXp       = XP_TABLE[goalLevel];

        int labelW = 82;
        int inputH = 20;

        // --- Current XP row ---
        int row1Y = 96;
        drawUiTextVerticallyCentered("Current XP:", x + 6, row1Y, inputH, 1, 0xFFAAAAAA);
        boolean f1 = lostHqCalcFocus == 1;
        fillUiRect(x + 6 + labelW, row1Y, pageW - labelW - 2, inputH, f1 ? 0xFF2A2A3A : 0xFF1E1E2A);
        fillUiRect(x + 6 + labelW, row1Y, 2, inputH, f1 ? 0xFFE89E14 : 0xFF4477CC);
        String cursor1 = f1 && (System.currentTimeMillis() / 500 % 2 == 0) ? "_" : "";
        drawUiTextVerticallyCentered(lostHqCalcCurrentXpStr + cursor1,
                x + 10 + labelW, row1Y, inputH, 1, 0xFFDDDDEE);

        // Current Level (auto)
        int row2Y = row1Y + 24;
        drawUiTextVerticallyCentered("Current Level: " + currentLevel,
                x + 6, row2Y, inputH, 1, 0xFF888899);

        // --- Goal Level row ---
        int row3Y = row2Y + 26;
        drawUiTextVerticallyCentered("Goal Level:", x + 6, row3Y, inputH, 1, 0xFFAAAAAA);
        boolean f2 = lostHqCalcFocus == 2;
        int halfW = (pageW - labelW - 4) / 2;
        fillUiRect(x + 6 + labelW, row3Y, halfW, inputH, f2 ? 0xFF2A2A3A : 0xFF1E1E2A);
        fillUiRect(x + 6 + labelW, row3Y, 2, inputH, f2 ? 0xFFE89E14 : 0xFF4477CC);
        String cursor2 = f2 && (System.currentTimeMillis() / 500 % 2 == 0) ? "_" : "";
        drawUiTextVerticallyCentered(lostHqCalcGoalLvlStr + cursor2,
                x + 10 + labelW, row3Y, inputH, 1, 0xFFDDDDEE);

        // Goal XP (auto, right side of same row)
        int gxpX = x + 6 + labelW + halfW + 4;
        drawUiTextVerticallyCentered("Goal XP:", gxpX, row3Y, inputH, 0, 0xFFAAAAAA);
        int gxpLabelW = sg.getFontMetrics(uiFont(0)).stringWidth("Goal XP: ");
        drawUiTextVerticallyCentered(String.format("%,d", goalXp),
                gxpX + gxpLabelW, row3Y, inputH, 0, 0xFFDDDDEE);

        // --- Separator ---
        int sepY = row3Y + 26;
        fillUiRect(x + 4, sepY, pageW, 1, 0xFF444444);

        // --- Results ---
        int resY = sepY + 8;
        drawUiTextCentered("Progress to Goal:", x + 4, resY, pageW, 14, 1, 0xFFE89E14);

        // Two stat boxes
        int boxY = resY + 18;
        int boxW = (pageW - 6) / 2;
        int boxH = 28;

        double pctToGoal = goalXp > 0 ? Math.min(100.0, currentXp * 100.0 / goalXp) : 100.0;
        double pctTo99   = XP_TABLE[99] > 0 ? Math.min(100.0, currentXp * 100.0 / XP_TABLE[99]) : 100.0;

        // Left box: % toward goal
        fillUiRect(x + 4, boxY, boxW, boxH, 0xFF1C1C28);
        String leftPct = String.format("%.2f%% from level %d", pctToGoal, currentLevel);
        drawUiTextCentered(leftPct, x + 4, boxY, boxW, boxH, 0, 0xFFDDDDEE);

        // Right box: % toward 99
        fillUiRect(x + 6 + boxW, boxY, boxW, boxH, 0xFF1C1C28);
        String rightPct = String.format("%.2f%% to level 99", pctTo99);
        drawUiTextCentered(rightPct, x + 6 + boxW, boxY, boxW, boxH, 0, 0xFFDDDDEE);

        // XP needed line
        int xpNeeded = Math.max(0, goalXp - currentXp);
        int xpY = boxY + boxH + 8;
        String neededStr = currentXp >= goalXp
                ? "Goal reached! (level " + currentLevel + ")"
                : String.format("%,d XP needed to reach level %d", xpNeeded, goalLevel);
        drawUiTextCentered(neededStr, x + 4, xpY, pageW, 14, 1, 0xFFDDDDEE);

        // --- Training unlocks table ---
        String[][] unlocks = SKILL_UNLOCKS.get(skill);
        if (unlocks == null || unlocks.length == 0) return;

        int tableStartY = xpY + 22;
        fillUiRect(x + 4, tableStartY - 1, pageW, 1, 0xFF444444);

        // Column header
        int headerY = tableStartY + 4;
        drawUiText("Item / Activity", x + 8, headerY, 1, 0xFFE89E14);
        String lvlHdr = "Level";
        int lvlHdrW = sg.getFontMetrics(uiFont(1)).stringWidth(lvlHdr);
        drawUiText(lvlHdr, x + 4 + pageW - lvlHdrW - 4, headerY, 1, 0xFFE89E14);
        fillUiRect(x + 4, headerY + 14, pageW, 1, 0xFF333333);

        int rowH = 18;
        int firstRowY = headerY + 18;

        // Clipping region for scrollable rows
        java.awt.Shape oldClip = sg.getClip();
        sg.clipRect(x + 4, firstRowY, pageW, screenH - firstRowY);

        int rowY = firstRowY - lostHqScrollY;
        for (int i = 0; i < unlocks.length; i++) {
            if (rowY + rowH > 0 && rowY < screenH) {
                if (i % 2 == 1) fillUiRect(x + 4, rowY, pageW, rowH, 0xFF1A1A1A);
                drawUiTextVerticallyCentered(unlocks[i][0], x + 8, rowY, rowH, 0, 0xFFDDDDEE);
                String lvlStr = unlocks[i][1];
                int lvlW = sg.getFontMetrics(uiFont(0)).stringWidth(lvlStr);
                drawUiTextVerticallyCentered(lvlStr, x + 4 + pageW - lvlW - 6, rowY, rowH, 0, 0xFF90C040);
            }
            rowY += rowH;
        }
        int maxScroll = Math.max(0, unlocks.length * rowH - (screenH - firstRowY));
        lostHqScrollY = Math.max(0, Math.min(maxScroll, lostHqScrollY));

        sg.setClip(oldClip);
    }

    private void drawLostHqPage(int x) {
        int pageY = 70;
        int panelW = sidebarPanelW();
        int pageW = panelW - 8;
        int pageH = screenH - pageY - HSCROLL_H; // reserve bottom strip for scrollbar
        double readerScale = lostHqReaderScale();
        int readerVisibleH = Math.max(1, (int) Math.ceil(pageH / readerScale));
        int readerVisibleW = Math.max(1, (int) Math.ceil(pageW / readerScale));
        fillUiRect(x + 4, 48, pageW, 18, 0xFF333333);
        drawUiTextCentered("<  BACK", x + 4, 48, pageW, 18, 1, 0xFFE89E14);
        JEditorPane page = lostHqPage;
        if (page == null || lostHqStatus != null) {
            drawUiText(lostHqStatus, x + 10, pageY + 12, 0, 0xFF999999);
            fillUiRect(x + 4, screenH - HSCROLL_H, pageW, HSCROLL_H, 0xFF1A1A1A);
            return;
        }
        int contentW = Math.max(LOSTHQ_READER_W, lostHqContentW);
        java.awt.Graphics2D pageGraphics = (java.awt.Graphics2D) sg.create();
        try {
            synchronized (page.getTreeLock()) {
                pageGraphics.clipRect(x + 4, pageY, pageW, pageH);
                pageGraphics.translate(x + 4, pageY);
                pageGraphics.scale(readerScale, readerScale);
                pageGraphics.translate(-lostHqScrollX, -lostHqScrollY);
                // Render at wider canvas so overflowing table cells aren't clipped.
                page.setSize(LOSTHQ_READER_WIDE, Short.MAX_VALUE);
                page.setSize(LOSTHQ_READER_WIDE, Math.max(readerVisibleH, page.getPreferredSize().height));
                int pw = page.getPreferredSize().width;
                contentW = lostHqMeasuredContentW(pw);
                lostHqContentW = contentW;
                int maxScrollX = Math.max(0, contentW - readerVisibleW);
                if (lostHqScrollX > maxScrollX) lostHqScrollX = maxScrollX;
                page.paint(pageGraphics);
            }
        } catch (RuntimeException ignored) {
            // JDK BoxView/TableView/FlowView bugs: skip this frame rather than crash
        } finally {
            pageGraphics.dispose();
        }
        // Horizontal scrollbar
        int sbX = x + 4;
        int sbY = screenH - HSCROLL_H;
        int maxScrollX = lostHqMaxScrollX();
        fillUiRect(sbX, sbY, pageW, HSCROLL_H, 0xFF1A1A1A);
        int thumbW = Math.max(20, pageW * readerVisibleW / Math.max(1, contentW));
        int trackRange = Math.max(1, pageW - thumbW);
        int thumbOff = maxScrollX > 0 ? (int) Math.min(trackRange, (long) lostHqScrollX * trackRange / maxScrollX) : 0;
        boolean hoverSb = cursorY >= sbY && isSidebarX(cursorX);
        int thumbCol = lostHqHScrollDrag ? 0xFF888888 : (hoverSb ? 0xFF666666 : 0xFF444444);
        fillUiRect(sbX + thumbOff, sbY + 2, thumbW, HSCROLL_H - 4, thumbCol);
    }

    private void drawSettingsPanel(int x) {
        int panelW = sidebarPanelW();
        int y = 52;

        y = drawSettingsSectionTitle(x, y, "Afk timer");
        drawSelectBox(x + 16, y, panelW - 32, AFK_LABELS[settingsAfkIndex], settingsAfkDropdownOpen);
        y += 22;
        if (settingsAfkDropdownOpen) {
            for (int i = 0; i < AFK_LABELS.length; i++) {
                int rowY = y + i * 14;
                fillUiRect(x + 16, rowY, panelW - 32, 14, i == settingsAfkIndex ? 0xFF3F3523 : 0xFF202020);
                fillUiRect(x + 16, rowY, panelW - 32, 1, 0xFF363636);
                drawUiText(AFK_LABELS[i], x + 22, rowY + 3, 0, i == settingsAfkIndex ? 0xFFE89E14 : 0xFFDCDCDC);
            }
            y += AFK_LABELS.length * 14 + 4;
        } else {
            y += 8;
        }

        y = drawSettingsSectionTitle(x, y, "Shift Click Actions");
        y = drawSettingsToggleRow(x, y, "Drop (Inventory Items)", settingShiftDropInventory);
        y = drawSettingsToggleRow(x, y, "Take (Ground Items)", settingShiftTakeGround);
        y = drawSettingsToggleRow(x, y, "Attack (NPC's)", settingShiftAttackNpc);
        y = drawSettingsToggleRow(x, y, "Pickpocket (NPC's)", settingShiftPickpocketNpc);
        y = drawSettingsToggleRow(x, y, "Bank (Bank NPC'S)", settingShiftBankNpc);
        y = drawSettingsToggleRow(x, y, "Use-Quickly (Bank Booth's)", settingShiftUseQuicklyBankBooth);
        y = drawSettingsToggleRow(x, y, "Examine (Anything)", settingShiftExamineAnything);
        y += 2;

        y = drawSettingsSectionTitle(x, y, "Discord Features");
        y = drawSettingsToggleRow(x, y, "Discord Rich Presence", settingDiscordRichPresence);
        y += 2;

        y = drawSettingsSectionTitle(x, y, "Client Settings");
        y = drawSettingsToggleRow(x, y, "60 Fps Mode", sidebarFpsEnabled);
        y = drawSettingsToggleRow(x, y, "Fullscreen Mode", settingsFullscreen);
        y += 4;
        drawClientVersionText(x, y);
    }

    private void loadSettings() {
        settingsAfkIndex = Math.max(0, Math.min(AFK_LABELS.length - 1, settingsAfkIndex));
        afkTimeoutCycles = AFK_CYCLES[settingsAfkIndex];
        settingFps60Enabled = sidebarFpsEnabled;
        settingShiftDropInventory = SETTINGS_PREFS.getBoolean("shiftDropInventory", false);
        settingShiftTakeGround = SETTINGS_PREFS.getBoolean("shiftTakeGround", false);
        settingShiftAttackNpc = SETTINGS_PREFS.getBoolean("shiftAttackNpc", false);
        settingShiftPickpocketNpc = SETTINGS_PREFS.getBoolean("shiftPickpocketNpc", false);
        settingShiftBankNpc = SETTINGS_PREFS.getBoolean("shiftBankNpc", false);
        settingShiftUseQuicklyBankBooth = SETTINGS_PREFS.getBoolean("shiftUseQuicklyBankBooth", false);
        settingShiftExamineAnything = SETTINGS_PREFS.getBoolean("shiftExamineAnything", false);
        settingDiscordRichPresence = SETTINGS_PREFS.getBoolean("discordRichPresence", false);
        if (settingDiscordRichPresence) {
            DISCORD_RPC.connect();
        }
    }

    private int drawSettingsSectionTitle(int x, int y, String title) {
        drawUiText(title, x + 16, y, 2, 0xFFE89E14);
        fillUiRect(x + 16, y + 15, sidebarPanelW() - 32, 1, 0xFF363636);
        return y + 18;
    }

    private int drawSettingsToggleRow(int x, int y, String text, boolean enabled) {
        int panelW = sidebarPanelW();
        drawUiTextFittedFull(text, x + 16, y + 5, panelW - 72, 0, 0xFFDCDCDC);
        drawToggle(x + panelW - 48, y + 2, enabled);
        return y + 20;
    }

    private void drawClientVersionText(int x, int y) {
        int panelW = sidebarPanelW();
        drawUiTextFittedFull(clientVersionText, x + 16, y + 5, panelW - 32, 0, 0xFF999999);
    }

    private void drawSelectBox(int x, int y, int w, String text, boolean open) {
        fillUiRect(x, y, w, 18, 0xFF202020);
        fillUiRect(x, y, w, 1, 0xFF666666);
        fillUiRect(x, y + 17, w, 1, 0xFF111111);
        fillUiRect(x, y, 1, 18, 0xFF4A4A4A);
        fillUiRect(x + w - 1, y, 1, 18, 0xFF111111);
        drawUiText(text, x + 7, y + 5, 0, 0xFFDCDCDC);
        drawUiText(open ? "^" : "v", x + w - 14, y + 5, 0, 0xFFE89E14);
    }

    private void drawPluginRow(int x, int y, String name, String description, boolean enabled) {
        drawUiText(name, x + 16, y, 2, 0xFFDCDCDC);
        drawUiText(description, x + 16, y + 20, 1, 0xFF999999);
        drawToggle(x + sidebarPanelW() - 48, y + 2, enabled);
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
        int panelW = sidebarPanelW();
        drawUiText(text, x + 16, y + 16, 1, 0xFFDCDCDC);
        drawToggle(x + panelW - 48, y + 9, enabled);
        fillUiRect(x + 16, y + 42, panelW - 32, 1, 0xFF363636);
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
        java.awt.Font font = uiFont(scale);
        sg.setFont(font);
        java.awt.FontMetrics fm = sg.getFontMetrics(font);
        int a = (argb >> 24) & 0xFF, r = (argb >> 16) & 0xFF,
            g = (argb >> 8)  & 0xFF, b =  argb        & 0xFF;
        sg.setColor(new java.awt.Color(r, g, b, a));
        sg.drawString(text, x, y + fm.getAscent());
    }

    private void drawUiTextFitted(String text, int x, int y, int maxWidth, int scale, int argb) {
        if (text == null || text.isEmpty() || maxWidth <= 0) return;
        int chosenScale = scale;
        java.awt.Font font = uiFont(chosenScale);
        java.awt.FontMetrics fm = sg.getFontMetrics(font);
        if (fm.stringWidth(text) > maxWidth && scale > 0) {
            chosenScale = 0;
            font = uiFont(chosenScale);
            fm = sg.getFontMetrics(font);
        }
        String fitted = text;
        if (fm.stringWidth(fitted) > maxWidth) {
            String ellipsis = "...";
            while (!fitted.isEmpty() && fm.stringWidth(fitted + ellipsis) > maxWidth) {
                fitted = fitted.substring(0, fitted.length() - 1);
            }
            fitted = fitted.isEmpty() ? ellipsis : fitted + ellipsis;
        }
        drawUiText(fitted, x, y, chosenScale, argb);
    }

    private void drawUiTextFittedFull(String text, int x, int y, int maxWidth, int scale, int argb) {
        if (text == null || text.isEmpty() || maxWidth <= 0) return;
        java.awt.Font font = uiFont(scale);
        java.awt.FontMetrics fm = sg.getFontMetrics(font);
        if (fm.stringWidth(text) > maxWidth) {
            font = UI_FONT_TINY;
            fm = sg.getFontMetrics(font);
        }
        float squeeze = Math.min(1f, maxWidth / (float) Math.max(1, fm.stringWidth(text)));

        java.awt.geom.AffineTransform prevTx = sg.getTransform();
        try {
            int a = (argb >> 24) & 0xFF, r = (argb >> 16) & 0xFF,
                g = (argb >> 8)  & 0xFF, b =  argb        & 0xFF;
            sg.setColor(new java.awt.Color(r, g, b, a));
            sg.setFont(font);
            sg.translate(x, y);
            sg.scale(squeeze, 1.0);
            sg.drawString(text, 0, fm.getAscent());
        } finally {
            sg.setTransform(prevTx);
        }
    }

    private java.awt.Font uiFont(int scale) {
        return (scale >= 2) ? UI_FONT_HEAD : (scale == 0) ? UI_FONT_TINY : UI_FONT_BODY;
    }

    private void drawUiTextCentered(String text, int x, int y, int width, int height, int scale,
                                    int argb) {
        java.awt.FontMetrics fm = sg.getFontMetrics(uiFont(scale));
        int textX = x + (width - fm.stringWidth(text)) / 2;
        int textY = y + (height - fm.getHeight()) / 2;
        drawUiText(text, textX, textY, scale, argb);
    }

    private void drawUiTextVerticallyCentered(String text, int x, int y, int height, int scale,
                                              int argb) {
        java.awt.FontMetrics fm = sg.getFontMetrics(uiFont(scale));
        drawUiText(text, x, y + (height - fm.getHeight()) / 2, scale, argb);
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
            int mouseX = worldMapFullscreen ? toFullscreenLogicalX(x) : toLogicalX(x);
            int mouseY = worldMapFullscreen ? toFullscreenLogicalY(y) : toLogicalY(y);
            if (mapDragging) {
                mapPanX += mouseX - mapDragLastX;
                mapPanY += mouseY - mapDragLastY;
                mapDragLastX = mouseX;
                mapDragLastY = mouseY;
            }
            if (lostHqHScrollDrag) {
                // Scroll the scrollbar thumb horizontally.
                double rs = lostHqReaderScale();
                int pw = Math.max(1, sidebarPanelW() - 8);
                int visW = Math.max(1, (int) Math.ceil(pw / rs));
                int contentW = Math.max(LOSTHQ_READER_W, lostHqContentW);
                int maxSX = Math.max(0, contentW - visW);
                int thumbW = Math.max(20, pw * visW / Math.max(1, contentW));
                int trackRange = Math.max(1, pw - thumbW);
                int dx = mouseX - lostHqHScrollAncX;
                lostHqScrollX = Math.max(0, Math.min(maxSX,
                        lostHqHScrollAncV + (int) ((long) dx * maxSX / trackRange)));
            }
            if (lostHqDragging) {
                int dy = mouseY - lostHqDragLastY;
                int dx = mouseX - lostHqDragLastX;
                if (!lostHqDragMoved && (Math.abs(dy) > 5 || Math.abs(dx) > 5))
                    lostHqDragMoved = true;
                if (lostHqDragMoved) {
                    if (isSkillCalcUri(lostHqPageUri))
                        lostHqScrollY = Math.max(0, lostHqScrollY - dy);
                    else scrollLostHqPage(-dy);
                    scrollLostHqHorizontal(-dx);
                    lostHqDragLastY = mouseY;
                    lostHqDragLastX = mouseX;
                }
            }
            cursorX = mouseX;
            cursorY = mouseY;
            if (worldMapFullscreen) {
                shell.mouseX = -1;
                shell.mouseY = -1;
                return;
            }
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

        glfwSetScrollCallback(window, (win, xoff, yoff) -> {
            if ((worldMapFullscreen || sidebarOpen && sidebarTab == 3) && worldMapLoaded
                    && cursorX >= worldMapViewX() && cursorY >= worldMapViewY()) {
                float factor = (float) Math.pow(1.18, yoff);
                float oldZoom = mapZoom;
                mapZoom = Math.max(0.03f, Math.min(12f, mapZoom * factor));
                float ratio = mapZoom / oldZoom;
                float cx = cursorX - worldMapViewX();
                float cy = cursorY - worldMapViewY();
                mapPanX = cx - (cx - mapPanX) * ratio;
                mapPanY = cy - (cy - mapPanY) * ratio;
            } else if (sidebarOpen && sidebarTab == 4 && isSkillCalcUri(lostHqPageUri)) {
                lostHqScrollY = Math.max(0, lostHqScrollY + (int) Math.round(-yoff * 24));
            } else if (sidebarOpen && sidebarTab == 4 && lostHqPage != null) {
                scrollLostHqPage((int) Math.round(-yoff * 36));
                if (xoff != 0) scrollLostHqHorizontal((int) Math.round(xoff * 36));
            }
        });

        glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (shell == null) return;
            shell.idleCycles = 0;
            if (action == GLFW_PRESS) {
                // Re-query cursor position so coords are fresh even after a sidebar
                // open/close changed the coordinate mapping since the last mousemove.
                double[] px = new double[1], py = new double[1];
                glfwGetCursorPos(win, px, py);
                int lx = worldMapFullscreen ? toFullscreenLogicalX(px[0]) : toLogicalX(px[0]);
                int ly = worldMapFullscreen ? toFullscreenLogicalY(py[0]) : toLogicalY(py[0]);
                cursorX = lx;
                cursorY = ly;
                // Quest-reward zoom overlay swallows the first click anywhere on screen
                // (only relevant in-game; the overlay never appears at the title screen).
                if (lostHqZoomImage != null && shell instanceof Client && ((Client) shell).ingame) {
                    lostHqZoomImage = null;
                    return;
                }
                if (worldMapFullscreen) {
                    clickWorldMap(lx, ly);
                    return;
                }
                if (isSidebarX(lx)) {
                    if (button == GLFW_MOUSE_BUTTON_LEFT && sidebarOpen && sidebarTab == 4
                            && (lostHqPage != null || isSkillCalcUri(lostHqPageUri))) {
                        if (ly >= screenH - HSCROLL_H && lostHqPage != null) {
                            // Press on the horizontal scrollbar — start thumb drag.
                            lostHqHScrollDrag = true;
                            lostHqHScrollAncX = lx;
                            lostHqHScrollAncV = lostHqScrollX;
                            return;
                        } else if (ly >= 70) {
                            // Press on page content — start pan drag.
                            lostHqDragging  = true;
                            lostHqDragMoved = false;
                            lostHqDragLastY = ly;
                            lostHqDragLastX = lx;
                            lostHqPressX    = lx;
                            lostHqPressY    = ly;
                            return;
                        }
                        // y < 70 (BACK button, tab icons, etc.) — fall through to clickSidebar.
                    }
                    clickSidebar(lx, ly);
                    return;
                }
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
                mapDragging = false;
                lostHqHScrollDrag = false;
                if (lostHqDragging) {
                    if (!lostHqDragMoved) {
                        // No significant movement — treat as a tap/click
                        clickSidebar(lostHqPressX, lostHqPressY);
                    }
                    lostHqDragging = false;
                }
            }
        });

        glfwSetFramebufferSizeCallback(window, (win, width, height) -> {
            framebufferW = width;
            framebufferH = height;
            frameDrawable = !windowIconified && width > 0 && height > 0;
            ClientDebugger.onRenderPauseState(!frameDrawable,
                    frameDrawable ? "drawable" : "framebuffer-callback",
                    framebufferW, framebufferH);
            if (frameDrawable) {
                beginRestoreCooldown();
                updateOutputViewport(width, height);
            }
        });
        glfwSetWindowIconifyCallback(window, (win, iconified) -> {
            windowIconified = iconified;
            frameDrawable = !iconified && framebufferW > 0 && framebufferH > 0;
            ClientDebugger.onRenderPauseState(!frameDrawable,
                    iconified ? "iconify-callback" : "restore-callback",
                    framebufferW, framebufferH);
            if (frameDrawable) {
                beginRestoreCooldown();
                updateOutputViewport();
            }
        });
        glfwSetWindowSizeCallback(window, (win, width, height) -> {
            windowW = width;
            windowH = height;
        });
        glfwSetWindowMaximizeCallback(window, (win, maximized) -> {
            updateWindowSizeLimits();
            if (!maximized && sidebarOpen) {
                resizeForSidebar();
            } else {
                updateOutputViewport();
            }
        });

        glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (key == GLFW_KEY_LEFT_SHIFT || key == GLFW_KEY_RIGHT_SHIFT) {
                shiftKeyDown = action != GLFW_RELEASE;
            }
            if (key == GLFW_KEY_GRAVE_ACCENT) {
                if (action == GLFW_PRESS) statsOverlayVisible = !statsOverlayVisible;
                return;
            }
            // Intercept keys for the native search panel
            if (lostHqSearchFocused && sidebarOpen && sidebarTab == 4
                    && (action == GLFW_PRESS || action == GLFW_REPEAT)) {
                if (key == GLFW_KEY_BACKSPACE) {
                    if (!lostHqSearchQuery.isEmpty()) {
                        lostHqSearchQuery = lostHqSearchQuery.substring(0, lostHqSearchQuery.length() - 1);
                        updateLostHqSearch();
                    }
                } else if (key == GLFW_KEY_ESCAPE) {
                    lostHqSearchQuery = "";
                    lostHqSearchFocused = false;
                    lostHqSearchResults = List.of();
                }
                return;
            }
            // Intercept keys for the skill calculator panel
            if (lostHqCalcFocus != 0 && sidebarOpen && sidebarTab == 4
                    && (action == GLFW_PRESS || action == GLFW_REPEAT)) {
                if (key == GLFW_KEY_BACKSPACE) {
                    if (lostHqCalcFocus == 1 && !lostHqCalcCurrentXpStr.isEmpty())
                        lostHqCalcCurrentXpStr = lostHqCalcCurrentXpStr.substring(0, lostHqCalcCurrentXpStr.length() - 1);
                    else if (lostHqCalcFocus == 2 && !lostHqCalcGoalLvlStr.isEmpty())
                        lostHqCalcGoalLvlStr = lostHqCalcGoalLvlStr.substring(0, lostHqCalcGoalLvlStr.length() - 1);
                } else if (key == GLFW_KEY_ESCAPE) {
                    lostHqCalcFocus = 0;
                } else if (key == GLFW_KEY_TAB) {
                    lostHqCalcFocus = lostHqCalcFocus == 1 ? 2 : 1;
                }
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
            // Route printable characters to the search box when it's focused.
            if (lostHqSearchFocused && sidebarOpen && sidebarTab == 4) {
                if (codepoint >= 32 && codepoint < 128) {
                    lostHqSearchQuery += (char) codepoint;
                    updateLostHqSearch();
                }
                return;
            }
            // Route digits to the focused skill calculator field.
            if (lostHqCalcFocus != 0 && sidebarOpen && sidebarTab == 4) {
                if (codepoint >= '0' && codepoint <= '9') {
                    if (lostHqCalcFocus == 1) {
                        String next = lostHqCalcCurrentXpStr + (char) codepoint;
                        if (next.length() <= 9) lostHqCalcCurrentXpStr = next;
                    } else if (lostHqCalcFocus == 2) {
                        String next = lostHqCalcGoalLvlStr + (char) codepoint;
                        int val = 0;
                        try { val = Integer.parseInt(next); } catch (NumberFormatException ignored) {}
                        if (val <= 99) lostHqCalcGoalLvlStr = next;
                    }
                }
                return;
            }
            if (codepoint == '`') return;
            if (shell == null || codepoint < 32 || codepoint >= 128) return;
            shell.idleCycles = 0;
            shell.keyQueue[shell.keyQueueWritePos] = codepoint;
            shell.keyQueueWritePos = (shell.keyQueueWritePos + 1) & 0x7F;
        });
    }

    private int toFullscreenLogicalX(double x) {
        return (int) Math.round(x * screenW / Math.max(1, windowW));
    }

    private int toFullscreenLogicalY(double y) {
        return (int) Math.round(y * screenH / Math.max(1, windowH));
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
            double scale = insideGameScale(width, height);
            int gameW   = Math.max(1, (int) Math.round(screenW * scale));
            int gameH   = Math.max(1, (int) Math.round(screenH * scale));
            int gameX   = insideGameX(width, gameW);
            int vertOff = (height - gameH) / 2;
            glViewport(gameX, vertOff, gameW, gameH);
        } else {
            double scale  = (width > 0) ? (double) width / outputW() : 1.0;
            int viewportW = Math.max(1, (int) Math.round(screenW * scale));
            int viewportH = Math.max(1, (int) Math.round(screenH * scale));
            int vertOff   = height - viewportH;
            glViewport(0, vertOff, viewportW, viewportH);
        }
    }

    private int toLogicalX(double x) {
        if (sidebarInsideWindow()) {
            int sidebarLogW = sidebarLogicalW();
            double scale = insideGameScale(windowW, windowH);
            double gamePhysW        = screenW * scale;
            double gamePhysStart    = insideGameX(windowW, gamePhysW);
            double sidebarPhysW     = insideSidebarW(windowW, gamePhysStart, gamePhysW,
                                                     sidebarLogW, scale);
            double sidebarPhysStart = windowW - sidebarPhysW;
            if (x >= sidebarPhysStart) {
                double logicalStart = screenW + sidebarLogW - sidebarPhysW / scale;
                return (int) (logicalStart + (x - sidebarPhysStart) / scale);
            }
            return (int) ((x - gamePhysStart) / scale);
        }
        double scale = Math.min((double) windowW / outputW(), (double) windowH / screenH);
        double left  = (windowW - outputW() * scale) / 2.0;
        return (int) ((x - left) / scale);
    }

    private int toLogicalY(double y) {
        double scale;
        if (sidebarInsideWindow()) {
            scale = insideGameScale(windowW, windowH);
        } else {
            scale = (windowW > 0) ? (double) windowW / outputW() : 1.0;
        }
        double top = (windowH - screenH * scale) / 2.0;
        return (int) ((y - top) / scale);
    }

    private double insideGameScale(double width, double height) {
        return Math.min(width / screenW, height / screenH);
    }

    private int insideGameX(int width, int gameW) {
        return sidebarOpen ? 0 : (width - gameW) / 2;
    }

    private double insideGameX(double width, double gameW) {
        return sidebarOpen ? 0 : (width - gameW) / 2.0;
    }

    private int insideSidebarW(int width, int gameX, int gameW, int sidebarLogW, double scale) {
        return (int) Math.min(Math.round(sidebarLogW * scale), Math.max(0, width - gameX - gameW));
    }

    private double insideSidebarW(double width, double gameX, double gameW,
                                  int sidebarLogW, double scale) {
        return Math.min(sidebarLogW * scale, Math.max(0, width - gameX - gameW));
    }

    private int outputW() {
        return screenW + SIDEBAR_RAIL_W + (sidebarOpen ? SIDEBAR_PANEL_W : 0);
    }

    private void updateWindowSizeLimits() {
        if (window == NULL) return;
        if (settingsFullscreen || sidebarInsideWindow()) {
            glfwSetWindowSizeLimits(window, GLFW_DONT_CARE, GLFW_DONT_CARE, GLFW_DONT_CARE, GLFW_DONT_CARE);
            glfwSetWindowAspectRatio(window, GLFW_DONT_CARE, GLFW_DONT_CARE);
        } else {
            glfwSetWindowSizeLimits(window, outputW(), screenH, GLFW_DONT_CARE, GLFW_DONT_CARE);
            glfwSetWindowAspectRatio(window, outputW(), screenH);
        }
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
            // Tab 1 (floating XP) is a toggle — no panel, just glow on/off
            if (tab == 1) {
                xpScreenEnabled = !xpScreenEnabled;
                if (sidebarOpen && sidebarTab == 1) {
                    sidebarOpen = false;
                    updateWindowSizeLimits();
                    resizeForSidebar();
                    updateOutputViewport();
                }
                return;
            }
            // Reset map view each time world map tab is freshly opened
            if (tab == 3 && !(sidebarOpen && sidebarTab == 3)) mapZoom = -1;
            if (sidebarOpen && sidebarTab == tab) {
                sidebarOpen = false;
            } else {
                sidebarOpen = true;
                sidebarTab  = tab;
            }
            updateWindowSizeLimits();
            resizeForSidebar();
            updateOutputViewport();
            return;
        }
        if (!sidebarOpen) return;
        if (sidebarTab == 3 && y <= 42) {
            clickWorldMap(x, y);
            return;
        }
        // Close button (top-right X in the panel header)
        if (x >= sidebarPanelX() + sidebarPanelW() - 22 && y <= 42) {
            sidebarOpen = false;
            updateWindowSizeLimits();
            resizeForSidebar();
            updateOutputViewport();
            return;
        }
        // Per-tab click handling
        switch (sidebarTab) {
            case 0 -> { // Hiscores – skill selector buttons (2 per row, 10 rows, y=52..181)
                int px = sidebarPanelX();
                int panelW = sidebarPanelW();
                int columns = HSCORE_SKILL_COLUMNS;
                int buttonW = (panelW - 20 - (columns - 1) * 4) / columns;
                int relX = x - (px + 10);
                int relY = y - 52;
                int col = relX / (buttonW + 4);
                int row = relY / 13;
                if (relX >= 0 && relY >= 0 && col >= 0 && col < columns
                        && relX % (buttonW + 4) < buttonW) {
                    int skill = row * columns + col;
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
                if (x >= px + sidebarPanelW() - 58 && y >= 52 && y < 74) {
                    resetXpSession();
                }
            }
            case 3 -> clickWorldMap(x, y);
            case 4 -> { // LostHQ toolkit links
                int px = sidebarPanelX();
                if (lostHqPage != null || lostHqPageUri != null) {
                    if (y >= 48 && y < 66) {
                        // BACK button — go to previous page in history
                        URI prev = lostHqHistory.isEmpty() ? null : lostHqHistory.pollFirst();
                        loadLostHqPage(prev);
                    } else if (isSkillCalcUri(lostHqPageUri)) {
                        // Skill calculator: focus the field the user clicked
                        int labelW = 82;
                        int inputH = 20;
                        int row1Y = 96;
                        int row3Y = row1Y + 24 + 26;
                        if (y >= row1Y && y < row1Y + inputH && x >= px + 6 + labelW) {
                            lostHqCalcFocus = 1;
                        } else if (y >= row3Y && y < row3Y + inputH && x >= px + 6 + labelW) {
                            int halfW = (sidebarPanelW() - 8 - labelW - 4) / 2;
                            if (x < px + 6 + labelW + halfW) lostHqCalcFocus = 2;
                            else lostHqCalcFocus = 0;
                        } else {
                            lostHqCalcFocus = 0;
                        }
                    } else if (isSearchPageUri(lostHqPageUri)) {
                        // Search page interaction
                        if (y >= 70 && y < 90) {
                            lostHqSearchFocused = true;   // clicked search box
                        } else {
                            lostHqSearchFocused = false;  // clicked elsewhere
                        }
                    } else {
                        clickLostHqPage(x, y);
                    }
                } else {
                    int itemStartY = 52;
                    int itemStep = Math.max(42, (screenH - itemStartY - 8) / LOSTHQ_ITEMS.length);
                    int relY = y - itemStartY;
                    int item = relY / itemStep;
                    if (x >= px + 4 && x < px + sidebarPanelW() - 4
                            && relY >= 0 && item < LOSTHQ_ITEMS.length) {
                        openLostHqPage(item);
                    }
                }
            }
            case 5 -> {
                clickSettingsPanel(x, y);
            }
        }
    }

    private void clickSettingsPanel(int x, int y) {
        int px = sidebarPanelX();
        int panelW = sidebarPanelW();
        int rowY = 52;

        rowY += 18;
        if (x >= px + 16 && x < px + panelW - 16 && y >= rowY && y < rowY + 18) {
            settingsAfkDropdownOpen = !settingsAfkDropdownOpen;
            return;
        }
        rowY += 22;
        if (settingsAfkDropdownOpen) {
            for (int i = 0; i < AFK_LABELS.length; i++) {
                int optY = rowY + i * 14;
                if (x >= px + 16 && x < px + panelW - 16 && y >= optY && y < optY + 14) {
                    setAfkIndex(i);
                    settingsAfkDropdownOpen = false;
                    return;
                }
            }
            rowY += AFK_LABELS.length * 14 + 4;
        } else {
            rowY += 8;
        }

        rowY += 18;
        if (toggleHit(px, rowY, x, y)) { setShiftDropInventory(!settingShiftDropInventory); return; }
        rowY += 20;
        if (toggleHit(px, rowY, x, y)) { setShiftTakeGround(!settingShiftTakeGround); return; }
        rowY += 20;
        if (toggleHit(px, rowY, x, y)) { setShiftAttackNpc(!settingShiftAttackNpc); return; }
        rowY += 20;
        if (toggleHit(px, rowY, x, y)) { setShiftPickpocketNpc(!settingShiftPickpocketNpc); return; }
        rowY += 20;
        if (toggleHit(px, rowY, x, y)) { setShiftBankNpc(!settingShiftBankNpc); return; }
        rowY += 20;
        if (toggleHit(px, rowY, x, y)) { setShiftUseQuicklyBankBooth(!settingShiftUseQuicklyBankBooth); return; }
        rowY += 20;
        if (toggleHit(px, rowY, x, y)) { setShiftExamineAnything(!settingShiftExamineAnything); return; }
        rowY += 22;

        rowY += 18;
        if (toggleHit(px, rowY, x, y)) { setDiscordRichPresence(!settingDiscordRichPresence); return; }
        rowY += 22;

        rowY += 18;
        if (toggleHit(px, rowY, x, y)) { setFps60(!sidebarFpsEnabled); return; }
        rowY += 20;
        if (toggleHit(px, rowY, x, y)) { toggleFullscreen(); return; }
    }

    private boolean toggleHit(int px, int rowY, int mouseX, int mouseY) {
        return mouseX >= px + 8 && mouseX < px + sidebarPanelW() - 8
                && mouseY >= rowY && mouseY < rowY + 20;
    }

    private void setAfkIndex(int index) {
        settingsAfkIndex = Math.max(0, Math.min(AFK_LABELS.length - 1, index));
        afkTimeoutCycles = AFK_CYCLES[settingsAfkIndex];
        SETTINGS_PREFS.putInt("afkIndex", settingsAfkIndex);
    }

    private void setFps60(boolean enabled) {
        sidebarFpsEnabled = enabled;
        settingFps60Enabled = enabled;
        SETTINGS_PREFS.putBoolean("fps60", enabled);
        if (shell != null) {
            shell.setFramerate(50);
        }
    }

    private void setShiftDropInventory(boolean enabled) {
        settingShiftDropInventory = enabled;
        SETTINGS_PREFS.putBoolean("shiftDropInventory", enabled);
    }

    private void setShiftTakeGround(boolean enabled) {
        settingShiftTakeGround = enabled;
        SETTINGS_PREFS.putBoolean("shiftTakeGround", enabled);
    }

    private void setShiftAttackNpc(boolean enabled) {
        settingShiftAttackNpc = enabled;
        SETTINGS_PREFS.putBoolean("shiftAttackNpc", enabled);
    }

    private void setShiftPickpocketNpc(boolean enabled) {
        settingShiftPickpocketNpc = enabled;
        SETTINGS_PREFS.putBoolean("shiftPickpocketNpc", enabled);
    }

    private void setShiftBankNpc(boolean enabled) {
        settingShiftBankNpc = enabled;
        SETTINGS_PREFS.putBoolean("shiftBankNpc", enabled);
    }

    private void setShiftUseQuicklyBankBooth(boolean enabled) {
        settingShiftUseQuicklyBankBooth = enabled;
        SETTINGS_PREFS.putBoolean("shiftUseQuicklyBankBooth", enabled);
    }

    private void setShiftExamineAnything(boolean enabled) {
        settingShiftExamineAnything = enabled;
        SETTINGS_PREFS.putBoolean("shiftExamineAnything", enabled);
    }

    private void setDiscordRichPresence(boolean enabled) {
        settingDiscordRichPresence = enabled;
        SETTINGS_PREFS.putBoolean("discordRichPresence", enabled);
        if (enabled) {
            DISCORD_RPC.connect();
            DISCORD_RPC.updateActivity("Playing 2004 Singleplayer Progressive", "Loading world...");
        } else {
            DISCORD_RPC.disconnect();
        }
    }

    public static void updateDiscordActivity(String details, String state) {
        if (settingDiscordRichPresence) {
            DISCORD_RPC.updateActivity(details, state);
        }
    }

    private void scrollLostHqPage(int amount) {
        JEditorPane page = lostHqPage;
        if (page == null) return;
        double readerScale = lostHqReaderScale();
        int pageH = Math.max(1, (int) Math.ceil((screenH - 70 - HSCROLL_H) / readerScale));
        try {
            int maxScroll = Math.max(0, page.getPreferredSize().height - pageH);
            int readerAmount = (int) Math.round(amount / readerScale);
            lostHqScrollY = Math.max(0, Math.min(maxScroll, lostHqScrollY + readerAmount));
        } catch (RuntimeException ignored) {
            // JDK BoxView/FlowView bugs: skip scroll recalculation this frame
        }
    }

    private void scrollLostHqHorizontal(int amountScreenPx) {
        double readerScale = lostHqReaderScale();
        int maxScroll = lostHqMaxScrollX();
        int readerAmt = (int) Math.round(amountScreenPx / readerScale);
        lostHqScrollX = Math.max(0, Math.min(maxScroll, lostHqScrollX + readerAmt));
    }

    private int lostHqMaxScrollX() {
        double readerScale = lostHqReaderScale();
        int pageW = Math.max(1, sidebarPanelW() - 8);
        int visibleW = Math.max(1, (int) Math.ceil(pageW / readerScale));
        return Math.max(0, Math.max(LOSTHQ_READER_W, lostHqContentW) - visibleW);
    }

    private static int lostHqMeasuredContentW(int measuredW) {
        int w = Math.max(LOSTHQ_READER_W, Math.min(measuredW, LOSTHQ_READER_WIDE));
        return w;
    }

    private void clickLostHqPage(int x, int y) {
        JEditorPane page = lostHqPage;
        URI pageUri = lostHqPageUri;
        if (page == null || pageUri == null || y < 70) return;
        double readerScale = lostHqReaderScale();
        int relX = (int) ((x - sidebarPanelX() - 4) / readerScale) + lostHqScrollX;
        int relY = (int) ((y - 70) / readerScale) + lostHqScrollY;
        if (relX < 0 || relX >= Math.max(LOSTHQ_READER_W, lostHqContentW) || relY < 0) return;
        try {
            int pos;
            synchronized (page.getTreeLock()) {
                pos = page.viewToModel2D(new java.awt.Point(relX, relY));
            }
            HTMLDocument doc = (HTMLDocument) page.getDocument();
            Element element = doc.getCharacterElement(pos);
            // First check for IMG element at click position — walk up the tree.
            // Swing represents <img> as a leaf whose name attribute is HTML.Tag.IMG.
            for (Element walk = element; walk != null; walk = walk.getParentElement()) {
                AttributeSet attrs = walk.getAttributes();
                Object name = attrs.getAttribute(StyleConstants.NameAttribute);
                if (name != HTML.Tag.IMG) continue;
                Object src = attrs.getAttribute(HTML.Attribute.SRC);
                if (src == null) break;
                String srcStr = src.toString();
                if (srcStr.contains("questimages/quest_complete_thumb/")
                        || srcStr.contains("questimages/quest_complete/")) {
                    // Only zoom while in-game — there's no 3D viewport to overlay onto
                    // at the title/login screen, so the click would do nothing useful.
                    if (shell instanceof Client && ((Client) shell).ingame) {
                        // Always load the high-res original for the zoom view.
                        openLostHqZoom(srcStr.replace("quest_complete_thumb/", "quest_complete/"));
                    }
                    return;
                }
                break;
            }
            AttributeSet anchor = (AttributeSet) element.getAttributes().getAttribute(HTML.Tag.A);
            if (anchor == null) return;
            Object href = anchor.getAttribute(HTML.Attribute.HREF);
            if (href == null) return;
            String target = href.toString();
            if (target.startsWith("progress:")) {
                toggleLostHqProgressStep(target);
            } else {
                openLostHqPage(pageUri.resolve(target));
            }
        } catch (Exception ignored) {
        }
    }

    private void openLostHqZoom(String src) {
        // src may be absolute (https://...) or relative (img/...).  Strip everything
        // up to and including "img/" so we can resolve against the classpath base.
        int idx = src.indexOf("img/");
        if (idx < 0) return;
        String resource = "/losthq/" + src.substring(idx);
        try (InputStream in = GLRenderer.class.getResourceAsStream(resource)) {
            if (in == null) return;
            BufferedImage img = ImageIO.read(in);
            if (img != null) lostHqZoomImage = img;
        } catch (Exception ignored) {
        }
    }

    private void drawLostHqZoomOverlay() {
        BufferedImage img = lostHqZoomImage;
        if (img == null || PixMap.uiBuffer == null) return;
        // Draw inside the 3D game viewport only, leaving chatbox / inventory / sidebar alone.
        int x0 = vpDrawX;
        int y0 = vpDrawY;
        int w  = vpW;
        int h  = vpH;
        if (w <= 0 || h <= 0) return;
        // Wrap the uiBuffer slice as a BufferedImage so we can draw with Graphics2D
        // (it's TYPE_INT_ARGB = BGRA on little-endian, which matches how GL uploads it).
        java.awt.image.DataBufferInt buf = new java.awt.image.DataBufferInt(
                PixMap.uiBuffer, PixMap.uiBuffer.length);
        java.awt.image.SinglePixelPackedSampleModel sm =
                new java.awt.image.SinglePixelPackedSampleModel(
                        java.awt.image.DataBuffer.TYPE_INT, maxUiW, screenH,
                        new int[]{0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000});
        java.awt.image.WritableRaster raster =
                java.awt.image.Raster.createWritableRaster(sm, buf, null);
        BufferedImage canvas = new BufferedImage(
                java.awt.image.ColorModel.getRGBdefault(), raster, false, null);
        java.awt.Graphics2D g = canvas.createGraphics();
        try {
            g.setClip(x0, y0, w, h);
            // Semi-transparent dark backdrop over the game viewport.
            g.setComposite(java.awt.AlphaComposite.Src);
            g.setColor(new java.awt.Color(0, 0, 0, 200));
            g.fillRect(x0, y0, w, h);
            // Fit image inside the viewport with a small margin, preserve aspect ratio.
            int maxW = (int) (w * 0.92);
            int maxH = (int) (h * 0.92);
            double scale = Math.min((double) maxW / img.getWidth(),
                                    (double) maxH / img.getHeight());
            int drawW = (int) (img.getWidth() * scale);
            int drawH = (int) (img.getHeight() * scale);
            int drawX = x0 + (w - drawW) / 2;
            int drawY = y0 + (h - drawH) / 2;
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(img, drawX, drawY, drawW, drawH, null);
            // Hint text just above the image.
            g.setColor(new java.awt.Color(0xE8, 0x9E, 0x14));
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 11));
            g.drawString("CLICK TO CLOSE", drawX, Math.max(y0 + 12, drawY - 6));
        } finally {
            g.dispose();
        }
    }

    private void clickWorldMap(int x, int y) {
        int vx = worldMapViewX();
        int vy = worldMapViewY();
        int vw = worldMapViewW();
        int vh = worldMapViewH();
        int right = vx + vw;

        if (y <= 42) {
            int keyX = right - (worldMapFullscreen ? 75 : 129);
            if (x >= keyX && x < keyX + 29) {
                worldMapKeyVisible = !worldMapKeyVisible;
            } else if (!worldMapFullscreen && x >= right - 98 && x < right - 46) {
                worldMapFollowing = !worldMapFollowing;
                mapDragging = false;
            } else if (x >= right - 44 && x < right - 24) {
                worldMapFullscreen = !worldMapFullscreen;
                mapZoom = -1;
                mapDragging = false;
                updateOutputViewport();
            } else if (x >= right - 22 && x < right - 2) {
                worldMapFullscreen = false;
                sidebarOpen = false;
                mapDragging = false;
                updateWindowSizeLimits();
                resizeForSidebar();
                updateOutputViewport();
            }
            return;
        }

        BufferedImage keyPage = worldMapKeyPages[worldMapKeyPage];
        int overlayKeyHeight = Math.min(vh - 8, (keyPage != null ? keyPage.getHeight() : 434) + 26);
        if (worldMapKeyVisible && (!worldMapFullscreen
                || x < vx + 8 + worldMapKeyOverlayWidth() && y < vy + 4 + overlayKeyHeight)) {
            int keyWidth = worldMapFullscreen ? worldMapKeyOverlayWidth() : vw;
            int keyX = worldMapFullscreen ? vx + 8 : vx;
            int keyY = worldMapFullscreen ? vy + 4 : vy;
            int keyHeight = worldMapFullscreen
                    ? overlayKeyHeight
                    : vh;
            if (y >= keyY + keyHeight - 24) {
                if (x < keyX + keyWidth / 2) {
                    worldMapKeyPage = Math.max(0, worldMapKeyPage - 1);
                } else {
                    worldMapKeyPage = Math.min(worldMapKeyPages.length - 1, worldMapKeyPage + 1);
                }
            }
            return;
        }
        if (!worldMapLoaded || y < vy) return;

        int btnX = vx + vw - 20;
        int btnY = vy + vh - 38;
        if (x >= btnX && x < btnX + 18 && y >= btnY && y < btnY + 17) {
            zoomMapAround(1.4f, vx + vw / 2, vy + vh / 2);
        } else if (x >= btnX && x < btnX + 18 && y >= btnY + 19 && y < btnY + 36) {
            zoomMapAround(1f / 1.4f, vx + vw / 2, vy + vh / 2);
        } else if (!worldMapFollowing) {
            mapDragging = true;
            mapDragLastX = x;
            mapDragLastY = y;
        }
    }

    private void toggleFullscreen() {
        settingsFullscreen = !settingsFullscreen;
        if (settingsFullscreen) {
            updateWindowSizeLimits();
            long monitor = glfwGetPrimaryMonitor();
            org.lwjgl.glfw.GLFWVidMode mode = glfwGetVideoMode(monitor);
            if (mode != null) {
                glfwSetWindowMonitor(window, monitor, 0, 0,
                        mode.width(), mode.height(), mode.refreshRate());
            }
        } else {
            glfwSetWindowMonitor(window, NULL, 100, 100, outputW(), screenH, GLFW_DONT_CARE);
            updateWindowSizeLimits();
        }
        updateOutputViewport();
    }

    private boolean sidebarInsideWindow() {
        return glfwGetWindowAttrib(window, GLFW_MAXIMIZED) == GLFW_TRUE;
    }

    private int sidebarPanelW() {
        if (!sidebarOpen || !sidebarInsideWindow()) return SIDEBAR_PANEL_W;
        double scale = insideGameScale(windowW, windowH);
        if (scale <= 0) return SIDEBAR_PANEL_W;
        int available = (int) Math.floor(windowW / scale) - screenW - SIDEBAR_RAIL_W;
        return Math.max(1, Math.min(SIDEBAR_PANEL_W, available));
    }

    private int sidebarLogicalW() {
        return SIDEBAR_RAIL_W + (sidebarOpen ? sidebarPanelW() : 0);
    }

    private double lostHqReaderScale() {
        return Math.min(1.0, Math.max(1, sidebarPanelW() - 8) / (double) LOSTHQ_READER_W);
    }

    private int sidebarRailX() {
        return screenW + (sidebarOpen ? sidebarPanelW() : 0);
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

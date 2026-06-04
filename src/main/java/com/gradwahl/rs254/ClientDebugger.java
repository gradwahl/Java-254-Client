package com.gradwahl.rs254;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight session debugger. Writes timestamped events to logs/debug.log.
 * Call ClientDebugger.enable() once at startup to activate.
 *
 * Detects:
 *   - logout() trigger cause (idle timeout, IOException, server packet, exception)
 *   - tryReconnect() calls and whether they fall through to logout
 *   - idleCycles / pendingLogout state when logout fires
 *   - socket read errors (SocketTimeoutException is the #1 random-logout cause)
 *   - render frame spikes (>50ms gap → flash risk)
 */
public final class ClientDebugger {

    public enum LogoutReason {
        SERVER_OPCODE,
        IO_EXCEPTION,
        UNHANDLED_EXCEPTION,
        IDLE_PENDING_ON_RECONNECT,
        UNKNOWN
    }

    private static final DateTimeFormatter FMT =
        DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private static volatile boolean enabled = false;
    private static PrintWriter out;
    private static File logDir;

    // render flash detection
    private static final AtomicLong lastRenderNs = new AtomicLong(0);
    private static final long FLASH_THRESHOLD_MS = 50;
    private static final long MINIMIZED_HANG_THRESHOLD_MS = 5_000L;
    private static final AtomicLong lastLoopNs = new AtomicLong(System.nanoTime());
    private static final AtomicLong lastDrawNs = new AtomicLong(System.nanoTime());
    private static volatile boolean renderPaused;
    private static volatile String renderPauseReason = "startup";
    private static volatile int lastLoopCycle;
    private static volatile int lastDrawCycle;
    private static volatile long lastThreadDumpAtMs;

    // state snapshot at logout time
    public static volatile int lastIdleCycles   = 0;
    public static volatile int lastPendingLogout = 0;

    public static void enable() {
        if (enabled) return;
        try {
            logDir = resolveLogDir();
            logDir.mkdirs();
            out = new PrintWriter(new FileWriter(new File(logDir, "debug.log"), true), true);
            enabled = true;
            log("=== ClientDebugger enabled ===");
            startWatchdog();
        } catch (IOException e) {
            System.err.println("[debug] Could not open debug.log: " + e.getMessage());
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    // -------------------------------------------------------------------------
    // Logout / reconnect events
    // -------------------------------------------------------------------------

    public static void onLogout(LogoutReason reason) {
        onLogout(reason, null);
    }

    public static void onLogout(LogoutReason reason, Throwable cause) {
        if (!enabled) return;
        StringBuilder sb = new StringBuilder();
        sb.append("[LOGOUT] reason=").append(reason);
        sb.append(" idleCycles=").append(lastIdleCycles);
        sb.append(" pendingLogout=").append(lastPendingLogout);
        if (cause != null) {
            sb.append(" exception=").append(cause.getClass().getSimpleName());
            sb.append(" msg=").append(cause.getMessage());
        }
        log(sb.toString());
        // Print 5-frame call stack so we know which logout() call site fired
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (int i = 2; i < Math.min(7, stack.length); i++) {
            log("  at " + stack[i]);
        }
    }

    public static void onTryReconnect() {
        if (!enabled) return;
        log("[RECONNECT] tryReconnect() called"
            + " pendingLogout=" + lastPendingLogout
            + " idleCycles=" + lastIdleCycles
            + (lastPendingLogout > 0 ? " → will logout instead of reconnect!" : ""));
    }

    public static void onIdleTimeout(int idleCycles) {
        if (!enabled) return;
        log("[IDLE] idleCycles=" + idleCycles + " → pendingLogout set to 250");
    }

    // -------------------------------------------------------------------------
    // Socket / connection events
    // -------------------------------------------------------------------------

    public static void onSocketError(Throwable t) {
        if (!enabled) return;
        log("[SOCKET] " + t.getClass().getSimpleName() + ": " + t.getMessage());
    }

    public static void onConnectionLost(String detail) {
        if (!enabled) return;
        log("[CONNECTION] lost: " + detail);
    }

    public static void onServerLogoutPacket() {
        if (!enabled) return;
        log("[PACKET] server sent LOGOUT opcode (21)");
    }

    // -------------------------------------------------------------------------
    // Render flash detection
    // -------------------------------------------------------------------------

    /** Call at the start of each render() pass to detect frame-time spikes. */
    public static void onRenderStart() {
        if (!enabled) return;
        long now  = System.nanoTime();
        long prev = lastRenderNs.getAndSet(now);
        if (prev != 0) {
            long gapMs = (now - prev) / 1_000_000L;
            if (gapMs > FLASH_THRESHOLD_MS) {
                log("[RENDER] frame gap " + gapMs + "ms — possible flash/stutter");
            }
        }
    }

    public static void onLoopHeartbeat(int loopCycle) {
        if (!enabled) return;
        lastLoopCycle = loopCycle;
        lastLoopNs.set(System.nanoTime());
    }

    public static void onDrawHeartbeat(int drawCycle) {
        if (!enabled) return;
        lastDrawCycle = drawCycle;
        lastDrawNs.set(System.nanoTime());
    }

    public static void onRenderPauseState(boolean paused, String reason, int framebufferW, int framebufferH) {
        if (!enabled) return;
        if (renderPaused != paused || !reason.equals(renderPauseReason)) {
            renderPaused = paused;
            renderPauseReason = reason;
            log("[WINDOW] renderPaused=" + paused
                    + " reason=" + reason
                    + " framebuffer=" + framebufferW + "x" + framebufferH
                    + " loopCycle=" + lastLoopCycle
                    + " drawCycle=" + lastDrawCycle);
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private static void startWatchdog() {
        Thread watchdog = new Thread(() -> {
            while (enabled) {
                try {
                    Thread.sleep(1000L);
                    checkForMinimizedHang();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Throwable t) {
                    System.err.println("[debug] Watchdog error: " + t);
                }
            }
        }, "minimize-hang-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private static void checkForMinimizedHang() throws IOException {
        if (!renderPaused) return;
        long nowNs = System.nanoTime();
        long loopGapMs = (nowNs - lastLoopNs.get()) / 1_000_000L;
        long drawGapMs = (nowNs - lastDrawNs.get()) / 1_000_000L;
        if (loopGapMs < MINIMIZED_HANG_THRESHOLD_MS) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        if (nowMs - lastThreadDumpAtMs < MINIMIZED_HANG_THRESHOLD_MS) {
            return;
        }
        lastThreadDumpAtMs = nowMs;
        log("[WATCHDOG] possible minimized hang"
                + " reason=" + renderPauseReason
                + " loopGapMs=" + loopGapMs
                + " drawGapMs=" + drawGapMs
                + " loopCycle=" + lastLoopCycle
                + " drawCycle=" + lastDrawCycle);
        writeThreadDump(loopGapMs, drawGapMs);
    }

    private static void writeThreadDump(long loopGapMs, long drawGapMs) throws IOException {
        File file = new File(logDir, "minimize_hang_threads_"
                + DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now())
                + ".log");
        try (PrintWriter dump = new PrintWriter(new FileWriter(file), true)) {
            dump.println("Minimized/render-paused hang snapshot at " + LocalDateTime.now());
            dump.println("reason=" + renderPauseReason);
            dump.println("loopGapMs=" + loopGapMs + " drawGapMs=" + drawGapMs);
            dump.println("loopCycle=" + lastLoopCycle + " drawCycle=" + lastDrawCycle);
            dump.println();
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                dump.println("\"" + thread.getName() + "\" state=" + thread.getState()
                        + " daemon=" + thread.isDaemon()
                        + " priority=" + thread.getPriority());
                for (StackTraceElement frame : entry.getValue()) {
                    dump.println("    at " + frame);
                }
                dump.println();
            }
        }
        log("[WATCHDOG] wrote " + file.getAbsolutePath());
    }

    private static File resolveLogDir() {
        String configuredLogDir = System.getProperty("rs254.logDir");
        if (configuredLogDir != null && !configuredLogDir.isBlank()) {
            return new File(configuredLogDir);
        }
        try {
            File jar = new File(ClientDebugger.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return jar.isFile() ? new File(jar.getParentFile(), "logs") : new File("logs");
        } catch (Exception ignored) {
            return new File("logs");
        }
    }

    public static void log(String msg) {
        if (out == null) return;
        out.println(LocalDateTime.now().format(FMT) + " " + msg);
    }
}

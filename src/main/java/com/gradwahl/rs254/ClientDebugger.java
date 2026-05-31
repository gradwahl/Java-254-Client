package com.gradwahl.rs254;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight session debugger. Writes timestamped events to debug.log.
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

    // render flash detection
    private static final AtomicLong lastRenderNs = new AtomicLong(0);
    private static final long FLASH_THRESHOLD_MS = 50;

    // state snapshot at logout time
    public static volatile int lastIdleCycles   = 0;
    public static volatile int lastPendingLogout = 0;

    public static void enable() {
        if (enabled) return;
        try {
            out = new PrintWriter(new FileWriter("debug.log", true), true);
            enabled = true;
            log("=== ClientDebugger enabled ===");
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

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    public static void log(String msg) {
        if (out == null) return;
        out.println(LocalDateTime.now().format(FMT) + " " + msg);
    }
}

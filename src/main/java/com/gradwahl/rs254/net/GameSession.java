package com.gradwahl.rs254.net;

import com.gradwahl.rs254.io.IsaacCipher;
import com.gradwahl.rs254.io.Protocol;

import java.io.IOException;

public final class GameSession implements AutoCloseable {
    private static final int OPCODE_IDLE_TIMER = 144;
    private static final int OPCODE_LOGOUT     = Protocol.Server.LOGOUT; // 21

    private final GameConnection connection;
    private final IsaacCipher inboundCipher;
    private final IsaacCipher outboundCipher;

    private volatile boolean running;
    private volatile String lastPacket = "none";
    private volatile int packetsReceived;

    public GameSession(GameConnection connection, IsaacCipher inboundCipher, IsaacCipher outboundCipher) {
        this.connection     = connection;
        this.inboundCipher  = inboundCipher;
        this.outboundCipher = outboundCipher;
    }

    public void start() {
        running = true;

        Thread reader = new Thread(this::readLoop, "game-reader");
        reader.setDaemon(true);
        reader.start();

        Thread keepAlive = new Thread(this::keepAliveLoop, "game-keepalive");
        keepAlive.setDaemon(true);
        keepAlive.start();
    }

    public String status() {
        return "In-game | packets=" + packetsReceived + " last=" + lastPacket;
    }

    private void readLoop() {
        try {
            while (running) {
                // Opcode is Isaac-encrypted: decrypt by subtracting the cipher value
                int raw    = connection.read();
                int opcode = (raw - inboundCipher.nextInt()) & 0xff;

                int size;
                if (opcode < Protocol.Server.SIZES.length) {
                    size = Protocol.Server.SIZES[opcode];
                } else {
                    System.out.println("[game] Unknown opcode " + opcode + ", disconnecting");
                    break;
                }

                int payloadLen;
                if (size >= 0) {
                    payloadLen = size;
                } else if (size == -1) {
                    payloadLen = connection.read();
                } else { // -2
                    payloadLen = (connection.read() << 8) | connection.read();
                }

                connection.readBytes(payloadLen);

                packetsReceived++;
                lastPacket = opcodeName(opcode) + "(" + payloadLen + "b)";

                if (opcode == OPCODE_LOGOUT) {
                    System.out.println("[game] Server sent LOGOUT");
                    break;
                }
            }
        } catch (IOException e) {
            if (running) System.out.println("[game] Connection lost: " + e.getMessage());
        } finally {
            running = false;
            close();
        }
    }

    private void keepAliveLoop() {
        try {
            while (running) {
                Thread.sleep(2_000);
                if (!running) break;
                sendEncrypted(OPCODE_IDLE_TIMER);
            }
        } catch (InterruptedException ignored) {
        } catch (IOException e) {
            if (running) System.out.println("[game] Keep-alive send failed: " + e.getMessage());
        }
    }

    private void sendEncrypted(int opcode) throws IOException {
        byte[] buf = { (byte) ((opcode + outboundCipher.nextInt()) & 0xff) };
        connection.send(buf);
    }

    @Override
    public void close() {
        running = false;
        connection.close();
    }

    private static String opcodeName(int opcode) {
        return switch (opcode) {
            case 0   -> "CAM_LOOKAT";
            case 3   -> "IF_SETNPCHEAD";
            case 5   -> "P_COUNTDIALOG";
            case 14  -> "IF_SETSCROLLPOS";
            case 21  -> "LOGOUT";
            case 24  -> "CHAT_FILTER_SETTINGS";
            case 25  -> "SYNTH_SOUND";
            case 27  -> "IF_SETPOSITION";
            case 28  -> "UPDATE_INV_FULL";
            case 29  -> "FINISH_TRACKING";
            case 38  -> "IF_SETCOLOUR";
            case 41  -> "IF_SETTEXT";
            case 55  -> "CAM_MOVETO";
            case 58  -> "TUT_FLASH";
            case 60  -> "MESSAGE_PRIVATE";
            case 61  -> "UPDATE_ZONE_PARTIAL_ENCLOSED";
            case 63  -> "UPDATE_IGNORELIST";
            case 64  -> "HINT_ARROW";
            case 73  -> "MESSAGE_GAME";
            case 75  -> "SET_MULTIWAY";
            case 85  -> "IF_OPENOVERLAY";
            case 87  -> "PLAYER_INFO";
            case 91  -> "IF_SETTAB";
            case 94  -> "UPDATE_RUNENERGY";
            case 95  -> "IF_SETANIM";
            case 108 -> "UNSET_MAP_FLAG";
            case 111 -> "UPDATE_FRIENDLIST";
            case 123 -> "NPC_INFO";
            case 136 -> "UPDATE_STAT";
            case 138 -> "IF_SETTAB_ACTIVE";
            case 140 -> "RESET_CLIENT_VARCACHE";
            case 141 -> "IF_OPENCHAT";
            case 143 -> "UPDATE_REBOOT_TIMER";
            case 146 -> "LAST_LOGIN_INFO";
            case 159 -> "UPDATE_ZONE_FULL_FOLLOWS";
            case 161 -> "IF_SETPLAYERHEAD";
            case 163 -> "MIDI_SONG";
            case 164 -> "UPDATE_RUNWEIGHT";
            case 167 -> "CAM_RESET";
            case 168 -> "UPDATE_INV_STOP_TRANSMIT";
            case 170 -> "UPDATE_INV_PARTIAL";
            case 173 -> "UPDATE_ZONE_PARTIAL_FOLLOWS";
            case 174 -> "IF_CLOSE";
            case 186 -> "VARP_SMALL";
            case 187 -> "IF_OPENSIDE";
            case 196 -> "VARP_LARGE";
            case 197 -> "IF_OPENMAIN";
            case 203 -> "RESET_ANIMS";
            case 204 -> "SET_PLAYER_OP";
            case 209 -> "REBUILD_NORMAL";
            case 211 -> "IF_SETMODEL";
            case 213 -> "UPDATE_PID";
            case 222 -> "IF_SETOBJECT";
            case 225 -> "CAM_SHAKE";
            case 227 -> "IF_SETHIDE";
            case 239 -> "TUT_OPEN";
            case 242 -> "MIDI_JINGLE";
            case 249 -> "IF_OPENMAIN_SIDE";
            case 251 -> "ENABLE_TRACKING";
            case 255 -> "FRIENDLIST_LOADED";
            default  -> "opcode" + opcode;
        };
    }
}

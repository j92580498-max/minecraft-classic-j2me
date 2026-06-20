package com.mojang.rubydung.net;

import com.mojang.rubydung.Player;
import com.mojang.rubydung.level.Level;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

import javax.microedition.io.Connector;
import javax.microedition.io.SocketConnection;

/**
 * Minecraft Classic / ClassiCube multiplayer client (the original Classic
 * protocol, no CPE extensions).
 *
 * Runs its own receive thread. The game thread polls {@link #poll()} state:
 *   - level transfer progress + the finished {@link Level}
 *   - remote players (id -> NetPlayer) for the renderer
 *   - chat lines
 * and pushes the local player position via {@link #sendPosition}.
 *
 * Protocol reference: ClassiCube src/Protocol.c (packet sizes verbatim).
 * Coordinates are fixed-point: 1 block == 32 units (S16.5).
 */
public final class NetworkClient implements Runnable {
    // --- server -> client opcodes ---
    private static final int S_IDENT = 0;
    private static final int S_PING = 1;
    private static final int S_LEVEL_INIT = 2;
    private static final int S_LEVEL_DATA = 3;
    private static final int S_LEVEL_FINAL = 4;
    private static final int S_SET_BLOCK = 6;
    private static final int S_ADD_ENTITY = 7;
    private static final int S_ENTITY_TP = 8;
    private static final int S_REL_POS_ORI = 9;
    private static final int S_REL_POS = 10;
    private static final int S_ORI = 11;
    private static final int S_REMOVE_ENTITY = 12;
    private static final int S_MESSAGE = 13;
    private static final int S_KICK = 14;
    private static final int S_SET_PERMISSION = 15;

    // --- client -> server opcodes ---
    private static final int C_IDENT = 0;
    private static final int C_SET_BLOCK = 5;
    private static final int C_POSITION = 8;
    private static final int C_MESSAGE = 13;

    public static final int ST_CONNECTING = 0;
    public static final int ST_LOADING = 1;
    public static final int ST_PLAYING = 2;
    public static final int ST_ERROR = 3;

    private final String host;
    private final int port;
    private final String username;

    private SocketConnection conn;
    private DataInputStream in;
    private DataOutputStream out;

    private volatile int state = ST_CONNECTING;
    private volatile String error;
    private volatile String serverName = "";
    private volatile String motd = "";

    // level transfer
    private final ByteArrayOutputStream levelStream = new ByteArrayOutputStream();
    private int levelVolume;
    private volatile int loadPercent;
    private volatile Level level;        // set once map finalises
    private volatile boolean levelReady;

    // remote players: id 0..255
    private final NetPlayer[] players = new NetPlayer[256];
    public byte localId = -1;

    // chat
    private final String[] chat = new String[5];
    private int chatHead = 0;

    private volatile boolean running = true;

    public NetworkClient(String host, int port, String username) {
        this.host = host;
        this.port = port;
        this.username = username;
    }

    public void start() {
        new Thread(this).start();
    }

    public void run() {
        try {
            conn = (SocketConnection) Connector.open("socket://" + host + ":" + port);
            try { conn.setSocketOption(SocketConnection.DELAY, 0); } catch (Throwable t) {}
            in = conn.openDataInputStream();
            out = conn.openDataOutputStream();
            sendLogin();
            state = ST_LOADING;
            loop();
        } catch (Throwable t) {
            error = String.valueOf(t.getMessage());
            state = ST_ERROR;
        } finally {
            closeQuietly();
        }
    }

    private void loop() throws Exception {
        while (running) {
            int op = in.read();
            if (op < 0) throw new Exception("disconnected");
            handle(op);
        }
    }

    private void handle(int op) throws Exception {
        switch (op) {
            case S_IDENT: {
                in.readByte();                  // protocol version
                serverName = readString();
                motd = readString();
                in.readByte();                  // user type
                break;
            }
            case S_PING:
                break;
            case S_LEVEL_INIT:
                levelStream.reset();
                levelVolume = 0;
                loadPercent = 0;
                break;
            case S_LEVEL_DATA: {
                int len = in.readShort() & 0xFFFF;
                byte[] chunk = new byte[1024];
                in.readFully(chunk);
                int pct = in.readByte() & 0xFF;  // percent complete
                levelStream.write(chunk, 0, len);
                loadPercent = pct;
                break;
            }
            case S_LEVEL_FINAL: {
                int w = in.readShort() & 0xFFFF;
                int h = in.readShort() & 0xFFFF;
                int l = in.readShort() & 0xFFFF;
                finishLevel(w, h, l);
                break;
            }
            case S_SET_BLOCK: {
                int x = in.readShort() & 0xFFFF;
                int y = in.readShort() & 0xFFFF;
                int z = in.readShort() & 0xFFFF;
                int block = in.readByte() & 0xFF;
                if (level != null) level.setTile(x, y, z, block);
                break;
            }
            case S_ADD_ENTITY: {
                int id = in.readByte();
                String name = readString();
                float x = in.readShort() / 32.0f;
                float y = in.readShort() / 32.0f;
                float z = in.readShort() / 32.0f;
                int yaw = in.readByte() & 0xFF;
                int pitch = in.readByte() & 0xFF;
                addPlayer(id, name, x, y, z, yaw, pitch);
                break;
            }
            case S_ENTITY_TP: {
                int id = in.readByte();
                float x = in.readShort() / 32.0f;
                float y = in.readShort() / 32.0f;
                float z = in.readShort() / 32.0f;
                int yaw = in.readByte() & 0xFF;
                int pitch = in.readByte() & 0xFF;
                setPlayer(id, x, y, z, yaw, pitch, true, true);
                break;
            }
            case S_REL_POS_ORI: {
                int id = in.readByte();
                float dx = in.readByte() / 32.0f;
                float dy = in.readByte() / 32.0f;
                float dz = in.readByte() / 32.0f;
                int yaw = in.readByte() & 0xFF;
                int pitch = in.readByte() & 0xFF;
                movePlayer(id, dx, dy, dz, yaw, pitch, true);
                break;
            }
            case S_REL_POS: {
                int id = in.readByte();
                float dx = in.readByte() / 32.0f;
                float dy = in.readByte() / 32.0f;
                float dz = in.readByte() / 32.0f;
                movePlayer(id, dx, dy, dz, 0, 0, false);
                break;
            }
            case S_ORI: {
                int id = in.readByte();
                int yaw = in.readByte() & 0xFF;
                int pitch = in.readByte() & 0xFF;
                NetPlayer p = id >= 0 ? players[id] : null;
                if (p != null) { p.yaw = yaw; p.pitch = pitch; }
                break;
            }
            case S_REMOVE_ENTITY: {
                int id = in.readByte();
                if (id >= 0 && id < 256) players[id] = null;
                break;
            }
            case S_MESSAGE: {
                in.readByte();                  // source player id
                addChat(stripColors(readString()));
                break;
            }
            case S_KICK: {
                error = "Kicked: " + readString();
                state = ST_ERROR;
                running = false;
                break;
            }
            case S_SET_PERMISSION:
                in.readByte();
                break;
            default:
                throw new Exception("unknown opcode " + op);
        }
    }

    private void finishLevel(int w, int h, int l) {
        try {
            byte[] gz = levelStream.toByteArray();
            byte[] raw = Inflate.gunzip(gz);
            // raw: 4-byte big-endian volume, then w*h*l blocks in (y*l+z)*w+x order.
            int volume = w * h * l;
            byte[] blocks = new byte[volume];
            int off = 4;
            int n = Math.min(volume, raw.length - off);
            System.arraycopy(raw, off, blocks, 0, n);
            // port Level(width=X, height=Zlength, depth=Yheight)
            level = new Level(w, l, h, blocks);
            levelReady = true;
            state = ST_PLAYING;
        } catch (Throwable t) {
            error = "level decode: " + t.getMessage();
            state = ST_ERROR;
        }
    }

    private void addPlayer(int id, String name, float x, float y, float z, int yaw, int pitch) {
        if (id == 255 || id == -1) { localId = (byte) id; return; }  // self
        if (id < 0 || id >= 256) return;
        NetPlayer p = new NetPlayer();
        p.name = stripColors(name);
        p.x = p.xo = x; p.y = p.yo = y; p.z = p.zo = z;
        p.yaw = yaw; p.pitch = pitch;
        players[id] = p;
    }

    private void setPlayer(int id, float x, float y, float z, int yaw, int pitch,
                           boolean setYaw, boolean setPitch) {
        if (id < 0 || id >= 256) return;
        NetPlayer p = players[id];
        if (p == null) return;
        p.xo = p.x; p.yo = p.y; p.zo = p.z;
        p.x = x; p.y = y; p.z = z;
        if (setYaw) p.yaw = yaw;
        if (setPitch) p.pitch = pitch;
    }

    private void movePlayer(int id, float dx, float dy, float dz, int yaw, int pitch, boolean ori) {
        if (id < 0 || id >= 256) return;
        NetPlayer p = players[id];
        if (p == null) return;
        p.xo = p.x; p.yo = p.y; p.zo = p.z;
        p.x += dx; p.y += dy; p.z += dz;
        if (ori) { p.yaw = yaw; p.pitch = pitch; }
    }

    // ---- outgoing ----
    private void sendLogin() throws Exception {
        synchronized (out) {
            out.writeByte(C_IDENT);
            out.writeByte(7);                  // protocol version
            writeString(username);
            writeString("");                   // verification key / mppass
            out.writeByte(0);                  // CPE magic (0 = vanilla)
            out.flush();
        }
    }

    public void sendPosition(Player pl) {
        if (state != ST_PLAYING || out == null) return;
        try {
            synchronized (out) {
                out.writeByte(C_POSITION);
                out.writeByte(localId);
                out.writeShort((int) (pl.x * 32.0f));
                out.writeShort((int) (pl.y * 32.0f));
                out.writeShort((int) (pl.z * 32.0f));
                out.writeByte((int) (pl.yRot * 256.0f / 360.0f) & 0xFF);
                out.writeByte((int) (pl.xRot * 256.0f / 360.0f) & 0xFF);
                out.flush();
            }
        } catch (Throwable t) { /* connection died; loop will notice */ }
    }

    public void sendSetBlock(int x, int y, int z, int block, boolean place) {
        if (state != ST_PLAYING || out == null) return;
        try {
            synchronized (out) {
                out.writeByte(C_SET_BLOCK);
                out.writeShort(x);
                out.writeShort(y);
                out.writeShort(z);
                out.writeByte(place ? 1 : 0);
                out.writeByte(place ? block : 0);
                out.flush();
            }
        } catch (Throwable t) {}
    }

    public void sendChat(String msg) {
        if (out == null) return;
        try {
            synchronized (out) {
                out.writeByte(C_MESSAGE);
                out.writeByte(-1);
                writeString(msg);
                out.flush();
            }
        } catch (Throwable t) {}
    }

    public void disconnect() {
        running = false;
        closeQuietly();
    }

    private void closeQuietly() {
        try { if (in != null) in.close(); } catch (Throwable t) {}
        try { if (out != null) out.close(); } catch (Throwable t) {}
        try { if (conn != null) conn.close(); } catch (Throwable t) {}
    }

    // ---- string helpers (Classic: 64-byte space-padded ASCII) ----
    private String readString() throws Exception {
        byte[] b = new byte[64];
        in.readFully(b);
        int end = 64;
        while (end > 0 && (b[end - 1] == ' ' || b[end - 1] == 0)) --end;
        return new String(b, 0, end);
    }

    private void writeString(String s) throws Exception {
        byte[] b = new byte[64];
        for (int i = 0; i < 64; ++i) b[i] = ' ';
        byte[] src = s.getBytes();
        int n = Math.min(src.length, 64);
        System.arraycopy(src, 0, b, 0, n);
        out.write(b);
    }

    private static String stripColors(String s) {
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < s.length(); ++i) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) { ++i; continue; }
            sb.append(c);
        }
        return sb.toString();
    }

    private void addChat(String line) {
        chat[chatHead] = line;
        chatHead = (chatHead + 1) % chat.length;
    }

    // ---- state accessors for the game thread ----
    public int getState() { return state; }
    public String getError() { return error; }
    public String getServerName() { return serverName; }
    public String getMotd() { return motd; }
    public int getLoadPercent() { return loadPercent; }
    public boolean isLevelReady() { return levelReady; }
    public Level getLevel() { return level; }
    public NetPlayer[] getPlayers() { return players; }

    public String[] getChat() {
        String[] ordered = new String[chat.length];
        for (int i = 0; i < chat.length; ++i) {
            ordered[i] = chat[(chatHead + i) % chat.length];
        }
        return ordered;
    }
}

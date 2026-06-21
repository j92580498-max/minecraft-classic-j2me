package com.mojang.rubydung;

import com.mojang.rubydung.level.Level;
import com.mojang.rubydung.level.WorldRenderer;
import com.mojang.rubydung.level.tile.Tile;

import com.mascotcapsule.micro3d.v3.Graphics3D;
import com.mascotcapsule.micro3d.v3.Texture;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Displayable;
import javax.microedition.midlet.MIDlet;

/**
 * Game loop + input for the J2ME / Mascot Capsule port.
 *
 * Controls (numeric keypad):
 *   2 / 8  move forward / back      4 / 6  strafe left / right
 *   1 / 3  turn left / right        7 / 9  look up / down
 *   5      jump                     *      break block      #  place block
 *   0      next block in hotbar
 *   left soft key or FIRE opens the in-game menu
 *   right soft key opens the inventory picker
 */
public class GameCanvas extends Canvas implements Runnable, CommandListener {
    private static final int PLAYER_SAVE_SCALE = 1000;
    private static final int INV_COLS = 8;

    private static final int MENU_RESUME = 0;
    private static final int MENU_SAVE = 1;
    private static final int MENU_LOAD = 2;
    private static final int MENU_NEW = 3;
    private static final int MENU_HELP = 4;
    private static final int MENU_MULTIPLAYER = 5;

    private static final int MENU_COUNT = 6;

    private final Timer timer = new Timer(60.0f);
    private Level level;
    private WorldRenderer renderer;
    private Player player;
    private Graphics3D g3d;
    private Texture texture;
    private HitResult hitResult;

    private volatile boolean running = false;
    private final int skyColor = 0x88B0FF;

    private int fps;
    private boolean showInventory = false;
    private boolean showMenu = false;
    private int menuIndex = 0;
    private String toast;
    private long toastUntil;
    private String hudLine;
    private long hudUntil;

    // ---- Multiplayer ----
    private com.mojang.rubydung.net.NetworkClient net;
    private boolean multiplayer = false;
    // server entry screen
    private boolean showServerEntry = false;
    private String serverHost = "";
    private int serverPort = 25565;
    private String userName = "Steve";
    private int entryField = 0;   // 0=host, 1=port, 2=name
    private MIDlet midlet;
    private TextBox joinBox;
    private Command joinOk, joinCancel;
    private int joinStep = 0;   // 0=host,1=port,2=name
    private boolean connecting = false;

    public GameCanvas(MIDlet midlet) {
        this.midlet = midlet;
    }


    // Input state, polled by Player.tick().
    public boolean kForward, kBack, kLeft, kRight, kJump, kReset;
    // Continuous look: held keys turn smoothly each tick (Symbian-port feel).
    private boolean kTurnLeft, kTurnRight, kLookUp, kLookDown;
    private float turnYaw, turnPitch;
    private static final float TURN_SPEED = 22.0f;   // raw units/tick (scaled *0.15 in Player.turn)
    private static final float PITCH_SPEED = 18.0f;

    // ---- Inventory: every placeable (non-gas) block id ----
    private int[] palette;
    private int hotbarIndex = 0;   // index into palette currently selected
    private int invCursor = 0;     // cursor while inventory overlay is open

    private void buildPalette() {
        int n = 0;
        int[] tmp = new int[256];
        for (int id = 1; id < 256; ++id) {
            Tile t = Tile.tiles[id];
            if (t != null && t.isRenderable()) tmp[n++] = id;
        }
        palette = new int[n];
        System.arraycopy(tmp, 0, palette, 0, n);
    }

    public void start() {
        try {
            buildPalette();
            g3d = new Graphics3D();
            byte[] texBytes = readResource("/terrain.bmp");
            texture = new Texture(texBytes, true);

            level = new Level(128, 128, 64);
            player = new Player(level);
            renderer = new WorldRenderer(level, g3d, texture);
            loadPlayerState();
        } catch (Throwable t) {
            t.printStackTrace();
        }
        running = true;
        new Thread(this).start();
    }

    public void stop() {
        running = false;
        saveGame();
    }

    public void run() {
        long lastTime = System.currentTimeMillis();
        int frames = 0;
        while (running) {
            timer.advanceTime();
            for (int i = 0; i < timer.ticks; ++i) {
                tick();
            }
            repaint();
            serviceRepaints();
            ++frames;
            if (System.currentTimeMillis() >= lastTime + 1000L) {
                lastTime += 1000L;
                fps = frames;
                frames = 0;
            }
            try { Thread.sleep(15); } catch (InterruptedException e) {}
        }
    }

    private void tick() {
        tickNetwork();
        if (showMenu || showInventory || connecting) return;
        if (kTurnLeft)  turnYaw -= TURN_SPEED;
        if (kTurnRight) turnYaw += TURN_SPEED;
        if (kLookUp)    turnPitch -= PITCH_SPEED;
        if (kLookDown)  turnPitch += PITCH_SPEED;
        if (turnYaw != 0 || turnPitch != 0) {
            player.turn(turnYaw, turnPitch);
            turnYaw = 0;
            turnPitch = 0;
        }
        player.kForward = kForward;
        player.kBack = kBack;
        player.kLeft = kLeft;
        player.kRight = kRight;
        player.kJump = kJump;
        player.kReset = kReset;
        level.tick();
        player.tick();
        tickParticles();
        hitResult = RayCast.pick(level, player, 4.0f);
    }

    private final java.util.Random rng = new java.util.Random();
    private final java.util.Vector particles = new java.util.Vector();

    private void tickParticles() {
        for (int i = particles.size() - 1; i >= 0; --i) {
            Particle pa = (Particle) particles.elementAt(i);
            pa.tick();
            if (pa.removed) particles.removeElementAt(i);
        }
    }

    protected void paint(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        g.setColor(skyColor);
        g.fillRect(0, 0, w, h);

        if (renderer == null) {
            g.setColor(0xFFFFFF);
            g.drawString("Loading...", w / 2, h / 2, Graphics.HCENTER | Graphics.BASELINE);
            return;
        }
        if (connecting) { drawConnecting(g, w, h); return; }

        g3d.bind(g);
        renderer.render(player, timer.a, w / 2, h / 2);
        if (multiplayer && net != null) renderer.renderNetPlayers(net.getPlayers(), timer.a);
        renderer.renderParticles(particles, player, timer.a);
        if (hitResult != null) renderer.renderHit(hitResult);
        g3d.flush();
        g3d.release(g);

        g.setColor(0xFFFFFF);
        g.drawLine(w / 2 - 4, h / 2, w / 2 + 4, h / 2);
        g.drawLine(w / 2, h / 2 - 4, w / 2, h / 2 + 4);

        drawHud(g, w, h);
        drawHealth(g, w, h);
        if (multiplayer && net != null) drawChat(g, w, h);
        if (showInventory) drawInventory(g, w, h);
        if (showMenu) drawMenu(g, w, h);
        if (player != null && player.isDead()) drawDeath(g, w, h);
    }

    private void drawHud(Graphics g, int w, int h) {
        Tile sel = Tile.tiles[palette[hotbarIndex]];
        String name = sel != null ? sel.name : "?";
        g.setColor(0x000000);
        g.drawString(name, 3, h - 2, Graphics.LEFT | Graphics.BOTTOM);
        g.setColor(0xFFFF66);
        g.drawString(name, 2, h - 3, Graphics.LEFT | Graphics.BOTTOM);

        g.setColor(0xFFFFFF);
        g.drawString(fps + " fps", w - 2, 1, Graphics.RIGHT | Graphics.TOP);

        long now = System.currentTimeMillis();
        if (toast != null && now < toastUntil) {
            g.setColor(0xFFFFFF);
            g.drawString(toast, w / 2, 2, Graphics.HCENTER | Graphics.TOP);
        }
        if (hudLine != null && now < hudUntil) {
            g.setColor(0xFFFFFF);
            g.drawString(hudLine, w / 2, 14, Graphics.HCENTER | Graphics.TOP);
        }
    }

    /** Survival HUD: 10 hearts (2 HP each) bottom-right, air bubbles above when underwater. */
    private void drawHealth(Graphics g, int w, int h) {
        if (player == null) return;
        int hp = player.health;
        int sz = 6;        // heart cell size
        int gap = 1;
        int total = 10;
        int rowW = total * (sz + gap);
        int x0 = w - rowW - 2;
        int y0 = h - sz - 2;

        for (int i = 0; i < total; ++i) {
            int hx = x0 + i * (sz + gap);
            int hpForHeart = hp - i * 2;
            // empty heart shadow
            g.setColor(0x401010);
            g.fillRect(hx, y0, sz, sz);
            if (hpForHeart >= 2) {
                g.setColor(0xFF2020);
                g.fillRect(hx, y0, sz, sz);
            } else if (hpForHeart == 1) {
                g.setColor(0xFF2020);
                g.fillRect(hx, y0, sz / 2, sz);
            }
            g.setColor(0x000000);
            g.drawRect(hx, y0, sz, sz);
        }

        // Air bubbles row above hearts while underwater.
        if (player.inWater && player.air < Player.MAX_AIR) {
            int bubbles = (player.air * 10 + Player.MAX_AIR - 1) / Player.MAX_AIR;
            int by0 = y0 - sz - 2;
            for (int i = 0; i < bubbles; ++i) {
                int bx = x0 + i * (sz + gap);
                g.setColor(0x40A0FF);
                g.fillRect(bx, by0, sz, sz);
                g.setColor(0x000000);
                g.drawRect(bx, by0, sz, sz);
            }
        }
    }

    /** Death overlay with an auto-respawn countdown. */
    private void drawDeath(Graphics g, int w, int h) {
        g.setColor(0x700000);
        for (int y = 0; y < h; y += 2) g.drawLine(0, y, w, y);
        g.setColor(0xFFFFFF);
        g.drawString("You died!", w / 2, h / 2 - 12, Graphics.HCENTER | Graphics.TOP);
        int left = (40 - player.deathTime + 19) / 20;
        if (left < 0) left = 0;
        g.drawString("Respawning...", w / 2, h / 2 + 2, Graphics.HCENTER | Graphics.TOP);
    }

    private void drawInventory(Graphics g, int w, int h) {
        int rows = (palette.length + INV_COLS - 1) / INV_COLS;
        int cell = Math.min((w - 16) / INV_COLS, 14);
        int gridW = cell * INV_COLS;
        int gridH = cell * rows;
        int ox = (w - gridW) / 2;
        int oy = (h - gridH) / 2;

        g.setColor(0x000000);
        g.fillRect(ox - 6, oy - 16, gridW + 12, gridH + 22);
        g.setColor(0xFFFFFF);
        g.drawRect(ox - 6, oy - 16, gridW + 12, gridH + 22);
        g.drawString("Select block", w / 2, oy - 15, Graphics.HCENTER | Graphics.TOP);

        for (int i = 0; i < palette.length; ++i) {
            int cx = ox + (i % INV_COLS) * cell;
            int cy = oy + (i / INV_COLS) * cell;
            int rgb = blockColor(palette[i]);
            g.setColor(rgb);
            g.fillRect(cx + 1, cy + 1, cell - 2, cell - 2);
            if (i == invCursor) {
                g.setColor(0xFFFF00);
                g.drawRect(cx, cy, cell - 1, cell - 1);
                g.drawRect(cx + 1, cy + 1, cell - 3, cell - 3);
            }
        }
        Tile t = Tile.tiles[palette[invCursor]];
        g.setColor(0xFFFFFF);
        g.drawString(t != null ? t.name : "?", w / 2, oy + gridH + 1,
            Graphics.HCENTER | Graphics.TOP);
    }

    private void drawMenu(Graphics g, int w, int h) {
        g.setColor(0x000000);
        g.fillRect(0, 0, w, h);
        g.setColor(0xFFFFFF);
        g.drawString("Minecraft Classic", w / 2, 8, Graphics.HCENTER | Graphics.TOP);
        drawMenuLine(g, w / 2, 30, menuIndex == MENU_RESUME, "Resume game");
        drawMenuLine(g, w / 2, 44, menuIndex == MENU_SAVE, "Save world");
        drawMenuLine(g, w / 2, 58, menuIndex == MENU_LOAD, "Load world");
        drawMenuLine(g, w / 2, 72, menuIndex == MENU_NEW, "New world");
        drawMenuLine(g, w / 2, 86, menuIndex == MENU_HELP, "Controls help");
        drawMenuLine(g, w / 2, 100, menuIndex == MENU_MULTIPLAYER, "Multiplayer");
        g.drawString("2/8 nav, 5/FIRE select, softkey closes", w / 2, h - 10, Graphics.HCENTER | Graphics.BOTTOM);
    }

    private void drawMenuLine(Graphics g, int x, int y, boolean selected, String text) {
        if (selected) {
            g.setColor(0xFFFF00);
            g.fillRect(8, y - 1, getWidth() - 16, 12);
            g.setColor(0x000000);
            g.drawString(text, x, y, Graphics.HCENTER | Graphics.TOP);
        } else {
            g.setColor(0xFFFFFF);
            g.drawString(text, x, y, Graphics.HCENTER | Graphics.TOP);
        }
    }

    /** A rough representative colour per block for inventory swatches. */
    private int blockColor(int id) {
        switch (id) {
            case 1: return 0x7E7E7E;
            case 2: return 0x6CB143;
            case 3: return 0x9B6B3F;
            case 4: return 0x6E6E6E;
            case 5: return 0xB08A4F;
            case 7: return 0x4A4A4A;
            case 8: case 9: return 0x3A5BD6;
            case 10: case 11: return 0xE5631E;
            case 12: return 0xE0D8A0;
            case 13: return 0x9A938C;
            case 17: return 0x6E5634;
            case 18: return 0x3E7A28;
            case 20: return 0xC8E0F0;
            case 37: return 0xE8E000;
            case 38: return 0xD02020;
            case 44: case 43: case 50: return 0x8A8A8A;
            case 45: return 0x9A4B36;
            case 49: return 0x2A2540;
            case 60: return 0x9FD8F0;
        }
        if (id >= 21 && id <= 36) {
            int[] cloth = {0xCC4444,0xCC8844,0xCCCC44,0x88CC44,0x44CC44,0x44CC88,
                           0x44CCCC,0x4488CC,0x4444CC,0x6644CC,0x8844CC,0xCC44CC,
                           0xCC4488,0x222222,0x888888,0xEEEEEE};
            return cloth[id - 21];
        }
        return 0xAAAAAA;
    }

    private byte[] readResource(String name) throws Exception {
        java.io.InputStream in = getClass().getResourceAsStream(name);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        in.close();
        return out.toByteArray();
    }

    // ---- Input ----
    protected void keyPressed(int kc) { handleKey(kc, true); }
    protected void keyReleased(int kc) { handleKey(kc, false); }

    private void handleKey(int kc, boolean down) {
        if (down && kc == -6) {
            toggleMenu();
            return;
        }
        if (down && kc == -7) {
            toggleInventory();
            return;
        }

        if (showMenu) {
            if (!down) return;
            if (kc == KEY_NUM2 || getGameAction(kc) == UP) {
                menuIndex = (menuIndex + 5) % 6;
                return;
            }
            if (kc == KEY_NUM8 || getGameAction(kc) == DOWN) {
                menuIndex = (menuIndex + 1) % 6;
                return;
            }
            if (kc == KEY_NUM5 || getGameAction(kc) == FIRE || kc == KEY_NUM0) {
                activateMenuItem();
                return;
            }
            return;
        }

        if (showInventory) {
            if (!down) return;
            handleInventoryKey(kc);
            return;
        }

        switch (kc) {
            case KEY_NUM2: kForward = down; break;
            case KEY_NUM8: kBack = down; break;
            case KEY_NUM4: kLeft = down; break;
            case KEY_NUM6: kRight = down; break;
            case KEY_NUM5: kJump = down; break;
            case KEY_NUM1: kTurnLeft = down; break;
            case KEY_NUM3: kTurnRight = down; break;
            case KEY_NUM7: kLookUp = down; break;
            case KEY_NUM9: kLookDown = down; break;
            case KEY_STAR: if (down) breakBlock(); break;
            case KEY_POUND: if (down) placeBlock(); break;
            case KEY_NUM0: if (down) nextBlock(); break;
            default:
                int ga = getGameAction(kc);
                if (ga == UP) kForward = down;
                else if (ga == DOWN) kBack = down;
                else if (ga == LEFT) kTurnLeft = down;
                else if (ga == RIGHT) kTurnRight = down;
                else if (ga == FIRE) { if (down) breakBlock(); }
                break;
        }
    }

    private void handleInventoryKey(int kc) {
        int ga = getGameAction(kc);
        if (kc == KEY_NUM4 || ga == LEFT) { if (invCursor > 0) invCursor--; }
        else if (kc == KEY_NUM6 || ga == RIGHT) { if (invCursor < palette.length - 1) invCursor++; }
        else if (kc == KEY_NUM2 || ga == UP) { if (invCursor >= INV_COLS) invCursor -= INV_COLS; }
        else if (kc == KEY_NUM8 || ga == DOWN) {
            if (invCursor + INV_COLS < palette.length) invCursor += INV_COLS;
        } else if (kc == KEY_NUM5 || ga == FIRE) {
            hotbarIndex = invCursor;
            toggleInventory();
        } else if (kc == KEY_NUM0) {
            toggleInventory();
        }
    }

    private void toggleInventory() {
        showInventory = !showInventory;
        if (showInventory) {
            showMenu = false;
            invCursor = hotbarIndex;
            releaseControls();
        }
    }

    private void toggleMenu() {
        showMenu = !showMenu;
        if (showMenu) {
            showInventory = false;
            menuIndex = MENU_RESUME;
            releaseControls();
        }
    }

    private void activateMenuItem() {
        if (menuIndex == MENU_RESUME) {
            showMenu = false;
        } else if (menuIndex == MENU_SAVE) {
            saveGame();
        } else if (menuIndex == MENU_LOAD) {
            loadGame();
        } else if (menuIndex == MENU_NEW) {
            newWorld();
        } else if (menuIndex == MENU_HELP) {
            hudLine = "2/4/6/8 move, 1/3 turn, 7/9 look, 5 jump, */# break/place";
            hudUntil = System.currentTimeMillis() + 4000;
        } else if (menuIndex == MENU_MULTIPLAYER) {
            showMenu = false;
            startJoin();
        }
    }


    // ================= Multiplayer =================

    private void startJoin() {
        joinStep = 0;
        promptNext();
    }

    private void promptNext() {
        Display d = Display.getDisplay(midlet);
        if (joinStep == 0) {
            joinBox = new TextBox("Server host", serverHost, 64, TextField.ANY);
        } else if (joinStep == 1) {
            joinBox = new TextBox("Server port", String.valueOf(serverPort), 6, TextField.NUMERIC);
        } else {
            joinBox = new TextBox("Username", userName, 16, TextField.ANY);
        }
        joinOk = new Command("OK", Command.OK, 1);
        joinCancel = new Command("Cancel", Command.CANCEL, 1);
        joinBox.addCommand(joinOk);
        joinBox.addCommand(joinCancel);
        joinBox.setCommandListener(this);
        d.setCurrent(joinBox);
    }

    public void commandAction(Command c, Displayable disp) {
        if (c == joinCancel) {
            Display.getDisplay(midlet).setCurrent(this);
            joinBox = null;
            return;
        }
        if (c == joinOk) {
            String v = joinBox.getString();
            if (joinStep == 0) {
                serverHost = v.trim();
                joinStep = 1;
                promptNext();
            } else if (joinStep == 1) {
                try { serverPort = Integer.parseInt(v.trim()); } catch (Throwable t) { serverPort = 25565; }
                joinStep = 2;
                promptNext();
            } else {
                userName = v.trim();
                if (userName.length() == 0) userName = "Steve";
                joinBox = null;
                Display.getDisplay(midlet).setCurrent(this);
                connectToServer();
            }
        }
    }

    private void connectToServer() {
        if (serverHost.length() == 0) {
            toast = "No host given";
            toastUntil = System.currentTimeMillis() + 1500;
            return;
        }
        if (net != null) net.disconnect();
        net = new com.mojang.rubydung.net.NetworkClient(serverHost, serverPort, userName);
        connecting = true;
        multiplayer = true;
        net.start();
    }

    private void tickNetwork() {
        if (net == null) return;
        int st = net.getState();
        if (st == com.mojang.rubydung.net.NetworkClient.ST_ERROR) {
            toast = "Net: " + net.getError();
            toastUntil = System.currentTimeMillis() + 3000;
            net = null;
            multiplayer = false;
            connecting = false;
            return;
        }
        if (connecting && net.isLevelReady()) {
            // swap to the server's world
            level = net.getLevel();
            player = new Player(level);
            renderer = new WorldRenderer(level, g3d, texture);
            hitResult = null;
            connecting = false;
        }
        if (!connecting && st == com.mojang.rubydung.net.NetworkClient.ST_PLAYING) {
            net.sendPosition(player);
        }
    }

    private void drawConnecting(Graphics g, int w, int h) {
        g.setColor(0x000000);
        g.fillRect(0, 0, w, h);
        g.setColor(0xFFFFFF);
        int pct = net != null ? net.getLoadPercent() : 0;
        String line = net == null ? "..." :
            (net.getState() == com.mojang.rubydung.net.NetworkClient.ST_PLAYING || net.isLevelReady()
                ? "Loading map " + pct + "%"
                : "Connecting to " + serverHost + "...");
        g.drawString(line, w / 2, h / 2, Graphics.HCENTER | Graphics.BASELINE);
        if (net != null && net.getServerName().length() > 0) {
            g.drawString(net.getServerName(), w / 2, h / 2 + 14, Graphics.HCENTER | Graphics.BASELINE);
        }
    }

    private void drawChat(Graphics g, int w, int h) {
        String[] lines = net.getChat();
        int y = h - 1;
        for (int i = lines.length - 1; i >= 0; --i) {
            String line = lines[i];
            if (line == null) continue;
            g.setColor(0xFFFFFF);
            g.drawString(line, 2, y, Graphics.LEFT | Graphics.TOP);
            y -= 10;
        }
    }

    private void nextBlock() {
        hotbarIndex = (hotbarIndex + 1) % palette.length;
        toast = nameOf(palette[hotbarIndex]);
        toastUntil = System.currentTimeMillis() + 1200;
    }

    private void breakBlock() {
        if (hitResult == null) return;
        int id = level.getTile(hitResult.x, hitResult.y, hitResult.z);
        if (id == 0) return;
        level.setTile(hitResult.x, hitResult.y, hitResult.z, 0);
        if (net != null) net.sendSetBlock(hitResult.x, hitResult.y, hitResult.z, id, false);
        spawnBreakParticles(hitResult.x, hitResult.y, hitResult.z, id);
        toast = "Removed: " + nameOf(id);
        toastUntil = System.currentTimeMillis() + 1000;
    }

    private void spawnBreakParticles(int bx, int by, int bz, int id) {
        if (particles.size() > 80) return;
        Tile t = Tile.tiles[id];
        int tex = (t != null) ? t.getTexture(1) : 0;
        int n = 20;
        for (int i = 0; i < n; ++i) {
            float px = bx + rng.nextFloat();
            float py = by + rng.nextFloat();
            float pz = bz + rng.nextFloat();
            particles.addElement(new Particle(level, px, py, pz, 0f, 0f, 0f, tex));
        }
    }

    private void placeBlock() {
        if (hitResult == null) return;
        int x = hitResult.x, y = hitResult.y, z = hitResult.z;
        switch (hitResult.f) {
            case 0: --y; break;
            case 1: ++y; break;
            case 2: --z; break;
            case 3: ++z; break;
            case 4: --x; break;
            case 5: ++x; break;
        }
        if (level.getTile(x, y, z) == 0) {
            int block = palette[hotbarIndex];
            level.setTile(x, y, z, block);
            if (net != null) net.sendSetBlock(x, y, z, block, true);
            toast = "Placed: " + nameOf(block);
            toastUntil = System.currentTimeMillis() + 1000;
        }
    }

    private void newWorld() {
        level = new Level(128, 128, 64);
        player = new Player(level);
        renderer = new WorldRenderer(level, g3d, texture);
        hitResult = null;
        particles.removeAllElements();
        showMenu = false;
        toast = "New world";
        toastUntil = System.currentTimeMillis() + 1000;
    }

    private void saveGame() {
        if (level != null) level.save();
        if (player != null) savePlayerState();
        toast = "World saved";
        toastUntil = System.currentTimeMillis() + 1200;
    }

    private void loadGame() {
        if (level != null && level.load()) {
            loadPlayerState();
            renderer = new WorldRenderer(level, g3d, texture);
            toast = "World loaded";
        } else {
            toast = "No saved world";
        }
        toastUntil = System.currentTimeMillis() + 1200;
        showMenu = false;
    }

    private void loadPlayerState() {
        try {
            javax.microedition.rms.RecordStore rs = javax.microedition.rms.RecordStore.openRecordStore("mc_classic_player", false);
            if (rs.getNumRecords() > 0) {
                byte[] data = rs.getRecord(1);
                java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
                player.x = in.readInt() / (float) PLAYER_SAVE_SCALE;
                player.y = in.readInt() / (float) PLAYER_SAVE_SCALE;
                player.z = in.readInt() / (float) PLAYER_SAVE_SCALE;
                player.yRot = in.readInt() / (float) PLAYER_SAVE_SCALE;
                player.xRot = in.readInt() / (float) PLAYER_SAVE_SCALE;
                try {
                    int hp = in.readInt();
                    int air = in.readInt();
                    if (hp < 1) hp = Player.MAX_HEALTH;
                    if (hp > Player.MAX_HEALTH) hp = Player.MAX_HEALTH;
                    if (air < 0) air = 0;
                    if (air > Player.MAX_AIR) air = Player.MAX_AIR;
                    player.health = hp;
                    player.air = air;
                } catch (Throwable ignore) {}
            }
            rs.closeRecordStore();
        } catch (Throwable t) {}
    }

    private void savePlayerState() {
        try {
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.DataOutputStream out = new java.io.DataOutputStream(baos);
            out.writeInt((int) (player.x * PLAYER_SAVE_SCALE));
            out.writeInt((int) (player.y * PLAYER_SAVE_SCALE));
            out.writeInt((int) (player.z * PLAYER_SAVE_SCALE));
            out.writeInt((int) (player.yRot * PLAYER_SAVE_SCALE));
            out.writeInt((int) (player.xRot * PLAYER_SAVE_SCALE));
            out.writeInt(player.health);
            out.writeInt(player.air);
            out.close();
            byte[] data = baos.toByteArray();
            try { javax.microedition.rms.RecordStore.deleteRecordStore("mc_classic_player"); } catch (Throwable t) {}
            javax.microedition.rms.RecordStore rs = javax.microedition.rms.RecordStore.openRecordStore("mc_classic_player", true);
            rs.addRecord(data, 0, data.length);
            rs.closeRecordStore();
        } catch (Throwable t) {}
    }

    private void releaseControls() {
        kForward = kBack = kLeft = kRight = kJump = kReset = false;
        turnYaw = 0;
        turnPitch = 0;
    }

    private String nameOf(int id) {
        Tile t = Tile.tiles[id];
        return t == null ? "?" : t.name;
    }

}


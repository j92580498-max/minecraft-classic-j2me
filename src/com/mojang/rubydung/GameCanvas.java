package com.mojang.rubydung;

import com.mojang.rubydung.level.Level;
import com.mojang.rubydung.level.WorldRenderer;
import com.mojang.rubydung.level.tile.Tile;

import com.mascotcapsule.micro3d.v3.Graphics3D;
import com.mascotcapsule.micro3d.v3.Texture;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;

/**
 * Game loop + input for the J2ME / Mascot Capsule port.
 *
 * Controls (numeric keypad):
 *   2 / 8  move forward / back      4 / 6  strafe left / right
 *   1 / 3  turn left / right        7 / 9  look up / down
 *   5      jump                     *      break block      #  place block
 *   0      next block in hotbar
 *   left soft key (or FIRE long)    open / close inventory
 *
 * In the inventory overlay: 2/4/6/8 move the cursor, 5/FIRE pick a block,
 * 0 or the soft key closes it.
 */
public class GameCanvas extends Canvas implements Runnable {
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
    private String toast;
    private long toastUntil;

    // Input state, polled by Player.tick().
    public boolean kForward, kBack, kLeft, kRight, kJump, kReset;
    private float turnYaw, turnPitch;

    // ---- Inventory: every placeable (non-gas) block id ----
    private int[] palette;
    private int hotbarIndex = 0;   // index into palette currently selected
    private int invCursor = 0;     // cursor while inventory overlay is open
    private static final int INV_COLS = 8;

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
        } catch (Throwable t) {
            t.printStackTrace();
        }
        running = true;
        new Thread(this).start();
    }

    public void stop() {
        running = false;
        if (level != null) level.save();
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
        if (showInventory) return;     // freeze world while choosing a block
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
        hitResult = RayCast.pick(level, player, 4.0f);
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

        g3d.bind(g);
        renderer.render(player, timer.a, w / 2, h / 2);
        g3d.flush();
        g3d.release(g);

        // Crosshair
        g.setColor(0xFFFFFF);
        g.drawLine(w / 2 - 4, h / 2, w / 2 + 4, h / 2);
        g.drawLine(w / 2, h / 2 - 4, w / 2, h / 2 + 4);

        drawHud(g, w, h);
        if (showInventory) drawInventory(g, w, h);
    }

    private void drawHud(Graphics g, int w, int h) {
        // selected block name + fps
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

    /** A rough representative colour per block for inventory swatches. */
    private int blockColor(int id) {
        switch (id) {
            case 1: return 0x7E7E7E;  // stone
            case 2: return 0x6CB143;  // grass
            case 3: return 0x9B6B3F;  // dirt
            case 4: return 0x6E6E6E;  // cobble
            case 5: return 0xB08A4F;  // wood
            case 7: return 0x4A4A4A;  // bedrock
            case 8: case 9: return 0x3A5BD6;  // water
            case 10: case 11: return 0xE5631E; // lava
            case 12: return 0xE0D8A0;  // sand
            case 13: return 0x9A938C;  // gravel
            case 17: return 0x6E5634;  // log
            case 18: return 0x3E7A28;  // leaves
            case 20: return 0xC8E0F0;  // glass
            case 37: return 0xE8E000;  // dandelion
            case 38: return 0xD02020;  // rose
            case 44: case 43: case 50: return 0x8A8A8A; // slabs
            case 45: return 0x9A4B36;  // brick
            case 49: return 0x2A2540;  // obsidian
            case 60: return 0x9FD8F0;  // ice
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
        // Soft keys (-6 left, -7 right on most MIDP phones) toggle inventory.
        if (down && (kc == -6 || kc == -7)) { toggleInventory(); return; }

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
            case KEY_NUM1: if (down) turnYaw -= 12.0f; break;
            case KEY_NUM3: if (down) turnYaw += 12.0f; break;
            case KEY_NUM7: if (down) turnPitch -= 12.0f; break;
            case KEY_NUM9: if (down) turnPitch += 12.0f; break;
            case KEY_STAR: if (down) breakBlock(); break;
            case KEY_POUND: if (down) placeBlock(); break;
            case KEY_NUM0: if (down) nextBlock(); break;
            default:
                int ga = getGameAction(kc);
                if (ga == UP) kForward = down;
                else if (ga == DOWN) kBack = down;
                else if (ga == LEFT) { if (down) turnYaw -= 12.0f; }
                else if (ga == RIGHT) { if (down) turnYaw += 12.0f; }
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
            invCursor = hotbarIndex;
            // release movement keys so we don't keep walking
            kForward = kBack = kLeft = kRight = kJump = false;
        }
    }

    private void nextBlock() {
        hotbarIndex = (hotbarIndex + 1) % palette.length;
        Tile t = Tile.tiles[palette[hotbarIndex]];
        toast = t != null ? t.name : null;
        toastUntil = System.currentTimeMillis() + 1200;
    }

    private void breakBlock() {
        if (hitResult != null) {
            level.setTile(hitResult.x, hitResult.y, hitResult.z, 0);
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
            level.setTile(x, y, z, palette[hotbarIndex]);
        }
    }
}

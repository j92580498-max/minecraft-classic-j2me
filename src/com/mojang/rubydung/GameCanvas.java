package com.mojang.rubydung;

import com.mojang.rubydung.level.Level;
import com.mojang.rubydung.level.WorldRenderer;

import com.mascotcapsule.micro3d.v3.Graphics3D;
import com.mascotcapsule.micro3d.v3.Texture;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Graphics;

/**
 * Game loop + input for the S500i port. Replaces RubyDung's LWJGL
 * Display/Keyboard/Mouse loop with a MIDP Canvas and keypad controls.
 *
 * Controls (numeric keypad):
 *   2 / 8  - move forward / back
 *   4 / 6  - strafe left / right
 *   1 / 3  - turn left / right
 *   5      - jump
 *   7 / 9  - look up / down
 *   *      - break targeted block
 *   #      - place block
 *   0      - save world
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
    private final int bgColor = 0x80B0FF; // sky blue (fog colour of rd-132211)

    // Input state, polled by Player.tick().
    public boolean kForward, kBack, kLeft, kRight, kJump, kReset;
    private float turnYaw, turnPitch;

    public void start() {
        try {
            g3d = new Graphics3D();
            byte[] texBytes = readResource("/terrain.bmp");
            texture = new Texture(texBytes, true);

            level = new Level(64, 64, 64);
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
                frames = 0;
            }
            try { Thread.sleep(20); } catch (InterruptedException e) {}
        }
    }

    private void tick() {
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
        g.setColor(bgColor);
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
    protected void keyPressed(int kc) {
        handleKey(kc, true);
    }

    protected void keyReleased(int kc) {
        handleKey(kc, false);
    }

    private void handleKey(int kc, boolean down) {
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
            case KEY_NUM0: if (down) cycleTile(); break;
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
        level.setTile(x, y, z, placeable[selectedIndex]);
    }

    // Placeable block ids: rock, dirt, stoneBrick, wood, bush.
    private final int[] placeable = new int[] {1, 3, 4, 5, 6};
    private int selectedIndex = 0;

    private void cycleTile() {
        selectedIndex = (selectedIndex + 1) % placeable.length;
    }
}

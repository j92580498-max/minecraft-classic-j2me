package com.mojang.rubydung.level;

import com.mojang.rubydung.HitResult;
import com.mojang.rubydung.Mob;
import com.mojang.rubydung.Particle;
import com.mojang.rubydung.Player;
import com.mojang.rubydung.level.tile.Tile;

import com.mascotcapsule.micro3d.v3.AffineTrans;
import com.mascotcapsule.micro3d.v3.Effect3D;
import com.mascotcapsule.micro3d.v3.FigureLayout;
import com.mascotcapsule.micro3d.v3.Graphics3D;
import com.mascotcapsule.micro3d.v3.Texture;
import com.mascotcapsule.micro3d.v3.Vector3D;

import java.util.Vector;

/**
 * Mascot Capsule Micro3D V3 software renderer for the Classic world.
 *
 * Each frame it walks the blocks in a radius around the player and emits the
 * exposed faces only (neighbour/occlusion culled like ClassiCube), submitting
 * them as textured quads to Graphics3D.renderPrimitives() in batches that
 * respect the engine's < 256 primitives-per-call limit.
 *
 * Supports the full Classic block set:
 *   - opaque full cubes (stone, dirt, ...) with per-face textures
 *   - slabs / snow (variable top height)
 *   - sprites (flowers, saplings, mushrooms, fire) as crossed quads
 *   - transparent (glass, leaves) and translucent (water, ice) blocks,
 *     culled against occluders only so you can see through them
 *
 * Fixed-point: BLOCK view units per world block; ONE == 1.0 for directions.
 */
public class WorldRenderer {
    public static final int ONE = 4096;     // fixed-point 1.0
    public static final int BLOCK = 256;    // view units per world block

    private static final int RADIUS = 14;   // render distance in blocks
    private static final int BATCH = 200;   // max quads per renderPrimitives call
    private static final int TILE = 16;     // atlas tile px (256 / 16)

    private final Level level;
    private final Texture texture;
    private final Texture mobTexture;
    private final Graphics3D g3d;
    private final FigureLayout layout;
    private final Effect3D effectOpaque;
    private final Effect3D effectTrans;
    private final AffineTrans view;
    private float camRightX = 1.0f, camRightZ = 0.0f;

    // Scratch batch buffers (4 verts * 3 coords per quad).
    private final int[] vc = new int[BATCH * 4 * 3];
    private final int[] tc = new int[BATCH * 4 * 2];
    private final int[] col = new int[BATCH];
    private final int[] nrm = new int[] {0, 0, ONE};
    private static final int[] EMPTY_TC = new int[0];
    private int quadCount;
    private int curCommand;
    private boolean useMobTexture = false;

    private final int cmdOpaque =
        Graphics3D.PRIMITVE_QUADS |
        Graphics3D.PDATA_NORMAL_NONE |
        Graphics3D.PDATA_TEXURE_COORD |
        Graphics3D.PDATA_COLOR_PER_FACE |
        Graphics3D.PATTR_BLEND_NORMAL;

    private final int cmdSprite =
        Graphics3D.PRIMITVE_QUADS |
        Graphics3D.PDATA_NORMAL_NONE |
        Graphics3D.PDATA_TEXURE_COORD |
        Graphics3D.PDATA_COLOR_PER_FACE |
        Graphics3D.PATTR_COLORKEY;   // alpha-test for sprites/leaves/glass

    private final int cmdTrans =
        Graphics3D.PRIMITVE_QUADS |
        Graphics3D.PDATA_NORMAL_NONE |
        Graphics3D.PDATA_TEXURE_COORD |
        Graphics3D.PDATA_COLOR_PER_FACE |
        Graphics3D.PATTR_BLEND_HALF;  // 50% blend for water / ice

    // Flat (untextured) per-face colour, used for mob cubes.
    private final int cmdFlat =
        Graphics3D.PRIMITVE_QUADS |
        Graphics3D.PDATA_NORMAL_NONE |
        Graphics3D.PDATA_TEXURE_COORD_NONE |
        Graphics3D.PDATA_COLOR_PER_FACE |
        Graphics3D.PATTR_BLEND_NORMAL;

    public WorldRenderer(Level level, Graphics3D g3d, Texture texture, Texture mobTexture) {
        this.level = level;
        this.g3d = g3d;
        this.texture = texture;
        this.mobTexture = mobTexture;
        this.layout = new FigureLayout();
        this.effectOpaque = new Effect3D(null, Effect3D.NORMAL_SHADING, false, null);
        this.effectTrans = new Effect3D(null, Effect3D.NORMAL_SHADING, true, null);
        this.view = new AffineTrans();
        this.curCommand = cmdOpaque;
    }

    private void setupCamera(Player p, float alpha, int centerX, int centerY) {
        float px = p.xo + (p.x - p.xo) * alpha;
        float py = p.yo + (p.y - p.yo) * alpha;
        float pz = p.zo + (p.z - p.zo) * alpha;

        double yaw = p.yRot * Math.PI / 180.0;
        double pitch = p.xRot * Math.PI / 180.0;
        double cosPitch = Math.cos(pitch);
        double fx = -Math.sin(yaw) * cosPitch;
        double fy = -Math.sin(pitch);
        double fz = Math.cos(yaw) * cosPitch;

        Vector3D pos = new Vector3D((int) (px * BLOCK), (int) (py * BLOCK), (int) (pz * BLOCK));
        Vector3D look = new Vector3D((int) (fx * ONE), (int) (fy * ONE), (int) (fz * ONE));
        Vector3D up = new Vector3D(0, ONE, 0);

        view.lookAt(pos, look, up);
        camRightX = (float) Math.cos(yaw);
        camRightZ = (float) Math.sin(yaw);
        layout.setCenter(centerX, centerY);
        layout.setAffineTrans(view);
        layout.setPerspective(100, 32767, 800);
    }

    public void render(Player p, float alpha, int centerX, int centerY) {
        setupCamera(p, alpha, centerX, centerY);

        int pcx = (int) p.x, pcy = (int) p.y, pcz = (int) p.z;
        int x0 = pcx - RADIUS, x1 = pcx + RADIUS;
        int y0 = pcy - RADIUS, y1 = pcy + RADIUS;
        int z0 = pcz - RADIUS, z1 = pcz + RADIUS;

        // Pass 0: opaque & sprites.  Pass 1: translucent (water/ice) last,
        // so half-blend composites over already-drawn opaque geometry.
        for (int pass = 0; pass < 2; ++pass) {
            beginBatch(pass == 0 ? cmdSprite : cmdTrans);
            for (int x = x0; x <= x1; ++x) {
                for (int y = y0; y <= y1; ++y) {
                    for (int z = z0; z <= z1; ++z) {
                        int id = level.getTile(x, y, z);
                        if (id == 0) continue;
                        Tile tile = Tile.tiles[id];
                        if (tile == null || !tile.isRenderable()) continue;
                        boolean trans = tile.isTranslucent();
                        if (pass == 0 && trans) continue;
                        if (pass == 1 && !trans) continue;
                        if (tile.isSprite()) {
                            if (pass == 0) emitSprite(tile, x, y, z);
                        } else {
                            emitCube(tile, x, y, z);
                        }
                    }
                }
            }
            flushBatch();
        }
    }

    /** Emit the exposed faces of one cube (full or slab), with neighbour cull. */
    private void emitCube(Tile tile, int x, int y, int z) {
        // For transparent/translucent blocks, only cull against full opaque
        // occluders (so glass/water faces between same type still show edges
        // unless the neighbour is fully opaque). Opaque blocks cull against
        // any occluder.
        float th = tile.topHeight();
        boolean sprite = false;

        // bottom (y-1)
        if (!level.occludes(x, y - 1, z)) {
            int b = shade(level.getBrightness(x, y - 1, z) * 0.5f);
            quad(tile.getTexture(0), b,
                x,     y, z + 1,
                x,     y, z,
                x + 1, y, z,
                x + 1, y, z + 1);
        }
        // top (y+1) at slab height
        if (th >= 1.0f ? !level.occludes(x, y + 1, z) : true) {
            int b = shade(level.getBrightness(x, y + 1, z) * 1.0f);
            quad(tile.getTexture(1), b,
                x + 1, y + th, z + 1,
                x + 1, y + th, z,
                x,     y + th, z,
                x,     y + th, z + 1);
        }
        // z-1
        if (!level.occludes(x, y, z - 1)) {
            int b = shade(level.getBrightness(x, y, z - 1) * 0.8f);
            quad(tile.getTexture(2), b,
                x,     y + th, z,
                x + 1, y + th, z,
                x + 1, y,      z,
                x,     y,      z);
        }
        // z+1
        if (!level.occludes(x, y, z + 1)) {
            int b = shade(level.getBrightness(x, y, z + 1) * 0.8f);
            quad(tile.getTexture(3), b,
                x,     y + th, z + 1,
                x,     y,      z + 1,
                x + 1, y,      z + 1,
                x + 1, y + th, z + 1);
        }
        // x-1
        if (!level.occludes(x - 1, y, z)) {
            int b = shade(level.getBrightness(x - 1, y, z) * 0.6f);
            quad(tile.getTexture(4), b,
                x, y + th, z + 1,
                x, y + th, z,
                x, y,      z,
                x, y,      z + 1);
        }
        // x+1
        if (!level.occludes(x + 1, y, z)) {
            int b = shade(level.getBrightness(x + 1, y, z) * 0.6f);
            quad(tile.getTexture(5), b,
                x + 1, y,      z + 1,
                x + 1, y,      z,
                x + 1, y + th, z,
                x + 1, y + th, z + 1);
        }
    }

    /** Emit a sprite block (flower/sapling/mushroom/fire) as two crossed quads. */
    private void emitSprite(Tile tile, int x, int y, int z) {
        int tex = tile.getTexture(1);
        int b = shade(level.getBrightness(x, y, z));
        for (int r = 0; r < 2; ++r) {
            double ang = (double) r * Math.PI / 2.0 + 0.7853981633974483;
            float xa = (float) (Math.sin(ang) * 0.5);
            float za = (float) (Math.cos(ang) * 0.5);
            float bx0 = x + 0.5f - xa, bx1 = x + 0.5f + xa;
            float bz0 = z + 0.5f - za, bz1 = z + 0.5f + za;
            quad(tex, b,
                bx0, y + 1.0f, bz0,
                bx1, y + 1.0f, bz1,
                bx1, y,        bz1,
                bx0, y,        bz0);
        }
    }

    private int shade(float brightness) {
        int v = (int) (brightness * 255.0f);
        if (v < 0) v = 0;
        if (v > 255) v = 255;
        return (v << 16) | (v << 8) | v;
    }

    private void beginBatch(int command) {
        flushBatch();
        curCommand = command;
    }

    private void quad(int tex, int color,
                      float ax, float ay, float az,
                      float bx, float by, float bz,
                      float cx, float cy, float cz,
                      float dx, float dy, float dz) {
        if (quadCount >= BATCH) flushBatch();

        int vi = quadCount * 12;
        vc[vi]      = (int) (ax * BLOCK); vc[vi + 1]  = (int) (ay * BLOCK); vc[vi + 2]  = (int) (az * BLOCK);
        vc[vi + 3]  = (int) (bx * BLOCK); vc[vi + 4]  = (int) (by * BLOCK); vc[vi + 5]  = (int) (bz * BLOCK);
        vc[vi + 6]  = (int) (cx * BLOCK); vc[vi + 7]  = (int) (cy * BLOCK); vc[vi + 8]  = (int) (cz * BLOCK);
        vc[vi + 9]  = (int) (dx * BLOCK); vc[vi + 10] = (int) (dy * BLOCK); vc[vi + 11] = (int) (dz * BLOCK);

        int u0 = (tex % 16) * TILE;
        int v0 = (tex / 16) * TILE;
        int u1 = u0 + TILE;
        int v1 = v0 + TILE;
        int ti = quadCount * 8;
        tc[ti]     = u0; tc[ti + 1] = v1;
        tc[ti + 2] = u0; tc[ti + 3] = v0;
        tc[ti + 4] = u1; tc[ti + 5] = v0;
        tc[ti + 6] = u1; tc[ti + 7] = v1;

        col[quadCount] = color;
        quadCount++;
    }

    private void flushBatch() {
        if (quadCount == 0) return;
        int[] verts = vc, texs = tc, cols = col;
        if (quadCount < BATCH) {
            verts = new int[quadCount * 12];
            texs = new int[quadCount * 8];
            cols = new int[quadCount];
            System.arraycopy(vc, 0, verts, 0, verts.length);
            System.arraycopy(tc, 0, texs, 0, texs.length);
            System.arraycopy(col, 0, cols, 0, cols.length);
        }
        Effect3D fx = (curCommand == cmdTrans) ? effectTrans : effectOpaque;
        int[] texArg = (curCommand == cmdFlat) ? new int[0] : texs;
        Texture activeTex = (useMobTexture && mobTexture != null) ? mobTexture : texture;
        g3d.renderPrimitives(activeTex, 0, 0, layout, fx,
            curCommand, quadCount, verts, nrm, texArg, cols);
        quadCount = 0;
    }

    /**
     * Render each live mob as a small set of flat-shaded boxes (head, body,
     * two arms, two legs) with a simple walk swing. Boxes are built in the
     * mob's local space then rotated by body yaw around its position.
     */
    public void renderMobs(java.util.Vector mobs, float alpha) {
        if (mobs == null || mobs.size() == 0) return;
        // Mobs use the 64x32 character skin atlas, color-keyed (alpha-test)
        // so transparent skin texels drop out like ClassiCube's humanoid.
        useMobTexture = true;
        beginBatch(cmdSprite);
        for (int i = 0; i < mobs.size(); ++i) {
            Mob m = (Mob) mobs.elementAt(i);
            if (m.isDead()) continue;
            renderMob(m, alpha);
        }
        flushBatch();
        useMobTexture = false;
        curCommand = cmdSprite;
    }

    public void renderNetPlayers(com.mojang.rubydung.net.NetPlayer[] players, float alpha) {
        if (players == null) return;
        beginBatch(cmdFlat);
        for (int i = 0; i < players.length; ++i) {
            com.mojang.rubydung.net.NetPlayer p = players[i];
            if (p == null) continue;
            renderNetPlayer(p, alpha);
        }
        flushBatch();
        curCommand = cmdSprite;
    }

    private void renderNetPlayer(com.mojang.rubydung.net.NetPlayer p, float alpha) {
        float mx = p.xo + (p.x - p.xo) * alpha;
        float my = p.yo + (p.y - p.yo) * alpha;
        float mz = p.zo + (p.z - p.zo) * alpha;
        // server feet position; model 1.8 tall
        float feetY = my;
        double yaw = p.yaw * 360.0 / 256.0 * Math.PI / 180.0;
        float sin = (float) Math.sin(yaw);
        float cos = (float) Math.cos(yaw);

        int skin = 0xC8A064;   // head/arms
        int body = 0x5050C0;   // shirt (blue, to distinguish from green zombies)
        int legs = 0x303060;   // trousers

        box(mx, mz, feetY + 0.75f, feetY + 1.5f, 0.25f, 0.125f, 0f, 0f, sin, cos, body);
        box(mx, mz, feetY + 1.5f, feetY + 2.0f, 0.25f, 0.25f, 0f, 0f, sin, cos, skin);
        box(mx, mz, feetY, feetY + 0.75f, 0.1f, 0.1f, -0.125f, 0f, sin, cos, legs);
        box(mx, mz, feetY, feetY + 0.75f, 0.1f, 0.1f, 0.125f, 0f, sin, cos, legs);
        box(mx, mz, feetY + 0.75f, feetY + 1.45f, 0.1f, 0.1f, -0.36f, 0f, sin, cos, skin);
        box(mx, mz, feetY + 0.75f, feetY + 1.45f, 0.1f, 0.1f, 0.36f, 0f, sin, cos, skin);
    }

    private void renderMob(Mob m, float alpha) {
        float mx = m.xo + (m.x - m.xo) * alpha;
        float my = m.yo + (m.y - m.yo) * alpha;
        float mz = m.zo + (m.z - m.zo) * alpha;
        // feet sit at my - 0.9 (H); model is ~1.8 tall.
        float feetY = my - 0.9f;

        double yaw = m.yRot * Math.PI / 180.0;
        float sin = (float) Math.sin(yaw);
        float cos = (float) Math.cos(yaw);

        // limb swing
        if (m.animSpeed > 1.2f) m.animSpeed = 1.2f;
        float swing = (float) Math.sin(m.animPos) * 0.6f * m.animSpeed;
        float swing2 = (float) Math.sin(m.animPos + Math.PI) * 0.6f * m.animSpeed;

        // Hurt flash tints the whole skin red, otherwise full-bright white.
        int tint = m.hurtTime > 0 ? 0xFF6050 : 0xFFFFFF;

        // ClassiCube humanoid skin layout (64x32), scaled to a ~1.8-block model.
        // Pixel boxes (w,h,d) match the vanilla Steve UVs:
        //   head 8x8x8 @tex(0,0); torso 8x12x4 @tex(16,16);
        //   arms 4x12x4 @tex(40,16); legs 4x12x4 @tex(0,16).
        // World half-extents derived from those pixel sizes (1 px = 1/16 block,
        // model is ~2 blocks tall) keep proportions faithful to ClassiCube.

        // Head: 8x8x8 cube centred, sitting on top of the torso (top at 2.0).
        skinBox(mx, mz, feetY + 1.5f, feetY + 2.0f, 0.25f, 0.25f, 0f, 0f, sin, cos,
                tint, 0, 0, 8, 8, 8);
        // Torso: 8x12x4, top at 1.5, bottom at 0.75.
        skinBox(mx, mz, feetY + 0.75f, feetY + 1.5f, 0.25f, 0.125f, 0f, 0f, sin, cos,
                tint, 16, 16, 8, 12, 4);
        // Legs: 4x12x4 each, offset left/right, swinging opposite each other.
        skinBox(mx, mz, feetY, feetY + 0.75f, 0.125f, 0.125f, -0.125f, swing, sin, cos,
                tint, 0, 16, 4, 12, 4);
        skinBox(mx, mz, feetY, feetY + 0.75f, 0.125f, 0.125f, 0.125f, swing2, sin, cos,
                tint, 0, 16, 4, 12, 4);
        // Arms: 4x12x4 each, at shoulder height, swinging opposite the legs.
        skinBox(mx, mz, feetY + 0.75f, feetY + 1.5f, 0.125f, 0.125f, -0.375f, swing2, sin, cos,
                tint, 40, 16, 4, 12, 4);
        skinBox(mx, mz, feetY + 0.75f, feetY + 1.5f, 0.125f, 0.125f, 0.375f, swing, sin, cos,
                tint, 40, 16, 4, 12, 4);
    }

    /**
     * Emit one textured box of a humanoid limb using the 64x32 skin atlas.
     * cx/cz is the mob centre; y0/y1 the world vertical span; hw/hd the world
     * half-width/depth; ox a sideways offset (local X); zswing a forward Z
     * offset for the walk cycle; sin/cos rotate local (X,Z) by body yaw.
     * (tx,ty) is the top-left skin pixel of the box's unwrap; (pw,ph,pd) are
     * the box pixel dimensions, laid out like Minecraft skins:
     *   top/bottom across the top row, then front/right/back/left in a strip.
     */
    private void skinBox(float cx, float cz, float y0, float y1,
                         float hw, float hd, float ox, float zswing,
                         float sin, float cos, int tint,
                         int tx, int ty, int pw, int ph, int pd) {
        float lx0 = ox - hw, lx1 = ox + hw;
        float lz0 = zswing - hd, lz1 = zswing + hd;
        // world corners (after yaw): a=(-,-) b=(+,-) c=(+,+) d=(-,+)
        float ax = cx + lx0 * cos - lz0 * sin, az = cz + lz0 * cos + lx0 * sin;
        float bx = cx + lx1 * cos - lz0 * sin, bz = cz + lz0 * cos + lx1 * sin;
        float cx2 = cx + lx1 * cos - lz1 * sin, cz2 = cz + lz1 * cos + lx1 * sin;
        float dx = cx + lx0 * cos - lz1 * sin, dz = cz + lz1 * cos + lx0 * sin;

        // Skin UV strip (standard Minecraft box unwrap):
        //   row0:  [pd][pw top][pw bottom]
        //   row1:  [pd left][pw front][pd right][pw back]
        int uTopX = tx + pd,           uTopY = ty;
        int uBotX = tx + pd + pw,      uBotY = ty;
        int uFrontX = tx + pd,         uFrontY = ty + pd;
        int uRightX = tx,              uRightY = ty + pd;
        int uBackX = tx + pd + pw + pd, uBackY = ty + pd;
        int uLeftX = tx + pd + pw,     uLeftY = ty + pd;

        // top (y1)
        skinQuad(tint, uTopX, uTopY, pw, pd,
                 ax, y1, az,  bx, y1, bz,  cx2, y1, cz2,  dx, y1, dz);
        // bottom (y0)
        skinQuad(tint, uBotX, uBotY, pw, pd,
                 dx, y0, dz,  cx2, y0, cz2,  bx, y0, bz,  ax, y0, az);
        // front side (a-b)
        skinQuad(tint, uFrontX, uFrontY, pw, ph,
                 ax, y1, az,  bx, y1, bz,  bx, y0, bz,  ax, y0, az);
        // right side (b-c)
        skinQuad(tint, uRightX, uRightY, pd, ph,
                 bx, y1, bz,  cx2, y1, cz2, cx2, y0, cz2, bx, y0, bz);
        // back side (c-d)
        skinQuad(tint, uBackX, uBackY, pw, ph,
                 cx2, y1, cz2, dx, y1, dz,  dx, y0, dz,  cx2, y0, cz2);
        // left side (d-a)
        skinQuad(tint, uLeftX, uLeftY, pd, ph,
                 dx, y1, dz,  ax, y1, az,  ax, y0, az,  dx, y0, dz);
    }

    /** Emit one quad with explicit skin-pixel UV rectangle (top-left + size). */
    private void skinQuad(int color, int ux, int uy, int uw, int uh,
                          float ax, float ay, float az,
                          float bx, float by, float bz,
                          float cx, float cy, float cz,
                          float dx, float dy, float dz) {
        if (quadCount >= BATCH) flushBatch();
        int vi = quadCount * 12;
        vc[vi]      = (int) (ax * BLOCK); vc[vi + 1]  = (int) (ay * BLOCK); vc[vi + 2]  = (int) (az * BLOCK);
        vc[vi + 3]  = (int) (bx * BLOCK); vc[vi + 4]  = (int) (by * BLOCK); vc[vi + 5]  = (int) (bz * BLOCK);
        vc[vi + 6]  = (int) (cx * BLOCK); vc[vi + 7]  = (int) (cy * BLOCK); vc[vi + 8]  = (int) (cz * BLOCK);
        vc[vi + 9]  = (int) (dx * BLOCK); vc[vi + 10] = (int) (dy * BLOCK); vc[vi + 11] = (int) (dz * BLOCK);
        int u0 = ux, v0 = uy, u1 = ux + uw, v1 = uy + uh;
        int ti = quadCount * 8;
        tc[ti]     = u0; tc[ti + 1] = v1;
        tc[ti + 2] = u0; tc[ti + 3] = v0;
        tc[ti + 4] = u1; tc[ti + 5] = v0;
        tc[ti + 6] = u1; tc[ti + 7] = v1;
        col[quadCount] = color;
        quadCount++;
    }

    /**
     * Emit one axis-aligned-in-local-space box for a mob limb. cx/cz is the
     * mob centre; y0/y1 the world vertical span; hw/hd half-width/depth; ox a
     * sideways offset (left/right of body, in local X); zswing a forward Z
     * offset used for the walk cycle. sin/cos rotate local (X,Z) by body yaw.
     */
    private void box(float cx, float cz, float y0, float y1,
                     float hw, float hd, float ox, float zswing,
                     float sin, float cos, int color) {
        // local corners (before yaw): X in [ox-hw, ox+hw], Z in [zswing-hd, zswing+hd]
        float lx0 = ox - hw, lx1 = ox + hw;
        float lz0 = zswing - hd, lz1 = zswing + hd;
        // rotate the four (x,z) corners by yaw and translate to world
        float ax = cx + lx0 * cos - lz0 * sin, az = cz + lz0 * cos + lx0 * sin;
        float bx = cx + lx1 * cos - lz0 * sin, bz = cz + lz0 * cos + lx1 * sin;
        float cx2 = cx + lx1 * cos - lz1 * sin, cz2 = cz + lz1 * cos + lx1 * sin;
        float dx = cx + lx0 * cos - lz1 * sin, dz = cz + lz1 * cos + lx0 * sin;

        int top = color;
        int side = darken(color, 0.8f);
        int bottom = darken(color, 0.6f);

        // top
        flatQuad(top,  ax, y1, az,  bx, y1, bz,  cx2, y1, cz2,  dx, y1, dz);
        // bottom
        flatQuad(bottom, dx, y0, dz,  cx2, y0, cz2,  bx, y0, bz,  ax, y0, az);
        // 4 sides
        flatQuad(side, ax, y1, az,  bx, y1, bz,  bx, y0, bz,  ax, y0, az);
        flatQuad(side, bx, y1, bz,  cx2, y1, cz2, cx2, y0, cz2, bx, y0, bz);
        flatQuad(side, cx2, y1, cz2, dx, y1, dz,  dx, y0, dz,  cx2, y0, cz2);
        flatQuad(side, dx, y1, dz,  ax, y1, az,  ax, y0, az,  dx, y0, dz);
    }

    private int darken(int rgb, float f) {
        int r = (int) (((rgb >> 16) & 0xFF) * f);
        int g = (int) (((rgb >> 8) & 0xFF) * f);
        int b = (int) ((rgb & 0xFF) * f);
        return (r << 16) | (g << 8) | b;
    }

    /** Like quad() but with no texture coords (flat per-face colour). */
    private void flatQuad(int color,
                          float ax, float ay, float az,
                          float bx, float by, float bz,
                          float cx, float cy, float cz,
                          float dx, float dy, float dz) {
        if (quadCount >= BATCH) flushBatch();
        int vi = quadCount * 12;
        vc[vi]      = (int) (ax * BLOCK); vc[vi + 1]  = (int) (ay * BLOCK); vc[vi + 2]  = (int) (az * BLOCK);
        vc[vi + 3]  = (int) (bx * BLOCK); vc[vi + 4]  = (int) (by * BLOCK); vc[vi + 5]  = (int) (bz * BLOCK);
        vc[vi + 6]  = (int) (cx * BLOCK); vc[vi + 7]  = (int) (cy * BLOCK); vc[vi + 8]  = (int) (cz * BLOCK);
        vc[vi + 9]  = (int) (dx * BLOCK); vc[vi + 10] = (int) (dy * BLOCK); vc[vi + 11] = (int) (dz * BLOCK);
        col[quadCount] = color;
        quadCount++;
    }

    public void renderParticles(java.util.Vector particles, Player p, float alpha) {
        if (particles == null || particles.size() == 0) return;
        beginBatch(cmdSprite);
        float rx = camRightX, rz = camRightZ;
        for (int i = 0; i < particles.size(); ++i) {
            Particle pa = (Particle) particles.elementAt(i);
            if (pa.removed) continue;
            float px = pa.xo + (pa.x - pa.xo) * alpha;
            float py = pa.yo + (pa.y - pa.yo) * alpha;
            float pz = pa.zo + (pa.z - pa.zo) * alpha;
            float s = 0.1f;
            // billboard quad facing camera: right vector (rx,0,rz), up (0,1,0)
            float ax = px - rx * s, az = pz - rz * s;
            float bx = px + rx * s, bz = pz + rz * s;
            int b = shade(level.getBrightness((int) px, (int) py, (int) pz));
            // sub-tile UVs sampled from the block atlas tile
            int tile = pa.tex;
            int u0 = (tile % 16) * TILE + (int) (pa.uo * TILE);
            int v0 = (tile / 16) * TILE + (int) (pa.vo * TILE);
            int u1 = u0 + 3, v1 = v0 + 3;
            partQuad(b,
                ax, py + s, az,  bx, py + s, bz,
                bx, py - s, bz,  ax, py - s, az,
                u0, v0, u1, v1);
        }
        flushBatch();
        curCommand = cmdSprite;
    }

    private void partQuad(int color,
                          float ax, float ay, float az,
                          float bx, float by, float bz,
                          float cx, float cy, float cz,
                          float dx, float dy, float dz,
                          int u0, int v0, int u1, int v1) {
        if (quadCount >= BATCH) flushBatch();
        int vi = quadCount * 12;
        vc[vi]      = (int) (ax * BLOCK); vc[vi + 1]  = (int) (ay * BLOCK); vc[vi + 2]  = (int) (az * BLOCK);
        vc[vi + 3]  = (int) (bx * BLOCK); vc[vi + 4]  = (int) (by * BLOCK); vc[vi + 5]  = (int) (bz * BLOCK);
        vc[vi + 6]  = (int) (cx * BLOCK); vc[vi + 7]  = (int) (cy * BLOCK); vc[vi + 8]  = (int) (cz * BLOCK);
        vc[vi + 9]  = (int) (dx * BLOCK); vc[vi + 10] = (int) (dy * BLOCK); vc[vi + 11] = (int) (dz * BLOCK);
        int ti = quadCount * 8;
        tc[ti]     = u0; tc[ti + 1] = v1;
        tc[ti + 2] = u0; tc[ti + 3] = v0;
        tc[ti + 4] = u1; tc[ti + 5] = v0;
        tc[ti + 6] = u1; tc[ti + 7] = v1;
        col[quadCount] = color;
        quadCount++;
    }

    public void renderHit(HitResult h) {
        if (h == null) return;
        // 12 edges of the unit cube at (h.x,h.y,h.z), slightly inflated so
        // the outline sits just outside the block faces (avoids z-fighting).
        float e = 0.003f;
        float x0 = h.x - e, y0 = h.y - e, z0 = h.z - e;
        float x1 = h.x + 1 + e, y1 = h.y + 1 + e, z1 = h.z + 1 + e;

        // 12 segments * 2 verts * 3 coords
        int[] v = new int[12 * 2 * 3];
        int i = 0;
        i = edge(v, i, x0,y0,z0, x1,y0,z0);
        i = edge(v, i, x1,y0,z0, x1,y0,z1);
        i = edge(v, i, x1,y0,z1, x0,y0,z1);
        i = edge(v, i, x0,y0,z1, x0,y0,z0);
        i = edge(v, i, x0,y1,z0, x1,y1,z0);
        i = edge(v, i, x1,y1,z0, x1,y1,z1);
        i = edge(v, i, x1,y1,z1, x0,y1,z1);
        i = edge(v, i, x0,y1,z1, x0,y1,z0);
        i = edge(v, i, x0,y0,z0, x0,y1,z0);
        i = edge(v, i, x1,y0,z0, x1,y1,z0);
        i = edge(v, i, x1,y0,z1, x1,y1,z1);
        i = edge(v, i, x0,y0,z1, x0,y1,z1);

        int[] colors = new int[12];
        for (int c = 0; c < 12; ++c) colors[c] = 0x000000;

        int cmd = Graphics3D.PRIMITVE_LINES |
                  Graphics3D.PDATA_NORMAL_NONE |
                  Graphics3D.PDATA_TEXURE_COORD_NONE |
                  Graphics3D.PDATA_COLOR_PER_FACE |
                  Graphics3D.PATTR_BLEND_NORMAL;
        try {
            g3d.renderPrimitives(texture, 0, 0, layout, effectOpaque,
                cmd, 12, v, nrm, null, colors);
        } catch (Throwable t) {
            // Some devices may not support line primitives; ignore.
        }
    }

    private int edge(int[] v, int i, float ax, float ay, float az,
                                     float bx, float by, float bz) {
        v[i++] = (int) (ax * BLOCK); v[i++] = (int) (ay * BLOCK); v[i++] = (int) (az * BLOCK);
        v[i++] = (int) (bx * BLOCK); v[i++] = (int) (by * BLOCK); v[i++] = (int) (bz * BLOCK);
        return i;
    }
}

package com.mojang.rubydung.level;

import com.mojang.rubydung.HitResult;
import com.mojang.rubydung.Player;
import com.mojang.rubydung.level.tile.Tile;

import com.mascotcapsule.micro3d.v3.AffineTrans;
import com.mascotcapsule.micro3d.v3.Effect3D;
import com.mascotcapsule.micro3d.v3.FigureLayout;
import com.mascotcapsule.micro3d.v3.Graphics3D;
import com.mascotcapsule.micro3d.v3.Texture;
import com.mascotcapsule.micro3d.v3.Vector3D;

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
    private final Graphics3D g3d;
    private final FigureLayout layout;
    private final Effect3D effectOpaque;
    private final Effect3D effectTrans;
    private final AffineTrans view;

    // Scratch batch buffers (4 verts * 3 coords per quad).
    private final int[] vc = new int[BATCH * 4 * 3];
    private final int[] tc = new int[BATCH * 4 * 2];
    private final int[] col = new int[BATCH];
    private final int[] nrm = new int[] {0, 0, ONE};
    private int quadCount;
    private int curCommand;

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

    public WorldRenderer(Level level, Graphics3D g3d, Texture texture) {
        this.level = level;
        this.g3d = g3d;
        this.texture = texture;
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
        g3d.renderPrimitives(texture, 0, 0, layout, fx,
            curCommand, quadCount, verts, nrm, texs, cols);
        quadCount = 0;
    }

    public void renderHit(HitResult h) {
        // Targeted-block highlight intentionally omitted for performance.
    }
}

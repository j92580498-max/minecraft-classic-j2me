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

import javax.microedition.lcdui.Graphics;

/**
 * Replaces RubyDung's LWJGL/OpenGL LevelRenderer with a Mascot Capsule
 * Micro3D V3 software renderer.
 *
 * The world is drawn as a set of textured quads (one per exposed block face).
 * Faces are gathered each frame within a render radius around the player,
 * back-face / neighbour culled exactly like the original Tile.render, and
 * submitted to Graphics3D.renderPrimitives in batches that respect the
 * Micro3D limit of < 256 primitives per call.
 *
 * Fixed-point convention: 4096 == 1.0. One block == ONE unit on screen scaled
 * by BLOCK so the integer view-space coordinates keep good precision.
 */
public class WorldRenderer {
    public static final int ONE = 4096;     // fixed-point 1.0
    public static final int BLOCK = 256;    // view units per world block

    // Render radius (in blocks) around the player. Keeps primitive counts sane
    // on a ~220 MHz ARM9 with no FPU-heavy 3D engine.
    private static final int RADIUS = 12;

    // Max quads per renderPrimitives() call (engine limit is < 256).
    private static final int BATCH = 200;

    private final Level level;
    private final Texture texture;
    private final Graphics3D g3d;
    private final FigureLayout layout;
    private final Effect3D effect;
    private final AffineTrans view;

    // Scratch batch buffers (4 verts * 3 coords per quad).
    private final int[] vc = new int[BATCH * 4 * 3];
    private final int[] tc = new int[BATCH * 4 * 2];
    private final int[] col = new int[BATCH];
    private final int[] nrm = new int[] {0, 0, ONE};
    private int quadCount;

    private final int command =
        Graphics3D.PRIMITVE_QUADS |
        Graphics3D.PDATA_NORMAL_NONE |
        Graphics3D.PDATA_TEXURE_COORD |
        Graphics3D.PDATA_COLOR_PER_FACE |
        Graphics3D.PATTR_BLEND_NORMAL;

    // Texture tile size inside terrain.png (256px / 16 tiles).
    private static final int TILE = 16;

    public WorldRenderer(Level level, Graphics3D g3d, Texture texture) {
        this.level = level;
        this.g3d = g3d;
        this.texture = texture;
        this.layout = new FigureLayout();
        this.effect = new Effect3D(null, Effect3D.NORMAL_SHADING, false, null);
        this.view = new AffineTrans();
    }

    /** Build the camera (view) transform from the player's interpolated pose. */
    private void setupCamera(Player p, float alpha, int centerX, int centerY) {
        float px = p.xo + (p.x - p.xo) * alpha;
        float py = p.yo + (p.y - p.yo) * alpha;
        float pz = p.zo + (p.z - p.zo) * alpha;

        double yaw = p.yRot * Math.PI / 180.0;
        double pitch = p.xRot * Math.PI / 180.0;

        // Forward direction from yaw/pitch (RubyDung look convention).
        double cosPitch = Math.cos(pitch);
        double fx = -Math.sin(yaw) * cosPitch;
        double fy = -Math.sin(pitch);
        double fz = Math.cos(yaw) * cosPitch;

        int camX = (int) (px * BLOCK);
        int camY = (int) (py * BLOCK);
        int camZ = (int) (pz * BLOCK);

        Vector3D pos = new Vector3D(camX, camY, camZ);
        Vector3D look = new Vector3D(
            (int) (fx * ONE),
            (int) (fy * ONE),
            (int) (fz * ONE));
        Vector3D up = new Vector3D(0, ONE, 0);

        view.lookAt(pos, look, up);

        layout.setCenter(centerX, centerY);
        layout.setAffineTrans(view);
        layout.setPerspective(100, 32767, 800);
    }

    public void render(Player p, float alpha, int centerX, int centerY) {
        setupCamera(p, alpha, centerX, centerY);
        quadCount = 0;

        int pcx = (int) p.x;
        int pcy = (int) p.y;
        int pcz = (int) p.z;

        int x0 = pcx - RADIUS, x1 = pcx + RADIUS;
        int y0 = pcy - RADIUS, y1 = pcy + RADIUS;
        int z0 = pcz - RADIUS, z1 = pcz + RADIUS;

        for (int x = x0; x <= x1; ++x) {
            for (int y = y0; y <= y1; ++y) {
                for (int z = z0; z <= z1; ++z) {
                    int id = level.getTile(x, y, z);
                    if (id == 0) continue;
                    Tile tile = Tile.tiles[id];
                    if (tile == null) continue;
                    if (tile.isSolid()) {
                        emitTile(tile, x, y, z);
                    } else {
                        emitBush(tile, x, y, z);
                    }
                }
            }
        }
        flushBatch();
    }

    /** Emit the exposed faces of one solid block, mirroring Tile.render culling. */
    private void emitTile(Tile tile, int x, int y, int z) {
        float c1 = 1.0f, c2 = 0.8f, c3 = 0.6f;

        // bottom (y-1)
        if (!level.isSolidTile(x, y - 1, z)) {
            int b = shade(level.getBrightness(x, y - 1, z) * c1);
            quad(tile.getTexture(0), b,
                x,     y, z + 1,
                x,     y, z,
                x + 1, y, z,
                x + 1, y, z + 1);
        }
        // top (y+1)
        if (!level.isSolidTile(x, y + 1, z)) {
            int b = shade(level.getBrightness(x, y + 1, z) * c1);
            quad(tile.getTexture(1), b,
                x + 1, y + 1, z + 1,
                x + 1, y + 1, z,
                x,     y + 1, z,
                x,     y + 1, z + 1);
        }
        // z-1
        if (!level.isSolidTile(x, y, z - 1)) {
            int b = shade(level.getBrightness(x, y, z - 1) * c2);
            quad(tile.getTexture(2), b,
                x,     y + 1, z,
                x + 1, y + 1, z,
                x + 1, y,     z,
                x,     y,     z);
        }
        // z+1
        if (!level.isSolidTile(x, y, z + 1)) {
            int b = shade(level.getBrightness(x, y, z + 1) * c2);
            quad(tile.getTexture(3), b,
                x,     y + 1, z + 1,
                x,     y,     z + 1,
                x + 1, y,     z + 1,
                x + 1, y + 1, z + 1);
        }
        // x-1
        if (!level.isSolidTile(x - 1, y, z)) {
            int b = shade(level.getBrightness(x - 1, y, z) * c3);
            quad(tile.getTexture(4), b,
                x, y + 1, z + 1,
                x, y + 1, z,
                x, y,     z,
                x, y,     z + 1);
        }
        // x+1
        if (!level.isSolidTile(x + 1, y, z)) {
            int b = shade(level.getBrightness(x + 1, y, z) * c3);
            quad(tile.getTexture(5), b,
                x + 1, y,     z + 1,
                x + 1, y,     z,
                x + 1, y + 1, z,
                x + 1, y + 1, z + 1);
        }
    }

    /**
     * Emit a non-solid plant tile (bush) as two crossed quads, mirroring
     * Classic 0.0.11a's Bush.render. Uses fixed-point world coords directly.
     */
    private void emitBush(Tile tile, int x, int y, int z) {
        int tex = tile.getTexture(15);
        int b = shade(level.getBrightness(x, y, z));
        int rots = 2;
        for (int r = 0; r < rots; ++r) {
            double ang = (double) r * Math.PI / (double) rots + 0.7853981633974483;
            float xa = (float) (Math.sin(ang) * 0.5);
            float za = (float) (Math.cos(ang) * 0.5);
            float bx0 = x + 0.5f - xa;
            float bx1 = x + 0.5f + xa;
            float by0 = y;
            float by1 = y + 1.0f;
            float bz0 = z + 0.5f - za;
            float bz1 = z + 0.5f + za;
            quadF(tex, b,
                bx0, by1, bz0,
                bx1, by1, bz1,
                bx1, by0, bz1,
                bx0, by0, bz0);
        }
    }

    private int shade(float brightness) {
        int v = (int) (brightness * 255.0f);
        if (v < 0) v = 0;
        if (v > 255) v = 255;
        return (v << 16) | (v << 8) | v;
    }

    private void quad(int tex, int color,
                      int ax, int ay, int az,
                      int bx, int by, int bz,
                      int cx, int cy, int cz,
                      int dx, int dy, int dz) {
        quadF(tex, color, ax, ay, az, bx, by, bz, cx, cy, cz, dx, dy, dz);
    }

    private void quadF(int tex, int color,
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

        // Texture tile origin inside the 16x16-tile, 256px atlas.
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
        int[] verts = vc;
        int[] texs = tc;
        int[] cols = col;
        if (quadCount < BATCH) {
            verts = new int[quadCount * 12];
            texs = new int[quadCount * 8];
            cols = new int[quadCount];
            System.arraycopy(vc, 0, verts, 0, verts.length);
            System.arraycopy(tc, 0, texs, 0, texs.length);
            System.arraycopy(col, 0, cols, 0, cols.length);
        }
        g3d.renderPrimitives(texture, 0, 0, layout, effect,
            command, quadCount, verts, nrm, texs, cols);
        quadCount = 0;
    }

    /** Highlight the targeted block face (semi-transparent overlay). */
    public void renderHit(HitResult h) {
        // Optional: a subtle wireframe-ish marker could be added here.
        // Left minimal for performance on the S500i.
    }
}

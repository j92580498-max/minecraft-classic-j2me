package com.mojang.rubydung.level;

import com.mojang.rubydung.level.tile.Tile;

import java.util.Random;

/**
 * Terrain generator ported verbatim from Minecraft Classic 0.0.11a
 * (com.mojang.minecraft.level.LevelGen): two height fields blended by a
 * "cliff" field, a rock layer, then carved caves.
 */
public class LevelGen {
    private int width;
    private int height;
    private int depth;
    private Random random = new Random();

    public LevelGen(int width, int height, int depth) {
        this.width = width;
        this.height = height;
        this.depth = depth;
    }

    public byte[] generateMap() {
        int w = this.width;
        int h = this.height;
        int d = this.depth;
        int[] heightmap1 = new NoiseMap(0).read(w, h);
        int[] heightmap2 = new NoiseMap(0).read(w, h);
        int[] cf = new NoiseMap(1).read(w, h);
        int[] rockMap = new NoiseMap(1).read(w, h);
        byte[] blocks = new byte[w * h * d];
        for (int x = 0; x < w; ++x) {
            for (int y = 0; y < d; ++y) {
                for (int z = 0; z < h; ++z) {
                    int dh1 = heightmap1[x + z * this.width];
                    int dh2 = heightmap2[x + z * this.width];
                    int cfh = cf[x + z * this.width];
                    if (cfh < 128) {
                        dh2 = dh1;
                    }
                    int dh = dh1;
                    if (dh2 > dh) {
                        dh = dh2;
                    } else {
                        dh2 = dh1;
                    }
                    dh = dh / 8 + d / 3;
                    int rh = rockMap[x + z * this.width] / 8 + d / 3;
                    if (rh > dh - 2) {
                        rh = dh - 2;
                    }
                    int i = (y * this.height + z) * this.width + x;
                    int id = 0;
                    if (y == dh) {
                        id = Tile.grass.id;
                    }
                    if (y < dh) {
                        id = Tile.dirt.id;
                    }
                    if (y <= rh) {
                        id = Tile.rock.id;
                    }
                    blocks[i] = (byte) id;
                }
            }
        }
        int count = w * h * d / 256 / 64;
        for (int i = 0; i < count; ++i) {
            float x2 = this.random.nextFloat() * (float) w;
            float y = this.random.nextFloat() * (float) d;
            float z = this.random.nextFloat() * (float) h;
            int length = (int) (this.random.nextFloat() + this.random.nextFloat() * 150.0f);
            float dir1 = (float) ((double) this.random.nextFloat() * Math.PI * 2.0);
            float dira1 = 0.0f;
            float dir2 = (float) ((double) this.random.nextFloat() * Math.PI * 2.0);
            float dira2 = 0.0f;
            for (int l = 0; l < length; ++l) {
                x2 = (float) ((double) x2 + Math.sin(dir1) * Math.cos(dir2));
                z = (float) ((double) z + Math.cos(dir1) * Math.cos(dir2));
                y = (float) ((double) y + Math.sin(dir2));
                dir1 += dira1 * 0.2f;
                dira1 *= 0.9f;
                dira1 += this.random.nextFloat() - this.random.nextFloat();
                dir2 += dira2 * 0.5f;
                dir2 *= 0.5f;
                dira2 *= 0.9f;
                dira2 += this.random.nextFloat() - this.random.nextFloat();
                float size = (float) (Math.sin((double) l * Math.PI / (double) length) * 2.5 + 1.0);
                for (int xx = (int) (x2 - size); xx <= (int) (x2 + size); ++xx) {
                    for (int yy = (int) (y - size); yy <= (int) (y + size); ++yy) {
                        for (int zz = (int) (z - size); zz <= (int) (z + size); ++zz) {
                            float xd = (float) xx - x2;
                            float yd = (float) yy - y;
                            float zd = (float) zz - z;
                            float dd = xd * xd + yd * yd * 2.0f + zd * zd;
                            if (dd < size * size
                                    && xx >= 1 && yy >= 1 && zz >= 1
                                    && xx < this.width - 1 && yy < this.depth - 1 && zz < this.height - 1) {
                                int ii = (yy * this.height + zz) * this.width + xx;
                                if (blocks[ii] == Tile.rock.id) {
                                    blocks[ii] = 0;
                                }
                            }
                        }
                    }
                }
            }
        }
        return blocks;
    }
}

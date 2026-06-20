package com.mojang.rubydung.level;

import com.mojang.rubydung.level.tile.Tile;

import java.util.Random;

/**
 * Terrain generator. Based on Minecraft Classic 0.0.11a's LevelGen (two height
 * fields blended by a "cliff" field, a rock layer, then carved caves), extended
 * toward ClassiCube's classic generator with a sea level (water), sand beaches,
 * gravel, and scattered trees + flowers.
 */
public class LevelGen {
    private int width;
    private int height;
    private int depth;
    private int waterLevel;
    private Random random = new Random();

    public LevelGen(int width, int height, int depth) {
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.waterLevel = depth / 2 + 5;
    }

    private int idx(int x, int y, int z) {
        return (y * this.height + z) * this.width + x;
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

        int[] surface = new int[w * h];

        for (int x = 0; x < w; ++x) {
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
                surface[x + z * this.width] = dh;

                boolean nearWater = dh <= this.waterLevel + 1;

                for (int y = 0; y < d; ++y) {
                    int id = 0;
                    if (y == dh) {
                        id = nearWater ? Tile.sand.id : Tile.grass.id;
                    } else if (y < dh) {
                        if (y <= rh) {
                            id = Tile.rock.id;
                        } else if (nearWater && y >= dh - 2) {
                            id = Tile.sand.id;
                        } else {
                            id = Tile.dirt.id;
                        }
                    }
                    // Fill open air below sea level with water.
                    if (id == 0 && y <= this.waterLevel) {
                        id = Tile.stillWater.id;
                    }
                    // Bedrock floor.
                    if (y == 0) {
                        id = Tile.bedrock.id;
                    }
                    blocks[idx(x, y, z)] = (byte) id;
                }
            }
        }

        carveCaves(blocks, w, h, d);
        decorateOres(blocks, w, h, d);
        plantTrees(blocks, surface, w, h, d);
        plantFlowers(blocks, surface, w, h, d);
        return blocks;
    }

    private void carveCaves(byte[] blocks, int w, int h, int d) {
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
                                int ii = idx(xx, yy, zz);
                                if (blocks[ii] == Tile.rock.id) {
                                    blocks[ii] = 0;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /** Scatter coal/iron/gold ore veins through the rock layer. */
    private void decorateOres(byte[] blocks, int w, int h, int d) {
        int[] ores = new int[] {16, 15, 14}; // coal, iron, gold ids
        int[] amounts = new int[] {w * h * d / 1800, w * h * d / 2600, w * h * d / 4000};
        for (int o = 0; o < ores.length; ++o) {
            for (int i = 0; i < amounts[o]; ++i) {
                int x = this.random.nextInt(w);
                int y = this.random.nextInt(d);
                int z = this.random.nextInt(h);
                int vein = 2 + this.random.nextInt(4);
                for (int v = 0; v < vein; ++v) {
                    int xx = x + this.random.nextInt(3) - 1;
                    int yy = y + this.random.nextInt(3) - 1;
                    int zz = z + this.random.nextInt(3) - 1;
                    if (xx < 0 || yy < 1 || zz < 0 || xx >= w || yy >= d || zz >= h) continue;
                    int ii = idx(xx, yy, zz);
                    if (blocks[ii] == Tile.rock.id) {
                        blocks[ii] = (byte) ores[o];
                    }
                }
            }
        }
    }

    private void plantTrees(byte[] blocks, int[] surface, int w, int h, int d) {
        int attempts = w * h / 64;
        for (int i = 0; i < attempts; ++i) {
            int x = 2 + this.random.nextInt(w - 4);
            int z = 2 + this.random.nextInt(h - 4);
            int gy = surface[x + z * this.width];
            if (gy <= this.waterLevel + 1 || gy + 6 >= d) continue;
            if (blocks[idx(x, gy, z)] != Tile.grass.id) continue;

            int trunk = 4 + this.random.nextInt(2);
            // leaves: two full 5x5 layers, then a 3x3 cap
            int top = gy + trunk;
            for (int yy = top - 2; yy <= top + 1; ++yy) {
                int rad = (yy <= top) ? 2 : 1;
                for (int xx = x - rad; xx <= x + rad; ++xx) {
                    for (int zz = z - rad; zz <= z + rad; ++zz) {
                        if (xx < 0 || zz < 0 || xx >= w || zz >= h || yy >= d) continue;
                        // skip the very corners of the widest layers
                        if (rad == 2 && Math.abs(xx - x) == 2 && Math.abs(zz - z) == 2
                                && yy < top) continue;
                        int ii = idx(xx, yy, zz);
                        if (blocks[ii] == 0) blocks[ii] = (byte) Tile.leaves.id;
                    }
                }
            }
            for (int t = 0; t < trunk; ++t) {
                blocks[idx(x, gy + t, z)] = (byte) Tile.log.id;
            }
        }
    }

    private void plantFlowers(byte[] blocks, int[] surface, int w, int h, int d) {
        int[] plants = new int[] {Tile.dandelion.id, Tile.rose.id,
                                  Tile.brownMushroom.id, Tile.redMushroom.id};
        int attempts = w * h / 32;
        for (int i = 0; i < attempts; ++i) {
            int x = this.random.nextInt(w);
            int z = this.random.nextInt(h);
            int gy = surface[x + z * this.width];
            if (gy + 1 >= d) continue;
            int below = blocks[idx(x, gy, z)] & 0xFF;
            int here = idx(x, gy + 1, z);
            if (below == Tile.grass.id && blocks[here] == 0) {
                blocks[here] = (byte) plants[this.random.nextInt(plants.length)];
            }
        }
    }
}

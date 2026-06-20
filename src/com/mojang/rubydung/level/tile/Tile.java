package com.mojang.rubydung.level.tile;

import com.mojang.rubydung.level.Level;
import com.mojang.rubydung.phys.AABB;

import java.util.Random;

public class Tile {
    public static final Tile[] tiles = new Tile[256];
    public static final Tile empty = null;
    public static final Tile rock = new Tile(1, 1);
    public static final Tile grass = new GrassTile(2);
    public static final Tile dirt = new DirtTile(3, 2);
    public static final Tile stoneBrick = new Tile(4, 16);
    public static final Tile wood = new Tile(5, 4);
    public static final Tile bush = new Bush(6);

    public final int id;
    public int tex;

    protected Tile(int id) {
        this(id, 0);
    }

    protected Tile(int id, int tex) {
        this.id = id;
        this.tex = tex;
        tiles[id] = this;
    }

    public int getTex() {
        return this.tex;
    }

    public int getTexture(int face) {
        return this.tex;
    }

    public boolean blocksLight() {
        return true;
    }

    public boolean isSolid() {
        return true;
    }

    public AABB getAABB(int x, int y, int z) {
        return new AABB(x, y, z, x + 1, y + 1, z + 1);
    }

    public void tick(Level level, int x, int y, int z, Random random) {
    }

    public void destroy(Level level, int x, int y, int z, Object particleEngine) {
    }
}

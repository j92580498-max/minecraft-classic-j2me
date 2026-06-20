package com.mojang.rubydung.level.tile;

import com.mojang.rubydung.level.Level;
import com.mojang.rubydung.phys.AABB;

import java.util.Random;

public class Bush extends Tile {
    protected Bush(int id) {
        super(id);
        this.tex = 15;
    }

    public void tick(Level level, int x, int y, int z, Random random) {
        int below = level.getTile(x, y - 1, z);
        if (!level.isLit(x, y, z) || (below != Tile.dirt.id && below != Tile.grass.id)) {
            level.setTile(x, y, z, 0);
        }
    }

    public AABB getAABB(int x, int y, int z) {
        return null;
    }

    public boolean blocksLight() {
        return false;
    }

    public boolean isSolid() {
        return false;
    }
}

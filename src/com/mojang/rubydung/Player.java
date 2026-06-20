package com.mojang.rubydung;

import com.mojang.rubydung.level.Level;
import com.mojang.rubydung.phys.AABB;

import java.util.Random;
import java.util.Vector;

public class Player {
    private static final Random random = new Random();
    private Level level;
    public float xo, yo, zo;
    public float x, y, z;
    public float xd, yd, zd;
    public float yRot;
    public float xRot;
    public AABB bb;
    public boolean onGround = false;

    // Input state, driven by the Canvas key handler.
    public boolean kForward, kBack, kLeft, kRight, kJump, kReset;

    public Player(Level level) {
        this.level = level;
        resetPos();
    }

    public void resetPos() {
        float x = random.nextFloat() * this.level.width;
        float z = random.nextFloat() * this.level.height;
        float y = this.level.depth * 2.0f / 3.0f + 4.0f;
        setPos(x, y, z);
    }

    private void setPos(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        float w = 0.3f;
        float h = 0.9f;
        this.bb = new AABB(x - w, y - h, z - w, x + w, y + h, z + w);
    }

    public void turn(float xo, float yo) {
        this.yRot = (float) ((double) this.yRot + (double) xo * 0.15);
        this.xRot = (float) ((double) this.xRot - (double) yo * 0.15);
        if (this.xRot < -90.0f) this.xRot = -90.0f;
        if (this.xRot > 90.0f) this.xRot = 90.0f;
    }

    public void tick() {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        float xa = 0.0f;
        float ya = 0.0f;
        if (kReset) {
            resetPos();
        }
        if (kForward) ya -= 1.0f;
        if (kBack) ya += 1.0f;
        if (kLeft) xa -= 1.0f;
        if (kRight) xa += 1.0f;
        if (kJump && this.onGround) {
            this.yd = 0.12f;
        }
        moveRelative(xa, ya, this.onGround ? 0.02f : 0.005f);
        this.yd = (float) ((double) this.yd - 0.005);
        move(this.xd, this.yd, this.zd);
        this.xd *= 0.91f;
        this.yd *= 0.98f;
        this.zd *= 0.91f;
        if (this.onGround) {
            this.xd *= 0.8f;
            this.zd *= 0.8f;
        }
    }

    public void move(float xa, float ya, float za) {
        float xaOrg = xa;
        float yaOrg = ya;
        float zaOrg = za;
        Vector aABBs = this.level.getCubes(this.bb.expand(xa, ya, za));
        for (int i = 0; i < aABBs.size(); ++i) {
            ya = ((AABB) aABBs.elementAt(i)).clipYCollide(this.bb, ya);
        }
        this.bb.move(0.0f, ya, 0.0f);
        for (int i = 0; i < aABBs.size(); ++i) {
            xa = ((AABB) aABBs.elementAt(i)).clipXCollide(this.bb, xa);
        }
        this.bb.move(xa, 0.0f, 0.0f);
        for (int i = 0; i < aABBs.size(); ++i) {
            za = ((AABB) aABBs.elementAt(i)).clipZCollide(this.bb, za);
        }
        this.bb.move(0.0f, 0.0f, za);
        this.onGround = yaOrg != ya && yaOrg < 0.0f;
        if (xaOrg != xa) this.xd = 0.0f;
        if (yaOrg != ya) this.yd = 0.0f;
        if (zaOrg != za) this.zd = 0.0f;
        this.x = (this.bb.x0 + this.bb.x1) / 2.0f;
        this.y = this.bb.y0 + 1.62f;
        this.z = (this.bb.z0 + this.bb.z1) / 2.0f;
    }

    public void moveRelative(float xa, float za, float speed) {
        float dist = xa * xa + za * za;
        if (dist < 0.01f) return;
        dist = speed / (float) Math.sqrt(dist);
        xa *= dist;
        za *= dist;
        float sin = (float) Math.sin((double) this.yRot * Math.PI / 180.0);
        float cos = (float) Math.cos((double) this.yRot * Math.PI / 180.0);
        this.xd += xa * cos - za * sin;
        this.zd += za * cos + xa * sin;
    }
}

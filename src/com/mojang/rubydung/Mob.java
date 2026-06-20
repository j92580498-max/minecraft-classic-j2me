package com.mojang.rubydung;

import com.mojang.rubydung.level.Level;
import com.mojang.rubydung.phys.AABB;

import java.util.Random;
import java.util.Vector;

/**
 * Minimal mob (zombie) ported from Classic 0.0.11a.
 *
 * Keeps a humanoid AABB, walks with the same collision/gravity rules as the
 * Player, and uses simple Classic-style wander AI: it picks a random heading,
 * occasionally jumps, and drifts toward that heading. When the player is close
 * it turns to face them (basic chase). Rendered by WorldRenderer as flat-shaded
 * cubes (head / body / arms / legs) so it needs no extra texture.
 */
public class Mob {
    private static final Random random = new Random();

    public final Level level;
    public float xo, yo, zo;
    public float x, y, z;
    public float xd, yd, zd;
    public float yRot;       // body yaw (degrees)
    public float xRot;       // head pitch
    public AABB bb;
    public boolean onGround = false;
    public boolean removed = false;

    // animation
    public float animPos = 0.0f;     // walk cycle phase
    public float animSpeed = 0.0f;   // current limb-swing amplitude
    public float animSpeedO = 0.0f;
    public float tilt = 0.0f;        // hurt tilt
    public int health = 10;
    public int hurtTime = 0;
    public int deathTime = 0;

    // AI
    private float moveYaw;           // desired heading
    private float forward;           // -1..1 desired forward input
    private int idleTime = 0;

    // size: classic zombie is ~0.6 wide, 1.8 tall
    private static final float W = 0.3f;
    private static final float H = 0.9f;

    public Mob(Level level, float x, float y, float z) {
        this.level = level;
        setPos(x, y, z);
        this.moveYaw = random.nextFloat() * 360.0f;
        this.yRot = this.moveYaw;
        this.health = 10;
    }

    private void setPos(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.bb = new AABB(x - W, y - H, z - W, x + W, y + H, z + W);
    }

    /** Player position is passed so the mob can decide whether to chase. */
    public void tick(Player target) {
        this.xo = this.x;
        this.yo = this.y;
        this.zo = this.z;
        this.animSpeedO = this.animSpeed;

        if (this.hurtTime > 0) --this.hurtTime;
        if (this.health <= 0) {
            ++this.deathTime;
            if (this.deathTime > 20) this.removed = true;
            return;
        }

        if (this.y < -64.0f) {
            this.removed = true;       // fell out of the world
            return;
        }

        // --- AI: pick a new heading every so often, chase if player near ---
        --this.idleTime;
        boolean chasing = false;
        if (target != null) {
            float dx = target.x - this.x;
            float dz = target.z - this.z;
            float dist2 = dx * dx + dz * dz;
            if (dist2 < 36.0f) {     // only within ~6 blocks lean toward player
                this.moveYaw = (float) (atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                this.forward = 1.0f;
                chasing = true;
            }
        }
        if (!chasing) {
            if (this.idleTime <= 0 || random.nextInt(100) == 0) {
                this.idleTime = 40 + random.nextInt(80);
                this.moveYaw = random.nextFloat() * 360.0f;
                this.forward = random.nextInt(4) == 0 ? 0.0f : 1.0f;
            }
        }

        // turn body toward desired heading
        float dyaw = this.moveYaw - this.yRot;
        while (dyaw < -180.0f) dyaw += 360.0f;
        while (dyaw > 180.0f) dyaw -= 360.0f;
        if (dyaw > 10.0f) dyaw = 10.0f;
        if (dyaw < -10.0f) dyaw = -10.0f;
        this.yRot += dyaw;

        // random jump
        boolean jump = this.onGround && (random.nextInt(50) == 0 || (chasing && random.nextInt(40) == 0));
        if (jump) this.yd = 0.12f;

        moveRelative(0.0f, this.forward, this.onGround ? 0.02f : 0.005f);
        this.yd = (float) (this.yd - 0.005);
        move(this.xd, this.yd, this.zd);
        this.xd *= 0.91f;
        this.yd *= 0.98f;
        this.zd *= 0.91f;
        if (this.onGround) {
            this.xd *= 0.8f;
            this.zd *= 0.8f;
        }

        // walk animation
        float dx = this.x - this.xo;
        float dz = this.z - this.zo;
        float speed = (float) Math.sqrt(dx * dx + dz * dz);
        this.animSpeed += (speed * 4.0f - this.animSpeed) * 0.4f;
        this.animPos += this.animSpeed;
    }

    public void hurt(int dmg) {
        if (this.health <= 0) return;
        this.health -= dmg;
        this.hurtTime = 10;
        this.yd = 0.10f;     // small knock-up
        if (this.health <= 0) this.deathTime = 0;
    }

    public boolean isDead() {
        return this.removed;
    }

    private void move(float xa, float ya, float za) {
        float yaOrg = ya;
        Vector aABBs = this.level.getCubes(this.bb.expand(xa, ya, za));
        for (int i = 0; i < aABBs.size(); ++i) {
            ya = ((AABB) aABBs.elementAt(i)).clipYCollide(this.bb, ya);
        }
        this.bb.move(0.0f, ya, 0.0f);
        float xaOrg = xa;
        for (int i = 0; i < aABBs.size(); ++i) {
            xa = ((AABB) aABBs.elementAt(i)).clipXCollide(this.bb, xa);
        }
        this.bb.move(xa, 0.0f, 0.0f);
        float zaOrg = za;
        for (int i = 0; i < aABBs.size(); ++i) {
            za = ((AABB) aABBs.elementAt(i)).clipZCollide(this.bb, za);
        }
        this.bb.move(0.0f, 0.0f, za);
        this.onGround = yaOrg != ya && yaOrg < 0.0f;
        if (xaOrg != xa) this.xd = 0.0f;
        if (yaOrg != ya) this.yd = 0.0f;
        if (zaOrg != za) this.zd = 0.0f;
        this.x = (this.bb.x0 + this.bb.x1) / 2.0f;
        this.y = this.bb.y0 + H;
        this.z = (this.bb.z0 + this.bb.z1) / 2.0f;
    }

    private void moveRelative(float xa, float za, float speed) {
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

    /** CLDC 1.1 has no Math.atan2; this is a compact polynomial approximation. */
    static double atan2(double y, double x) {
        if (x == 0.0) {
            if (y > 0.0) return Math.PI / 2.0;
            if (y < 0.0) return -Math.PI / 2.0;
            return 0.0;
        }
        double atan;
        double z = y / x;
        if (z < 0.0 ? -z < 1.0 : z < 1.0) {
            atan = z / (1.0 + 0.28 * z * z);
            if (x < 0.0) {
                if (y < 0.0) return atan - Math.PI;
                return atan + Math.PI;
            }
        } else {
            atan = Math.PI / 2.0 - z / (z * z + 0.28);
            if (y < 0.0) return atan - Math.PI;
        }
        return atan;
    }
}

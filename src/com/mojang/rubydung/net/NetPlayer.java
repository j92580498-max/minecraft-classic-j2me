package com.mojang.rubydung.net;

/**
 * A remote player in a multiplayer session. Position is interpolated between
 * (xo,yo,zo) and (x,y,z) by the renderer. yaw/pitch are 0..255 (256 == 360deg).
 */
public final class NetPlayer {
    public String name = "";
    public float xo, yo, zo;
    public float x, y, z;
    public int yaw, pitch;
}

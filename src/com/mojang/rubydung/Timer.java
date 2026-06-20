package com.mojang.rubydung;

public class Timer {
    private static final long MS_PER_SECOND = 1000L;
    private float ticksPerSecond;
    private long lastTime;
    public int ticks;
    public float a;
    public float timeScale = 1.0f;
    public float fps = 0.0f;
    public float passedTime = 0.0f;

    public Timer(float ticksPerSecond) {
        this.ticksPerSecond = ticksPerSecond;
        this.lastTime = System.currentTimeMillis();
    }

    public void advanceTime() {
        long now = System.currentTimeMillis();
        long passedMs = now - lastTime;
        lastTime = now;
        if (passedMs < 0L) passedMs = 0L;
        if (passedMs > 1000L) passedMs = 1000L;
        if (passedMs > 0L) fps = (float) (1000L / passedMs);
        passedTime += (float) passedMs * timeScale * ticksPerSecond / 1000.0f;
        ticks = (int) passedTime;
        if (ticks > 100) ticks = 100;
        passedTime -= (float) ticks;
        a = passedTime;
    }
}

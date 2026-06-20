package com.mascotcapsule.micro3d.v3;

// Compile-time stub. Real implementation is provided by the device firmware.
public class Effect3D {
    public static final int NORMAL_SHADING = 0;
    public static final int TOON_SHADING = 1;
    public Effect3D() {}
    public Effect3D(Light light, int shading, boolean isEnableTrans, Texture tex) {}
    public final Light getLight() { return null; }
    public final void setLight(Light light) {}
    public final int getShading() { return 0; }
    public final int getShadingType() { return 0; }
    public final void setShading(int shading) {}
    public final void setShadingType(int shading) {}
    public final int getThreshold() { return 0; }
    public final int getThresholdHigh() { return 0; }
    public final int getThresholdLow() { return 0; }
    public final void setThreshold(int threshold, int high, int low) {}
    public final void setToonParams(int threshold, int high, int low) {}
    public final boolean isSemiTransparentEnabled() { return false; }
    public final boolean isTransparency() { return false; }
    public final void setSemiTransparentEnabled(boolean isEnable) {}
    public final void setTransparency(boolean isEnable) {}
    public final Texture getSphereMap() { return null; }
    public final Texture getSphereTexture() { return null; }
    public final void setSphereMap(Texture tex) {}
    public final void setSphereTexture(Texture tex) {}
}

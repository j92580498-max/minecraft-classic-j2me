package com.mascotcapsule.micro3d.v3;

// Compile-time stub. Real implementation is provided by the device firmware.
public class AffineTrans {
    public int m00, m01, m02, m03;
    public int m10, m11, m12, m13;
    public int m20, m21, m22, m23;

    public AffineTrans() {}
    public AffineTrans(AffineTrans a) {}
    public AffineTrans(int[] a) {}
    public AffineTrans(int[][] a) {}
    public AffineTrans(int m00, int m01, int m02, int m03,
                       int m10, int m11, int m12, int m13,
                       int m20, int m21, int m22, int m23) {}

    public final void setIdentity() {}
    public final void set(AffineTrans a) {}
    public final void set(int[] a) {}
    public final void set(int[][] a) {}
    public final void set(int m00, int m01, int m02, int m03,
                          int m10, int m11, int m12, int m13,
                          int m20, int m21, int m22, int m23) {}
    public final void get(int[] a) {}

    public final void rotationX(int r) {}
    public final void rotationY(int r) {}
    public final void rotationZ(int r) {}
    public final void setRotationX(int r) {}
    public final void setRotationY(int r) {}
    public final void setRotationZ(int r) {}
    public final void rotationV(Vector3D v, int r) {}
    public final void setRotation(Vector3D v, int r) {}

    public final void mul(AffineTrans a) {}
    public final void mul(AffineTrans a1, AffineTrans a2) {}
    public final void multiply(AffineTrans a) {}
    public final void multiply(AffineTrans a1, AffineTrans a2) {}

    public final void lookAt(Vector3D pos, Vector3D look, Vector3D up) {}
    public final void setViewTrans(Vector3D pos, Vector3D look, Vector3D up) {}
    public final Vector3D transform(Vector3D v) { return new Vector3D(); }
    public final Vector3D transPoint(Vector3D v) { return new Vector3D(); }
}

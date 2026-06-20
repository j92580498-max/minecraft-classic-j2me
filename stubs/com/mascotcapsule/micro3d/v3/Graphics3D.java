package com.mascotcapsule.micro3d.v3;

import javax.microedition.lcdui.Graphics;

// Compile-time stub. Real implementation is provided by the device firmware.
public class Graphics3D {
    // Primitive types
    public static final int PRIMITVE_POINTS        = 0x01000000;
    public static final int PRIMITVE_LINES         = 0x02000000;
    public static final int PRIMITVE_TRIANGLES     = 0x03000000;
    public static final int PRIMITVE_QUADS         = 0x04000000;
    public static final int PRIMITVE_POINT_SPRITES = 0x05000000;

    // Normal data type
    public static final int PDATA_NORMAL_NONE       = 0x0000;
    public static final int PDATA_NORMAL_PER_FACE   = 0x0200;
    public static final int PDATA_NORMAL_PER_VERTEX = 0x0300;

    // Color data type
    public static final int PDATA_COLOR_NONE        = 0x0000;
    public static final int PDATA_COLOR_PER_COMMAND = 0x0400;
    public static final int PDATA_COLOR_PER_FACE    = 0x0800;

    // Texture coordinate data type
    public static final int PDATA_TEXURE_COORD_NONE = 0x0000;
    public static final int PDATA_TEXURE_COORD      = 0x3000;

    // Point sprite params
    public static final int PDATA_POINT_SPRITE_PARAMS_PER_CMD    = 0x1000;
    public static final int PDATA_POINT_SPRITE_PARAMS_PER_FACE   = 0x2000;
    public static final int PDATA_POINT_SPRITE_PARAMS_PER_VERTEX = 0x3000;

    // Primitive attributes
    public static final int PATTR_BLEND_NORMAL = 0x00;
    public static final int PATTR_COLORKEY     = 0x10;
    public static final int PATTR_BLEND_HALF   = 0x20;
    public static final int PATTR_BLEND_ADD    = 0x40;
    public static final int PATTR_BLEND_SUB    = 0x60;
    public static final int PATTR_LIGHTING     = 0x01;
    public static final int PATTR_SPHERE_MAP   = 0x02;

    // Environment attributes
    public static final int ENV_ATTR_LIGHTING     = 1;
    public static final int ENV_ATTR_SPHERE_MAP   = 2;
    public static final int ENV_ATTR_TOON_SHADING = 4;
    public static final int ENV_ATTR_SEMI_TRANSPARENT = 8;

    // Point sprite flags
    public static final int POINT_SPRITE_LOCAL_SIZE  = 0;
    public static final int POINT_SPRITE_PIXEL_SIZE  = 1;
    public static final int POINT_SPRITE_PERSPECTIVE = 0;
    public static final int POINT_SPRITE_NO_PERS     = 2;

    // Command list commands
    public static final int COMMAND_LIST_VERSION_1_0 = 0;
    public static final int COMMAND_END              = 0x80000000;
    public static final int COMMAND_NOP              = 0x81000000;
    public static final int COMMAND_FLUSH            = 0x82000000;
    public static final int COMMAND_ATTRIBUTE        = 0x83000000;
    public static final int COMMAND_CLIP             = 0x84000000;
    public static final int COMMAND_CENTER           = 0x85000000;
    public static final int COMMAND_TEXTURE_INDEX    = 0x86000000;
    public static final int COMMAND_AFFINE_INDEX     = 0x87000000;
    public static final int COMMAND_PARALLEL_SCALE   = 0x90000000;
    public static final int COMMAND_PARALLEL_SIZE    = 0x91000000;
    public static final int COMMAND_PERSPECTIVE_FOV  = 0x92000000;
    public static final int COMMAND_PERSPECTIVE_WH   = 0x93000000;
    public static final int COMMAND_AMBIENT_LIGHT    = 0xa0000000;
    public static final int COMMAND_THRESHOLD        = 0xaf000000;

    public Graphics3D() {}

    public final void bind(Graphics graphics) {}
    public final void release(Graphics graphics) {}
    public final void flush() {}

    public final void renderPrimitives(Texture texture, int x, int y,
            FigureLayout layout, Effect3D effect, int command, int numPrimitives,
            int[] vertexCoords, int[] normals, int[] textureCoords, int[] colors) {}

    public final void drawCommandList(Texture texture, int x, int y,
            FigureLayout layout, Effect3D effect, int[] commandList) {}

    public final void drawCommandList(Texture[] textures, int x, int y,
            FigureLayout layout, Effect3D effect, int[] commandList) {}
}

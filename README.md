# Minecraft Classic 0.0.11a for Sony Ericsson S500i

A **Java ME / MIDP 2.0** port of an early public Minecraft build — **Classic
0.0.11a** (16 May 2009) — targeting the **Sony Ericsson S500i** and other phones
on Sony Ericsson's **JP-7** platform.

The original `minecraft.jar` is a **desktop** Java program built on **LWJGL +
OpenGL**. It cannot run on a feature phone as-is. This project reimplements the
same game logic on top of **MIDP 2.0 / CLDC 1.1** and replaces the OpenGL
renderer with one built on the phone's hardware-accelerated **Mascot Capsule
Micro3D V3** API (`com.mascotcapsule.micro3d.v3`), which the JP-7 platform
supports natively.

> Lineage: this began as a port of rd-132211 (RubyDung) and was advanced to
> Classic 0.0.11a. The Java package is still `com.mojang.rubydung.*` for
> continuity, but the game logic matches 0.0.11a.

## What 0.0.11a adds over rd-132211

- **Multiple block types** — rock, grass, dirt, stone brick, wood, and bush
  (`Tile` + `GrassTile`/`DirtTile`/`Bush`), each with per-face textures.
- **Procedural terrain** — `LevelGen` + `NoiseMap` build a fractal heightmap
  (two blended fields + a rock layer) and then carve caves, exactly like the
  desktop build, instead of rd-132211's flat half-filled box.
- **Living world** — random tile updates (`Level.tick`): grass spreads to lit
  dirt and dies in shadow; bushes decay without light or soil.
- **Light/shadow** — per-column light depth drives face brightness.

## Why Mascot Capsule

The S500i / JP-7 has no OpenGL ES, but it ships two 3D options in firmware:

- **Mascot Capsule Micro3D V3** (HI Corporation) — the native, fast software 3D engine
- **JSR-184 (M3G)** — the standard Mobile 3D Graphics API

This port uses **Mascot Capsule** because its immediate-mode
`Graphics3D.renderPrimitives()` call maps cleanly onto the "emit a batch of
textured quads per frame" rendering style, and it is the faster path on JP-7
hardware.

## How the rendering works

Each frame the renderer:

1. Builds a camera (view) transform from the player's interpolated position and
   yaw/pitch via `AffineTrans.lookAt()`.
2. Walks the blocks in a radius around the player and, per block, emits the
   **exposed faces only** (same neighbour-culling as the original `Tile.render`),
   using each face's atlas tile via `Tile.getTexture(face)`. Non-solid bushes
   are emitted as two crossed quads.
3. Submits those faces as **textured quads** (`PRIMITVE_QUADS |
   PDATA_TEXURE_COORD | PDATA_COLOR_PER_FACE`) to `Graphics3D.renderPrimitives()`,
   in batches that respect the engine's limit of < 256 primitives per call.
4. Per-face brightness (1.0 / 0.8 / 0.6 for top/sides/etc., dimmed in shadow) is
   folded into the per-face colour.

The terrain atlas (`terrain.png`) is converted to an 8-bit uncompressed BMP
(`terrain.bmp`), the texture format Mascot Capsule expects.

## What carried over from the desktop original

Ported (adapted to CLDC: `Vector` instead of `ArrayList`, `java.util.Random`
instead of `Math.random`, RMS instead of file I/O):

- `Level` — world storage, light columns, random tile ticks, save/load (RMS)
- `LevelGen`, `NoiseMap` — terrain generation + cave carving
- `Tile`, `GrassTile`, `DirtTile`, `Bush` — block definitions and behaviour
- `Player` — movement, gravity, AABB collision response
- `AABB` — swept-axis collision clipping
- `Timer` — fixed 60 Hz tick accumulator

Reimplemented for the phone:

- `WorldRenderer` — Mascot Capsule renderer (replaces LWJGL `LevelRenderer` +
  `Tesselator` + `Chunk` display lists)
- `RayCast` — DDA voxel ray for block targeting (replaces the OpenGL
  selection-buffer picking, which has no Mascot equivalent)
- `GameCanvas` — MIDP `Canvas` game loop + keypad input
- `RubyDungMIDlet` — MIDlet lifecycle entry point

## Controls (numeric keypad)

| Key | Action |
|-----|--------|
| `2` / `8` | move forward / back |
| `4` / `6` | strafe left / right |
| `1` / `3` | turn left / right |
| `7` / `9` | look up / down |
| `5` | jump |
| `*` | break targeted block |
| `#` | place selected block |
| `0` | cycle selected block type (rock → dirt → stone brick → wood → bush) |

The D-pad also works (up/down to move, left/right to turn, fire to break). The
world auto-saves on exit (RMS).

## Project layout

```
src/      game source (the actual MIDlet — this is what ships)
stubs/    compile-time stubs for the Mascot Capsule API.
          These are provided NATIVELY by the phone and are NEVER packaged.
res/      terrain.bmp, icon.png, MANIFEST.MF
libs/     MIDP 2.0 + CLDC 1.1 API stubs (compile-only bootclasspath)
build.sh  build pipeline
dist/     output: mc-c0.0.11a.jar + mc-c0.0.11a.jad
```

## Building

Requires a **JDK 8** (to emit Java 1.3 bytecode) and **ProGuard** (for J2ME
preverification — adds the CLDC `StackMap` attributes real devices require).

```bash
./build.sh
```

This produces `dist/mc-c0.0.11a.jar` and `dist/mc-c0.0.11a.jad`. Copy both to the
phone (or load the `.jad` in an emulator such as KEmulator / J2ME Loader / a real
JP-7 device) and install.

> The Mascot Capsule stub classes in `stubs/` are used **only** to compile
> against the API. They are deliberately excluded from the final JAR — the real
> implementation lives in the phone firmware, and shipping the empty stubs would
> break 3D rendering.

## Credits

- Original Minecraft Classic 0.0.11a / rd-132211 by Markus "Notch" Persson / Mojang.
- Mascot Capsule Micro3D V3 by HI Corporation.
- This is a non-commercial preservation / homebrew port.

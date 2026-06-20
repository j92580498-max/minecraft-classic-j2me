# AGENTS.md — Minecraft Classic 0.0.11a → Sony Ericsson S500i (J2ME) port

## What this is
A J2ME (MIDP 2.0 / CLDC 1.1) port of **Minecraft Classic 0.0.11a** (16 May
2009). The original is a **desktop LWJGL/OpenGL** Java SE program; this rewrites
it for Sony Ericsson **JP-7** phones (S500i) using the **Mascot Capsule Micro3D
V3** 3D API (`com.mascotcapsule.micro3d.v3`).

This started as an rd-132211 (RubyDung) port and was advanced to Classic
0.0.11a: real block types (rock/grass/dirt/stoneBrick/wood/bush), fractal
terrain generation with caves, light/shadow columns, and random tile updates
(grass spread, bush decay).

## Source of truth
- The desktop 0.0.11a classes, decompiled with CFR, were the reference for the
  game logic (`com.mojang.minecraft.*`). Logic is ported as faithfully as CLDC
  allows; the package was kept as `com.mojang.rubydung.*` for continuity.
- API facts came from the Mascot Capsule v3 SDK javadoc. Key call:
  `renderPrimitives(Texture, int x, int y, FigureLayout, Effect3D, int command,
  int numPrimitives, int[] vtx, int[] nrm, int[] texCoord, int[] colors)`.

## Hard rules
- **`stubs/` (Mascot Capsule) MUST NOT be packaged into the JAR.** The phone
  provides those classes natively; shipping the empty stubs breaks 3D. The
  build compiles stubs to a separate dir used only on the compile classpath.
- Target **Java 1.3 bytecode** (JDK 8 `-source/-target 1.3`), then **preverify**
  with ProGuard `-microedition` (adds CLDC StackMap attributes).
- CLDC has **no** `ArrayList`/generics, **no** `Math.random()`, **no** file I/O.
  Use `Vector`, `java.util.Random`, and RMS `RecordStore`. CLDC 1.1 **does**
  have `Math.sin/cos/sqrt` and `float`.

## Build
`./build.sh` (needs JDK8 at `/opt/jdk8u402-b06` or `$JAVA8_HOME`, and `proguard`).
Output: `dist/mc-c0.0.11a.jar` + `dist/mc-c0.0.11a.jad`.

## Map of the code
- `RubyDungMIDlet` — MIDlet entry.
- `GameCanvas` — MIDP Canvas, game loop, keypad input; drives `level.tick()`.
- `level/Level` — Classic 0.0.11a world: `getTile/setTile/isLit/isSolidTile`,
  light columns, random tile ticks, RMS save/load. Generates via `LevelGen`.
- `level/LevelGen` + `level/NoiseMap` — fractal terrain + cave carving (verbatim).
- `level/tile/Tile` + `GrassTile`/`DirtTile`/`Bush` — block set, per-face
  textures, tick behaviour. `Tile.tiles[id]` registry.
- `level/WorldRenderer` — Mascot Capsule renderer; emits exposed solid faces as
  textured quads and bushes as crossed quads, in <256-primitive batches.
- `RayCast` — DDA voxel pick (replaces GL selection buffer).
- `Player`, `phys/AABB`, `Timer` — movement / collision / fixed-step timing.

## Controls (numeric keypad)
- 2/8 forward/back, 4/6 strafe, 1/3 turn, 7/9 look up/down, 5 jump
- `*` break block, `#` place selected block, `0` cycle block type
- D-pad also moves/turns; FIRE breaks.

## Ideas if continuing
- Particle bursts on block break (Classic spawns 4³ texture-shard particles).
- Zombies + ZombieModel (cubes) — present in 0.0.11a desktop, omitted here.
- Targeted-block highlight (renderHit is a stub).
- Chunk caching of quad batches to cut per-frame rebuild cost.
- Tune RADIUS/BATCH and perspective angle on real hardware.

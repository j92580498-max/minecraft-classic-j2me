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
- **Textures must be SQUARE, power-of-two.** Mascot Capsule Micro3D V3 samples
  textures in a square UV space, so a non-square atlas (the Classic source is
  256x128) renders distorted/rotated on device. `res/terrain.png` and
  `res/terrain.bmp` are therefore padded to **256x256** with the atlas content
  anchored in the top half and the bottom half left transparent. UV coords in
  `WorldRenderer` are absolute pixels (tile index -> u/v), so the padding does
  not shift any tile.
- Target **Java 1.3 bytecode** (JDK 8 `-source/-target 1.3`), then **preverify**
  with ProGuard `-microedition` (adds CLDC StackMap attributes).
- CLDC has **no** `ArrayList`/generics, **no** `Math.random()`, **no** file I/O.
  Use `Vector`, `java.util.Random`, and RMS `RecordStore`. CLDC 1.1 **does**
  have `Math.sin/cos/sqrt` and `float`.

## Build
`./build.sh` (needs JDK8 at `/opt/jdk8u402-b06` or `$JAVA8_HOME`, and `proguard`).
Output: `dist/mc-c0.0.11a.jar` + `dist/mc-c0.0.11a.jad`.

## Map of the code
- `RubyDungMIDlet` - MIDlet entry; passes itself to GameCanvas for Display access (multiplayer text entry).
- `GameCanvas` - MIDP Canvas, game loop, keypad input; in-game menu (resume/save/load/new/help/multiplayer), inventory picker, block break/place, particle + mob + net-player ticking, multiplayer join flow.
- `level/Level` - Classic 0.0.11a world: `getTile/setTile/isLit/isSolidTile`, light columns, random tile ticks, RMS save/load, `mobs` Vector. Second constructor accepts pre-loaded blocks (network maps). Generates via `LevelGen`.
- `level/LevelGen` + `level/NoiseMap` - fractal terrain + cave carving (verbatim).
- `level/tile/Tile` + `GrassTile`/`DirtTile`/`Bush` - block set, per-face textures, tick behaviour. `Tile.tiles[id]` registry.
- `level/WorldRenderer` - Mascot Capsule renderer; exposed solid faces as textured quads, sprites as crossed quads, flat-shaded cube mobs/net-players, billboarded break particles, and a 3D wireframe block highlight (`renderHit`).
- `RayCast` - DDA voxel pick (replaces GL selection buffer).
- `Player`, `phys/AABB`, `Timer` - movement / collision / fixed-step timing.
- `Mob` - zombie: humanoid AABB, gravity/collision, wander + chase AI, hurt/death; rendered as colour cubes.
- `Particle` - block-break shard: gravity, ground collision, short life; samples the broken block's texture.
- `net/NetworkClient` - Classic / ClassiCube multiplayer client (vanilla protocol, no CPE). Own receive thread, login handshake, gzip level transfer, entity add/move/remove, chat, set-block; sends position + block edits. Packet sizes verbatim from ClassiCube `src/Protocol.c`.
- `net/NetPlayer` - remote player (interpolated position + yaw/pitch).
- `net/Inflate` - pure-Java DEFLATE/gunzip (CLDC 1.1 has no `java.util.zip`), O(n) with a 32 KB sliding window.

## Controls (numeric keypad)
- 2/8 forward/back, 4/6 strafe, 1/3 turn (held = continuous), 7/9 look up/down (held), 5 jump
- `*` break block (also attacks mobs in reach), `#` place selected block, `0` cycle block type
- Left soft key (or FIRE) opens the in-game menu; right soft key opens the inventory picker
- D-pad also moves/turns; menu: 2/8 navigate, 5/FIRE select

## Multiplayer
- Menu -> Multiplayer prompts for host, port, and username via MIDP TextBox, then connects over `socket://host:port`.
- Implements the original Classic protocol (opcodes 0-15). CPE extensions are not negotiated (sends CPE magic byte 0 = vanilla).
- Coordinates are fixed-point (1 block = 32 units). Strings are 64-byte space-padded ASCII.
- Verified end-to-end against a mock server: login, level transfer + gunzip, entity spawn, chat, and client position/set-block sends.

## Done in this pass
- Mobs (zombies) with AI + flat-cube rendering and melee.
- Block-break particles.
- 3D targeted-block highlight (`renderHit`).
- Improved column lighting (darker, slightly graded shadows).
- In-game menu + world save/load + player-state persistence.
- Continuous (held-key) look controls in the spirit of the Symbian/ClassiCube port.
- Classic multiplayer client (connect, map download, remote players, chat, synced block edits).

## Ideas if continuing
- Interpolate remote player limbs / model yaw; render their names.
- CPE extensions (ExtInfo/ExtEntry) for custom blocks and longer names.
- Chunk caching of quad batches to cut per-frame rebuild cost.
- Tune RADIUS/BATCH, mob cap, and perspective on real hardware.

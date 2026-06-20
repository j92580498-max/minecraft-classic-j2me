from PIL import Image
import struct

# Convert the 64x32 player/mob skin (char.png) into an 8-bit indexed BMP with
# palette index 0 reserved as the Mascot Capsule color-key (transparency).
# Mirrors tools/make_terrain_bmp.py so the device Texture loader treats both
# the same way.
SRC = 'res/char.png'
im = Image.open(SRC).convert('RGBA')
W, H = im.size
print('skin', W, H)

px = im.load()
SENT = (255, 0, 255)
rgb = Image.new('RGB', (W, H))
rp = rgb.load()
trans = 0
for y in range(H):
    for x in range(W):
        r, g, b, a = px[x, y]
        if a < 128:
            rp[x, y] = SENT
            trans += 1
        else:
            rp[x, y] = (r, g, b)
print('transparent texels', trans)

q = rgb.quantize(colors=255, method=Image.MEDIANCUT)
pal = q.getpalette()
qpx = q.load()

idx = [[0] * W for _ in range(H)]
for y in range(H):
    for x in range(W):
        if rp[x, y] == SENT:
            idx[y][x] = 0
        else:
            idx[y][x] = qpx[x, y] + 1

newpal = [(0, 0, 0)]
for i in range(255):
    if i * 3 + 2 < len(pal):
        newpal.append((pal[i * 3], pal[i * 3 + 1], pal[i * 3 + 2]))
    else:
        newpal.append((0, 0, 0))
while len(newpal) < 256:
    newpal.append((0, 0, 0))


def write_bmp(path):
    rowsize = (W + 3) & ~3
    pixdata = bytearray()
    for y in range(H - 1, -1, -1):
        row = bytearray(idx[y][x] for x in range(W))
        row += b'\x00' * (rowsize - W)
        pixdata += row
    palsize = 256 * 4
    off = 14 + 40 + palsize
    filesize = off + len(pixdata)
    out = bytearray()
    out += b'BM' + struct.pack('<IHHI', filesize, 0, 0, off)
    out += struct.pack('<IiiHHIIiiII', 40, W, H, 1, 8, 0, len(pixdata), 2835, 2835, 256, 0)
    for (r, g, b) in newpal:
        out += bytes((b, g, r, 0))
    out += pixdata
    open(path, 'wb').write(out)
    print('wrote', path, filesize, 'bytes')


write_bmp('res/char.bmp')
print('done')

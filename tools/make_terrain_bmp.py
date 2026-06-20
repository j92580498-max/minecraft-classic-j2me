from PIL import Image
import struct, sys

SRC='/tmp/cc_tex/terrain.png'
im=Image.open(SRC).convert('RGBA')
W,H=im.size
print('atlas',W,H)

px=im.load()
# Determine a transparency color-key. ClassiCube atlas uses alpha for sprites/glass.
# MascotCapsule color-key = palette index 0. We map fully/most-transparent texels to index 0.
# Build an RGB image where transparent pixels become a sentinel, then quantize.

SENT=(255,0,255)  # sentinel for transparent -> will force to palette[0]
rgb=Image.new('RGB',(W,H))
rp=rgb.load()
trans=0
for y in range(H):
    for x in range(W):
        r,g,b,a=px[x,y]
        if a<128:
            rp[x,y]=SENT; trans+=1
        else:
            rp[x,y]=(r,g,b)
print('transparent texels',trans)

# Quantize to 255 colors (reserve index for sentinel separately)
q=rgb.quantize(colors=255, method=Image.MEDIANCUT)
pal=q.getpalette()  # list of r,g,b *256
# Find/append sentinel index
# Map: we want palette[0] to be the color-key. Rebuild palette so index0 = sentinel.
qpx=q.load()
# Build new indexed array with index0 reserved for transparency.
idx=[[0]*W for _ in range(H)]
# Identify which quantized index corresponds to sentinel by checking original
for y in range(H):
    for x in range(W):
        if rp[x,y]==SENT:
            idx[y][x]=0
        else:
            idx[y][x]=qpx[x,y]+1  # shift up by 1 so 0 stays free

# Build palette: index0 = sentinel(black-ish magenta won't matter, it's keyed out), 1..255 from q
newpal=[(0,0,0)]  # index0
for i in range(255):
    newpal.append((pal[i*3],pal[i*3+1],pal[i*3+2]))
while len(newpal)<256: newpal.append((0,0,0))

# Write 8-bit BMP (BITMAPINFOHEADER, bottom-up, palette BGRA)
def write_bmp(path):
    rowsize=(W+3)&~3
    pixdata=bytearray()
    for y in range(H-1,-1,-1):
        row=bytearray(idx[y][x] for x in range(W))
        row += b'\x00'*(rowsize-W)
        pixdata+=row
    palsize=256*4
    off=14+40+palsize
    filesize=off+len(pixdata)
    out=bytearray()
    out+=b'BM'+struct.pack('<IHHI',filesize,0,0,off)
    out+=struct.pack('<IiiHHIIiiII',40,W,H,1,8,0,len(pixdata),2835,2835,256,0)
    for (r,g,b) in newpal:
        out+=bytes((b,g,r,0))
    out+=pixdata
    open(path,'wb').write(out)
    print('wrote',path,filesize,'bytes')

write_bmp('res/terrain.bmp')
# also save the corrected source png for the repo
Image.open(SRC).convert('RGBA').save('res/terrain.png')
print('done')

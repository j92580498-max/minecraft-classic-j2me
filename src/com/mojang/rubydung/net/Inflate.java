package com.mojang.rubydung.net;

/**
 * Minimal pure-Java DEFLATE (RFC 1951) + GZIP (RFC 1952) decompressor.
 *
 * CLDC 1.1 has no java.util.zip, but Minecraft Classic servers send the level
 * as a GZIP stream split across LEVEL_DATA packets. This implements just enough
 * INFLATE to decode that stream: stored, fixed-Huffman and dynamic-Huffman
 * blocks. Output goes into a single growable byte[] so LZ77 back-references are
 * O(1) (important on the phone  a Classic level is ~1 MB uncompressed).
 */
public final class Inflate {
    private final byte[] in;
    private int inPos;
    private int bitBuf;
    private int bitCnt;

    private byte[] win = new byte[1 << 16];
    private int outLen = 0;

    private Inflate(byte[] data, int offset) {
        this.in = data;
        this.inPos = offset;
    }

    /** Decompress a full GZIP stream into raw bytes. */
    public static byte[] gunzip(byte[] data) throws Exception {
        int p = 0;
        if (data[p] != (byte) 0x1f || data[p + 1] != (byte) 0x8b) {
            throw new Exception("not gzip");
        }
        int flg = data[p + 3] & 0xFF;
        p += 10;
        if ((flg & 4) != 0) {           // FEXTRA
            int xlen = (data[p] & 0xFF) | ((data[p + 1] & 0xFF) << 8);
            p += 2 + xlen;
        }
        if ((flg & 8) != 0) {           // FNAME
            while (data[p++] != 0) {}
        }
        if ((flg & 16) != 0) {          // FCOMMENT
            while (data[p++] != 0) {}
        }
        if ((flg & 2) != 0) {           // FHCRC
            p += 2;
        }
        Inflate inf = new Inflate(data, p);
        inf.inflate();
        byte[] result = new byte[inf.outLen];
        System.arraycopy(inf.win, 0, result, 0, inf.outLen);
        return result;
    }

    private void emit(int b) {
        if (outLen >= win.length) {
            byte[] bigger = new byte[win.length * 2];
            System.arraycopy(win, 0, bigger, 0, win.length);
            win = bigger;
        }
        win[outLen++] = (byte) b;
    }

    private int getBit() {
        if (bitCnt == 0) {
            bitBuf = in[inPos++] & 0xFF;
            bitCnt = 8;
        }
        int b = bitBuf & 1;
        bitBuf >>= 1;
        --bitCnt;
        return b;
    }

    private int getBits(int n) {
        int v = 0;
        for (int i = 0; i < n; ++i) v |= getBit() << i;
        return v;
    }

    private void inflate() throws Exception {
        int last;
        do {
            last = getBit();
            int type = getBits(2);
            if (type == 0) {
                stored();
            } else if (type == 1) {
                fixed();
            } else if (type == 2) {
                dynamic();
            } else {
                throw new Exception("bad block type");
            }
        } while (last == 0);
    }

    private void stored() {
        bitBuf = 0;
        bitCnt = 0;                 // align to byte boundary
        int len = (in[inPos] & 0xFF) | ((in[inPos + 1] & 0xFF) << 8);
        inPos += 4;                 // skip LEN + NLEN
        for (int i = 0; i < len; ++i) {
            emit(in[inPos++] & 0xFF);
        }
    }

    static final class Huff {
        final short[] count = new short[16];
        final short[] symbol;
        Huff(int n) { symbol = new short[n]; }
    }

    private int decode(Huff h) {
        int code = 0, first = 0, index = 0;
        for (int len = 1; len <= 15; ++len) {
            code |= getBit();
            int count = h.count[len];
            if (code - first < count) {
                return h.symbol[index + (code - first)];
            }
            index += count;
            first += count;
            first <<= 1;
            code <<= 1;
        }
        return -1;
    }

    private static Huff construct(int[] lengths, int n) {
        Huff h = new Huff(n);
        for (int i = 0; i < n; ++i) h.count[lengths[i]]++;
        h.count[0] = 0;
        short[] offs = new short[16];
        for (int len = 1; len < 16; ++len) offs[len] = (short) (offs[len - 1] + h.count[len - 1]);
        for (int i = 0; i < n; ++i) {
            if (lengths[i] != 0) h.symbol[offs[lengths[i]]++] = (short) i;
        }
        return h;
    }

    private static final short[] LEN_BASE = {
        3,4,5,6,7,8,9,10,11,13,15,17,19,23,27,31,35,43,51,59,67,83,99,115,131,163,195,227,258
    };
    private static final short[] LEN_EXTRA = {
        0,0,0,0,0,0,0,0,1,1,1,1,2,2,2,2,3,3,3,3,4,4,4,4,5,5,5,5,0
    };
    private static final short[] DIST_BASE = {
        1,2,3,4,5,7,9,13,17,25,33,49,65,97,129,193,257,385,513,769,
        1025,1537,2049,3073,4097,6145,8193,12289,16385,24577
    };
    private static final short[] DIST_EXTRA = {
        0,0,0,0,1,1,2,2,3,3,4,4,5,5,6,6,7,7,8,8,9,9,10,10,11,11,12,12,13,13
    };

    private void codes(Huff lenH, Huff distH) throws Exception {
        int sym;
        do {
            sym = decode(lenH);
            if (sym < 0) throw new Exception("bad symbol");
            if (sym == 256) break;
            if (sym < 256) {
                emit(sym);
            } else {
                sym -= 257;
                int len = LEN_BASE[sym] + getBits(LEN_EXTRA[sym]);
                int dsym = decode(distH);
                int dist = DIST_BASE[dsym] + getBits(DIST_EXTRA[dsym]);
                int start = outLen - dist;
                for (int i = 0; i < len; ++i) {
                    emit(win[start + i] & 0xFF);
                }
            }
        } while (true);
    }

    private void fixed() throws Exception {
        int[] ll = new int[288];
        int i = 0;
        for (; i < 144; ++i) ll[i] = 8;
        for (; i < 256; ++i) ll[i] = 9;
        for (; i < 280; ++i) ll[i] = 7;
        for (; i < 288; ++i) ll[i] = 8;
        int[] dl = new int[30];
        for (i = 0; i < 30; ++i) dl[i] = 5;
        codes(construct(ll, 288), construct(dl, 30));
    }

    private static final int[] ORDER = {16,17,18,0,8,7,9,6,10,5,11,4,12,3,13,2,14,1,15};

    private void dynamic() throws Exception {
        int hlit = getBits(5) + 257;
        int hdist = getBits(5) + 1;
        int hclen = getBits(4) + 4;
        int[] codeLen = new int[19];
        for (int i = 0; i < hclen; ++i) codeLen[ORDER[i]] = getBits(3);
        Huff codeH = construct(codeLen, 19);

        int[] lengths = new int[hlit + hdist];
        int idx = 0;
        while (idx < hlit + hdist) {
            int sym = decode(codeH);
            if (sym < 16) {
                lengths[idx++] = sym;
            } else if (sym == 16) {
                int prev = lengths[idx - 1];
                int rep = 3 + getBits(2);
                while (rep-- > 0) lengths[idx++] = prev;
            } else if (sym == 17) {
                int rep = 3 + getBits(3);
                while (rep-- > 0) lengths[idx++] = 0;
            } else {
                int rep = 11 + getBits(7);
                while (rep-- > 0) lengths[idx++] = 0;
            }
        }
        int[] ll = new int[hlit];
        int[] dl = new int[hdist];
        System.arraycopy(lengths, 0, ll, 0, hlit);
        System.arraycopy(lengths, hlit, dl, 0, hdist);
        codes(construct(ll, hlit), construct(dl, hdist));
    }
}

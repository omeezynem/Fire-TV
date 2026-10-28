package com.tvdash.launcher;

import java.nio.charset.StandardCharsets;

/**
 * Minimal QR code encoder (byte mode, error correction level M, versions 1-10) so the TV can show a code the
 * phone camera scans to open the remote. Follows ISO/IEC 18004; structure based on Nayuki's reference design.
 */
final class QrCode {
    private static final int[] ECC_PER_BLOCK = {-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] NUM_BLOCKS = {-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};

    final int size;
    private final boolean[][] modules;
    private final boolean[][] isFunction;

    /** Dark/light grid, indexed [y][x]. */
    boolean get(int x, int y) {
        return modules[y][x];
    }

    static QrCode encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        for (int ver = 1; ver <= 10; ver++) {
            int capacityBits = numDataCodewords(ver) * 8;
            int countBits = ver <= 9 ? 8 : 16;
            if (4 + countBits + data.length * 8 <= capacityBits) return new QrCode(ver, data);
        }
        throw new IllegalArgumentException("Text too long for QR code");
    }

    private QrCode(int ver, byte[] data) {
        size = ver * 4 + 17;
        modules = new boolean[size][size];
        isFunction = new boolean[size][size];

        // Bit stream: mode, length, data, terminator, padding.
        int capacityBits = numDataCodewords(ver) * 8;
        BitBuffer bb = new BitBuffer(capacityBits);
        bb.append(0x4, 4);
        bb.append(data.length, ver <= 9 ? 8 : 16);
        for (byte b : data) bb.append(b & 0xFF, 8);
        bb.append(0, Math.min(4, capacityBits - bb.length));
        bb.append(0, (8 - bb.length % 8) % 8);
        for (int pad = 0xEC; bb.length < capacityBits; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        byte[] dataCodewords = new byte[bb.length / 8];
        for (int i = 0; i < bb.length; i++) dataCodewords[i >>> 3] |= bb.bit(i) << (7 - (i & 7));

        drawFunctionPatterns(ver);
        drawCodewords(addEccAndInterleave(ver, dataCodewords));

        int bestMask = 0;
        long bestPenalty = Long.MAX_VALUE;
        for (int mask = 0; mask < 8; mask++) {
            applyMask(mask);
            drawFormatBits(mask);
            long p = penalty();
            if (p < bestPenalty) {
                bestPenalty = p;
                bestMask = mask;
            }
            applyMask(mask); // XOR again to undo
        }
        applyMask(bestMask);
        drawFormatBits(bestMask);
    }

    // ------------------------------------------------------------------ layout

    private void drawFunctionPatterns(int ver) {
        for (int i = 0; i < size; i++) {
            setFunction(6, i, i % 2 == 0);
            setFunction(i, 6, i % 2 == 0);
        }
        drawFinder(3, 3);
        drawFinder(size - 4, 3);
        drawFinder(3, size - 4);

        int[] align = alignmentPositions(ver);
        int n = align.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if (i == 0 && j == 0 || i == 0 && j == n - 1 || i == n - 1 && j == 0) continue;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        setFunction(align[i] + dx, align[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }

        drawFormatBits(0); // placeholder so these modules count as function modules
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                setFunction(a, b, bit);
                setFunction(b, a, bit);
            }
        }
    }

    private void drawFinder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) setFunction(xx, yy, dist != 2 && dist != 4);
            }
        }
    }

    private void drawFormatBits(int mask) {
        int data = 0 << 3 | mask; // level M = 0b00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;

        for (int i = 0; i <= 5; i++) setFunction(8, i, bit(bits, i));
        setFunction(8, 7, bit(bits, 6));
        setFunction(8, 8, bit(bits, 7));
        setFunction(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFunction(14 - i, 8, bit(bits, i));

        for (int i = 0; i < 8; i++) setFunction(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFunction(8, size - 15 + i, bit(bits, i));
        setFunction(8, size - 8, true);
    }

    private void setFunction(int x, int y, boolean dark) {
        modules[y][x] = dark;
        isFunction[y][x] = true;
    }

    private int[] alignmentPositions(int ver) {
        if (ver == 1) return new int[0];
        int num = ver / 7 + 2;
        int step = (ver * 4 + num * 2 + 1) / (num * 2 - 2) * 2;
        int[] result = new int[num];
        result[0] = 6;
        for (int i = num - 1, pos = size - 7; i >= 1; i--, pos -= step) result[i] = pos;
        return result;
    }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!isFunction[y][x] && i < data.length * 8) {
                        modules[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private void applyMask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (isFunction[y][x]) continue;
                boolean invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 == 0; break;
                    case 1: invert = y % 2 == 0; break;
                    case 2: invert = x % 3 == 0; break;
                    case 3: invert = (x + y) % 3 == 0; break;
                    case 4: invert = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: invert = x * y % 2 + x * y % 3 == 0; break;
                    case 6: invert = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: invert = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                modules[y][x] ^= invert;
            }
        }
    }

    /** Simplified penalty score (runs, 2x2 blocks, dark balance); any mask is valid, this just picks a clean one. */
    private long penalty() {
        long result = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                int run = 0;
                boolean color = false;
                for (int b = 0; b < size; b++) {
                    boolean m = pass == 0 ? modules[a][b] : modules[b][a];
                    if (b > 0 && m == color) {
                        run++;
                    } else {
                        if (run >= 5) result += run - 2;
                        color = m;
                        run = 1;
                    }
                }
                if (run >= 5) result += run - 2;
            }
        }
        int dark = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) dark++;
                if (y < size - 1 && x < size - 1) {
                    boolean c = modules[y][x];
                    if (c == modules[y][x + 1] && c == modules[y + 1][x] && c == modules[y + 1][x + 1]) result += 3;
                }
            }
        }
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        result += Math.max(0, k) * 10L;
        return result;
    }

    // ------------------------------------------------------------------ error correction

    private static int numRawDataModules(int ver) {
        int result = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int numAlign = ver / 7 + 2;
            result -= (25 * numAlign - 10) * numAlign - 55;
            if (ver >= 7) result -= 36;
        }
        return result;
    }

    private static int numDataCodewords(int ver) {
        return numRawDataModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver];
    }

    private static byte[] addEccAndInterleave(int ver, byte[] data) {
        int numBlocks = NUM_BLOCKS[ver];
        int eccLen = ECC_PER_BLOCK[ver];
        int rawCodewords = numRawDataModules(ver) / 8;
        int numShort = numBlocks - rawCodewords % numBlocks;
        int shortLen = rawCodewords / numBlocks;

        byte[][] blocks = new byte[numBlocks][];
        byte[] divisor = rsDivisor(eccLen);
        for (int i = 0, k = 0; i < numBlocks; i++) {
            int datLen = shortLen - eccLen + (i < numShort ? 0 : 1);
            byte[] dat = new byte[datLen];
            System.arraycopy(data, k, dat, 0, datLen);
            k += datLen;
            byte[] ecc = rsRemainder(dat, divisor);
            byte[] block = new byte[shortLen + 1];
            System.arraycopy(dat, 0, block, 0, datLen);
            System.arraycopy(ecc, 0, block, block.length - eccLen, eccLen);
            blocks[i] = block;
        }

        byte[] result = new byte[rawCodewords];
        int idx = 0;
        for (int i = 0; i < blocks[0].length; i++) {
            for (int j = 0; j < numBlocks; j++) {
                if (i != shortLen - eccLen || j >= numShort) result[idx++] = blocks[j][i];
            }
        }
        return result;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] result = new byte[degree];
        result[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                result[j] = (byte) rsMultiply(result[j] & 0xFF, root);
                if (j + 1 < degree) result[j] ^= result[j + 1];
            }
            root = rsMultiply(root, 0x02);
        }
        return result;
    }

    private static byte[] rsRemainder(byte[] data, byte[] divisor) {
        byte[] result = new byte[divisor.length];
        for (byte b : data) {
            int factor = (b ^ result[0]) & 0xFF;
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int i = 0; i < result.length; i++) result[i] ^= (byte) rsMultiply(divisor[i] & 0xFF, factor);
        }
        return result;
    }

    private static int rsMultiply(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static boolean bit(int x, int i) {
        return ((x >>> i) & 1) != 0;
    }

    private static final class BitBuffer {
        private final boolean[] bits;
        int length;

        BitBuffer(int capacity) {
            bits = new boolean[capacity];
        }

        void append(int val, int len) {
            for (int i = len - 1; i >= 0; i--) bits[length++] = ((val >>> i) & 1) != 0;
        }

        int bit(int i) {
            return bits[i] ? 1 : 0;
        }
    }
}

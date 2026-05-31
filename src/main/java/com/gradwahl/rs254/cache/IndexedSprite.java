package com.gradwahl.rs254.cache;

import java.awt.image.BufferedImage;

/**
 * Decodes a paletted sprite from a Jagfile.
 *
 * Pixel data lives in {@code <name>.dat}; metadata lives in {@code index.dat}.
 *
 * index.dat layout per sprite-group (at the offset stored as g2 at dat[0..1]):
 *   g2  full_width   (canvas size incl. crop padding)
 *   g2  full_height
 *   g1  palette_count
 *   (palette_count-1) × g3  palette RGB entries (palette[0] = transparent)
 *
 * Then for each frame:
 *   g1  crop_left
 *   g1  crop_top
 *   g2  crop_width   (actual pixel columns)
 *   g2  crop_height  (actual pixel rows)
 *   g1  pixel_order  (0 = row-major, 1 = column-major)
 *
 * Pixels are palette indices stored in dat[], starting at dat[2].
 */
public final class IndexedSprite {
    public final int fullWidth, fullHeight;
    public final BufferedImage[] frames;

    private IndexedSprite(int fullWidth, int fullHeight, BufferedImage[] frames) {
        this.fullWidth  = fullWidth;
        this.fullHeight = fullHeight;
        this.frames     = frames;
    }

    /**
     * Decode all frames of a named sprite from a JagFile.
     * Returns {@code null} if the sprite data is missing.
     */
    public static IndexedSprite decode(JagFile jag, String name) {
        byte[] dat = jag.get(name + ".dat");
        byte[] idx = jag.get("index.dat");
        if (dat == null || idx == null) return null;

        int idxPos = u2(dat, 0);       // pointer into index.dat
        int fullW  = u2(idx, idxPos);  idxPos += 2;
        int fullH  = u2(idx, idxPos);  idxPos += 2;

        int palCount = idx[idxPos++] & 0xff;
        int[] palette = new int[palCount];
        // palette[0] is transparent (0x00000000); remaining entries are opaque RGB
        for (int i = 1; i < palCount; i++) {
            int rgb = u3(idx, idxPos); idxPos += 3;
            palette[i] = (rgb == 0) ? 1 : rgb; // ensure non-zero so black ≠ transparent
        }

        // Count frames by reading how many fit
        int datPos = 2;
        java.util.List<BufferedImage> frames = new java.util.ArrayList<>();
        int tmpIdx = idxPos;

        while (tmpIdx + 7 <= idx.length) {
            int cl  = idx[tmpIdx++] & 0xff;
            int ct  = idx[tmpIdx++] & 0xff;
            int cw  = u2(idx, tmpIdx); tmpIdx += 2;
            int ch  = u2(idx, tmpIdx); tmpIdx += 2;
            int ord = idx[tmpIdx++] & 0xff;

            int pixCount = cw * ch;
            if (datPos + pixCount > dat.length) break;

            BufferedImage img = new BufferedImage(fullW, fullH, BufferedImage.TYPE_INT_ARGB);

            if (ord == 0) { // row-major
                for (int y = 0; y < ch; y++) {
                    for (int x = 0; x < cw; x++) {
                        int pi = dat[datPos++] & 0xff;
                        if (pi != 0) img.setRGB(x + cl, y + ct, 0xff000000 | palette[pi]);
                    }
                }
            } else { // column-major
                for (int x = 0; x < cw; x++) {
                    for (int y = 0; y < ch; y++) {
                        int pi = dat[datPos++] & 0xff;
                        if (pi != 0) img.setRGB(x + cl, y + ct, 0xff000000 | palette[pi]);
                    }
                }
            }

            frames.add(img);
        }

        if (frames.isEmpty()) return null;
        return new IndexedSprite(fullW, fullH, frames.toArray(new BufferedImage[0]));
    }

    private static int u2(byte[] b, int p) {
        return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
    }

    private static int u3(byte[] b, int p) {
        return ((b[p] & 0xff) << 16) | ((b[p + 1] & 0xff) << 8) | (b[p + 2] & 0xff);
    }
}

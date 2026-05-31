package jagex2.graphics;

public interface TriangleRenderer {

    /** Solid-colour triangle. rgb is already a resolved 0xRRGGBB pixel value. trans is legacy [0,255] transparency. */
    void addFlat(int x0, int y0, int x1, int y1, int x2, int y2, int rgb, int trans, int z0, int z1, int z2);

    /** Gouraud-shaded triangle. hsl0/1/2 are direct colourTable indices. trans is legacy [0,255] transparency. */
    void addGouraud(int x0, int y0, int x1, int y1, int x2, int y2,
                    int hsl0, int hsl1, int hsl2, int trans, int z0, int z1, int z2);

    /**
     * Textured triangle with projective UV (already divided and normalised to [0,1])
     * and per-vertex gouraud lighting values that modulate the texture. z0/1/2 are view-space Z.
     */
    void addTextured(int x0, int y0, int x1, int y1, int x2, int y2,
                     float u0, float v0, float u1, float v1, float u2, float v2,
                     int hsl0, int hsl1, int hsl2, int texId, int z0, int z1, int z2);

    void beginFrame();
    void endFrame();
    boolean shouldClose();
}

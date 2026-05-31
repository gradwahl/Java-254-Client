package com.gradwahl.rs254;

import com.gradwahl.rs254.cache.DiskCache;
import com.gradwahl.rs254.cache.IndexedSprite;
import com.gradwahl.rs254.cache.JagFile;

import javax.imageio.ImageIO;
import java.awt.Graphics;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Loads and renders the RS254 title screen assets from title.jag (archive 0, file 1).
 *
 * Layout (765×503 canvas):
 *   - Background: title.dat (JPEG, 383×503) drawn at x=0 and mirrored at x=382
 *   - Logo:       logo.dat  centred horizontally, y=18
 *   - Login box:  titlebox.dat centred, y=182
 */
public final class TitleScreen {
    // Title screen layout constants
    static final int CANVAS_W = 765;
    static final int CANVAS_H = 503;

    // Login box position — centred
    static final int BOX_X = (CANVAS_W - 360) / 2; // 202
    static final int BOX_Y = 182;

    // Username/password fields inside the box
    static final int FIELD_W     = 250;
    static final int FIELD_H     = 15;
    static final int FIELD_X     = BOX_X + 55;
    static final int USERNAME_Y  = BOX_Y + 70;
    static final int PASSWORD_Y  = BOX_Y + 100;

    // Login button (existing user)
    static final int BTN_X       = BOX_X + 18;
    static final int BTN_Y       = BOX_Y + 142;

    private BufferedImage background; // left half (383×503)
    private BufferedImage bgMirror;   // right half (mirrored)
    private IndexedSprite logo;
    private IndexedSprite titleBox;
    private IndexedSprite titleButton;
    private boolean loaded;
    private String loadError;

    public void load(ClientConfig config) {
        try {
            DiskCache cache = new DiskCache(config.cacheDir());
            byte[] raw = cache.read(0, 1); // archive 0, file 1 = title.jag
            if (raw == null) throw new IOException("title.jag not found in cache");

            JagFile jag = JagFile.load(raw);

            // Background — stored as JPEG
            byte[] titleDat = jag.get("title.dat");
            if (titleDat != null) {
                background = ImageIO.read(new ByteArrayInputStream(titleDat));
                bgMirror   = mirrorH(background);
            }

            logo        = IndexedSprite.decode(jag, "logo");
            titleBox    = IndexedSprite.decode(jag, "titlebox");
            titleButton = IndexedSprite.decode(jag, "titlebutton");

            loaded = true;
        } catch (Exception e) {
            loadError = e.getMessage();
            System.err.println("[title] Load failed: " + e.getMessage());
        }
    }

    public boolean isLoaded() { return loaded; }
    public String  loadError() { return loadError; }

    /** Render background + logo + login box onto {@code g}. */
    public void renderBackground(Graphics g) {
        if (!loaded) return;

        if (background != null) {
            g.drawImage(background, 0, 0, null);
            // Mirror fills x=383..765 (383 px wide matches the image)
            g.drawImage(bgMirror, CANVAS_W - bgMirror.getWidth(), 0, null);
        }

        if (logo != null && logo.frames.length > 0) {
            int lx = (CANVAS_W - logo.fullWidth) / 2;
            g.drawImage(logo.frames[0], lx, 18, null);
        }

        if (titleBox != null && titleBox.frames.length > 0) {
            g.drawImage(titleBox.frames[0], BOX_X, BOX_Y, null);
        }

        if (titleButton != null && titleButton.frames.length > 0) {
            g.drawImage(titleButton.frames[0], BTN_X, BTN_Y, null);
        }
    }

    // -------------------------------------------------------------------------

    private static BufferedImage mirrorH(BufferedImage src) {
        AffineTransform tx = AffineTransform.getScaleInstance(-1, 1);
        tx.translate(-src.getWidth(), 0);
        AffineTransformOp op = new AffineTransformOp(tx, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);
        return op.filter(src, null);
    }
}

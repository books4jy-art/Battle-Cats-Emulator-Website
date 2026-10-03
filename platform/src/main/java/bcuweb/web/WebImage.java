package bcuweb.web;

import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.function.Supplier;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * BCU's image type for the browser. An image is either
 * <ul>
 * <li>a PNG from the game files: its size is read from the PNG header straight away, the pixels are
 * decoded by the browser (asynchronously) the first time it's drawn or {@link #prepare()}d;</li>
 * <li>a rectangle of another image (sprite sheets are cut into parts this way; parts share the decoded sheet);</li>
 * <li>a blank canvas the core draws into ({@link #getGraphics()}).</li>
 * </ul>
 * Until its pixels are ready an image draws nothing (battles prepare their images before starting).
 */
public class WebImage implements FakeImage {
    /** Images being decoded right now (the page waits for 0 before starting a battle). */
    public static int pending;

    private Supplier<InputStream> source;
    private final WebImage parent;
    private final int x, y;
    private int w = -1, h = -1;
    private JSObject bitmap;   // ImageBitmap or OffscreenCanvas once ready
    private JSObject canvas;   // own pixels (blank images, or after setRGB)
    private boolean decoding, failed;
    // BCU recolours some effects pixel by pixel (EffAnim.excColor: a fixed swap of the colour channels).
    // That's far too slow through a canvas, so while it runs we only work out which swap it is, then
    // apply it to the whole image at once.
    private boolean recolor;
    private int[] swap; // output r, g, b <- input channel index

    public WebImage(Supplier<InputStream> source) {
        this.source = source;
        this.parent = null;
        this.x = this.y = 0;
    }

    public WebImage(int w, int h) {
        this.parent = null;
        this.x = this.y = 0;
        this.w = Math.max(1, w);
        this.h = Math.max(1, h);
        this.canvas = Canvas.newCanvas(this.w, this.h);
        this.bitmap = canvas;
    }

    private WebImage(WebImage parent, int x, int y, int w, int h) {
        this.parent = parent;
        this.x = x;
        this.y = y;
        this.w = Math.max(1, w);
        this.h = Math.max(1, h);
    }

    // ---------------------------------------------------------------- pixels
    private byte[] bytes() {
        try (InputStream is = source.get()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = is.read(buf)) > 0) {
                out.write(buf, 0, r);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private void readSize() {
        if (w >= 0 || parent != null) {
            return;
        }
        w = h = 0;
        byte[] b = source == null ? null : bytes();
        if (b != null && b.length >= 24 && (b[1] & 0xff) == 'P' && (b[2] & 0xff) == 'N' && (b[3] & 0xff) == 'G') {
            w = int32(b, 16); // PNG IHDR: width, height (big-endian)
            h = int32(b, 20);
        }
    }

    private static int int32(byte[] b, int i) {
        return (b[i] & 0xff) << 24 | (b[i + 1] & 0xff) << 16 | (b[i + 2] & 0xff) << 8 | (b[i + 3] & 0xff);
    }

    /** Starts decoding the pixels if that hasn't happened yet. */
    public void prepare() {
        if (parent != null) {
            parent.prepare();
            return;
        }
        if (bitmap != null || decoding || failed || source == null) {
            return;
        }
        byte[] b = bytes();
        if (b == null) {
            failed = true;
            return;
        }
        decoding = true;
        pending++;
        Canvas.decode(Int8Array.fromJavaArray(b), (bmp, bw, bh) -> {
            decoding = false;
            pending--;
            if (bmp == null) {
                failed = true;
            } else {
                bitmap = swap == null ? bmp : Canvas.swapChannels(bmp, swap[0], swap[1], swap[2]);
                w = bw;
                h = bh;
            }
        });
    }

    /** The drawable source (ImageBitmap/OffscreenCanvas) of this image or of the sheet it's cut from; null if not ready. */
    JSObject source() {
        if (parent != null) {
            return parent.source();
        }
        if (bitmap == null) {
            prepare();
        }
        return bitmap;
    }

    int offsetX() {
        return parent != null ? parent.offsetX() + x : 0;
    }

    int offsetY() {
        return parent != null ? parent.offsetY() + y : 0;
    }

    /** Gives this image its own canvas copy of its pixels (needed before changing pixels). */
    private JSObject ownCanvas() {
        if (canvas == null) {
            JSObject src = source();
            canvas = Canvas.newCanvas(getWidth(), getHeight());
            if (src != null) {
                Canvas.drawImage(Canvas.context(canvas), src, offsetX(), offsetY(), getWidth(), getHeight(), 0, 0, getWidth(), getHeight());
            }
        }
        return canvas;
    }

    // ---------------------------------------------------------------- FakeImage
    @Override
    public Object bimg() {
        return this;
    }

    @Override
    public int getHeight() {
        readSize();
        return h;
    }

    @Override
    public int getWidth() {
        readSize();
        return w;
    }

    private static final int PROBE = 1 << 24 | 0x10 << 16 | 0x20 << 8 | 0x30; // a=1, r/g/b tell themselves apart

    @Override
    public void mark(Marker m) {
        if (m == Marker.RECOLOR && parent == null && canvas == null) {
            recolor = true;
        } else if (m == Marker.RECOLORED) {
            recolor = false;
            if (swap != null && bitmap != null && canvas == null) {
                bitmap = Canvas.swapChannels(bitmap, swap[0], swap[1], swap[2]);
            }
        }
    }

    @Override
    public int getRGB(int i, int j) {
        if (recolor) {
            return PROBE;
        }
        if (canvas == null && source() == null) {
            return 0;
        }
        return Canvas.getPixel(Canvas.context(ownCanvas()), i, j);
    }

    @Override
    public void setRGB(int i, int j, int p) {
        if (recolor) {
            if (swap == null) {
                swap = new int[] { channel(p >> 16 & 255), channel(p >> 8 & 255), channel(p & 255) };
            }
            return;
        }
        Canvas.setPixel(Canvas.context(ownCanvas()), i, j, p);
        if (parent == null) {
            bitmap = canvas;
        }
    }

    private static int channel(int v) {
        return v == 0x10 ? 0 : v == 0x20 ? 1 : v == 0x30 ? 2 : 0;
    }

    @Override
    public FakeImage getSubimage(int i, int j, int k, int l) {
        return new WebImage(this, i, j, k, l);
    }

    @Override
    public Object gl() {
        return null;
    }

    @Override
    public boolean isValid() {
        return !failed;
    }

    @Override
    public void unload() {
        // keep decoded pixels: they're shared by all parts cut from this sheet
    }

    @Override
    public FakeImage cloneImage() {
        WebImage c = new WebImage(getWidth(), getHeight());
        JSObject src = canvas != null ? canvas : source();
        if (src != null) {
            Canvas.drawImage(Canvas.context(c.canvas), src, canvas != null ? 0 : offsetX(), canvas != null ? 0 : offsetY(),
                    getWidth(), getHeight(), 0, 0, getWidth(), getHeight());
        }
        return c;
    }

    @Override
    public FakeGraphics getGraphics() {
        return new WebGraphics(Canvas.context(ownCanvas()));
    }

    /** What to draw: own canvas if it has one, else the (shared) decoded sheet. */
    JSObject drawSource() {
        return canvas != null ? canvas : source();
    }

    boolean ownsPixels() {
        return canvas != null;
    }
}

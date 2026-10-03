package bcuweb.jvm;

import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import java.io.InputStream;
import java.util.function.Supplier;

/**
 * Image stand-in for the JVM test harness (no drawing there): remembers where the picture comes from
 * and reads only its size (from the PNG header) when asked.
 */
public class LazyImage implements FakeImage {
    private final Supplier<InputStream> source;
    private final LazyImage parent;
    private int x, y, w = -1, h = -1;

    public LazyImage(Supplier<InputStream> source) {
        this.source = source;
        this.parent = null;
    }

    public LazyImage(int w, int h) {
        this.source = null;
        this.parent = null;
        this.w = w;
        this.h = h;
    }

    private LazyImage(LazyImage parent, int x, int y, int w, int h) {
        this.source = null;
        this.parent = parent;
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    private void readSize() {
        if (w >= 0) {
            return;
        }
        w = h = 0;
        if (source == null) {
            return;
        }
        try (InputStream is = source.get()) {
            byte[] head = new byte[24];
            int n = 0;
            while (n < 24) {
                int r = is.read(head, n, 24 - n);
                if (r < 0) {
                    break;
                }
                n += r;
            }
            if (n == 24 && (head[1] & 0xff) == 'P' && (head[2] & 0xff) == 'N' && (head[3] & 0xff) == 'G') {
                w = int32(head, 16); // PNG IHDR: width then height, big-endian
                h = int32(head, 20);
            }
        } catch (Exception e) {
            w = h = 0;
        }
    }

    private static int int32(byte[] b, int i) {
        return (b[i] & 0xff) << 24 | (b[i + 1] & 0xff) << 16 | (b[i + 2] & 0xff) << 8 | (b[i + 3] & 0xff);
    }

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

    @Override
    public int getRGB(int i, int j) {
        return 0;
    }

    @Override
    public FakeImage getSubimage(int i, int j, int k, int l) {
        return new LazyImage(this, i, j, k, l);
    }

    @Override
    public Object gl() {
        return null;
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public void setRGB(int i, int j, int p) {
    }

    @Override
    public void unload() {
    }

    @Override
    public FakeImage cloneImage() {
        return parent != null ? new LazyImage(parent, x, y, w, h) : source != null ? new LazyImage(source) : new LazyImage(w, h);
    }

    @Override
    public FakeGraphics getGraphics() {
        return null;
    }
}

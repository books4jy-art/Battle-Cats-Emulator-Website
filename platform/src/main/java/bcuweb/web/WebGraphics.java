package bcuweb.web;

import common.system.fake.FakeGraphics;
import common.system.fake.FakeImage;
import common.system.fake.FakeTransform;
import org.teavm.jso.JSObject;

/**
 * BCU's drawing interface on a browser Canvas 2D context (modelled on the Android app's CVGraphics).
 * Transforms map one-to-one (Canvas translate/scale/rotate multiply on the right, like Android's pre*()).
 */
public class WebGraphics implements FakeGraphics {
    /** Extra composite mode (as in the Android app): clears the negative/mask filters. */
    public static final int POSITIVE = 100;

    private final JSObject c;
    private String color = "#000";
    private float alpha = 1;
    private String op = "source-over";
    private boolean mask;
    private boolean positive; // after POSITIVE: no negative filter until the next composite change
    /** Negative colours (used while the battle is paused by time-stop effects). */
    public boolean neg;

    public WebGraphics(JSObject context) {
        this.c = context;
        Canvas.smoothing(c, true);
    }

    public JSObject context() {
        return c;
    }

    // What the context is currently set to (avoids re-setting canvas state for every call).
    private float curAlpha = -1;
    private String curOp, curFilter;

    private void state(float a, String o, String f) {
        if (a != curAlpha || !o.equals(curOp) || !f.equals(curFilter)) {
            Canvas.composite(c, a, o, f);
            curAlpha = a;
            curOp = o;
            curFilter = f;
        }
    }

    /** Images get the current alpha, blend mode and colour filter (as Android's bitmap paint). */
    private void imageState() {
        state(alpha, op, mask ? "brightness(0)" : neg && !positive ? "invert(1)" : "none");
    }

    /** Solid colours ignore the image composite (as Android's separate colour paint). */
    private void shapeState() {
        state(1, "source-over", "none");
    }

    // ---------------------------------------------------------------- images
    @Override
    public void drawImage(FakeImage img, float x, float y) {
        drawImage(img, x, y, img.getWidth(), img.getHeight());
    }

    @Override
    public void drawImage(FakeImage img, float x, float y, float d, float e) {
        if (!(img instanceof WebImage) || d * e == 0) {
            return;
        }
        WebImage wi = (WebImage) img;
        JSObject src = wi.drawSource();
        if (src == null) {
            return; // still decoding
        }
        int sx = wi.ownsPixels() ? 0 : wi.offsetX(), sy = wi.ownsPixels() ? 0 : wi.offsetY();
        int sw = wi.getWidth(), sh = wi.getHeight();
        if (sw <= 0 || sh <= 0) {
            return;
        }
        imageState();
        if (d < 0 || e < 0) {
            // A negative width/height means "mirrored" in BCU (Java2D and Android draw it flipped, e.g. a left
            // arm made from the right-arm picture). Canvas would only move the rectangle, so flip it here.
            JSObject saved = Canvas.getTransform(c);
            Canvas.translate(c, x, y);
            Canvas.scale(c, d < 0 ? -1 : 1, e < 0 ? -1 : 1);
            Canvas.drawImage(c, src, sx, sy, sw, sh, 0, 0, Math.abs(d), Math.abs(e));
            Canvas.setTransform(c, saved);
            return;
        }
        Canvas.drawImage(c, src, sx, sy, sw, sh, x, y, d, e);
    }

    // ---------------------------------------------------------------- shapes
    @Override
    public void colRect(float x, float y, float w, float h, int r, int g, int b, int a) {
        shapeState();
        Canvas.color(c, Canvas.rgba(r, g, b, Math.max(0, Math.min(255, a))));
        Canvas.fillRect(c, x, y, w, h);
        Canvas.color(c, color);
    }

    @Override
    public void drawLine(float i, float j, float x, float y) {
        shapeState();
        Canvas.line(c, i, j, x, y);
    }

    @Override
    public void drawOval(float i, float j, float k, float l) {
        shapeState();
        Canvas.oval(c, i, j, k, l, false);
    }

    @Override
    public void fillOval(float i, float j, float k, float l) {
        shapeState();
        Canvas.oval(c, i, j, k, l, true);
    }

    @Override
    public void drawRect(float x, float y, float w, float h) {
        shapeState();
        Canvas.strokeRect(c, x, y, w, h);
    }

    @Override
    public void fillRect(float x, float y, float w, float h) {
        shapeState();
        Canvas.fillRect(c, x, y, w, h);
    }

    @Override
    public void gradRect(float x, float y, float w, float h, float a, float b, int[] top, float d, float e, int[] bottom) {
        state(1, "source-over", neg ? "invert(1)" : "none");
        Canvas.gradRect(c, x, y, w, h, Canvas.rgba(top[0], top[1], top[2], 255), Canvas.rgba(bottom[0], bottom[1], bottom[2], 255));
    }

    @Override
    public void gradRectAlpha(float x, float y, float w, float h, float a, float b, int al, int[] top, float d, float e, int al2, int[] bottom) {
        state(1, "source-over", neg ? "invert(1)" : "none");
        Canvas.gradRect(c, x, y, w, h, Canvas.rgba(top[0], top[1], top[2], al), Canvas.rgba(bottom[0], bottom[1], bottom[2], al2));
    }

    @Override
    public void setColor(int col) {
        switch (col) {
            case RED: color = "#ff0000"; break;
            case YELLOW: color = "#ffff00"; break;
            case BLACK: color = "#000000"; break;
            case MAGENTA: color = "#ff00ff"; break;
            case BLUE: color = "#0000ff"; break;
            case CYAN: color = "#00ffff"; break;
            case WHITE: color = "#ffffff"; break;
            default:
                color = Canvas.rgba((col >> 16) & 255, (col >> 8) & 255, col & 255, 255);
        }
        Canvas.color(c, color);
    }

    @Override
    public void setColor(int r, int g, int b) {
        color = Canvas.rgba(r, g, b, 255);
        Canvas.color(c, color);
    }

    // ---------------------------------------------------------------- composite
    @Override
    public void setComposite(int mode, int p0, int p1) {
        float a = Math.max(0, Math.min(255, p0)) / 255f;
        switch (mode) {
            case DEF:
                alpha = 1;
                op = "source-over";
                mask = false;
                break;
            case TRANS:
                alpha = a;
                op = "source-over";
                mask = false;
                break;
            case BLEND:
                alpha = a;
                op = p1 == 1 ? "lighter" : p1 == 2 ? "multiply" : p1 == 3 ? "screen" : p1 == -1 ? "darken" : "source-over";
                mask = false;
                break;
            case GRAY: // Android: negative colours (time stop)
                neg = true;
                break;
            case MASK: // solid black in the image's shape, with alpha p0
                mask = true;
                alpha = a;
                break;
            case POSITIVE: // drops the colour filters for what follows; p0 == 1 also ends the negative mode
                mask = false;
                if (p0 == 1) {
                    neg = false;
                }
                positive = true;
                return;
            default:
                break;
        }
        positive = false;
    }

    @Override
    public void setRenderingHint(int key, int object) {
    }

    // ---------------------------------------------------------------- transforms
    private static final class Transform implements FakeTransform {
        final JSObject m;

        Transform(JSObject m) {
            this.m = m;
        }

        @Override
        public Object getAT() {
            return m;
        }
    }

    @Override
    public FakeTransform getTransform() {
        return new Transform(Canvas.getTransform(c));
    }

    @Override
    public void setTransform(FakeTransform at) {
        Canvas.setTransform(c, ((Transform) at).m);
    }

    @Override
    public void translate(float x, float y) {
        Canvas.translate(c, x, y);
    }

    @Override
    public void scale(float hf, float vf) {
        Canvas.scale(c, hf, vf);
    }

    @Override
    public void rotate(float d) {
        Canvas.rotate(c, d);
    }

    /** Back to a clean state at the start of a frame. */
    public void reset() {
        Canvas.resetTransform(c);
        alpha = 1;
        op = "source-over";
        mask = false;
        positive = false;
        curAlpha = -1;
        shapeState();
    }
}

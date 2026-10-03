package bcuweb.web;

import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Thin calls into the browser's Canvas 2D API (on OffscreenCanvas, inside the worker). Contexts,
 * bitmaps and matrices are passed around as opaque JS objects.
 */
public final class Canvas {
    private Canvas() {
    }

    // TeaVM pastes these scripts into its own (minified) code, so anything needing local variables lives
    // in site/js/worker.js (bcuCanvas) and is only called from here.

    @JSFunctor
    public interface BitmapCallback extends JSObject {
        void done(JSObject bitmap, int width, int height);
    }

    /** Decodes PNG bytes in the background (browser's native decoder); calls back with an ImageBitmap (or null). */
    @JSBody(params = { "bytes", "cb" }, script = "createImageBitmap(new Blob([bytes], {type: 'image/png'}))"
            + ".then(b => cb(b, b.width, b.height), () => cb(null, 0, 0));")
    public static native void decode(Int8Array bytes, BitmapCallback cb);

    @JSBody(params = { "w", "h" }, script = "return new OffscreenCanvas(Math.max(1, w), Math.max(1, h));")
    public static native JSObject newCanvas(int w, int h);

    @JSBody(params = "canvas", script = "return canvas.getContext('2d');")
    public static native JSObject context(JSObject canvas);

    @JSBody(params = "canvas", script = "return canvas.width;")
    public static native int width(JSObject canvas);

    @JSBody(params = "canvas", script = "return canvas.height;")
    public static native int height(JSObject canvas);

    @JSBody(params = { "canvas", "w", "h" }, script = "if (canvas.width !== w) canvas.width = w; if (canvas.height !== h) canvas.height = h;")
    public static native void resize(JSObject canvas, int w, int h);

    // ---- drawing
    @JSBody(params = { "c", "img", "sx", "sy", "sw", "sh", "x", "y", "w", "h" },
            script = "c.drawImage(img, sx, sy, sw, sh, x, y, w, h);")
    public static native void drawImage(JSObject c, JSObject img, float sx, float sy, float sw, float sh, float x, float y, float w, float h);

    @JSBody(params = { "c", "css" }, script = "c.fillStyle = css; c.strokeStyle = css;")
    public static native void color(JSObject c, String css);

    @JSBody(params = { "c", "x", "y", "w", "h" }, script = "c.fillRect(x, y, w, h);")
    public static native void fillRect(JSObject c, float x, float y, float w, float h);

    @JSBody(params = { "c", "x", "y", "w", "h" }, script = "c.strokeRect(x, y, w, h);")
    public static native void strokeRect(JSObject c, float x, float y, float w, float h);

    @JSBody(params = { "c", "x0", "y0", "x1", "y1" }, script = "c.beginPath(); c.moveTo(x0, y0); c.lineTo(x1, y1); c.stroke();")
    public static native void line(JSObject c, float x0, float y0, float x1, float y1);

    @JSBody(params = { "c", "x", "y", "w", "h", "fill" }, script = "if (w <= 0 || h <= 0) return; c.beginPath();"
            + " c.ellipse(x + w / 2, y + h / 2, w / 2, h / 2, 0, 0, Math.PI * 2); if (fill) c.fill(); else c.stroke();")
    public static native void oval(JSObject c, float x, float y, float w, float h, boolean fill);

    @JSBody(params = { "c", "x", "y", "w", "h", "top", "bottom" }, script = "bcuCanvas.gradRect(c, x, y, w, h, top, bottom);")
    public static native void gradRect(JSObject c, float x, float y, float w, float h, String top, String bottom);

    // ---- state
    @JSBody(params = { "c", "x", "y" }, script = "c.translate(x, y);")
    public static native void translate(JSObject c, float x, float y);

    @JSBody(params = { "c", "x", "y" }, script = "c.scale(x, y);")
    public static native void scale(JSObject c, float x, float y);

    @JSBody(params = { "c", "a" }, script = "c.rotate(a);")
    public static native void rotate(JSObject c, float a);

    @JSBody(params = "c", script = "return c.getTransform();")
    public static native JSObject getTransform(JSObject c);

    @JSBody(params = { "c", "m" }, script = "c.setTransform(m);")
    public static native void setTransform(JSObject c, JSObject m);

    @JSBody(params = "c", script = "c.setTransform(1, 0, 0, 1, 0, 0);")
    public static native void resetTransform(JSObject c);

    @JSBody(params = { "c", "alpha", "op", "filter" }, script = "c.globalAlpha = alpha; c.globalCompositeOperation = op; c.filter = filter;")
    public static native void composite(JSObject c, float alpha, String op, String filter);

    @JSBody(params = { "c", "smooth" }, script = "c.imageSmoothingEnabled = smooth;")
    public static native void smoothing(JSObject c, boolean smooth);

    // ---- pixels (rarely used by the core; slow)
    @JSBody(params = { "c", "x", "y" }, script = "return bcuCanvas.getPixel(c, x, y);")
    public static native int getPixel(JSObject c, int x, int y);

    @JSBody(params = { "c", "x", "y", "argb" }, script = "bcuCanvas.setPixel(c, x, y, argb);")
    public static native void setPixel(JSObject c, int x, int y, int argb);

    /**
     * Copy of a bitmap with its colour channels swapped: output red/green/blue take input channel
     * src[0..2] (0 = red, 1 = green, 2 = blue). Used for BCU's recoloured effects.
     */
    @JSBody(params = { "img", "r", "g", "b" }, script = "return bcuCanvas.swapChannels(img, r, g, b);")
    public static native JSObject swapChannels(JSObject img, int r, int g, int b);

    @JSBody(params = { "c", "text", "x", "y", "font" }, script = "c.font = font; c.fillText(text, x, y);")
    public static native void text(JSObject c, String text, float x, float y, String font);

    public static String rgba(int r, int g, int b, int a) {
        return "rgba(" + r + "," + g + "," + b + "," + (a / 255f) + ")";
    }
}

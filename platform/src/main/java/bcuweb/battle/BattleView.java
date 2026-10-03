package bcuweb.battle;

import bcuweb.BattleSession;
import bcuweb.web.Canvas;
import bcuweb.web.WebGraphics;
import bcuweb.web.WebImage;
import common.CommonStatic;
import common.CommonStatic.BCAuxAssets;
import common.battle.SBCtrl;
import common.system.VImg;
import common.system.fake.FakeImage;
import common.util.anim.AnimU;
import common.util.unit.Enemy;
import common.util.unit.Form;
import org.teavm.jso.JSObject;

/**
 * Draws the running battle onto the page's canvas (an OffscreenCanvas handed to the worker).
 * Images decode in the background: a frame drawn before they're ready leaves them out, and the
 * worker draws it again once they are ({@link WebImage#pending}).
 */
public final class BattleView implements BattlePainter.Box {
    private static final BattleView VIEW = new BattleView();

    private JSObject canvas;
    private WebGraphics g;
    private SBCtrl battle;
    private BattlePainter painter;
    private int speed;

    private BattleView() {
    }

    public static void attach(JSObject canvas) {
        VIEW.canvas = canvas;
        VIEW.g = new WebGraphics(Canvas.context(canvas));
    }

    /** Draws the current frame; returns how many images are still decoding (0 = the frame is complete). */
    public static int draw(int speed) {
        BattleView v = VIEW;
        SBCtrl b = BattleSession.battle();
        if (v.canvas == null || b == null) {
            return 0;
        }
        if (b != v.battle) {
            v.battle = b;
            v.painter = new BattlePainter(v, b);
            v.painter.dpi = 32;
            v.painter.stmImageOffset = 52;
            v.g.neg = false;
            prepare(b);
        }
        v.speed = speed;
        v.g.reset();
        Canvas.color(v.g.context(), "#000");
        Canvas.fillRect(v.g.context(), 0, 0, v.getWidth(), v.getHeight());
        v.painter.draw(v.g);
        return WebImage.pending;
    }

    // ---------------------------------------------------------------- input (canvas pixels)
    /**
     * A tap or click at (x, y): deploys the cat whose slot is there, levels up the worker, fires the
     * cannon or toggles the sniper. Same hit areas as the BCU Android app (BBCtrl.click); long = also lock
     * the slot (BCU's long press).
     */
    public static void tap(float x, float y, boolean longPress) {
        BattleView v = VIEW;
        SBCtrl b = v.battle;
        if (b == null || v.painter == null || b.sb.ebase.health <= 0 || b.sb.ubase.health <= 0) {
            return;
        }
        BCAuxAssets aux = CommonStatic.getBCAssets();
        int w = v.getWidth(), h = v.getHeight();
        float hr = v.painter.unir;
        float term = hr * aux.slot[0].getImg().getWidth() * 0.2f;
        int row = b.sb.frontLineup;
        for (int i = 0; i < 5; i++) {
            Form f = b.sb.b.lu.fs[row][i];
            FakeImage img = f == null ? aux.slot[0].getImg() : f.anim.getUni().getImg();
            int iw = (int) (hr * img.getWidth()), ih = (int) (hr * img.getHeight());
            int sx = (w - iw * 5) / 2 + iw * i + (int) (term * (i - 2) + (row == 0 ? 0 : term / 2));
            int sy = h - (int) (ih * 1.1);
            if (inside(x, y, sx, sy, sx + iw, sy + ih)) {
                BattleSession.act(i + row * 5);
                if (longPress) {
                    BattleSession.act(10);
                }
                return;
            }
        }
        if (longPress) {
            return;
        }
        hr = v.painter.corr;
        float cutout = v.painter.cutout, gap = BattlePainter.BOTTOM_GAP * hr;
        FakeImage left = aux.battle[0][0].getImg(), right = aux.battle[1][0].getImg();
        if (inside(x, y, cutout - gap, h - hr * left.getHeight(), cutout - gap + (int) (hr * left.getWidth()), h)) {
            BattleSession.act(-1);
        } else if (inside(x, y, w - (int) (hr * right.getWidth()) - cutout + gap, h - (int) (hr * right.getHeight()), w - cutout + gap, h)) {
            BattleSession.act(-2);
        } else if ((b.sb.conf[0] & 2) > 0) {
            float ratio = v.painter.dpi / 42f;
            FakeImage bimg = aux.battle[2][1].getImg();
            int cw = (int) (bimg.getWidth() * ratio), ch = (int) (bimg.getHeight() * ratio);
            int mh = (int) (aux.num[0][0].getImg().getHeight() * ratio);
            if (inside(x, y, w - cw - cutout, mh, w - cutout, mh + ch)) {
                BattleSession.act(-3);
            }
        }
    }

    private static boolean inside(float x, float y, float x0, float y0, float x1, float y1) {
        return x >= x0 && x <= x1 && y >= y0 && y <= y1;
    }

    /** Is (x, y) on the lineup / bottom buttons (where a vertical swipe switches rows instead of scrolling)? */
    public static boolean onLineup(float x, float y) {
        return VIEW.painter != null && y > VIEW.getHeight() * 0.75f;
    }

    /** Scrolls the battlefield sideways by dx canvas pixels. */
    public static void pan(float dx) {
        SBCtrl b = VIEW.battle;
        if (b != null && VIEW.painter != null) {
            b.sb.pos += (int) dx;
            VIEW.painter.regulate();
        }
    }

    /** Zooms by factor around canvas x (pinch / mouse wheel), as the Android app's ScaleListener. */
    public static void zoom(float factor, float focusX) {
        SBCtrl b = VIEW.battle;
        if (b == null || VIEW.painter == null) {
            return;
        }
        float before = b.sb.siz;
        int pos = b.sb.pos;
        b.sb.siz *= factor;
        VIEW.painter.regulate();
        float diff = (focusX - pos) * (b.sb.siz / before - 1);
        b.sb.pos = (int) (pos - diff);
        VIEW.painter.regulate();
    }

    /** Starts decoding the pictures a battle needs right away (the lineup's and the stage's enemies' animations). */
    private static void prepare(SBCtrl b) {
        for (Form[] row : b.sb.b.lu.fs) {
            for (Form f : row) {
                if (f != null) {
                    prepare(f.anim);
                    prepare(f.anim.getUni());
                }
            }
        }
        for (Enemy e : b.sb.st.data.getAllEnemy()) {
            prepare(e.anim);
        }
    }

    private static void prepare(AnimU<?> anim) {
        if (anim == null) {
            return;
        }
        anim.check();
        prepare(anim.getNum());
    }

    private static void prepare(VImg v) {
        if (v != null) {
            prepare(v.getImg());
        }
    }

    private static void prepare(FakeImage img) {
        if (img instanceof WebImage) {
            ((WebImage) img).prepare();
        }
    }

    @Override
    public int getWidth() {
        return Canvas.width(canvas);
    }

    @Override
    public int getHeight() {
        return Canvas.height(canvas);
    }

    @Override
    public int getSpeed() {
        return speed;
    }

    static {
        CommonStatic.getConfig().battle = false;
    }
}

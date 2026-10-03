package bcuweb.battle;

import bcuweb.BattleSession;
import bcuweb.web.Canvas;
import bcuweb.web.WebGraphics;
import bcuweb.web.WebImage;
import common.CommonStatic;
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
    private int speed = 1;

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

package bcuweb;

import bcuweb.battle.BattleView;
import bcuweb.web.Js;
import bcuweb.web.WebContext;
import bcuweb.web.WebFileData;
import bcuweb.web.WebImageBuilder;
import bcuweb.web.WebItf;
import common.CommonStatic;
import common.pack.UserProfile;
import common.system.fake.ImageBuilder;
import common.system.files.VFile;
import common.system.files.VFileRoot;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * Browser entry point (runs inside a Web Worker, see site/js/worker.js). main() wires BCU's core to
 * the browser and exposes a few functions to JavaScript; the worker calls them.
 */
public class Main {
    @JSFunctor
    public interface StrFn extends JSObject {
        String call(String arg);
    }

    @JSFunctor
    public interface BattleStartFn extends JSObject {
        String call(String colc, int map, int stage, int seed, String lineup, boolean auto);
    }

    @JSFunctor
    public interface StepFn extends JSObject {
        String call(int frames);
    }

    @JSFunctor
    public interface ObjFn extends JSObject {
        String call(JSObject arg);
    }

    public static void main(String[] args) {
        CommonStatic.ctx = new WebContext();
        CommonStatic.def = new WebItf();
        ImageBuilder.builder = new WebImageBuilder();
        Js.export("bcuLoad", (StrFn) Main::load);
        Js.export("bcuStages", (StrFn) s -> BattleSession.stages());
        Js.export("bcuSetLang", (StrFn) lang -> {
            Names.setLanguage(lang);
            return lang;
        });
        Js.export("bcuBattleStart", (BattleStartFn) BattleSession::start);
        Js.export("bcuUnits", (StrFn) s -> BattleSession.units());
        Js.export("bcuBattleFiles", (BattleStartFn) (colc, map, stage, seed, lineup, auto) -> BattleSession.battleFiles(colc, map, stage, lineup));
        Js.export("bcuInput", (StrFn) Main::input);
        Js.export("bcuBattleStep", (StepFn) BattleSession::step);
        Js.export("bcuAttachCanvas", (ObjFn) c -> {
            BattleView.attach(c);
            return "";
        });
        Js.export("bcuDraw", (StepFn) speed -> String.valueOf(BattleView.draw(speed)));
        Js.post("ready", "");
    }

    /**
     * Player input on the battle, one command per call: "tap x y long", "pan dx", "zoom factor x",
     * "act code" (see {@link BattleSession#act}). Coordinates are canvas pixels.
     */
    private static String input(String cmd) {
        String[] p = cmd.split(" ");
        switch (p[0]) {
            case "tap":
                BattleView.tap(Float.parseFloat(p[1]), Float.parseFloat(p[2]), "1".equals(p[3]));
                break;
            case "pan":
                BattleView.pan(Float.parseFloat(p[1]));
                break;
            case "zoom":
                BattleView.zoom(Float.parseFloat(p[1]), Float.parseFloat(p[2]));
                break;
            case "act":
                BattleSession.act(Integer.parseInt(p[1]));
                break;
            default:
                return "unknown";
        }
        return "";
    }

    /** Builds BCU's virtual file tree from the asset index, then loads the base game (units, enemies, stages...). */
    private static String load(String lang) {
        long t0 = System.currentTimeMillis();
        Names.setLanguage(lang);
        VFileRoot root = VFile.getBCFileTree();
        int n = 0;
        for (String line : Js.fileList().split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) {
                String path = line.substring(0, tab);
                root.build(path, new WebFileData(path, Integer.parseInt(line.substring(tab + 1))));
                n++;
            }
        }
        Js.post("progress", n + " files in the virtual file tree");
        UserProfile.getBCData().load(s -> Js.post("progress", s), d -> {
        });
        Names.load();
        return "{\"files\":" + n + ",\"ms\":" + (System.currentTimeMillis() - t0)
                + ",\"units\":" + UserProfile.getBCData().units.size()
                + ",\"enemies\":" + UserProfile.getBCData().enemies.size() + "}";
    }
}

package bcuweb;

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
        String call(String colc, int map, int stage, int seed);
    }

    @JSFunctor
    public interface StepFn extends JSObject {
        String call(int frames);
    }

    public static void main(String[] args) {
        CommonStatic.ctx = new WebContext();
        CommonStatic.def = new WebItf();
        ImageBuilder.builder = new WebImageBuilder();
        Js.export("bcuLoad", (StrFn) Main::load);
        Js.export("bcuStages", (StrFn) s -> BattleSession.stages());
        Js.export("bcuBattleStart", (BattleStartFn) BattleSession::start);
        Js.export("bcuBattleStep", (StepFn) BattleSession::step);
        Js.post("ready", "");
    }

    /** Builds BCU's virtual file tree from the asset index, then loads the base game (units, enemies, stages...). */
    private static String load(String lang) {
        long t0 = System.currentTimeMillis();
        CommonStatic.getConfig().lang = "kr".equals(lang) ? CommonStatic.Lang.Locale.KR : CommonStatic.Lang.Locale.EN;
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
        return "{\"files\":" + n + ",\"ms\":" + (System.currentTimeMillis() - t0)
                + ",\"units\":" + UserProfile.getBCData().units.size()
                + ",\"enemies\":" + UserProfile.getBCData().enemies.size() + "}";
    }
}

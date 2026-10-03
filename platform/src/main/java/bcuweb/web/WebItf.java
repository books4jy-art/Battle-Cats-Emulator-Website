package bcuweb.web;

import common.CommonStatic;
import common.pack.Identifier;
import common.util.stage.Music;
import java.io.File;

/** BCU's sound/app hooks for the browser: sound effects are collected for the page to play (see BattleSession). */
public class WebItf implements CommonStatic.Itf {
    @Override
    public void save(boolean save, boolean exit) {
    }

    @Override
    public long getMusicLength(Music f) {
        return 0;
    }

    @Override
    @Deprecated
    public File route(String path) {
        return new File("/bcu/" + path);
    }

    @Override
    public void setSE(int mus) {
        bcuweb.BattleSession.sound(mus);
    }

    @Override
    public void setSE(Identifier<Music> mus) {
        if (mus != null && Identifier.DEF.equals(mus.pack)) {
            bcuweb.BattleSession.sound(mus.id);
        }
    }

    @Override
    public void setBGM(Identifier<Music> mus) {
    }
}

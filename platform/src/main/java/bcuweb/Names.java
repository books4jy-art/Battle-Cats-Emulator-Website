package bcuweb;

import bcuweb.web.Js;
import common.CommonStatic;
import common.CommonStatic.Lang.Locale;
import common.pack.UserProfile;
import common.util.lang.MultiLangCont;
import common.util.stage.MapColc;
import common.util.stage.MapColc.DefMapColc;
import common.util.stage.Stage;
import common.util.stage.StageMap;
import common.util.unit.Enemy;
import common.util.unit.Unit;
import java.nio.charset.StandardCharsets;

/**
 * Stage, cat and enemy names. BCU keeps them in per-language text files (bcu-assets/lang/xx-*.txt), which
 * the desktop app reads into the core's MultiLangCont at start-up; this does the same for the files the
 * worker downloaded. The core's toString() methods then show names in the current language (falling back
 * to other languages, e.g. Japanese, where a name isn't translated).
 */
public final class Names {
    private Names() {
    }

    static final String[][] LANGS = { { "en", "EN" }, { "kr", "KR" }, { "jp", "JP" } };

    public static void load() {
        for (String[] l : LANGS) {
            Locale locale = Locale.valueOf(l[1]);
            stageNames(locale, read(l[0] + "-StageName.txt"));
            unitNames(locale, read(l[0] + "-UnitName.txt"));
            enemyNames(locale, read(l[0] + "-EnemyName.txt"));
        }
    }

    public static void setLanguage(String lang) {
        CommonStatic.getConfig().lang = "kr".equals(lang) ? Locale.KR : Locale.EN;
    }

    private static String[] read(String file) {
        byte[] bs = Js.bytes(Js.readExtra("names/" + file));
        return bs == null ? new String[0] : new String(bs, StandardCharsets.UTF_8).split("\r?\n");
    }

    /** Lines "id<TAB>name", where id is "c" (collection), "c-m" (map) or "c-m-s" (stage). */
    private static void stageNames(Locale locale, String[] lines) {
        for (String line : lines) {
            String[] strs = line.trim().split("\t");
            if (strs.length < 2) {
                continue;
            }
            String idstr = strs[0].trim();
            String name = strs[strs.length - 1].trim();
            if (idstr.isEmpty() || name.isEmpty()) {
                continue;
            }
            String[] ids = idstr.split("-");
            StageMap stm = DefMapColc.getMap(CommonStatic.parseIntN(ids[0]) * 1000);
            MapColc mc = stm == null ? null : stm.getCont();
            if (mc == null) {
                continue;
            }
            if (ids.length == 1) {
                MultiLangCont.getStatic().MCNAME.put(locale, mc, name);
                continue;
            }
            int id1 = CommonStatic.parseIntN(ids[1]);
            StageMap sm = id1 >= 0 && id1 < mc.maps.size() ? mc.maps.get(id1) : null;
            if (sm == null) {
                continue;
            }
            if (ids.length == 2) {
                MultiLangCont.getStatic().SMNAME.put(locale, sm, name);
                continue;
            }
            int id2 = CommonStatic.parseIntN(ids[2]);
            if (id2 >= 0 && id2 < sm.list.size()) {
                Stage st = sm.list.get(id2);
                MultiLangCont.getStatic().STNAME.put(locale, st, name);
            }
        }
    }

    /** Lines "unit id<TAB>form 1 name<TAB>form 2 name...". */
    private static void unitNames(Locale locale, String[] lines) {
        for (String line : lines) {
            String[] strs = line.trim().split("\t");
            if (strs.length < 2) {
                continue;
            }
            Unit u = UserProfile.getBCData().units.get(CommonStatic.parseIntN(strs[0]));
            if (u == null) {
                continue;
            }
            for (int i = 0; i < Math.min(u.forms.length, strs.length - 1); i++) {
                MultiLangCont.getStatic().FNAME.put(locale, u.forms[i], strs[i + 1].trim());
            }
        }
    }

    /** Lines "enemy id<TAB>name". */
    private static void enemyNames(Locale locale, String[] lines) {
        for (String line : lines) {
            String[] strs = line.trim().split("\t");
            if (strs.length < 2) {
                continue;
            }
            Enemy e = UserProfile.getBCData().enemies.get(CommonStatic.parseIntN(strs[0]));
            if (e != null) {
                MultiLangCont.getStatic().ENAME.put(locale, e, strs[1].trim());
            }
        }
    }
}

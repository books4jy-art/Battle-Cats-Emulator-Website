package bcuweb.info;

import common.battle.BasisSet;
import common.battle.Treasure;
import common.battle.data.MaskAtk;
import common.battle.data.MaskEntity;
import common.battle.data.MaskUnit;
import common.battle.data.PCoin;
import common.pack.Identifier;
import common.pack.UserProfile;
import common.util.Data;
import common.util.Data.Proc.ProcItem;
import common.util.lang.Formatter;
import common.util.lang.MultiLangCont;
import common.util.lang.ProcLang;
import common.util.stage.MapColc;
import common.util.stage.SCDef;
import common.util.stage.Stage;
import common.util.unit.AbEnemy;
import common.util.unit.EForm;
import common.util.unit.Enemy;
import common.util.unit.Form;
import common.util.unit.Level;
import common.util.unit.Trait;
import common.util.unit.Unit;
import java.util.List;

/**
 * Data for the info pages (cats, enemies, stages), as JSON. Numbers are worked out the way BCU's desktop
 * app does in its info tables (page.info.UnitInfoTable / EnemyInfoTable / StageTable): same level, talent
 * and treasure handling as battles. Ability texts come from the core's own ProcLang/Formatter (the
 * proc.json texts BCU ships); traits and simple abilities are sent as numbers and named by the page.
 */
public final class Info {
    private Info() {
    }

    // ---------------------------------------------------------------- cats
    /** One cat form at a level (lv and plus below 0: BCU's defaults, with all talents). */
    public static String unit(int id, int formIndex, int lvIn, int plusIn) {
        Unit u = UserProfile.getBCData().units.get(id);
        if (u == null || u.id.id != id || formIndex < 0 || formIndex >= u.forms.length) {
            return "{\"error\":\"no such cat\"}";
        }
        Form f = u.forms[formIndex];
        Level lv = u.getPrefLvs();
        if (lvIn > 0) {
            lv.setLevel(Math.min(lvIn, u.max));
        }
        if (plusIn >= 0) {
            lv.setPlusLevel(Math.min(plusIn, u.maxp));
        }
        Treasure t = BasisSet.current().t();
        EForm ef = new EForm(f, lv);
        MaskUnit du = ef.du;
        double mul = u.lv.getMult(lv.getLv() + lv.getPlusLv());
        PCoin pc = f.du.getPCoin();
        double pcAtk = pc == null ? 1 : pc.getAtkMultiplication(lv.getTalents());
        double pcHp = pc == null ? 1 : pc.getHPMultiplication(lv.getTalents());
        int attack = (int) ((int) (Math.round(du.allAtk() * mul) * t.getAtkMulti()) * pcAtk);
        int hp = (int) ((int) (Math.round(du.getHp() * mul) * t.getDefMulti()) * pcHp);

        J j = new J();
        j.num("id", id).num("form", formIndex).str("name", formName(f)).num("rarity", u.rarity)
                .num("lv", lv.getLv()).num("plus", lv.getPlusLv()).num("max", u.max).num("maxp", u.maxp)
                .bool("talents", pc != null)
                .num("hp", hp).num("kb", du.getHb()).num("atk", attack).num("dps", attack * 30 / Math.max(1, du.getItv()))
                .num("range", du.getRange()).num("speed", du.getSpeed()).num("cost", (int) ef.getPrice(1))
                .num("cooldown", t.getFinRes(du.getRespawn(), 0)).num("itv", du.getItv()).num("tba", du.getTBA())
                .num("post", du.getPost()).bool("area", du.isRange()).bool("ld", du.isLD()).bool("omni", du.isOmni());
        attacks(j, du, mul * t.getAtkMulti(), pcAtk);
        traits(j, du.getTraits());
        abilities(j, du);
        procs(j, du, false, new double[] { mul, lv.getLv() + lv.getPlusLv() });
        StringBuilder forms = new StringBuilder("[");
        for (int i = 0; i < u.forms.length; i++) {
            forms.append(i == 0 ? "" : ",").append(q(formName(u.forms[i])));
        }
        j.raw("forms", forms.append(']').toString());
        return j.end();
    }

    // ---------------------------------------------------------------- enemies
    /** One enemy at a strength (hp % and attack %, as stages set them). */
    public static String enemy(int id, int magHp, int magAtk) {
        Enemy e = UserProfile.getBCData().enemies.get(id);
        if (e == null || e.id.id != id) {
            return "{\"error\":\"no such enemy\"}";
        }
        BasisSet b = BasisSet.current();
        double m = e.de.multi(b);
        double mul = magHp * m / 100, mula = magAtk * m / 100;
        int attack = (int) (e.de.allAtk() * mula);
        J j = new J();
        j.num("id", id).str("name", enemyName(e)).num("magHp", magHp).num("magAtk", magAtk)
                .num("hp", (int) (e.de.getHp() * mul)).num("kb", e.de.getHb()).num("atk", attack)
                .num("dps", attack * 30 / Math.max(1, e.de.getItv()))
                .num("money", Math.floor(e.de.getDrop() * b.t().getDropMulti()) / 100)
                .num("range", e.de.getRange()).num("speed", e.de.getSpeed()).num("itv", e.de.getItv()).num("tba", e.de.getTBA())
                .num("post", e.de.getPost()).bool("area", e.de.isRange()).bool("ld", e.de.isLD()).bool("omni", e.de.isOmni());
        attacks(j, e.de, mula, 1);
        traits(j, e.de.getTraits());
        abilities(j, e.de);
        procs(j, e.de, true, new double[] { mul, mula });
        return j.end();
    }

    /** Every base-game enemy for the enemy list: id, name, icon file. */
    public static String enemies() {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Enemy e : UserProfile.getBCData().enemies) {
            sb.append(first ? "" : ",").append('[').append(e.id.id).append(',').append(q(enemyName(e))).append(',')
                    .append(q("./org/enemy/" + Data.trio(e.id.id) + "/edi_" + Data.trio(e.id.id) + ".png")).append(']');
            first = false;
        }
        return sb.append(']').toString();
    }

    // ---------------------------------------------------------------- stages
    /** A stage: its settings and the enemy list (as BCU's StageTable shows it). */
    public static String stage(String colc, int map, int stage, int crown) {
        Stage st = null;
        for (MapColc mc : MapColc.values()) {
            if (mc.getSID().equals(colc) && map < mc.maps.size() && stage < mc.maps.get(map).list.size()) {
                st = mc.maps.get(map).list.get(stage);
            }
        }
        if (st == null) {
            return "{\"error\":\"no such stage\"}";
        }
        J j = new J();
        j.str("name", st.toString()).str("map", st.getCont().toString()).num("len", st.len).num("health", st.health).num("max", st.max)
                .num("timeLimit", st.timeLimit).bool("continue", !st.non_con).bool("bossGuard", st.bossGuard)
                .num("minSpawn", st.minSpawn).num("maxSpawn", st.maxSpawn);
        // enemy strength: a row's % times the stage map's star (crown) multiplier; 0 means "the star multiplier"
        int[] stars = st.getCont().stars;
        int star = stars.length > 0 ? stars[Math.max(0, Math.min(crown, stars.length - 1))] : 100;
        StringBuilder sj = new StringBuilder("[");
        for (int i = 0; i < stars.length; i++) {
            sj.append(i == 0 ? "" : ",").append(stars[i]);
        }
        j.raw("stars", sj.append(']').toString()).num("star", star);
        StringBuilder rows = new StringBuilder("[");
        SCDef.Line[] lines = st.data.getSimple();
        for (int i = 0; i < lines.length; i++) {
            SCDef.Line l = lines[i];
            AbEnemy ab = l.enemy == null ? null : Identifier.get(l.enemy);
            int eid = ab instanceof Enemy && Identifier.DEF.equals(l.enemy.pack) ? l.enemy.id : -1;
            String name = ab instanceof Enemy ? enemyName((Enemy) ab) : ab == null ? "?" : ab.toString();
            rows.append(i == 0 ? "" : ",").append("{\"id\":").append(eid).append(",\"name\":").append(q(name))
                    .append(",\"number\":").append(l.number).append(",\"boss\":").append(l.boss)
                    .append(",\"mag\":").append(l.multiple == 0 && l.mult_atk == 0 ? 100 : l.multiple)
                    .append(",\"magAtk\":").append(l.multiple == 0 && l.mult_atk == 0 ? 100 : l.mult_atk)
                    .append(",\"start\":[").append(l.spawn_0).append(',').append(l.spawn_1).append(']')
                    .append(",\"respawn\":[").append(l.respawn_0).append(',').append(l.respawn_1).append(']')
                    .append(",\"castle\":[").append(l.castle_0).append(',').append(l.castle_1).append(']')
                    .append(",\"layer\":[").append(l.layer_0).append(',').append(l.layer_1).append(']')
                    .append(",\"kill\":").append(l.kill_count).append('}');
        }
        j.raw("enemies", rows.append(']').toString());
        return j.end();
    }

    // ---------------------------------------------------------------- shared
    private static void attacks(J j, MaskEntity du, double mul, double extra) {
        StringBuilder sb = new StringBuilder("[");
        int[][] raw = du.rawAtkData();
        for (int i = 0; i < raw.length; i++) {
            int dmg = (int) ((int) (Math.round(raw[i][0] * mul)) * extra);
            sb.append(i == 0 ? "" : ",").append('[').append(dmg).append(',').append(raw[i][1]).append(']');
        }
        j.raw("hits", sb.append(']').toString()); // [damage, frame of the hit]
    }

    /** Trait numbers (base game) or names (custom traits). */
    private static void traits(J j, List<Trait> traits) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Trait t : traits) {
            sb.append(first ? "" : ",").append(Identifier.DEF.equals(t.id.pack) ? String.valueOf(t.id.id) : q(t.name));
            first = false;
        }
        j.raw("traits", sb.append(']').toString());
    }

    /** Ability bit numbers (Data.ABI_*), named by the page. */
    private static void abilities(J j, MaskEntity du) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (int i = 0; i < Data.ABI_TOT; i++) {
            if (((du.getAbi() >> i) & 1) > 0) {
                sb.append(first ? "" : ",").append(i);
                first = false;
            }
        }
        j.raw("abilities", sb.append(']').toString());
    }

    /** Ability texts from BCU's proc.json (in the current language), as the desktop app's Interpret.getProc. */
    private static void procs(J j, MaskEntity du, boolean enemy, double[] magnif) {
        Formatter.Context ctx = new Formatter.Context(enemy, false, magnif);
        MaskAtk ma = du.getRepAtk();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (int i = 0; i < Data.PROC_TOT; i++) {
            ProcItem item = ma.getProc().getArr(i);
            if (item == null || !item.exists()) {
                continue;
            }
            String text;
            try {
                text = Formatter.format(ProcLang.get().get(i).format, item, ctx);
            } catch (Exception ex) {
                continue;
            }
            sb.append(first ? "" : ",").append(q(text));
            first = false;
        }
        j.raw("procs", sb.append(']').toString());
    }

    static String formName(Form f) {
        String n = MultiLangCont.get(f);
        return n == null || n.isEmpty() ? f.names.toString() : n;
    }

    static String enemyName(Enemy e) {
        String n = MultiLangCont.get(e);
        return n == null || n.isEmpty() ? e.names.toString() : n;
    }

    static String q(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c == '\n') {
                b.append("\\n");
            } else if (c < 0x20) {
                b.append(' ');
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }

    /** Tiny JSON object writer. */
    private static final class J {
        private final StringBuilder sb = new StringBuilder("{");

        private J key(String k) {
            if (sb.length() > 1) {
                sb.append(',');
            }
            sb.append('"').append(k).append("\":");
            return this;
        }

        J num(String k, double v) {
            key(k);
            if (v == Math.rint(v) && Math.abs(v) < 1e15) {
                sb.append((long) v);
            } else {
                sb.append(v);
            }
            return this;
        }

        J str(String k, String v) {
            key(k).sb.append(q(v));
            return this;
        }

        J bool(String k, boolean v) {
            key(k).sb.append(v);
            return this;
        }

        J raw(String k, String json) {
            key(k).sb.append(json);
            return this;
        }

        String end() {
            return sb.append('}').toString();
        }
    }
}

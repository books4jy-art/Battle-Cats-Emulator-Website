package bcuweb;

import common.CommonStatic;
import common.battle.BasisLU;
import common.battle.BasisSet;
import common.battle.SBCtrl;
import common.battle.StageBasis;
import common.battle.entity.Entity;
import common.pack.UserProfile;
import common.util.lang.MultiLangCont;
import common.util.stage.MapColc;
import common.util.stage.Stage;
import common.util.stage.StageMap;
import common.util.unit.Enemy;
import common.util.unit.Form;
import common.util.unit.Level;
import common.util.unit.Unit;

/**
 * Plain-Java battle helpers shared by the browser entry point ({@link Main}) and the JVM test harness:
 * stage listing, starting a battle and stepping it, with results as JSON text.
 */
public final class BattleSession {
    private BattleSession() {
    }

    private static SBCtrl battle;

    /** Crown (star) difficulty for the next battle: 0 = first crown (EoC: chapter 1), as the stage map's stars. */
    private static int star;

    public static void setStar(int s) {
        star = Math.max(0, s);
    }

    /** Sound effects (BCU music numbers) the battle asked for since the last step; each played once per step. */
    private static final java.util.LinkedHashSet<Integer> sounds = new java.util.LinkedHashSet<>();
    private static boolean autoMode;

    /** BCU's core asks for a sound effect (CommonStatic.setSE, through WebItf). */
    public static void sound(int id) {
        // auto-deploy "presses" every slot each frame: skip its endless "can't afford" beeps
        if (autoMode && id == common.util.Data.SE_SPEND_FAIL) {
            return;
        }
        if (battle != null && sounds.size() < 32) {
            sounds.add(id);
        }
    }

    /** The battle running now (null before the first start). */
    public static SBCtrl battle() {
        return battle;
    }

    /** Auto-deploy "player": keeps trying to deploy every cat in the front row and to level up the worker. */
    private static final CommonStatic.FakeKey AUTO = new CommonStatic.FakeKey() {
        @Override
        public boolean pressed(int i, int j) {
            return i == 0 || (i == -1 && j == 0);
        }

        @Override
        public void remove(int i, int j) {
        }
    };

    /** No keys held: the player acts through {@link #act(int)} (taps and clicks on the battle). */
    private static final CommonStatic.FakeKey NONE = new CommonStatic.FakeKey() {
        @Override
        public boolean pressed(int i, int j) {
            return false;
        }

        @Override
        public void remove(int i, int j) {
        }
    };

    /** The stage collections, maps and stages of the base game, as JSON for the stage picker. */
    public static String stages() {
        StringBuilder sb = new StringBuilder("[");
        boolean firstColc = true;
        for (MapColc mc : MapColc.values()) {
            if (!(mc instanceof MapColc.DefMapColc)) {
                continue;
            }
            sb.append(firstColc ? "" : ",").append("{\"id\":").append(q(mc.getSID())).append(",\"name\":").append(q(mc.toString())).append(",\"maps\":[");
            firstColc = false;
            boolean firstMap = true;
            for (StageMap sm : mc.maps) {
                StringBuilder stars = new StringBuilder();
                for (int i = 0; i < sm.stars.length; i++) {
                    stars.append(i == 0 ? "" : ",").append(sm.stars[i]);
                }
                sb.append(firstMap ? "" : ",").append("{\"name\":").append(q(sm.toString()))
                        .append(",\"stars\":[").append(stars).append("],\"stages\":[");
                firstMap = false;
                boolean firstSt = true;
                for (Stage st : sm.list) {
                    sb.append(firstSt ? "" : ",").append(q(st.toString()));
                    firstSt = false;
                }
                sb.append("]}");
            }
            sb.append("]}");
        }
        return sb.append("]").toString();
    }

    /** Starts a battle on the given stage with the first five basic cats, deploying them automatically. */
    public static String start(String colc, int map, int stage, int seed) {
        StringBuilder lineup = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            lineup.append(i == 0 ? "" : ",").append(i).append(":0");
        }
        return start(colc, map, stage, seed, lineup.toString(), true);
    }

    /**
     * Starts a battle. lineup: up to 10 comma-separated slots (first row, then second row), each
     * "unitId:form:level:plusLevel:talents:orbs" or "-" for an empty slot. Everything after the form is optional
     * (BCU's default levels, all talents at maximum, no orbs). talents: talent levels separated by ".";
     * orbs: one "type.trait.grade" per orb slot, separated by "/" ("" = no orb). Each row is packed to the left
     * (as in the game: BCU's combos and controls expect no gaps). auto: deploy the front row automatically.
     * Treasures, castle parts and items come from {@link Setup}.
     */
    public static String start(String colc, int map, int stage, int seed, String lineup, boolean auto) {
        MapColc mc = null;
        for (MapColc m : MapColc.values()) {
            if (m.getSID().equals(colc)) {
                mc = m;
            }
        }
        if (mc == null) {
            return "{\"error\":\"no such stage collection\"}";
        }
        Stage st = mc.maps.get(map).list.get(stage);
        BasisSet set = BasisSet.current();
        BasisLU lu = set.sele;
        int placed = fill(lu, lineup);
        if (placed == 0) {
            return "{\"error\":\"empty lineup\"}";
        }
        System.arraycopy(Setup.nyc, 0, lu.nyc, 0, 3);
        // deploy on press (BCU's optional "button delay" selects first and deploys a few frames later)
        CommonStatic.getConfig().buttonDelay = false;
        // one lineup row on screen with a switch button (as in the game), and no hitbox/reference lines
        CommonStatic.getConfig().twoRow = false;
        CommonStatic.getConfig().ref = false;
        autoMode = auto;
        int crown = Math.min(star, Math.max(0, st.getCont().stars.length - 1));
        battle = new SBCtrl(auto ? AUTO : NONE, st, crown, lu.copy(), new int[] { Setup.items }, seed); // ints[0]: bit 1 = max worker, bit 2 = sniper
        sounds.clear();
        // music: mus0 from the start, mus1 once the enemy base's health drops below mush percent (as BCU's apps)
        return "{\"stage\":" + q(st.toString()) + ",\"len\":" + st.len
                + ",\"mus0\":" + musicId(st.mus0) + ",\"loop0\":" + musicLoop(st.mus0)
                + ",\"mus1\":" + musicId(st.mus1) + ",\"loop1\":" + musicLoop(st.mus1) + ",\"mush\":" + st.mush + "}";
    }

    /** Puts a lineup (see {@link #start}) into a BasisLU, each row packed to the left; returns the number of cats. */
    private static int fill(BasisLU lu, String lineup) {
        String[] slots = lineup.split(",");
        int placed = 0;
        lu.lu.map.clear(); // (levels, talents and orbs of earlier battles)
        int[] used = new int[2];
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < 5; c++) {
                lu.lu.fs[r][c] = null;
            }
        }
        for (int i = 0; i < 10; i++) {
            String[] p = i < slots.length ? slots[i].trim().split(":", -1) : new String[0];
            if (p.length < 2) {
                continue;
            }
            Unit u = UserProfile.getBCData().units.get(parse(p[0], -1));
            int form = parse(p[1], 0);
            if (u == null || form < 0 || form >= u.forms.length) {
                continue;
            }
            Level lv = u.getPrefLvs(); // BCU's default levels (and all talents), used where none is given
            if (p.length > 2 && !p[2].isEmpty()) {
                lv.setLevel(Math.max(1, Math.min(parse(p[2], lv.getLv()), u.max)));
            }
            if (p.length > 3 && !p[3].isEmpty()) {
                lv.setPlusLevel(Math.max(0, Math.min(parse(p[3], lv.getPlusLv()), u.maxp)));
            }
            if (p.length > 4 && !p[4].isEmpty()) {
                int[] max = lv.getTalents().clone(); // (at maximum)
                int[] t = new int[max.length];
                String[] ts = p[4].split("\\.");
                for (int j = 0; j < t.length; j++) {
                    t[j] = j < ts.length ? Math.max(0, Math.min(parse(ts[j], max[j]), max[j])) : max[j];
                }
                lv.setTalents(t);
            }
            if (p.length > 5 && !p[5].isEmpty() && !u.orbs.isEmpty()) {
                String[] os = p[5].split("/", -1);
                int[][] orbs = new int[u.orbs.size()][];
                for (int j = 0; j < orbs.length; j++) {
                    String[] o = j < os.length ? os[j].split("\\.") : new String[0];
                    orbs[j] = o.length == 3 ? new int[] { parse(o[0], 0), parse(o[1], 0), parse(o[2], 0) } : new int[0];
                }
                lv.setOrbs(orbs);
            }
            lu.lu.setLv(u, lv);
            int r = i / 5;
            lu.lu.fs[r][used[r]++] = u.forms[form];
            placed++;
        }
        lu.lu.renew();
        return placed;
    }

    /** The combos a lineup's front row makes: [[combo id, name, type, level], ...]. */
    public static String combos(String lineup) {
        BasisLU lu = new BasisLU();
        fill(lu, lineup);
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (common.util.unit.Combo c : lu.lu.coms) {
            sb.append(first ? "" : ",").append('[').append(c.id.id).append(',').append(q(c.getName()))
                    .append(',').append(c.type).append(',').append(c.lv).append(']');
            first = false;
        }
        return sb.append(']').toString();
    }

    /** Number of a base-game music file (bcu-assets/music/NNN.ogg), or -1. */
    private static int musicId(common.pack.Identifier<common.util.stage.Music> m) {
        return m != null && common.pack.Identifier.DEF.equals(m.pack) ? m.id : -1;
    }

    /** Loop start of a music in milliseconds (0: the whole track loops). */
    private static long musicLoop(common.pack.Identifier<common.util.stage.Music> m) {
        common.util.stage.Music mu = m == null ? null : common.pack.Identifier.get(m);
        return mu == null ? 0 : mu.loop;
    }

    /**
     * A player action, as BCU's own controls send them: 0-9 deploy the cat in that slot (row * 5 + column),
     * 10 = lock the slot pressed with it, -1 = worker level-up, -2 = cat cannon, -3 = sniper,
     * -4 / -5 = switch lineup row up / down.
     */
    public static void act(int code) {
        if (battle != null) {
            battle.action.add(code);
        }
    }

    /** Every base-game cat for the lineup editor: id, rarity, max and default levels, form names, icon files. */
    public static String units() {
        StringBuilder sb = new StringBuilder("{\"uniCut\":");
        int[] cut = CommonStatic.getBCAssets().unicut.cuts[0];
        sb.append('[').append(cut[0]).append(',').append(cut[1]).append(',').append(cut[2]).append(',').append(cut[3]).append("],\"units\":[");
        boolean first = true;
        for (Unit u : UserProfile.getBCData().units) {
            sb.append(first ? "" : ",").append("{\"id\":").append(u.id.id).append(",\"r\":").append(u.rarity)
                    .append(",\"max\":").append(u.max).append(",\"maxp\":").append(u.maxp)
                    .append(",\"lv\":").append(u.getPreferredLevel()).append(",\"plus\":").append(u.getPreferredPlusLevel()).append(",\"f\":[");
            first = false;
            for (int i = 0; i < u.forms.length; i++) {
                String name = MultiLangCont.get(u.forms[i]); // (Form.toString() adds "id-form " in front)
                if (name == null || name.isEmpty()) {
                    name = u.forms[i].names.toString();
                }
                sb.append(i == 0 ? "" : ",").append(q(name == null ? "" : name));
            }
            sb.append("]");
            // talents (of the form with the most, as BCU's default levels): [talent type, max level, super talent 0/1]
            common.battle.data.PCoin pc = null;
            for (Form f : u.forms) {
                common.battle.data.PCoin c = f.du.getPCoin();
                if (c != null && (pc == null || c.max.length > pc.max.length)) {
                    pc = c;
                }
            }
            if (pc != null) {
                sb.append(",\"t\":[");
                for (int i = 0; i < pc.max.length; i++) {
                    int[] inf = pc.info.get(i);
                    sb.append(i == 0 ? "" : ",").append('[').append(inf[0]).append(',').append(pc.max[i]).append(',').append(inf[13] == 1 ? 1 : 0).append(']');
                }
                sb.append(']');
            }
            if (!u.orbs.isEmpty()) {
                // orb slots, and the level (level + plus) each one needs (some need 60, as in the game)
                sb.append(",\"o\":").append(u.orbs.size()).append(",\"ol\":[");
                for (int i = 0; i < u.orbs.size(); i++) {
                    sb.append(i == 0 ? "" : ",").append(u.orbs.get(i).minLv);
                }
                sb.append(']');
            }
            sb.append(",\"i\":[");
            for (int i = 0; i < u.forms.length; i++) {
                sb.append(i == 0 ? "" : ",").append(q(iconPath(u.forms[i])));
            }
            sb.append("]}");
        }
        return sb.append("]}").toString();
    }

    /**
     * Folders of game files a battle will read (animations of the lineup's forms and the stage's enemies,
     * castles), one per line, so the browser can download them in bulk before the battle starts.
     */
    public static String battleFiles(String colc, int map, int stage, String lineup) {
        StringBuilder sb = new StringBuilder("./org/castle/\n./org/img/ec/\n");
        for (String slot : lineup.split(",")) {
            String[] p = slot.trim().split(":");
            if (p.length < 2) {
                continue;
            }
            Unit u = UserProfile.getBCData().units.get(parse(p[0], -1));
            int form = parse(p[1], 0);
            if (u != null && form >= 0 && form < u.forms.length) {
                String icon = iconPath(u.forms[form]);
                if (icon != null) {
                    sb.append(icon, 0, icon.lastIndexOf('/') + 1).append('\n');
                }
            }
        }
        for (MapColc mc : MapColc.values()) {
            if (mc.getSID().equals(colc) && map < mc.maps.size() && stage < mc.maps.get(map).list.size()) {
                for (Enemy e : mc.maps.get(map).list.get(stage).data.getAllEnemy()) {
                    if (e.id.pack.equals(common.pack.Identifier.DEF)) {
                        sb.append("./org/enemy/").append(common.util.Data.trio(e.id.id)).append("/\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    /** The game file with a cat form's deploy icon (as Form builds it: "uni" + animation name + "00.png"). */
    private static String iconPath(Form f) {
        String name = f.anim.toString(); // "000_f", or "123_m" for forms from the shared egg folder
        int us = name.indexOf('_');
        if (us < 0) {
            return null;
        }
        String id = name.substring(0, us), suffix = name.substring(us + 1);
        if (suffix.equals("m")) {
            return "./org/img/m/" + id + "/uni" + name + (f.fid < 10 ? "0" : "") + f.fid + ".png";
        }
        return "./org/unit/" + id + "/" + suffix + "/uni" + name + "00.png";
    }

    private static int parse(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Runs the battle for some frames (30 per second) and returns what's on the field afterwards. */
    public static String step(int frames) {
        if (battle == null) {
            return "{\"error\":\"no battle\"}";
        }
        StageBasis sb = battle.sb;
        for (int i = 0; i < frames && result(sb) == 0; i++) {
            battle.update();
        }
        StringBuilder out = new StringBuilder("{\"time\":").append(sb.time)
                .append(",\"result\":").append(result(sb))
                .append(",\"money\":").append(sb.money).append(",\"maxMoney\":").append(sb.maxMoney)
                .append(",\"workLv\":").append(sb.work_lv).append(",\"row\":").append(sb.frontLineup)
                .append(",\"se\":").append(sounds.toString().replace(" ", ""))
                .append(",\"ebase\":[").append(sb.ebase.health).append(',').append(sb.ebase.maxH).append(',').append(sb.ebase.pos).append(']')
                .append(",\"ubase\":[").append(sb.ubase.health).append(',').append(sb.ubase.maxH).append(',').append(sb.ubase.pos).append(']')
                .append(",\"e\":[");
        boolean first = true;
        for (Entity e : sb.le) {
            out.append(first ? "" : ",").append('[').append(Math.round(e.pos)).append(',').append(e.dire)
                    .append(',').append(e.health).append(',').append(e.maxH).append(']');
            first = false;
        }
        sounds.clear();
        return out.append("]}").toString();
    }

    /** 1 = cats won (enemy base destroyed), -1 = cats lost, 0 = still going. */
    public static int result(StageBasis sb) {
        return sb.ebase.health <= 0 ? 1 : sb.ubase.health <= 0 ? -1 : 0;
    }

    static String q(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                b.append('\\').append(c);
            } else if (c < 0x20) {
                b.append(' ');
            } else {
                b.append(c);
            }
        }
        return b.append('"').toString();
    }
}

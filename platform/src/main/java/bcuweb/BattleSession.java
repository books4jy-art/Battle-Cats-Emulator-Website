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
                sb.append(firstMap ? "" : ",").append("{\"name\":").append(q(sm.toString())).append(",\"stages\":[");
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
     * "unitId:form:level:plusLevel" (levels optional: BCU's defaults), or "-" for an empty slot. auto: deploy the front row automatically.
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
        String[] slots = lineup.split(",");
        int placed = 0;
        for (int i = 0; i < 10; i++) {
            Form f = null;
            String[] p = i < slots.length ? slots[i].trim().split(":") : new String[0];
            if (p.length >= 2) {
                Unit u = UserProfile.getBCData().units.get(parse(p[0], -1));
                int form = parse(p[1], 0);
                if (u != null && form >= 0 && form < u.forms.length) {
                    f = u.forms[form];
                    Level lv = u.getPrefLvs(); // BCU's default levels (and all talents), used where none is given
                    if (p.length > 2) {
                        lv.setLevel(Math.max(1, Math.min(parse(p[2], lv.getLv()), u.max)));
                    }
                    if (p.length > 3) {
                        lv.setPlusLevel(Math.max(0, Math.min(parse(p[3], lv.getPlusLv()), u.maxp)));
                    }
                    lu.lu.setLv(u, lv);
                    placed++;
                }
            }
            lu.lu.fs[i / 5][i % 5] = f;
        }
        if (placed == 0) {
            return "{\"error\":\"empty lineup\"}";
        }
        lu.lu.renew();
        // deploy on press (BCU's optional "button delay" selects first and deploys a few frames later)
        CommonStatic.getConfig().buttonDelay = false;
        // one lineup row on screen with a switch button (as in the game), and no hitbox/reference lines
        CommonStatic.getConfig().twoRow = false;
        CommonStatic.getConfig().ref = false;
        battle = new SBCtrl(auto ? AUTO : NONE, st, 0, lu.copy(), new int[1], seed); // ints[0]: bit 1 = max worker, bit 2 = sniper
        return "{\"stage\":" + q(st.toString()) + ",\"len\":" + st.len + "}";
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
            sb.append("],\"i\":[");
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
                .append(",\"ebase\":[").append(sb.ebase.health).append(',').append(sb.ebase.maxH).append(',').append(sb.ebase.pos).append(']')
                .append(",\"ubase\":[").append(sb.ubase.health).append(',').append(sb.ubase.maxH).append(',').append(sb.ubase.pos).append(']')
                .append(",\"e\":[");
        boolean first = true;
        for (Entity e : sb.le) {
            out.append(first ? "" : ",").append('[').append(Math.round(e.pos)).append(',').append(e.dire)
                    .append(',').append(e.health).append(',').append(e.maxH).append(']');
            first = false;
        }
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

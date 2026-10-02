package bcuweb;

import common.CommonStatic;
import common.battle.BasisLU;
import common.battle.BasisSet;
import common.battle.SBCtrl;
import common.battle.StageBasis;
import common.battle.entity.Entity;
import common.pack.UserProfile;
import common.util.stage.MapColc;
import common.util.stage.Stage;
import common.util.stage.StageMap;
import common.util.unit.Unit;

/**
 * Plain-Java battle helpers shared by the browser entry point ({@link Main}) and the JVM test harness:
 * stage listing, starting a battle and stepping it, with results as JSON text.
 */
public final class BattleSession {
    private BattleSession() {
    }

    private static SBCtrl battle;

    /** Milestone 1 "player": keeps trying to deploy every cat in the front row and to level up the worker. */
    private static final CommonStatic.FakeKey AUTO = new CommonStatic.FakeKey() {
        @Override
        public boolean pressed(int i, int j) {
            return i == 0 || (i == -1 && j == 0);
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

    /** Starts a battle on the given stage with the first five basic cats. */
    public static String start(String colc, int map, int stage, int seed) {
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
        for (int i = 0; i < 5; i++) {
            Unit u = UserProfile.getBCData().units.get(i);
            lu.lu.fs[0][i] = u == null ? null : u.forms[0];
        }
        lu.lu.renew();
        // deploy on press (BCU's optional "button delay" selects first and deploys a few frames later)
        CommonStatic.getConfig().buttonDelay = false;
        battle = new SBCtrl(AUTO, st, 0, lu.copy(), new int[1], seed); // ints[0]: bit 1 = max worker, bit 2 = sniper
        return "{\"stage\":" + q(st.toString()) + ",\"len\":" + st.len + "}";
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
                .append(",\"workLv\":").append(sb.work_lv)
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

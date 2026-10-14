package bcuweb;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import common.CommonStatic;
import common.battle.BasisSet;
import common.battle.Treasure;
import common.util.Data;

import java.util.List;
import java.util.Map;

/**
 * Battle setup that isn't the lineup: treasures and base upgrades (BCU's Treasure, used by battles and the
 * info pages), the cat base's cannon, foundation and style parts, and the battle items BCU has (max worker
 * level from the start, sniper cat). The page sends it as JSON; anything left out keeps BCU's default
 * (everything at maximum, no parts, no items).
 */
public final class Setup {
    private Setup() {
    }

    /** Cat base parts: cannon, style (decoration), foundation; 0 = basic, 1-7 = BCU's DECO_BASE_* sets. */
    static final int[] nyc = new int[3];
    /** Battle items, as BCU's battle flags: bit 1 = worker cat starts at max level, bit 2 = sniper the cat. */
    static int items;

    private static int[] fill(int n, int v) {
        int[] a = new int[n];
        java.util.Arrays.fill(a, v);
        return a;
    }

    /** Maximums of everything the page can set, plus the orbs that exist, as JSON. */
    public static String info() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"tech\":").append(arr(Data.MLV)).append(",\"trea\":").append(arr(Data.MT))
                .append(",\"fruit\":300,\"gods\":100,\"alien\":600,\"star\":1500");
        int[] bslv = new int[Data.BASE_TOT], base = new int[Data.DECO_BASE_TOT], deco = new int[Data.DECO_BASE_TOT];
        bslv[0] = 30;
        for (int i = 1; i < Data.BASE_TOT; i++) {
            bslv[i] = max(Treasure.curveData, i);
            base[i - 1] = max(Treasure.baseData, i);
            deco[i - 1] = max(Treasure.decorationData, i);
        }
        sb.append(",\"bslv\":").append(arr(bslv)).append(",\"base\":").append(arr(base)).append(",\"deco\":").append(arr(deco));
        // orbs: [type, [[trait bitmask (bit = BCU trait id, bit 12 = no trait), [grades 0-4 = D-S]], ...]]
        sb.append(",\"orbs\":[");
        boolean first = true;
        for (Map.Entry<Integer, Map<Integer, List<Integer>>> e : CommonStatic.getBCAssets().ORB.entrySet()) {
            sb.append(first ? "" : ",").append('[').append(e.getKey()).append(",[");
            first = false;
            boolean f2 = true;
            for (Map.Entry<Integer, List<Integer>> t : e.getValue().entrySet()) {
                List<Integer> g = new java.util.ArrayList<>(t.getValue());
                java.util.Collections.sort(g);
                sb.append(f2 ? "" : ",").append('[').append(t.getKey()).append(',').append(g.toString().replace(" ", "")).append(']');
                f2 = false;
            }
            sb.append("]]");
        }
        sb.append("],\"combo\":[");
        int[][] v = CommonStatic.getBCAssets().values;
        for (int i = 0; i < v.length; i++) {
            sb.append(i == 0 ? "" : ",").append(arr(v[i]));
        }
        return sb.append("]}").toString();
    }

    private static int max(Map<Integer, common.battle.CannonLevelCurve> m, int i) {
        return m.containsKey(i) ? m.get(i).getMax() : 0;
    }

    /** Applies the page's setup (JSON, see the class comment); returns "" or an error text. */
    public static String apply(String json) {
        Treasure t = BasisSet.current().t();
        JsonObject o;
        try {
            o = JsonParser.parseString(json).getAsJsonObject();
        } catch (RuntimeException e) {
            return "bad setup";
        }
        // (BCU's defaults are the maximums)
        ints(o, "tech", t.tech, Data.MLV, Data.MLV);
        ints(o, "trea", t.trea, Data.MT, Data.MT);
        ints(o, "fruit", t.fruit, fill(7, 300), fill(7, 300));
        ints(o, "gods", t.gods, fill(3, 100), fill(3, 100));
        t.alien = num(o, "alien", 600, 0, 600);
        t.star = num(o, "star", 1500, 0, 1500);
        int[] bslvMax = new int[Data.BASE_TOT], baseMax = new int[Data.DECO_BASE_TOT], decoMax = new int[Data.DECO_BASE_TOT];
        bslvMax[0] = 30;
        for (int i = 1; i < Data.BASE_TOT; i++) {
            bslvMax[i] = max(Treasure.curveData, i);
            baseMax[i - 1] = max(Treasure.baseData, i);
            decoMax[i - 1] = max(Treasure.decorationData, i);
        }
        ints(o, "bslv", t.bslv, bslvMax, bslvMax);
        ints(o, "base", t.base, baseMax, baseMax);
        ints(o, "deco", t.deco, decoMax, decoMax);
        int[] parts = new int[3];
        ints(o, "nyc", parts, new int[3], fill(3, Data.DECO_BASE_TOT));
        System.arraycopy(parts, 0, nyc, 0, 3);
        items = (bool(o, "worker") ? 1 : 0) | (bool(o, "sniper") ? 2 : 0);
        return "";
    }

    private static void ints(JsonObject o, String key, int[] to, int[] def, int[] max) {
        JsonArray a = o.has(key) && o.get(key).isJsonArray() ? o.getAsJsonArray(key) : null;
        for (int i = 0; i < to.length; i++) {
            int v = def[i];
            if (a != null && i < a.size() && a.get(i).isJsonPrimitive()) {
                v = a.get(i).getAsInt();
            }
            to[i] = Math.max(0, Math.min(v, i < max.length ? max[i] : v));
        }
    }

    private static int num(JsonObject o, String key, int def, int min, int max) {
        JsonElement e = o.get(key);
        int v = e != null && e.isJsonPrimitive() ? e.getAsInt() : def;
        return Math.max(min, Math.min(v, max));
    }

    private static boolean bool(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    static String arr(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) {
            sb.append(i == 0 ? "" : ",").append(a[i]);
        }
        return sb.append(']').toString();
    }
}

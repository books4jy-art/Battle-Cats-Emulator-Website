package bcuweb;

import common.io.assets.AssetLoader;
import common.util.Data;
import java.lang.reflect.Field;

/** Browser entry point. M0: proves BCU's core classes run in the browser. */
public class Main {
    public static void main(String[] args) throws Exception {
        System.out.println("BCU core " + AssetLoader.CORE_VER + " running in the browser");
        // reflection the battle engine relies on: ability (Proc) items are read field by field
        Data.Proc proc = Data.Proc.blank();
        Field prob = proc.KB.getClass().getDeclaredField("prob");
        prob.setInt(proc.KB, 100);
        System.out.println("knockback proc fields: " + proc.KB.getClass().getDeclaredFields().length
                + ", prob via reflection = " + prob.getInt(proc.KB));
    }
}

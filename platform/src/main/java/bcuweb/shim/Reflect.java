package bcuweb.shim;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * Class.getMethods, remembered per class. TeaVM rebuilds the whole method list on every call, and the core
 * calls it each time it looks up a game object by ID (Identifier.getContainer). The core's calls are
 * pointed here at build time (see bcuweb.teavm.BcuTeaVMPlugin).
 */
public final class Reflect {
    private Reflect() {
    }

    private static final Map<Class<?>, Method[]> METHODS = new HashMap<>();

    public static Method[] getMethods(Class<?> cls) {
        Method[] ms = METHODS.get(cls);
        if (ms == null) {
            ms = cls.getMethods();
            METHODS.put(cls, ms);
        }
        return ms.clone();
    }
}

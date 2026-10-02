package bcuweb.shim;

import java.lang.reflect.Field;

/**
 * The primitive Field shortcuts TeaVM lacks, built on Field.get/set (which TeaVM supports).
 * Calls in BCU's core are redirected here at build time by {@link bcuweb.teavm.BcuTeaVMPlugin}.
 */
public final class FieldAccess {
    private FieldAccess() {
    }

    public static int getInt(Field f, Object o) throws IllegalAccessException {
        Object v = f.get(o);
        return v instanceof Character ? (Character) v : ((Number) v).intValue();
    }

    public static long getLong(Field f, Object o) throws IllegalAccessException {
        Object v = f.get(o);
        return v instanceof Character ? (Character) v : ((Number) v).longValue();
    }

    public static float getFloat(Field f, Object o) throws IllegalAccessException {
        return ((Number) f.get(o)).floatValue();
    }

    public static double getDouble(Field f, Object o) throws IllegalAccessException {
        return ((Number) f.get(o)).doubleValue();
    }

    public static short getShort(Field f, Object o) throws IllegalAccessException {
        return ((Number) f.get(o)).shortValue();
    }

    public static byte getByte(Field f, Object o) throws IllegalAccessException {
        return ((Number) f.get(o)).byteValue();
    }

    public static char getChar(Field f, Object o) throws IllegalAccessException {
        return (Character) f.get(o);
    }

    public static boolean getBoolean(Field f, Object o) throws IllegalAccessException {
        return (Boolean) f.get(o);
    }

    public static void setInt(Field f, Object o, int v) throws IllegalAccessException {
        f.set(o, v);
    }

    public static void setLong(Field f, Object o, long v) throws IllegalAccessException {
        f.set(o, v);
    }

    public static void setFloat(Field f, Object o, float v) throws IllegalAccessException {
        f.set(o, v);
    }

    public static void setDouble(Field f, Object o, double v) throws IllegalAccessException {
        f.set(o, v);
    }

    public static void setShort(Field f, Object o, short v) throws IllegalAccessException {
        f.set(o, v);
    }

    public static void setByte(Field f, Object o, byte v) throws IllegalAccessException {
        f.set(o, v);
    }

    public static void setChar(Field f, Object o, char v) throws IllegalAccessException {
        f.set(o, v);
    }

    public static void setBoolean(Field f, Object o, boolean v) throws IllegalAccessException {
        f.set(o, v);
    }
}

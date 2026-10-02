package org.teavm.classlib.java.util.concurrent.atomic;

/**
 * java.util.concurrent.atomic.AtomicIntegerArray for TeaVM (which doesn't ship it). Gson's JSON
 * parser needs it. The browser runs this code on one thread, so a plain array is enough.
 * TeaVM uses classes named org.teavm.classlib.java.X.TName as java.X.Name.
 */
public class TAtomicIntegerArray {
    private final int[] array;

    public TAtomicIntegerArray(int length) {
        array = new int[length];
    }

    public TAtomicIntegerArray(int[] values) {
        array = values.clone();
    }

    public final int length() {
        return array.length;
    }

    public final int get(int i) {
        return array[i];
    }

    public final void set(int i, int value) {
        array[i] = value;
    }

    public final void lazySet(int i, int value) {
        array[i] = value;
    }

    public final int getAndSet(int i, int value) {
        int old = array[i];
        array[i] = value;
        return old;
    }

    public final boolean compareAndSet(int i, int expect, int update) {
        if (array[i] != expect) {
            return false;
        }
        array[i] = update;
        return true;
    }

    public final int getAndIncrement(int i) {
        return array[i]++;
    }

    public final int getAndDecrement(int i) {
        return array[i]--;
    }

    public final int getAndAdd(int i, int delta) {
        int old = array[i];
        array[i] += delta;
        return old;
    }

    public final int incrementAndGet(int i) {
        return ++array[i];
    }

    public final int decrementAndGet(int i) {
        return --array[i];
    }

    public final int addAndGet(int i, int delta) {
        return array[i] += delta;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < array.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(array[i]);
        }
        return sb.append(']').toString();
    }
}

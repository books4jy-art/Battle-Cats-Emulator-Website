package bcuweb.shim;

import java.util.ArrayList;

/**
 * String.split with the JDK's fast path: a separator that is one plain character (or a backslash and a
 * non-letter, non-digit) is cut without regular expressions; anything else goes to the real split. The
 * core's String.split calls are pointed here at build time (see bcuweb.teavm.BcuTeaVMPlugin), because
 * TeaVM compiles a regular expression on every call and the core splits a lot of text while loading.
 * Same results as String.split (this is OpenJDK's fast path, written out).
 */
public final class Strings {
    private Strings() {
    }

    public static String[] split(String s, String regex) {
        return split(s, regex, 0);
    }

    public static String[] split(String s, String regex, int limit) {
        char ch;
        boolean fast = regex.length() == 1 && ".$|()[{^?*+\\".indexOf(ch = regex.charAt(0)) == -1
                || regex.length() == 2 && regex.charAt(0) == '\\' && ((ch = regex.charAt(1)) - '0' | '9' - ch) < 0
                        && (ch - 'a' | 'z' - ch) < 0 && (ch - 'A' | 'Z' - ch) < 0;
        if (!fast || Character.isHighSurrogate(ch = regex.charAt(regex.length() - 1)) || Character.isLowSurrogate(ch)) {
            return s.split(regex, limit);
        }
        int off = 0, next;
        boolean limited = limit > 0;
        ArrayList<String> list = new ArrayList<>();
        while ((next = s.indexOf(ch, off)) != -1) {
            if (!limited || list.size() < limit - 1) {
                list.add(s.substring(off, next));
                off = next + 1;
            } else { // last one
                list.add(s.substring(off));
                off = s.length();
                break;
            }
        }
        if (off == 0) { // no match
            return new String[] { s };
        }
        if (!limited || list.size() < limit) {
            list.add(s.substring(off));
        }
        int size = list.size();
        if (limit == 0) {
            while (size > 0 && list.get(size - 1).isEmpty()) {
                size--;
            }
        }
        return list.subList(0, size).toArray(new String[0]);
    }
}

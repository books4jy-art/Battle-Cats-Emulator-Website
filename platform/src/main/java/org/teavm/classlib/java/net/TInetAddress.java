package org.teavm.classlib.java.net;

/**
 * java.net.InetAddress for TeaVM (which doesn't ship it). Gson registers a JSON adapter for it, so the
 * class must exist; BCU never reads or writes IP addresses, so it only holds a string.
 */
public class TInetAddress {
    private final String host;

    protected TInetAddress(String host) {
        this.host = host;
    }

    public static TInetAddress getByName(String host) {
        return new TInetAddress(host);
    }

    public String getHostAddress() {
        return host;
    }

    public String getHostName() {
        return host;
    }

    @Override
    public String toString() {
        return "/" + host;
    }
}

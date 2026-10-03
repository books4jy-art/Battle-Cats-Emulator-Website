package bcuweb.web;

import common.system.fake.FakeImage;
import common.system.files.FileData;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Queue;

/** A game file in BCU's virtual file tree whose bytes come from the browser (fetched and decrypted by JS). */
public class WebFileData implements FileData {
    private final String path;
    private final int size;

    public WebFileData(String path, int size) {
        this.path = path;
        this.size = size;
    }

    @Override
    public byte[] getBytes() {
        byte[] bs = Js.bytes(Js.readFile(path));
        if (bs == null) {
            throw new IllegalStateException("couldn't load " + path);
        }
        return bs;
    }

    @Override
    public FakeImage getImg() {
        return FakeImage.read(this);
    }

    /**
     * Text lines, as FileData.readLine (BufferedReader over UTF-8) gives them, but decoded by the browser:
     * the core reads thousands of text files while loading and Java's stream readers are slow in TeaVM.
     */
    @Override
    public Queue<String> readLine() {
        String text = Js.readText(path);
        if (text == null) {
            throw new IllegalStateException("couldn't load " + path);
        }
        Queue<String> lines = new ArrayDeque<>();
        int n = text.length(), start = 0;
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(text.substring(start, i));
                if (c == '\r' && i + 1 < n && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < n) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    @Override
    public InputStream getStream() {
        return new ByteArrayInputStream(getBytes());
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public String toString() {
        return path;
    }
}

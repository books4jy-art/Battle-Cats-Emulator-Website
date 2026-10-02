package bcuweb.web;

import common.system.fake.FakeImage;
import common.system.files.FileData;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

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

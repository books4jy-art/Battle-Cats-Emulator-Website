package bcuweb.web;

import common.system.fake.FakeImage;
import common.system.fake.ImageBuilder;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;

/** BCU's image factory for the browser. Images are drawn with the browser's Canvas API (see WebImage). */
public class WebImageBuilder extends ImageBuilder<Object> {
    @Override
    public FakeImage build(File f) throws IOException {
        return new WebImage(1, 1);
    }

    @Override
    public FakeImage build(Supplier<InputStream> sup) throws IOException {
        return new WebImage(sup);
    }

    @Override
    public FakeImage build(Object o) {
        return o instanceof FakeImage ? (FakeImage) o : new WebImage(1, 1);
    }

    @Override
    public FakeImage build(int w, int h) {
        return new WebImage(w, h);
    }

    @Override
    public boolean write(FakeImage o, String fmt, Object out) throws IOException {
        return false;
    }
}

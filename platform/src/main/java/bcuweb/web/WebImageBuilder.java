package bcuweb.web;

import common.system.fake.FakeImage;
import common.system.fake.ImageBuilder;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.Supplier;

/** BCU's image factory for the browser. Milestone 1: images that know only their size. */
public class WebImageBuilder extends ImageBuilder<Object> {
    @Override
    public FakeImage build(File f) throws IOException {
        return new LazyImage(0, 0);
    }

    @Override
    public FakeImage build(Supplier<InputStream> sup) throws IOException {
        return new LazyImage(sup);
    }

    @Override
    public FakeImage build(Object o) {
        return o instanceof FakeImage ? (FakeImage) o : new LazyImage(0, 0);
    }

    @Override
    public FakeImage build(int w, int h) {
        return new LazyImage(w, h);
    }

    @Override
    public boolean write(FakeImage o, String fmt, Object out) throws IOException {
        return false;
    }
}

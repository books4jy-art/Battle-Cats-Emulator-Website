package bcuweb.jvm;

import bcuweb.BattleSession;
import bcuweb.web.LazyImage;
import bcuweb.web.WebImageBuilder;
import bcuweb.web.WebItf;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import common.CommonStatic;
import common.io.Backup;
import common.io.PackLoader.ZipDesc.FileDesc;
import common.pack.Context;
import common.pack.UserProfile;
import common.system.fake.FakeImage;
import common.system.fake.ImageBuilder;
import common.system.files.FileData;
import common.system.files.VFile;
import common.system.files.VFileRoot;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Test harness: runs the same battle code as the browser build, but on a normal Java VM, so the two
 * can be compared. Game files come through tools/dev_server.py (must be running) and are decrypted
 * with Java's own AES.
 *
 * <pre>./gradlew runJvmBattle --args="000003 9 0 60"   (collection, map, stage, seconds)</pre>
 */
public class JvmBattle {
    private static final String SERVER = System.getProperty("bcu.server", "http://localhost:8765");
    private static final byte[] IV = hex("5af764bb8e1a80e202fe24a3d56add1d");
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static JsonObject index;

    public static void main(String[] args) throws Exception {
        String colc = args.length > 0 ? args[0] : "000003";
        int map = args.length > 1 ? Integer.parseInt(args[1]) : 9;
        int stage = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int seconds = args.length > 3 ? Integer.parseInt(args[3]) : 60;

        index = JsonParser.parseString(Files.readString(Path.of("site/data/index.json"))).getAsJsonObject();
        CommonStatic.ctx = new JvmContext();
        CommonStatic.def = new WebItf();
        ImageBuilder.builder = new WebImageBuilder();
        VFileRoot root = VFile.getBCFileTree();
        for (Map.Entry<String, com.google.gson.JsonElement> e : index.getAsJsonObject("files").entrySet()) {
            root.build(e.getKey(), new JvmFileData(e.getKey(), e.getValue().getAsJsonArray()));
        }
        long t0 = System.currentTimeMillis();
        UserProfile.getBCData().load(s -> { }, d -> { });
        System.out.println("loaded in " + (System.currentTimeMillis() - t0) + " ms: "
                + UserProfile.getBCData().units.size() + " units, " + UserProfile.getBCData().enemies.size() + " enemies");
        System.out.println(BattleSession.start(colc, map, stage, 1));
        for (int s = 5; s <= seconds; s += 5) {
            String f = BattleSession.step(150);
            System.out.println("t=" + s + "s " + f.substring(0, Math.min(f.length(), 400)));
            if (!f.contains("\"result\":0")) {
                break;
            }
        }
    }

    static byte[] hex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    static byte[] fetch(String url, String range) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url));
        if (range != null) {
            rb.header("Range", range);
        }
        HttpResponse<byte[]> r = HTTP.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + r.statusCode() + " for " + url);
        }
        return r.body();
    }

    /** A game file fetched by range from the dev server and decrypted on the JVM. */
    static class JvmFileData implements FileData {
        private final String path;
        private final JsonArray f; // [zip, offset, size]

        JvmFileData(String path, JsonArray f) {
            this.path = path;
            this.f = f;
        }

        @Override
        public byte[] getBytes() {
            try {
                JsonArray zip = index.getAsJsonArray("zips").get(f.get(0).getAsInt()).getAsJsonArray();
                int size = f.get(2).getAsInt();
                int reg = size % 16 == 0 ? size : (size | 15) + 1;
                long start = zip.get(2).getAsLong() + f.get(1).getAsLong();
                byte[] enc = fetch(SERVER + "/bcu-assets/" + zip.get(0).getAsString() + ".asset.bcuzip",
                        "bytes=" + start + "-" + (start + reg - 1));
                Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
                c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(hex(zip.get(1).getAsString()), "AES"), new IvParameterSpec(IV));
                return Arrays.copyOf(c.doFinal(enc), size);
            } catch (Exception e) {
                throw new IllegalStateException("couldn't load " + path, e);
            }
        }

        @Override
        public FakeImage getImg() {
            return new LazyImage(this::getStream);
        }

        @Override
        public InputStream getStream() {
            return new ByteArrayInputStream(getBytes());
        }

        @Override
        public int size() {
            return f.get(2).getAsInt();
        }
    }

    static class JvmContext implements Context {
        private final File root = new File("build/jvm-bcu");

        @Override public boolean confirmDelete() { return true; }
        @Override public boolean confirmDelete(File f) { return true; }
        @Override public File getAssetFile(String s) { return new File(root, "assets/" + s); }
        @Override public File getAuxFile(String s) { return new File(root, s); }
        @Override public File getUserFile(String s) { return new File(root, "user/" + s); }
        @Override public File getWorkspaceFile(String s) { return new File(root, "workspace/" + s); }
        @Override public File getBackupFile(String s) { return new File(root, "backups/" + s); }
        @Override public File getBCUFolder() { return root; }
        @Override public String getAuthor() { return ""; }
        @Override public void initProfile() { }
        @Override public boolean preload(FileDesc desc) { return false; }
        @Override public void loadProg(String str) { }
        @Override public boolean restore(Backup b, Consumer<Double> prog) { return false; }

        @Override
        public InputStream getLangFile(String file) {
            try {
                return new ByteArrayInputStream(fetch(SERVER + "/bcu-extra/" + file, null));
            } catch (Exception e) {
                return null;
            }
        }

        @Override
        public void noticeErr(Exception e, ErrType t, String str) {
            System.err.println(t + ": " + str + " " + e);
        }

        @Override
        public void printErr(ErrType t, String str) {
            System.err.println(t + ": " + str);
        }
    }
}

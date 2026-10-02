package bcuweb.web;

import common.io.Backup;
import common.io.PackLoader.ZipDesc.FileDesc;
import common.pack.Context;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.function.Consumer;

/**
 * BCU's platform context for the browser. Game data never comes from java.io.File here (it's in the
 * virtual file tree, filled by {@link bcuweb.Main}); the folders below are TeaVM's in-memory files.
 */
public class WebContext implements Context {
    private static final File ROOT = new File("/bcu");

    @Override
    public boolean confirmDelete() {
        return true;
    }

    @Override
    public boolean confirmDelete(File f) {
        return true;
    }

    @Override
    public File getAssetFile(String string) {
        return new File(ROOT, "assets/" + string);
    }

    @Override
    public File getAuxFile(String string) {
        return new File(ROOT, string);
    }

    @Override
    public InputStream getLangFile(String file) {
        byte[] bs = Js.bytes(Js.readExtra("lang/" + file));
        return bs == null ? null : new ByteArrayInputStream(bs);
    }

    @Override
    public File getUserFile(String string) {
        return new File(ROOT, "user/" + string);
    }

    @Override
    public File getWorkspaceFile(String relativePath) {
        return new File(ROOT, "workspace/" + relativePath);
    }

    @Override
    public File getBackupFile(String string) {
        return new File(ROOT, "backups/" + string);
    }

    @Override
    public File getBCUFolder() {
        return ROOT;
    }

    @Override
    public String getAuthor() {
        return "";
    }

    @Override
    public void initProfile() {
    }

    @Override
    public void noticeErr(Exception e, ErrType t, String str) {
        Js.post("error", t + ": " + str + (e == null ? "" : " (" + e + ")"));
        if (e != null) {
            e.printStackTrace();
        }
    }

    @Override
    public boolean preload(FileDesc desc) {
        return false;
    }

    @Override
    public void printErr(ErrType t, String str) {
        Js.post(t == ErrType.INFO || t == ErrType.DEBUG ? "log" : "error", t + ": " + str);
    }

    @Override
    public void loadProg(String str) {
        Js.post("progress", str);
    }

    @Override
    public boolean restore(Backup b, Consumer<Double> prog) {
        return false;
    }
}

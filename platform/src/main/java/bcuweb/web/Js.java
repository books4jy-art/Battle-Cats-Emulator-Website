package bcuweb.web;

import org.teavm.jso.JSBody;
import org.teavm.jso.typedarrays.Int8Array;

/** Calls into the JavaScript side (site/js/worker.js), which owns downloading and decrypting files. */
public final class Js {
    private Js() {
    }

    /** Decrypted bytes of a game file ("./org/..."), downloaded on demand if needed; null if there's no such file. */
    @JSBody(params = "path", script = "return bcuReadFile(path);")
    public static native Int8Array readFile(String path);

    /** Bytes of a non-pack file the worker fetched before start (proc.json, name lists, ...); null if missing. */
    @JSBody(params = "name", script = "return bcuReadExtra(name);")
    public static native Int8Array readExtra(String name);

    /** Every game file the index knows, as "path\tsize" lines. */
    @JSBody(script = "return bcuFileList();")
    public static native String fileList();

    @JSBody(params = { "type", "text" }, script = "bcuPost(type, text);")
    public static native void post(String type, String text);

    // One overload per function shape: TeaVM turns a Java lambda into a JS function only when the
    // parameter's declared type is the @JSFunctor interface itself.
    @JSBody(params = { "name", "fn" }, script = "self[name] = fn;")
    public static native void export(String name, bcuweb.Main.StrFn fn);

    @JSBody(params = { "name", "fn" }, script = "self[name] = fn;")
    public static native void export(String name, bcuweb.Main.BattleStartFn fn);

    @JSBody(params = { "name", "fn" }, script = "self[name] = fn;")
    public static native void export(String name, bcuweb.Main.StepFn fn);

    public static byte[] bytes(Int8Array a) {
        return a == null ? null : a.copyToJavaArray();
    }
}

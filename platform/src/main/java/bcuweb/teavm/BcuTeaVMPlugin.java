package bcuweb.teavm;

import org.teavm.model.AccessLevel;
import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.ElementModifier;
import org.teavm.model.Instruction;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.emit.ProgramEmitter;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;
import java.util.Map;
import java.util.Set;

/**
 * Build-time only (never in the browser). Adapts BCU's core to TeaVM without editing its source:
 * <ul>
 * <li>TeaVM's java.lang.reflect.Field has get/set but not the primitive shortcuts (getInt, setBoolean, ...)
 * the core uses: every such call is rewritten to the matching helper in {@link bcuweb.shim.FieldAccess}.</li>
 * <li>Desktop-only methods (writing/encrypting packs, reading the desktop asset folder, saving to disk) can't
 * work in a browser and would pull in JDK classes TeaVM doesn't have. Their bodies are replaced with
 * "throw UnsupportedOperationException". The browser version gets game data another way
 * ({@link bcuweb.web.WebFileData}) and never calls them.</li>
 * <li>Speed (TeaVM's library is slower than the JDK's in a few spots the core leans on at start-up):
 * String.split calls in the core go to {@link bcuweb.shim.Strings#split}, which has the JDK's fast path
 * for one-character separators instead of always compiling a regular expression, and Class.getMethods
 * calls go to {@link bcuweb.shim.Reflect#getMethods}, which remembers the answer per class. Likewise the
 * core's String.format / printf calls go through {@link bcuweb.shim.Strings}, which turns "%n" (unknown to
 * TeaVM's formatter) into a newline first.</li>
 * <li>TeaVM lists every class with a public no-argument constructor that might reach Class.newInstance,
 * but forgets to leave out abstract classes (which have no create function), breaking the output.
 * Public constructors of abstract classes are made protected; only subclasses can call them anyway.</li>
 * </ul>
 */
public class BcuTeaVMPlugin implements TeaVMPlugin, ClassHolderTransformer {
    private static final String FIELD = "java.lang.reflect.Field";
    private static final String HELPER = "bcuweb.shim.FieldAccess";
    /** "class.method" -> static helper class taking the instance as its first argument (speed-ups). */
    private static final Map<String, String> FASTER = Map.of(
            "java.lang.String.split", "bcuweb.shim.Strings",
            "java.lang.Class.getMethods", "bcuweb.shim.Reflect",
            "java.io.PrintStream.printf", "bcuweb.shim.Strings",
            "java.io.PrintStream.format", "bcuweb.shim.Strings");
    /** Static calls pointed at a helper with the same signature (TeaVM's formatter lacks %n). */
    private static final Map<String, String> STATIC_FIX = Map.of(
            "java.lang.String.format", "bcuweb.shim.Strings");

    /** class -> desktop-only methods (by name) that become "not supported in the browser". */
    private static final Map<String, Set<String>> DESKTOP_ONLY = Map.ofEntries(
            Map.entry("common.io.PackLoader", Set.of("decrypt", "encrypt", "getMD5")),
            Map.entry("common.io.PackLoader$ZipDesc", Set.of("unzip")),
            Map.entry("common.io.PackLoader$FileSaver", Set.of("save")),
            Map.entry("common.io.PackLoader$FileLoader", Set.of("decode")),
            Map.entry("common.io.PackLoader$FileLoader$FLStream", Set.of("update")),
            Map.entry("common.io.OutStreamDef", Set.of("MD5")),
            Map.entry("common.io.OutStreamAnim", Set.of("MD5")),
            Map.entry("common.system.files.FDFile", Set.of("getBytes")),
            Map.entry("common.pack.Source$Workspace", Set.of("writeFile", "streamFile")),
            Map.entry("common.pack.Source$SourceAnimSaver", Set.of("write")),
            Map.entry("common.util.stage.Replay", Set.of("read")),
            Map.entry("common.battle.BasisSet", Set.of("read", "write")));

    @Override
    public void install(TeaVMHost host) {
        host.add(this);
    }

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        if (cls.getName().startsWith("bcuweb.shim.")) {
            return;
        }
        boolean speedUp = cls.getName().startsWith("common.") || cls.getName().startsWith("bcuweb.");
        Set<String> desktopOnly = DESKTOP_ONLY.getOrDefault(cls.getName(), Set.of());
        boolean abstractCls = cls.hasModifier(ElementModifier.ABSTRACT) && !cls.hasModifier(ElementModifier.INTERFACE);
        for (MethodHolder method : cls.getMethods()) {
            if (abstractCls && method.getName().equals("<init>") && method.getLevel() == AccessLevel.PUBLIC) {
                method.setLevel(AccessLevel.PROTECTED);
            }
            Program program = method.getProgram();
            if (program == null) {
                continue;
            }
            if (desktopOnly.contains(method.getName())) {
                ProgramEmitter pe = ProgramEmitter.create(method, context.getHierarchy());
                pe.construct(UnsupportedOperationException.class,
                        pe.constant("not available in the browser: " + cls.getName() + "." + method.getName())).raise();
                continue;
            }
            for (BasicBlock block : program.getBasicBlocks()) {
                for (Instruction insn : block) {
                    if (insn instanceof InvokeInstruction) {
                        rewrite((InvokeInstruction) insn, speedUp);
                    }
                }
            }
        }
    }

    private static void rewrite(InvokeInstruction invoke, boolean speedUp) {
        MethodReference m = invoke.getMethod();
        if (invoke.getInstance() == null) {
            String helper = speedUp ? STATIC_FIX.get(m.getClassName() + "." + m.getName()) : null;
            // only String.format(String, Object[]) (not the Locale variant)
            if (helper != null && m.parameterCount() == 2 && m.parameterType(0).isObject("java.lang.String")) {
                invoke.setMethod(new MethodReference(helper, m.getName(), m.getSignature()));
            }
            return;
        }
        String faster = speedUp ? FASTER.get(m.getClassName() + "." + m.getName()) : null;
        if (faster != null) {
            toStatic(invoke, faster);
            return;
        }
        if (!m.getClassName().equals(FIELD)) {
            return;
        }
        String name = m.getName();
        boolean getter = name.matches("get(Int|Boolean|Long|Float|Double|Short|Byte|Char)");
        boolean setter = name.matches("set(Int|Boolean|Long|Float|Double|Short|Byte|Char)");
        if (!getter && !setter) {
            return;
        }
        // Field.getInt(Object) -> FieldAccess.getInt(Field, Object); Field.setInt(Object, int) -> FieldAccess.setInt(Field, Object, int)
        toStatic(invoke, HELPER);
    }

    /** obj.method(args) -> helper.method(obj, args), same name, the instance's type added in front. */
    private static void toStatic(InvokeInstruction invoke, String helper) {
        MethodReference m = invoke.getMethod();
        String name = m.getName();
        ValueType[] sig = m.getSignature();
        ValueType[] staticSig = new ValueType[sig.length + 1];
        staticSig[0] = ValueType.object(m.getClassName());
        System.arraycopy(sig, 0, staticSig, 1, sig.length);
        Variable[] args = new Variable[invoke.getArguments().size() + 1];
        args[0] = invoke.getInstance();
        for (int i = 0; i < invoke.getArguments().size(); i++) {
            args[i + 1] = invoke.getArguments().get(i);
        }
        invoke.setInstance(null);
        invoke.setType(InvocationType.SPECIAL);
        invoke.setMethod(new MethodReference(helper, name, staticSig));
        invoke.setArguments(args);
    }
}

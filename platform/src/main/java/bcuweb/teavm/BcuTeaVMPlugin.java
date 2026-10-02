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
 * <li>TeaVM lists every class with a public no-argument constructor that might reach Class.newInstance,
 * but forgets to leave out abstract classes (which have no create function), breaking the output.
 * Public constructors of abstract classes are made protected; only subclasses can call them anyway.</li>
 * </ul>
 */
public class BcuTeaVMPlugin implements TeaVMPlugin, ClassHolderTransformer {
    private static final String FIELD = "java.lang.reflect.Field";
    private static final String HELPER = "bcuweb.shim.FieldAccess";

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
        if (cls.getName().equals(HELPER)) {
            return;
        }
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
                        rewrite((InvokeInstruction) insn);
                    }
                }
            }
        }
    }

    private static void rewrite(InvokeInstruction invoke) {
        MethodReference m = invoke.getMethod();
        if (!m.getClassName().equals(FIELD) || invoke.getInstance() == null) {
            return;
        }
        String name = m.getName();
        boolean getter = name.matches("get(Int|Boolean|Long|Float|Double|Short|Byte|Char)");
        boolean setter = name.matches("set(Int|Boolean|Long|Float|Double|Short|Byte|Char)");
        if (!getter && !setter) {
            return;
        }
        // Field.getInt(Object) -> FieldAccess.getInt(Field, Object); Field.setInt(Object, int) -> FieldAccess.setInt(Field, Object, int)
        ValueType[] sig = m.getSignature();
        ValueType[] staticSig = new ValueType[sig.length + 1];
        staticSig[0] = ValueType.object(FIELD);
        System.arraycopy(sig, 0, staticSig, 1, sig.length);
        Variable[] args = new Variable[invoke.getArguments().size() + 1];
        args[0] = invoke.getInstance();
        for (int i = 0; i < invoke.getArguments().size(); i++) {
            args[i + 1] = invoke.getArguments().get(i);
        }
        invoke.setInstance(null);
        invoke.setType(InvocationType.SPECIAL);
        invoke.setMethod(new MethodReference(HELPER, name, staticSig));
        invoke.setArguments(args);
    }
}

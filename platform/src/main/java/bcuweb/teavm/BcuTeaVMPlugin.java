package bcuweb.teavm;

import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.Instruction;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReference;
import org.teavm.model.Program;
import org.teavm.model.ValueType;
import org.teavm.model.Variable;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.vm.spi.TeaVMHost;
import org.teavm.vm.spi.TeaVMPlugin;

/**
 * Build-time only (never in the browser). TeaVM's java.lang.reflect.Field has get/set but not the
 * primitive shortcuts (getInt, setBoolean, ...) that BCU's core uses. Instead of editing the core,
 * every such call is rewritten to the matching static helper in {@link bcuweb.shim.FieldAccess}.
 */
public class BcuTeaVMPlugin implements TeaVMPlugin, ClassHolderTransformer {
    private static final String FIELD = "java.lang.reflect.Field";
    private static final String HELPER = "bcuweb.shim.FieldAccess";

    @Override
    public void install(TeaVMHost host) {
        host.add(this);
    }

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        if (cls.getName().equals(HELPER)) {
            return;
        }
        for (MethodHolder method : cls.getMethods()) {
            Program program = method.getProgram();
            if (program == null) {
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

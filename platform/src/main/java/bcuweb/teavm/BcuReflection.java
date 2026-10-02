package bcuweb.teavm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.teavm.classlib.ReflectionContext;
import org.teavm.classlib.ReflectionSupplier;
import org.teavm.model.ClassReader;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldReader;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodReader;
import org.teavm.model.ValueType;

/**
 * Build-time only. TeaVM keeps reflection data only for members a supplier lists. BCU's core reads
 * its own classes' fields and methods by reflection (ability data, JSON, identifiers), so all of
 * them are kept for classes in the core's "common" package, except fields whose type doesn't exist in
 * the browser build (e.g. the desktop pack reader's javax.crypto.Cipher), and methods taking or returning
 * such types, which TeaVM can't describe.
 */
public class BcuReflection implements ReflectionSupplier {
    /** JDK packages with no browser implementation (only desktop-only code in the core uses them). */
    private static final String[] BROWSERLESS = { "javax.crypto.", "java.nio.file.", "java.security." };

    private static boolean wanted(String cls) {
        // "$$_" = helper classes TeaVM generates itself (e.g. for annotations); their members are internal
        return cls.startsWith("common.") && !cls.contains("$$_");
    }

    @Override
    public Collection<String> getAccessibleFields(ReflectionContext context, String className) {
        ClassReader cls = wanted(className) ? context.getClassSource().get(className) : null;
        if (cls == null) {
            return Collections.emptyList();
        }
        List<String> fields = new ArrayList<>();
        for (FieldReader f : cls.getFields()) {
            if (available(context, f.getType())) {
                fields.add(f.getName());
            }
        }
        return fields;
    }

    private static boolean available(ReflectionContext context, ValueType type) {
        while (type instanceof ValueType.Array) {
            type = ((ValueType.Array) type).getItemType();
        }
        if (!(type instanceof ValueType.Object)) {
            return true;
        }
        String name = ((ValueType.Object) type).getClassName();
        for (String pkg : BROWSERLESS) {
            if (name.startsWith(pkg)) {
                return false;
            }
        }
        return context.getClassSource().get(name) != null;
    }

    @Override
    public Collection<MethodDescriptor> getAccessibleMethods(ReflectionContext context, String className) {
        ClassReader cls = wanted(className) ? context.getClassSource().get(className) : null;
        if (cls == null) {
            return Collections.emptyList();
        }
        List<MethodDescriptor> methods = new ArrayList<>();
        for (MethodReader m : cls.getMethods()) {
            boolean abstractCls = cls.hasModifier(ElementModifier.ABSTRACT) || cls.hasModifier(ElementModifier.INTERFACE);
            boolean ok = !m.hasModifier(ElementModifier.NATIVE) && !m.hasModifier(ElementModifier.ABSTRACT)
                    && !m.getName().equals("<clinit>") && !(abstractCls && m.getName().equals("<init>"))
                    && available(context, m.getResultType());
            for (ValueType p : m.getParameterTypes()) {
                ok &= available(context, p);
            }
            if (ok) {
                methods.add(m.getDescriptor());
            }
        }
        return methods;
    }
}

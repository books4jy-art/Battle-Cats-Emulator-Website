package bcuweb.teavm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.teavm.classlib.ReflectionContext;
import org.teavm.classlib.ReflectionSupplier;
import org.teavm.model.ClassReader;
import org.teavm.model.FieldReader;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodReader;

/**
 * Build-time only. TeaVM keeps reflection data only for members a supplier lists. BCU's core reads
 * its own classes' fields and methods by reflection (ability data, JSON, identifiers), so all of
 * them are kept for classes in the core's "common" package.
 */
public class BcuReflection implements ReflectionSupplier {
    private static boolean wanted(String cls) {
        return cls.startsWith("common.");
    }

    @Override
    public Collection<String> getAccessibleFields(ReflectionContext context, String className) {
        ClassReader cls = wanted(className) ? context.getClassSource().get(className) : null;
        if (cls == null) {
            return Collections.emptyList();
        }
        List<String> fields = new ArrayList<>();
        for (FieldReader f : cls.getFields()) {
            fields.add(f.getName());
        }
        return fields;
    }

    @Override
    public Collection<MethodDescriptor> getAccessibleMethods(ReflectionContext context, String className) {
        ClassReader cls = wanted(className) ? context.getClassSource().get(className) : null;
        if (cls == null) {
            return Collections.emptyList();
        }
        List<MethodDescriptor> methods = new ArrayList<>();
        for (MethodReader m : cls.getMethods()) {
            methods.add(m.getDescriptor());
        }
        return methods;
    }
}

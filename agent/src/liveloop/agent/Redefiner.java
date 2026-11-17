package liveloop.agent;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Hot class redefinition via the JVM's standard (JVMTI) redefine mechanism. */
public final class Redefiner {
    private static Instrumentation inst;

    private Redefiner() {}

    static void init(Instrumentation i) { inst = i; }

    static Instrumentation inst() { return inst; }

    public static boolean supported() { return inst != null && inst.isRedefineClassesSupported(); }

    public static boolean modifiable(Class<?> c) { return inst != null && inst.isModifiableClass(c); }

    public static boolean isRetransformSupport() { return inst != null && inst.isRetransformClassesSupported(); }

    public static Class<?> findLoaded(String dottedName) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if (c.getName().equals(dottedName)) return c;
        }
        return null;
    }

    public static List<String> loadedClassNames() {
        List<String> out = new ArrayList<>();
        for (Class<?> c : inst.getAllLoadedClasses()) out.add(c.getName());
        return out;
    }

    /** Per-class redefine with individual results. Never throws. */
    public static Map<String, Object> redefine(Map<String, byte[]> classes, List<String> onlyThese) {
        Map<String, Object> results = new LinkedHashMap<>();
        if (inst == null || !supported()) {
            results.put("error", "redefine not supported in this JVM");
            return results;
        }
        for (Map.Entry<String, byte[]> e : classes.entrySet()) {
            String fqn = e.getKey();
            if (onlyThese != null && !onlyThese.contains(fqn)) continue;
            if (fqn.startsWith("liveloop.agent.")) {
                results.put(fqn, "skipped: agent classes cannot hot-swap themselves");
                continue;
            }
            try {
                Class<?> c = findLoaded(fqn);
                if (c == null) {
                    results.put(fqn, "notLoadedYet");
                    continue;
                }
                if (!inst.isModifiableClass(c)) {
                    results.put(fqn, "error: class not modifiable");
                    continue;
                }
                inst.redefineClasses(new ClassDefinition(c, e.getValue()));
                results.put(fqn, "redefined");
            } catch (Throwable t) {
                String m = String.valueOf(t.getMessage());
                if (m.length() > 300) m = m.substring(0, 300);
                results.put(fqn, "error: " + t.getClass().getSimpleName() + ": " + m);
            }
        }
        return results;
    }
}

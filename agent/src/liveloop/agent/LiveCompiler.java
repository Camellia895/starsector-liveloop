package liveloop.agent;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Compiles the watched source trees with the in-JDK javax.tools compiler (needs a full JDK).
 * Mod sources and corepatch sources are compiled as SEPARATE units: a broken core patch
 * must never block mod hot-updates.
 */
public final class LiveCompiler {
    private LiveCompiler() {}

    public static final class Result {
        public boolean ok;
        public List<String> diagnostics = new ArrayList<>();
        /** FQN (dotted) -> bytes, for every .class produced under outDir */
        public Map<String, byte[]> classes = new HashMap<>();
        public boolean patchFailed;
    }

    public static Result compile(Config cfg) {
        Result r = new Result();
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) {
            r.diagnostics.add("no system java compiler: runtime is a JRE; use a JDK (jdk-28+13 or JBR SDK)");
            return r;
        }
        File out = cfg.outDirFile();
        try {
            Files.createDirectories(out.toPath());
        } catch (Exception e) {
            r.diagnostics.add("cannot create out dir: " + e);
            return r;
        }

        List<File> modSources = new ArrayList<>();
        for (String sd : cfg.srcDirs) {
            File dir = new File(sd);
            if (!dir.isDirectory()) {
                r.diagnostics.add("src dir missing: " + sd);
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir.toPath())) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> modSources.add(p.toFile()));
            } catch (Exception e) {
                r.diagnostics.add("walk failed for " + sd + ": " + e);
            }
        }

        // classpath: running game classpath + this agent jar + build output (so mod code can
        // reference structurally-redefined core classes that added members via DCEVM)
        String cp = System.getProperty("java.class.path", "") + File.pathSeparator + out.getAbsolutePath();
        try {
            File agentJar = new File(LiveCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (agentJar.isFile()) cp = agentJar.getAbsolutePath() + File.pathSeparator + cp;
        } catch (Exception ignore) {}

        Result mod = compileUnit(modSources, out, cp, "mod");
        r.diagnostics.addAll(mod.diagnostics);
        r.classes.putAll(mod.classes);
        r.ok = mod.ok;

        File patch = cfg.patchDirFile();
        if (patch != null && patch.isDirectory()) {
            List<File> patchSources = new ArrayList<>();
            try (Stream<Path> walk = Files.walk(patch.toPath())) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> patchSources.add(p.toFile()));
            } catch (Exception e) {
                r.diagnostics.add("patch walk failed: " + e);
            }
            if (!patchSources.isEmpty()) {
                Result pr = compileUnit(patchSources, out, cp, "patch");
                for (String d : pr.diagnostics) r.diagnostics.add("PATCH: " + d);
                if (pr.ok) {
                    r.classes.putAll(pr.classes);
                } else {
                    r.patchFailed = true;   // isolated: mod classes still redefine below
                }
            }
        }

        if (r.classes.isEmpty() && r.ok) r.ok = false;
        return r;
    }

    /** One javac invocation over one source set. Never throws. */
    private static Result compileUnit(List<File> sources, File out, String cp, String unit) {
        Result r = new Result();
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (sources.isEmpty()) {
            r.diagnostics.add("no .java sources in unit " + unit);
            r.ok = true; // empty unit is not a failure
            return r;
        }
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = jc.getStandardFileManager(diags, null, null)) {
            List<String> opts = new ArrayList<>();
            opts.add("-encoding"); opts.add("UTF-8");
            opts.add("--release"); opts.add("17");
            opts.add("-classpath"); opts.add(cp);
            opts.add("-d"); opts.add(out.getAbsolutePath());
            opts.add("-nowarn");
            JavaCompiler.CompilationTask task = jc.getTask(null, fm, diags, opts, null,
                    fm.getJavaFileObjectsFromFiles(sources));
            r.ok = Boolean.TRUE.equals(task.call());
            for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                r.diagnostics.add(d.getKind() + " " + d.getLineNumber() + ": " + d.getMessage(null));
            }
        } catch (Exception e) {
            r.ok = false;
            r.diagnostics.add("compiler error: " + e);
        }
        if (r.ok) {
            try (Stream<Path> walk = Files.walk(out.toPath())) {
                walk.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                    String rel = out.toPath().relativize(p).toString();
                    String fqn = rel.substring(0, rel.length() - 6).replace(File.separatorChar, '.');
                    try { r.classes.put(fqn, Files.readAllBytes(p)); } catch (Exception ignore) {}
                });
            } catch (Exception e) {
                r.diagnostics.add("read-back failed: " + e);
                r.ok = false;
            }
        }
        return r;
    }

    public static String summarize(Result r) {
        String d = r.diagnostics.stream().limit(12).collect(Collectors.joining(" | "));
        return (r.ok ? "OK classes=" + r.classes.size() : "FAIL") + (d.isEmpty() ? "" : " diag: " + d);
    }
}

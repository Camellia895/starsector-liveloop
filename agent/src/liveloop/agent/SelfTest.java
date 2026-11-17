package liveloop.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Offline verification that compile+redefine works end-to-end in the current JVM. */
final class SelfTest {
    private SelfTest() {}

    static void run(Instrumentation inst, Config cfg) {
        int failures = 0;
        try {
            Path tmp = Files.createTempDirectory("liveloop-selftest");
            Path src = tmp.resolve("src");
            Path out = tmp.resolve("out");
            Files.createDirectories(src);
            Files.createDirectories(out);

            String v1 = "package selftest;\n"
                    + "public class Probe {\n"
                    + "  public static int value() { return 1; }\n"
                    + "  public String hello() { return \"A\"; }\n"
                    + "}\n";
            Files.createDirectories(src.resolve("selftest"));
            Files.write(src.resolve("selftest/Probe.java"), v1.getBytes("UTF-8"));

            Config c = new Config();
            c.srcDirs = new String[] { src.toString() };
            c.outDir = out.toString();
            LiveCompiler.Result r1 = LiveCompiler.compile(c);
            if (!r1.ok) { fail("compile v1: " + LiveCompiler.summarize(r1)); failures++; }
            else Log.log("selftest: compiled " + r1.classes.size() + " classes");

            URLClassLoader cl = new URLClassLoader(new URL[] { out.toUri().toURL() }, SelfTest.class.getClassLoader());
            Class<?> probe = Class.forName("selftest.Probe", true, cl);
            Object instance = probe.getDeclaredConstructor().newInstance();
            int v = (Integer) probe.getMethod("value").invoke(null);
            String h = (String) probe.getMethod("hello").invoke(instance);
            Log.log("selftest: before redefine value=" + v + " hello=" + h);

            String v2 = v1.replace("return 1;", "return 2;").replace("return \"A\";", "return \"B\";");
            Files.write(src.resolve("selftest/Probe.java"), v2.getBytes("UTF-8"));
            LiveCompiler.Result r2 = LiveCompiler.compile(c);
            if (!r2.ok) { fail("compile v2: " + LiveCompiler.summarize(r2)); failures++; }

            Map<String, Object> res = Redefiner.redefine(r2.classes, null);
            Log.log("selftest: redefine -> " + res);
            if (!"redefined".equals(res.get("selftest.Probe"))) { fail("redefine did not report redefined: " + res); failures++; }

            int v3 = (Integer) probe.getMethod("value").invoke(null);
            String h3 = (String) probe.getMethod("hello").invoke(instance);
            Log.log("selftest: after redefine value=" + v3 + " hello=" + h3);
            if (v3 != 2 || !"B".equals(h3)) { fail("hot swap did not take effect"); failures++; }

            if (failures == 0) {
                Log.log("SELFTEST PASS: compile + redefine verified in this JVM");
                System.out.println("SELFTEST PASS");
            } else {
                System.out.println("SELFTEST FAIL");
            }
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("SELFTEST FAIL: " + t);
        } finally {
            System.exit(failures == 0 ? 0 : 2);
        }
    }

    private static void fail(String m) { Log.log("selftest FAIL: " + m); }
}

package liveloop.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LiveLoop premain: hot class redefinition + compile-on-change + game-thread pump injection
 * + local control API. Stage 1 supports method-body hot swaps (standard JVMTI redefine);
 * under a DCEVM runtime (JBR) structural changes work too — capability is probed, not assumed.
 */
public final class HotSwapAgent {
    private static volatile Instrumentation instrumentation;
    private static Config config;
    private static volatile String lastReload = "none yet";
    private static volatile boolean pumpInjected = false;
    private static volatile ClassFileTransformer transformer;

    private HotSwapAgent() {}

    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
        config = Config.parse(args);
        Redefiner.init(inst);
        if ("selftest".equals(config.mode)) {
            SelfTest.run(inst, config);
            return;
        }
        Log.log("LiveLoop agent premain: " + config);
        try {
            transformer = new InjectTransformer();
            inst.addTransformer(transformer, true);
        } catch (Throwable t) {
            Log.log("transformer registration failed: " + t);
        }
        try {
            HttpApi.start(config);
        } catch (Throwable t) {
            Log.log("HTTP api failed to start: " + t);
        }
        if (config.watch) SrcWatcher.start(config);
        ModLink.startPolling();
        logStage("pump injection targets: TitleScreenState / CampaignState / CombatState (load-time)");
    }

    /** Compile the watched sources and hot-redefine every loaded class that changed. */
    public static Map<String, Object> recompileAndRedefine() {
        Map<String, Object> out = new LinkedHashMap<>();
        LiveCompiler.Result res = LiveCompiler.compile(config);
        out.put("compile", LiveCompiler.summarize(res));
        Map<String, Object> results = new LinkedHashMap<>();
        if (res.ok) {
            results = Redefiner.redefine(res.classes, null);
            int ok = 0, err = 0, notLoaded = 0;
            for (Object v : results.values()) {
                String s = String.valueOf(v);
                if (s.equals("redefined")) ok++;
                else if (s.equals("notLoadedYet")) notLoaded++;
                else err++;
            }
            out.put("redefined", ok);
            out.put("notLoadedYet", notLoaded);
            out.put("errors", err);
        }
        out.put("results", results);
        String summary = out.get("compile") + " redefined=" + out.getOrDefault("redefined", 0)
                + " notLoaded=" + out.getOrDefault("notLoadedYet", 0) + " errors=" + out.getOrDefault("errors", 0);
        out.put("summary", summary);
        lastReload = summary;
        Log.log("recompileAndRedefine: " + summary);
        if (!res.diagnostics.isEmpty()) {
            for (String d : res.diagnostics) if (d.startsWith("ERROR")) Log.log(d);
        }
        return out;
    }

    static void logStage(String msg) {
        Log.log(msg);
        if (msg.contains("pump injected into com/fs/starfarer/title")) pumpInjected = true;
    }

    public static Instrumentation inst() { return instrumentation; }
    public static Config cfg() { return config; }
    public static String lastReloadSummary() { return lastReload; }
    public static boolean pumpInjected() { return pumpInjected; }
}

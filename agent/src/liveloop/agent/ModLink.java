package liveloop.agent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Discovers the SimArena mod's static command class (simarena.ArenaCommands) among loaded
 * classes and invokes its methods reflectively. The mod NEVER imports agent classes, so the
 * game's script classloader sandbox (no java.io.File / java.lang.reflect for scripts) never
 * sees a foreign linkage — all reflection happens here, on the unrestricted agent side.
 */
public final class ModLink {
    private ModLink() {}

    private static final String COMMANDS_CLASS = "simarena.ArenaCommands";
    private static volatile boolean available = false;
    private static volatile String lastInvokeError = "";

    static void startPolling() {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 3600; i++) { // poll for up to 2h; cheap
                if (!available && Redefiner.inst() != null) {
                    try {
                        // the mod plugin class is loaded at boot; use ITS classloader
                        // (the game's script classloader) to force-load the commands class
                        Class<?> plugin = Redefiner.findLoaded("simarena.SimArenaModPlugin");
                        if (plugin != null) {
                            Class<?> c = plugin.getClassLoader().loadClass(COMMANDS_CLASS);
                            if (c != null) {
                                available = true;
                                Log.log("mod commands discovered via plugin classloader: " + COMMANDS_CLASS);
                                // push results dir so the mod can write CSVs (PrintWriter is sandbox-whitelisted)
                                invoke("configure", new Class<?>[]{String.class},
                                        new Object[]{HotSwapAgent.cfg().resultsDir.getAbsolutePath()});
                            }
                        }
                    } catch (Throwable t2) {
                        Log.log("mod discovery attempt failed: " + t2);
                    }
                }
                try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
            }
        }, "liveloop-modlink");
        t.setDaemon(true);
        t.start();
    }

    public static boolean available() { return available; }

    public static String lastError() { return lastInvokeError; }

    private static Class<?> commandsClass() throws ClassNotFoundException {
        Class<?> c = Redefiner.findLoaded(COMMANDS_CLASS);
        if (c != null) return c;
        Class<?> plugin = Redefiner.findLoaded("simarena.SimArenaModPlugin");
        if (plugin == null) return null;
        return plugin.getClassLoader().loadClass(COMMANDS_CLASS);
    }

    /** Reflective call; never throws. Returns {ok:false,error:...} on failure. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> invoke(String method, Class<?>[] types, Object[] args) {
        Map<String, Object> err;
        try {
            Class<?> c = commandsClass();
            if (c == null) {
                err = map(false, "error", "mod commands not loaded yet (SimArena enabled and game past mod loading?)");
                lastInvokeError = (String) err.get("error");
                return err;
            }
            Method m = c.getMethod(method, types);
            Object r = m.invoke(null, args);
            if (r instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> res = (Map<String, Object>) r;
                return res;
            }
            return map(true, "result", String.valueOf(r));
        } catch (Throwable t) {
            String msg = method + ": " + t + (t.getCause() != null ? " <- " + t.getCause() : "");
            lastInvokeError = msg;
            Log.log("ModLink invoke failed: " + msg);
            return map(false, "error", msg);
        }
    }

    /** Invoke a no-arg method that returns a Map. */
    public static Map<String, Object> call(String method) {
        return invoke(method, new Class<?>[0], new Object[0]);
    }

    /** Invoke on the game thread via the injected pump (for state-mutating commands). */
    public static Map<String, Object> callOnGameThread(final String method, final Class<?>[] types, final Object[] args) {
        final java.util.concurrent.CompletableFuture<Map<String, Object>> fut =
                new java.util.concurrent.CompletableFuture<>();
        AgentBridge.enqueue(() -> {
            fut.complete(invoke(method, types, args));
        });
        try {
            // battle build can take a few seconds; never block the HTTP thread forever
            return fut.get(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            return map(false, "error", "game-thread invoke timed out/failed: " + method + " -> " + t);
        }
    }

    public static List<String> listScenarioFiles() {
        List<String> out = new ArrayList<>();
        try {
            java.io.File dir = HotSwapAgent.cfg().scenarioDir;
            java.io.File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
            if (files != null) for (java.io.File f : files) {
                String n = f.getName();
                out.add(n.substring(0, n.length() - 5));
            }
        } catch (Throwable ignore) {}
        return out;
    }

    public static String readScenario(String scenarioId) throws Exception {
        String name = scenarioId.endsWith(".json") ? scenarioId : scenarioId + ".json";
        java.io.File f = new java.io.File(HotSwapAgent.cfg().scenarioDir, name);
        if (!f.isFile()) throw new java.io.FileNotFoundException(f.getAbsolutePath());
        return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static Map<String, Object> map(boolean ok, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", ok);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }
}

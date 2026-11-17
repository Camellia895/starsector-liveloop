package liveloop.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Control API on 127.0.0.1 only. All handlers return JSON. Mod commands go through ModLink. */
public final class HttpApi {
    private HttpApi() {}

    static void start(Config cfg) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", cfg.port), 0);
        server.setExecutor(Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "liveloop-http");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", ex -> safe(ex, cfg));
        server.start();
        Log.log("HTTP api on http://127.0.0.1:" + cfg.port + "/  (endpoints: /api/status /api/run /api/goto /api/redefine "
                + "/api/reload-specs /api/end /api/scenarios /api/variants /api/stats /api/stats.csv "
                + "/api/reload-strings /api/log)");
    }

    private static void safe(HttpExchange ex, Config cfg) {
        try {
            handle(ex, cfg);
        } catch (Throwable t) {
            try {
                Log.log("http error " + ex.getRequestURI().getPath() + ": " + t);
                send(ex, 500, MiniJson.write(mapOf("error", String.valueOf(t))));
            } catch (Exception ignore) {}
        } finally {
            ex.close();
        }
    }

    private static void handle(HttpExchange ex, Config cfg) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        String body = method.equals("POST") ? readBody(ex) : "";

        if (path.equals("/") && method.equals("GET")) { send(ex, 200, indexHtml(cfg)); return; }

        if (path.equals("/api/status")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("java", System.getProperty("java.vm.name") + " " + System.getProperty("java.version"));
            m.put("redefineSupported", Redefiner.supported());
            m.put("retransformSupported", Redefiner.isRetransformSupport());
            m.put("compilerAvailable", compilerAvailable());
            m.put("config", String.valueOf(cfg));
            m.put("commandsAvailable", ModLink.available());
            m.put("queuedCommands", AgentBridge.acceptedCommands());
            m.put("lastReload", HotSwapAgent.lastReloadSummary());
            m.put("pumpInjected", HotSwapAgent.pumpInjected());
            if (ModLink.available()) m.put("game", ModLink.call("status"));
            send(ex, 200, MiniJson.write(m));
            return;
        }
        if (path.equals("/api/log")) { send(ex, 200, MiniJson.write(Log.tail())); return; }

        if (path.equals("/api/redefine") && method.equals("POST")) {
            send(ex, 200, MiniJson.write(HotSwapAgent.recompileAndRedefine()));
            return;
        }
        if (path.equals("/api/classes") && method.equals("GET")) {
            String q = queryParam(ex, "q", "");
            List<String> hits = new ArrayList<>();
            for (String n : Redefiner.loadedClassNames()) {
                if (n.contains(q)) { hits.add(n); if (hits.size() >= 200) break; }
            }
            send(ex, 200, MiniJson.write(hits));
            return;
        }

        if (path.equals("/api/scenarios")) {
            send(ex, 200, MiniJson.write(ModLink.listScenarioFiles()));
            return;
        }

        // 字节码字符串常量补丁：绕开源码编译，yGuard 关键字类也可用（不依赖 ModLink）
        if (path.equals("/api/patch-string") && method.equals("POST")) {
            Map<String, Object> req = MiniJson.parseObject(body);
            String cls = String.valueOf(req.get("class"));
            String oldV = String.valueOf(req.getOrDefault("old", ""));
            String newV = String.valueOf(req.getOrDefault("new", ""));
            if (cls.equals("null") || cls.isEmpty() || oldV.isEmpty()) {
                send(ex, 400, MiniJson.write(mapOf("error", "need 'class', 'old', 'new'")));
                return;
            }
            send(ex, 200, MiniJson.write(PatchString.patchLive(cls, oldV, newV)));
            return;
        }

        // 数据层文本热重载：重读 data/strings/*.json（不依赖 ModLink，游戏未启用 mod 也可用）
        if (path.equals("/api/reload-strings") && method.equals("POST")) {
            if (HotSwapAgent.pumpInjected()) {
                // run on the game main thread: the reload swaps static JSONObjects and
                // rebuilds the title tip picker, which the render thread reads per click
                final java.util.concurrent.CompletableFuture<Map<String, Object>> fut =
                        new java.util.concurrent.CompletableFuture<>();
                AgentBridge.enqueue(() -> fut.complete(DataReload.reloadStrings()));
                Map<String, Object> res;
                try {
                    res = fut.get(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Throwable t) {
                    res = new LinkedHashMap<>();
                    res.put("ok", false);
                    res.put("error", "reload-strings on game thread timed out/failed: " + t);
                }
                res.put("thread", "game");
                send(ex, 200, MiniJson.write(res));
            } else {
                Map<String, Object> res = DataReload.reloadStrings();
                res.put("thread", "http");
                send(ex, 200, MiniJson.write(res));
            }
            return;
        }

        if (!ModLink.available()) {
            send(ex, 503, MiniJson.write(mapOf("error", "SimArena commands not discovered yet (game still loading or mod missing)")));
            return;
        }

        if (path.equals("/api/run") && method.equals("POST")) {
            Map<String, Object> req = MiniJson.parseObject(body);
            String id = String.valueOf(req.getOrDefault("scenario", req.getOrDefault("id", "")));
            if (id.equals("null") || id.isEmpty()) { send(ex, 400, MiniJson.write(mapOf("error", "missing 'scenario'"))); return; }
            String text;
            try {
                text = ModLink.readScenario(id);
            } catch (Exception e) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("error", "scenario not found: " + id);
                m.put("available", ModLink.listScenarioFiles());
                send(ex, 404, MiniJson.write(m));
                return;
            }
            final String sid = id;
            final String json = text;
            send(ex, 200, MiniJson.write(ModLink.callOnGameThread("runScenario",
                    new Class<?>[]{String.class, String.class}, new Object[]{json, sid})));
            return;
        }
        if (path.equals("/api/goto") && method.equals("POST")) {
            Map<String, Object> req = MiniJson.parseObject(body);
            String where = String.valueOf(req.get("where"));
            if (where.equals("title")) {
                send(ex, 200, MiniJson.write(ModLink.callOnGameThread("gotoTitle", new Class<?>[0], new Object[0])));
            } else if (where.equals("combat") || where.equals("arena")) {
                String last = String.valueOf(ModLink.call("lastScenario"));
                String text;
                try { text = ModLink.readScenario(last); }
                catch (Exception e) { send(ex, 404, MiniJson.write(mapOf("error", "no previous scenario"))); return; }
                send(ex, 200, MiniJson.write(ModLink.callOnGameThread("runScenario",
                        new Class<?>[]{String.class, String.class}, new Object[]{text, last})));
            } else {
                send(ex, 400, MiniJson.write(mapOf("error", "unknown where (title|combat)")));
            }
            return;
        }
        if (path.equals("/api/end") && method.equals("POST")) {
            send(ex, 200, MiniJson.write(ModLink.callOnGameThread("endBattle", new Class<?>[0], new Object[0])));
            return;
        }
        if (path.equals("/api/reload-specs") && method.equals("POST")) {
            send(ex, 200, MiniJson.write(ModLink.callOnGameThread("reloadVariants", new Class<?>[0], new Object[0])));
            return;
        }
        if (path.equals("/api/variants")) {
            send(ex, 200, MiniJson.write(ModLink.call("variants")));
            return;
        }
        if (path.equals("/api/stats")) {
            send(ex, 200, MiniJson.write(ModLink.call("stats")));
            return;
        }
        if (path.equals("/api/stats.csv")) {
            send(ex, 200, String.valueOf(ModLink.call("statsCsv").getOrDefault("result", "# unavailable\n")), "text/csv");
            return;
        }

        send(ex, 404, MiniJson.write(mapOf("error", "unknown endpoint " + path)));
    }

    private static boolean compilerAvailable() {
        try { return javax.tools.ToolProvider.getSystemJavaCompiler() != null; } catch (Throwable t) { return false; }
    }

    private static String queryParam(HttpExchange ex, String key, String def) {
        String q = ex.getRequestURI().getQuery();
        if (q == null) return def;
        for (String kv : q.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals(key)) return kv.substring(eq + 1);
        }
        return def;
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = readAll(in, 2_000_000);
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    private static byte[] readAll(InputStream in, int cap) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > cap) throw new IOException("body too large");
        }
        return out.toByteArray();
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        send(ex, code, body, "application/json; charset=utf-8");
    }

    private static void send(HttpExchange ex, int code, String body, String ct) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ct);
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private static Map<String, Object> mapOf(String k, Object v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    private static String indexHtml(Config cfg) {
        return "<!doctype html><meta charset='utf-8'><title>LiveLoop</title>"
                + "<body style='font-family:monospace;background:#111;color:#9f9'>"
                + "<h2>LiveLoop agent @ 127.0.0.1:" + cfg.port + "</h2>"
                + "<p>AI-facing JSON API. Quick links:</p><ul>"
                + "<li><a style='color:#6cf' href='/api/status'>/api/status</a></li>"
                + "<li><a style='color:#6cf' href='/api/scenarios'>/api/scenarios</a></li>"
                + "<li><a style='color:#6cf' href='/api/stats'>/api/stats</a></li>"
                + "<li><a style='color:#6cf' href='/api/log'>/api/log</a></li>"
                + "</ul><p>POST /api/run {\"scenario\":\"id\"} to start a battle; POST /api/redefine {} to hot-swap edited sources.</p>";
    }
}

package liveloop.agent;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.io.File;

import static java.nio.file.StandardWatchEventKinds.*;

/** Watches the mod source tree; after a quiet period triggers compile+redefine. */
public final class SrcWatcher {
    private SrcWatcher() {}

    private static volatile boolean dirty = false;

    static void start(Config cfg) {
        Thread t = new Thread(() -> run(cfg), "liveloop-watcher");
        t.setDaemon(true);
        t.start();
    }

    private static void run(Config cfg) {
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            for (String sd : cfg.srcDirs) {
                Path p = new File(sd).toPath();
                if (!p.toFile().isDirectory()) { Log.log("watch: missing src dir " + sd); continue; }
                Files_walk(p, ws);
            }
            File patch = cfg.patchDirFile();
            if (patch != null) {
                if (!patch.isDirectory()) {
                    try { Files.createDirectories(patch.toPath()); } catch (Exception ignore) {}
                }
                if (patch.isDirectory()) Files_walk(patch.toPath(), ws);
            }
            Log.log("watching " + String.join(";", cfg.srcDirs)
                    + (patch != null ? " + patch:" + patch.getPath() : ""));
            while (true) {
                WatchKey key = ws.take();
                for (WatchEvent<?> e : key.pollEvents()) {
                    if (e.kind() == OVERFLOW) dirty = true;
                    else if (e.kind() == ENTRY_MODIFY || e.kind() == ENTRY_CREATE || e.kind() == ENTRY_DELETE) {
                        String name = String.valueOf(e.context());
                        if (name.endsWith(".java")) dirty = true;
                    }
                }
                boolean valid = key.reset();
                if (!valid) { Log.log("watch key invalid; rescanning"); Files_walk((Path) key.watchable(), ws); }
                if (dirty) {
                    dirty = false;
                    Thread.sleep(700); // debounce quiet period
                    try { Thread.sleep(50); } catch (InterruptedException ignore) {}
                    Log.log("source change detected: compiling");
                    Map<String, Object> res = HotSwapAgent.recompileAndRedefine();
                    Log.log("hot-reload result: " + res.get("summary"));
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            Log.log("watcher died: " + t);
        }
    }

    private static void Files_walk(Path root, WatchService ws) {
        try {
            java.nio.file.Files.walk(root)
                    .filter(java.nio.file.Files::isDirectory)
                    .forEach(d -> { try { d.register(ws, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE); } catch (Exception ignore) {} });
        } catch (Exception e) {
            Log.log("register watch failed: " + e);
        }
    }
}

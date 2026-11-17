package liveloop.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Registration point for the in-game mod side, plus the game-thread command pump.
 *
 * The pump() is injected into BaseGameState.processInput by the agent's transformer, so
 * Runnables enqueued here execute on the game's main thread OUTSIDE engine traversal,
 * at the same stage where the game's own UI button handlers run.
 */
public final class AgentBridge {
    private AgentBridge() {}

    /** Implemented by the mod (SimArena). All query methods may be called from any thread;
     *  implementations must enqueue state-mutating work via {@link #enqueue}. */
    public interface Bridge {
        Map<String, Object> status();

        Map<String, Object> runScenario(String scenarioId);

        Map<String, Object> gotoState(String where);

        Map<String, Object> reloadSpecs();

        Map<String, Object> endBattle();

        Map<String, Object> stats();

        String statsCsv();

        java.util.List<String> scenarios();

        java.util.List<String> variants();
    }

    private static volatile Bridge bridge;
    private static volatile long acceptedCommands = 0;

    public static void register(Bridge b) {
        bridge = b;
        Log.log("bridge registered: " + b.getClass().getName());
    }

    public static Bridge get() { return bridge; }

    /** Queue work for the game main thread. Safe from any thread. */
    public static void enqueue(Runnable r) {
        QUEUE.add(r);
        acceptedCommands++;
    }

    public static long acceptedCommands() { return acceptedCommands; }

    private static final ConcurrentLinkedQueue<Runnable> QUEUE = new ConcurrentLinkedQueue<>();

    /** Called once per frame on the game main thread (injected into BaseGameState.processInput). */
    public static void pump() {
        try {
            Runnable r;
            while ((r = QUEUE.poll()) != null) {
                try {
                    r.run();
                } catch (Throwable t) {
                    Log.log("pump job failed: " + t);
                    java.io.StringWriter sw = new java.io.StringWriter();
                    t.printStackTrace(new java.io.PrintWriter(sw));
                    Log.log(sw.toString());
                }
            }
        } catch (Throwable ignore) {
            // never break the game loop
        }
    }
}

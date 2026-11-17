package simarena;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONObject;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.state.AppDriver;

/**
 * Static command facade invoked reflectively by the LiveLoop agent.
 * MUST NOT reference java.io.File / java.nio.file / java.lang.reflect
 * (banned by the game's script classloader), nor any liveloop.agent class.
 *
 * runScenario/gotoTitle/endBattle/reloadVariants are called on the GAME THREAD
 * (the agent injects a per-frame pump into BaseGameState.processInput and queues
 * these calls); status/stats/statsCsv may be called from any thread.
 */
public class ArenaCommands {

    private static volatile String resultsDir = ".";
    private static volatile String lastScenario = "?";
    private static volatile String lastError = "";

    // ------------- configuration (any thread, called once by the agent) -------------

    public static void configure(String resultsDirPath) {
        resultsDir = resultsDirPath;
        SimArenaModPlugin.log.info("[SimArena] configured; results dir: " + resultsDirPath);
    }

    // ------------- game-thread commands -------------

    /** @param scenarioJson full text of the scenario file (agent reads it for us) */
    public static Map<String, Object> runScenario(String scenarioJson, String scenarioId) {
        try {
            JSONObject sc = new JSONObject(scenarioJson);
            ArenaBuilder.start(sc, scenarioId);
            lastScenario = scenarioId;
            lastError = "";
            SimArenaModPlugin.log.info("[SimArena] scenario started: " + scenarioId);
            return ok("scenario", scenarioId);
        } catch (Throwable t) {
            lastError = String.valueOf(t);
            SimArenaModPlugin.log.error("[SimArena] scenario failed: " + scenarioId, t);
            return err("scenario failed: " + t);
        }
    }

    public static Map<String, Object> gotoTitle() {
        try {
            AppDriver.getInstance().getCurrentState().goToState("Title Screen State");
            return ok("goto", "title");
        } catch (Throwable t) {
            return err("gotoTitle failed: " + t);
        }
    }

    public static Map<String, Object> endBattle() {
        try {
            CombatEngine e = CombatEngine.getInstance();
            if (e != null && !e.isDestroyed()) {
                e.setPaused(false);          // dialogs pause the engine; unblock the end sequence
                e.endCombat(0f, FleetSide.ENEMY);
                return ok("endBattle", "triggered");
            }
            return ok("endBattle", "no active engine");
        } catch (Throwable t) {
            return err("endBattle failed: " + t);
        }
    }

    public static Map<String, Object> reloadVariants() {
        try {
            ArenaInternals.reloadVariants();
            return ok("reloaded", "variants");
        } catch (Throwable t) {
            return err("reloadVariants failed: " + t);
        }
    }

    // ------------- queries (any thread) -------------

    public static Map<String, Object> status() {
        String state = "?";
        try {
            state = AppDriver.getInstance().getCurrentState().getID();
        } catch (Throwable ignore) {}
        Map<String, Object> m = ok(
                "mod", "SimArena " + SimArenaModPlugin.VERSION,
                "currentState", state,
                "lastScenario", lastScenario,
                "lastError", lastError);
        return m;
    }

    public static Map<String, Object> stats() {
        Map<String, Object> live = ArenaStats.liveSnapshot();
        if (live != null) return live;
        Map<String, Object> last = ArenaStats.snapshot();
        return last != null ? last : ok("statsReady", false);
    }

    public static String statsCsv() {
        String csv = ArenaStats.lastCsv();
        return csv == null || csv.isEmpty() ? "# no finished battle yet\n" : csv;
    }

    public static List<String> variants() {
        try {
            return new ArrayList<>(Global.getSettings().getAllVariantIds());
        } catch (Throwable t) {
            return new ArrayList<>();
        }
    }

    // ------------- helpers -------------

    static String resultsDir() { return resultsDir; }

    public static String lastScenario() { return lastScenario; }

    private static Map<String, Object> ok(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    private static Map<String, Object> err(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", false);
        m.put("error", message);
        return m;
    }
}

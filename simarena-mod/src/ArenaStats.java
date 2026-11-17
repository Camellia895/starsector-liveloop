package simarena;

import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.CombatEntityAPI;
import com.fs.starfarer.api.combat.DamagingProjectileAPI;
import com.fs.starfarer.api.combat.MissileAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.listeners.ApplyDamageResultAPI;
import com.fs.starfarer.api.combat.listeners.DamageListener;

/** Damage + hull tracking for the running arena battle; publishes snapshots and CSV results. */
final class ArenaStats implements DamageListener {

    private static final class Rec {
        int owner = -1;
        String hullId = "", variant = "";
        float dealt, taken;
        float lastHull, maxHull;
        boolean everSeen, dead;
        float deadAt = -1f;
        boolean fighter;
    }

    private static volatile ArenaStats active;
    private static volatile Map<String, Object> lastSnapshot;
    private static volatile String lastCsv = "";

    private final String scenarioId;
    private final LinkedHashMap<String, Rec> recs = new LinkedHashMap<>();
    private final float[] dealt = new float[2];
    private final float[] taken = new float[2];
    private float simTime = 0f;
    private float sampleTimer = 0f;
    private boolean ended = false;

    private ArenaStats(String scenarioId) { this.scenarioId = scenarioId; }

    // ---------------- lifecycle ----------------

    static void begin(CombatEngineAPI engine, String scenarioId) {
        ArenaStats s = new ArenaStats(scenarioId);
        active = s;
        try {
            engine.getListenerManager().addListener(s);
        } catch (Throwable t) {
            SimArenaModPlugin.log.error("[SimArena] listener registration failed", t);
        }
        SimArenaModPlugin.log.info("[SimArena] stats tracking started for " + scenarioId);
    }

    static void tick(CombatEngineAPI engine, float dt) {
        ArenaStats s = active;
        if (s == null) return;
        s.simTime += dt;
        s.sampleTimer += dt;
        if (s.sampleTimer >= 0.5f) {
            s.sampleTimer = 0f;
            s.sample(engine);
        }
    }

    private void sample(CombatEngineAPI engine) {
        try {
            for (ShipAPI ship : engine.getShips()) {
                if (ship.isShuttlePod()) continue;
                String key = String.valueOf(System.identityHashCode(ship));
                Rec r = recs.get(key);
                if (r == null) {
                    r = new Rec();
                    r.owner = ship.getOwner();
                    try { r.hullId = ship.getHullSpec().getHullId(); } catch (Throwable ignore) {}
                    try { r.variant = ship.getVariant().getHullVariantId(); } catch (Throwable ignore) {}
                    r.fighter = ship.isFighter();
                    recs.put(key, r);
                }
                r.everSeen = true;
                r.lastHull = ship.getHitpoints();
                r.maxHull = Math.max(r.maxHull, r.lastHull);
                if (!r.dead && ship.isHulk()) {
                    r.dead = true;
                    r.deadAt = simTime;
                }
            }
        } catch (Throwable ignore) {}
    }

    static void checkEnded(CombatEngineAPI engine) {
        ArenaStats s = active;
        if (s == null || s.ended) return;
        try {
            if (engine instanceof com.fs.starfarer.combat.CombatEngine && ((com.fs.starfarer.combat.CombatEngine) engine).isDestroyed()) { s.finalize(engine, -1, "engineDestroyed"); return; }
            int[] alive = new int[2];
            for (Rec r : s.recs.values()) {
                if (r.fighter || r.owner < 0 || r.owner > 1) continue;
                if (!r.dead) alive[r.owner]++;
            }
            if (s.simTime > 5f) {
                if (alive[0] == 0 && alive[1] == 0) s.finalize(engine, -1, "mutualDestruction");
                else if (alive[1] == 0) s.finalize(engine, 0, "playerSideWins");
                else if (alive[0] == 0) s.finalize(engine, 1, "enemySideWins");
            }
        } catch (Throwable t) {
            SimArenaModPlugin.log.error("[SimArena] checkEnded error", t);
        }
    }

    private void finalize(CombatEngineAPI engine, int winner, String how) {
        ended = true;
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("battle_id,scenario,duration_s,winner_side,how\n");
        String battleId = ts + "_" + scenarioId;
        sb.append(battleId).append(',').append(scenarioId).append(',')
                .append(String.format("%.1f", simTime)).append(',')
                .append(winner).append(',').append(how).append('\n');
        sb.append("\nside,hull_id,variant,dealt,taken,hull_end,max_hull,alive_time_s,destroyed\n");
        for (Rec r : recs.values()) {
            sb.append(r.owner).append(',').append(r.hullId).append(',').append(r.variant).append(',')
                    .append(Math.round(r.dealt)).append(',').append(Math.round(r.taken)).append(',')
                    .append(Math.round(r.lastHull)).append(',').append(Math.round(r.maxHull)).append(',')
                    .append(r.dead ? String.format("%.1f", r.deadAt) : String.format("%.1f", simTime)).append(',')
                    .append(r.dead).append('\n');
        }
        lastCsv = sb.toString();
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("statsReady", true);
        snap.put("scenario", scenarioId);
        snap.put("duration_s", Math.round(simTime * 10) / 10.0);
        snap.put("winner_side", winner);
        snap.put("how", how);
        snap.put("dealt", new double[]{round(dealt[0]), round(dealt[1])});
        snap.put("taken", new double[]{round(taken[0]), round(taken[1])});
        List<Map<String, Object>> ships = new ArrayList<>();
        for (Rec r : recs.values()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("side", r.owner);
            sm.put("hull", r.hullId);
            sm.put("variant", r.variant);
            sm.put("dealt", Math.round(r.dealt));
            sm.put("taken", Math.round(r.taken));
            sm.put("hullEnd", Math.round(r.lastHull));
            sm.put("destroyed", r.dead);
            ships.add(sm);
        }
        snap.put("ships", ships);
        lastSnapshot = snap;
        try {
            // PrintWriter(String) is on the script classloader's whitelist (java.io.File is NOT)
            String path = ArenaCommands.resultsDir() + "/" + battleId + ".csv";
            PrintWriter out = new PrintWriter(path, "UTF-8");
            try {
                out.print(lastCsv);
            } finally {
                out.close();
            }
            SimArenaModPlugin.log.info("[SimArena] results written: " + path);
        } catch (Throwable t) {
            SimArenaModPlugin.log.error("[SimArena] csv write failed", t);
        }
        active = null;
    }

    private static double round(double d) { return Math.round(d * 10) / 10.0; }

    // ---------------- damage listener ----------------

    @Override
    public void reportDamageApplied(Object damageSource, CombatEntityAPI target, ApplyDamageResultAPI result) {
        try {
            float dmg = result.getDamageToHull() + result.getTotalDamageToArmor() + result.getDamageToShields();
            ShipAPI dealer = resolveShip(damageSource);
            ShipAPI tgt = resolveShip(target);
            if (dealer != null) {
                int o = dealer.getOwner();
                if (o >= 0 && o <= 1) {
                    dealt[o] += dmg;
                    Rec r = rec(dealer);
                    if (r != null) r.dealt += dmg;
                }
            }
            if (tgt != null) {
                int o = tgt.getOwner();
                if (o >= 0 && o <= 1) {
                    taken[o] += dmg;
                    Rec r = rec(tgt);
                    if (r != null) r.taken += dmg;
                }
            }
        } catch (Throwable ignore) {}
    }

    private Rec rec(ShipAPI ship) {
        return recs.get(String.valueOf(System.identityHashCode(ship)));
    }

    static ShipAPI resolveShip(Object o) {
        if (o instanceof ShipAPI) return (ShipAPI) o;
        if (o instanceof MissileAPI) return ((MissileAPI) o).getSource();
        if (o instanceof DamagingProjectileAPI) return ((DamagingProjectileAPI) o).getSource();
        return null;
    }

    // ---------------- readouts for the bridge ----------------

    static float dealt(int side) {
        ArenaStats s = active;
        return s == null ? 0f : s.dealt[side];
    }

    static float simTime() {
        ArenaStats s = active;
        return s == null ? 0f : s.simTime;
    }

    static boolean hasSnapshot() { return lastSnapshot != null; }

    static boolean hasActive() { return active != null; }

    /** Live readout of the battle in progress (any thread; reads volatile fields). */
    static Map<String, Object> liveSnapshot() {
        ArenaStats s = active;
        if (s == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("statsReady", true);
        m.put("live", true);
        m.put("scenario", s.scenarioId);
        m.put("duration_s", round(s.simTime));
        m.put("dealt", new double[]{round(s.dealt[0]), round(s.dealt[1])});
        m.put("taken", new double[]{round(s.taken[0]), round(s.taken[1])});
        List<Map<String, Object>> ships = new ArrayList<>();
        for (Rec r : s.recs.values()) {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("side", r.owner);
            sm.put("hull", r.hullId);
            sm.put("dealt", Math.round(r.dealt));
            sm.put("taken", Math.round(r.taken));
            sm.put("hull", Math.round(r.lastHull));
            sm.put("destroyed", r.dead);
            ships.add(sm);
        }
        m.put("ships", ships);
        return m;
    }

    static Map<String, Object> snapshot() { return lastSnapshot; }

    static String lastCsv() { return lastCsv; }
}

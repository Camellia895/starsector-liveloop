package simarena;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.fleet.FleetGoal;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.mission.FleetSide;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;

/**
 * Builds a real in-engine battle from a scenario JSON object, following the same recipe
 * the game's own simulator uses (see ArenaInternals.launch). No file IO here — the agent
 * reads the scenario file and passes the parsed-ready text.
 */
final class ArenaBuilder {
    private ArenaBuilder() {}

    static void start(JSONObject sc, String scenarioId) throws Exception {
        if (!sc.has("fleets")) throw new JSONException("scenario missing 'fleets': " + scenarioId);

        MissionDefinitionAPI m = ArenaInternals.newMission();

        JSONObject map = sc.optJSONObject("map");
        float minX = (float) opt(map, "minX", -10000), maxX = (float) opt(map, "maxX", 10000);
        float minY = (float) opt(map, "minY", -7500), maxY = (float) opt(map, "maxY", 7500);
        m.initMap(minX, maxX, minY, maxY);

        JSONObject fleets = sc.getJSONObject("fleets");
        buildFleet(m, fleets.optJSONObject("player"), FleetSide.PLAYER);
        buildFleet(m, fleets.optJSONObject("enemy"), FleetSide.ENEMY);

        JSONObject env = sc.optJSONObject("env");
        if (env != null) {
            JSONArray nebula = env.optJSONArray("nebula");
            if (nebula != null) for (int i = 0; i < nebula.length(); i++) {
                JSONArray n = nebula.getJSONArray(i);
                m.addNebula((float) n.getDouble(0), (float) n.getDouble(1), (float) n.getDouble(2));
            }
            JSONObject ast = env.optJSONObject("asteroids");
            if (ast != null) {
                m.addAsteroidField((float) opt(ast, "cx", 0), (float) opt(ast, "cy", 0),
                        (float) opt(ast, "angle", 0), (float) opt(ast, "width", 16000),
                        (float) opt(ast, "minSpeed", 20), (float) opt(ast, "maxSpeed", 70),
                        ast.optInt("count", 120));
            }
            JSONArray objs = env.optJSONArray("objectives");
            if (objs != null) for (int i = 0; i < objs.length(); i++) {
                JSONArray o = objs.getJSONArray(i);
                m.addObjective((float) o.getDouble(0), (float) o.getDouble(1), o.optString(2, "nav_buoy"));
            }
        }

        ArenaInternals.setAIFight(m, true);

        String bg = sc.optString("background", null);
        if (bg != null && !bg.isEmpty()) m.setBackgroundSpriteName(bg);

        ArenaEnginePlugin plugin = new ArenaEnginePlugin(scenarioId, minX, maxX, minY, maxY);
        m.addPlugin((EveryFrameCombatPlugin) plugin);

        ArenaInternals.launch(m, minY, maxY, scenarioId);
    }

    private static void buildFleet(MissionDefinitionAPI m, JSONObject f, FleetSide side) throws JSONException {
        if (f == null) return;
        FleetGoal goal = "ESCAPE".equalsIgnoreCase(f.optString("goal")) ? FleetGoal.ESCAPE : FleetGoal.ATTACK;
        String tag = f.optString("tag", side == FleetSide.PLAYER ? "ISS" : "SIM");
        int cp = f.optInt("commandPoints", 300);
        m.initFleet(side, tag, goal, false, cp);

        JSONArray ships = f.optJSONArray("ships");
        if (ships == null) return;
        for (int i = 0; i < ships.length(); i++) {
            JSONObject s = ships.getJSONObject(i);
            String variant = s.getString("variant");
            int count = s.optInt("count", 1);
            boolean wing = s.optBoolean("wing", variant.endsWith("_wing"));
            FleetMemberType type = wing ? FleetMemberType.FIGHTER_WING : FleetMemberType.SHIP;
            for (int k = 0; k < count; k++) {
                m.addToFleet(side, variant, type, false);
            }
        }
    }

    private static double opt(JSONObject o, String k, double def) {
        return o == null ? def : o.optDouble(k, def);
    }
}

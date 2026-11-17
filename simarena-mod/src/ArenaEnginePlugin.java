package simarena;

import java.awt.Color;
import java.util.List;

import org.lwjgl.util.vector.Vector2f;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.CombatEngineAPI;
import com.fs.starfarer.api.combat.EveryFrameCombatPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ViewportAPI;
import com.fs.starfarer.api.input.InputEventAPI;

/** Per-engine hook: statistics sampling, HUD ticker, battle-end detection. */
class ArenaEnginePlugin implements EveryFrameCombatPlugin {

    private final String scenarioId;
    private final float minX, maxX, minY, maxY;
    private float hudTimer = 0f;
    private boolean statsBegun = false;

    ArenaEnginePlugin(String scenarioId, float minX, float maxX, float minY, float maxY) {
        this.scenarioId = scenarioId;
        this.minX = minX; this.maxX = maxX; this.minY = minY; this.maxY = maxY;
    }

    private boolean isArena() {
        try {
            CombatEngineAPI e = Global.getCombatEngine();
            return e != null && e.getCustomData() != null
                    && e.getCustomData().containsKey("simarena");
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void init(CombatEngineAPI engine) {}

    @Override
    public void processInputPreCoreControls(float dt, List<InputEventAPI> events) {
        // reserved: in-combat hotkeys
    }

    @Override
    public void advance(float dt, List<InputEventAPI> events) {
        if (!isArena()) return;
        CombatEngineAPI engine = Global.getCombatEngine();
        try {
            if (!statsBegun) {
                ArenaStats.begin(engine, scenarioId);
                statsBegun = true;
            }
            ArenaStats.tick(engine, dt);
            hudTimer += dt;
            if (hudTimer >= 1.0f && !engine.isPaused()) {
                hudTimer = 0f;
                String banner = "SIMARENA " + scenarioId + " " + TestFormula.banner()
                        + "  P-dealt:" + Math.round(ArenaStats.dealt(0))
                        + " E-dealt:" + Math.round(ArenaStats.dealt(1))
                        + "  T+" + String.format("%.1f", ArenaStats.simTime());
                ShipAPI ps = engine.getPlayerShip();
                if (ps != null && !ps.isHulk()) {
                    engine.addFloatingText(ps.getLocation(), banner, 48f,
                            new Color(120, 255, 120, 255), ps, 1.4f, 0f);
                } else {
                    engine.addFloatingText(new Vector2f(0f, maxY * 0.35f), banner, 64f,
                            new Color(120, 255, 120, 255), null, 1.4f, 0f);
                }
            }
            ArenaStats.checkEnded(engine);
        } catch (Throwable t) {
            SimArenaModPlugin.log.error("[SimArena] plugin advance error", t);
        }
    }

    @Override
    public void renderInWorldCoords(ViewportAPI viewport) {}

    @Override
    public void renderInUICoords(ViewportAPI viewport) {}
}

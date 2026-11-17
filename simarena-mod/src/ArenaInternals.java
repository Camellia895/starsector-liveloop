package simarena;

import java.util.List;

import com.fs.starfarer.api.combat.AdmiralAIPlugin;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.mission.MissionDefinitionAPI;
import com.fs.starfarer.combat.CombatEngine;
import com.fs.starfarer.combat.CombatFleetManager;
import com.fs.starfarer.combat.CombatState;
import com.fs.starfarer.combat.entities.Ship;
import com.fs.state.AppDriver;
import com.fs.starfarer.title.Object.ObfC0884;

/**
 * The ONLY file allowed to reference renamed (ObfC####) game-internal classes.
 * If semantic names are introduced later, only this file needs updating.
 *
 * Battle launch recipe mirrors the game's own simulator entry point
 * (deobf: campaign/command/ObfC0065.java + coreui/refit/ObfC0697.java) and the
 * auto-deployment branch of CombatState (deobf lines 450-462).
 */
public final class ArenaInternals {
    private ArenaInternals() {}

    public static MissionDefinitionAPI newMission() {
        return new ObfC0884();
    }

    public static void setAIFight(MissionDefinitionAPI m, boolean fight) {
        ((ObfC0884) m).setAIWantsToFight(fight);
    }

    /** Must run on the game main thread (via GameThreadPump). */
    public static void launch(MissionDefinitionAPI m, float mapMinY, float mapMaxY, String scenarioId) {
        ObfC0884 mm = (ObfC0884) m;
        mm.load(); // CombatEngine.destroyInstance() + fresh engine + fleets into reserves

        CombatEngine engine = CombatEngine.getInstance();
        engine.getCustomData().put("simarena", scenarioId);
        AppDriver.getInstance().getSession().put("run combat simulator", new Object());
        engine.setStateToReturnTo("Title Screen State");

        CombatState cs = (CombatState) AppDriver.getInstance().getState(CombatState.STATE_ID);
        if (cs != null) cs.setShowDeploymentDialogOnStart(false);

        AppDriver.getInstance().getCurrentState().goToState(CombatState.STATE_ID);
        engine.init();
        engine.setShowDeploymentDialog(false);   // engine-side flag: NOT reset by CombatState.prepare
        engine.setShowEnemyDeploymentDialog(false);
        engine.getFleetManager(0).setMaxSkills(false);
        engine.getFleetManager(1).setMaxSkills(false);
        engine.preCombat();

        // auto-deploy both sides, same math CombatState uses for pre-deploy battles
        float h = mapMaxY - mapMinY;
        float line = Math.max(1000f, (h - engine.getStandoffRange()) * 0.5f);
        engine.getFleetManager(0).deployAll(line);
        engine.getFleetManager(1).deployAll(line);
        engine.executeRemoves();
        engine.executeAdds();

        // mirror CombatState's admiral init (that block is skipped when dialog suppressed)
        for (CombatFleetManager fm : engine.getFleetManagers()) {
            AdmiralAIPlugin ai = fm.getAdmiralAI();
            if (ai != null) ai.preCombat();
        }

        // give the engine a player ship so camera/UI have a focus; battle itself stays AI-vs-AI
        for (ShipAPI s : engine.getShips()) {
            if (s.getOwner() == 0 && !s.isFighter() && !s.isHulk()) {
                engine.setPlayerShip((Ship) s);
                break;
            }
        }
    }

    /** Variant-spec hot reload: clear cache, re-read all variant files (mirrors Ctrl+B subset). */
    public static void reloadVariants() throws Exception {
        com.fs.starfarer.loading.ObfC0767.\u00d300000();
        com.fs.starfarer.loading.SpecStore.oO0000();
    }

    /** Variant ids including mission-added ones. */
    public static List<String> variantIds() {
        return com.fs.starfarer.loading.ObfC0767.o00000();
    }
}

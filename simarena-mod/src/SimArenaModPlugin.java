package simarena;

import org.apache.log4j.Logger;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;

/**
 * SimArena LiveLoop mod.
 *
 * IMPORTANT: classes in this mod are loaded by the game's script classloader, which bans
 * java.io.File / java.nio.file / java.lang.reflect. All file IO and all agent communication
 * therefore happen in the agent (liveloop.agent.*, on the system classpath); this mod only
 * exposes static command methods that the agent invokes reflectively:
 *
 *   ArenaCommands.configure(resultsDir)           — any thread
 *   ArenaCommands.runScenario(jsonText, id)       — game thread (via agent's pump)
 *   ArenaCommands.gotoTitle / endBattle / reloadVariants / stats / statsCsv / status
 */
public class SimArenaModPlugin extends BaseModPlugin {

    public static final Logger log = Global.getLogger(SimArenaModPlugin.class);
    public static final String VERSION = "0.2.0";

    @Override
    public void onApplicationLoad() throws Exception {
        log.info("[SimArena] loaded v" + VERSION + "; LiveLoop agent will discover ArenaCommands automatically");
    }

    @Override
    public void onDevModeF8Reload() {
        log.info("[SimArena] dev F8 reload observed");
    }
}

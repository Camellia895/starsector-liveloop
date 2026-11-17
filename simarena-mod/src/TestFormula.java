package simarena;

/**
 * Hot-swap demo target. Edit this file while the game runs; the agent recompiles and
 * redefines it within ~2s, and the in-battle HUD banner changes on the next frame.
 * Used to verify the live-edit loop end to end.
 */
public final class TestFormula {
    private TestFormula() {}

    // === DCEVM structural hot-swap experiment (added while game runs under JBR 21) ===
    public static int dcevmCounter = 0;

    public static String dcevmEcho(String s) {
        dcevmCounter++;
        return "DCEVM-OK:" + s + ":" + dcevmCounter;
    }
    // === end DCEVM experiment ===

    public static String banner() {
        return dcevmEcho("banner-via-struct-hotswap");
    }

    /** Example tunable: read by future arena features. */
    public static float shieldDamageFactor() {
        return 1.0f;
    }
}

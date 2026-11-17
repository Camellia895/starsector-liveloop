package liveloop.agent;

import java.io.File;

/** Agent options parsed from the -javaagent argument string (k=v,k=v). */
public final class Config {
    public int port = 7899;
    public String[] srcDirs = new String[0];
    public String outDir;
    public boolean watch = true;
    public String mode = "";
    /** core-class patch source dir (UI/game-code hot edits); default <gameRoot>/_work/explore/liveloop/corepatch/src */
    public String patchDir;
    /** derived from srcDir parent: <parent>/scenarios, <parent>/results */
    public File scenarioDir;
    public File resultsDir;

    static Config parse(String args) {
        Config c = new Config();
        if (args != null && !args.isEmpty()) {
            for (String kv : args.split(",")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) continue;
                String k = kv.substring(0, eq).trim();
                String v = kv.substring(eq + 1).trim();
                switch (k) {
                    case "port":  c.port = Integer.parseInt(v); break;
                    case "src":   c.srcDirs = v.split(";"); break;
                    case "patch": c.patchDir = v; break;
                    case "watch": c.watch = Boolean.parseBoolean(v); break;
                    case "out":   c.outDir = v; break;
                    case "mode":  c.mode = v; break;
                    default: Log.log("unknown agent option: " + k);
                }
            }
        }
        if (c.srcDirs.length == 0) {
            c.srcDirs = new String[] { new File(System.getProperty("user.dir"), "mods/SimArena/src").getPath() };
        }
        File firstSrc = new File(c.srcDirs[0]);
        File modRoot = firstSrc.getParentFile(); // .../mods/SimArena
        if (c.outDir == null || c.outDir.isEmpty()) {
            c.outDir = new File(modRoot, "build/classes").getPath();
        }
        if (c.patchDir == null || c.patchDir.isEmpty()) {
            File gameRoot = new File(System.getProperty("user.dir")).getParentFile(); // starsector-core/..
            c.patchDir = new File(gameRoot, "_work/explore/liveloop/corepatch/src").getPath();
        }
        c.scenarioDir = new File(modRoot, "scenarios");
        c.resultsDir = new File(modRoot, "results");
        return c;
    }

    public File outDirFile() { return new File(outDir); }
    public File patchDirFile() { return patchDir == null || patchDir.isEmpty() ? null : new File(patchDir); }

    @Override public String toString() {
        return "Config{port=" + port + ", src=" + String.join(";", srcDirs) + ", patch=" + patchDir
                + ", out=" + outDir + ", watch=" + watch + ", mode=" + mode + "}";
    }
}

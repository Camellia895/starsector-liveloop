package liveloop.agent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Data-layer text hot reload: re-reads data/strings/*.json from disk into the game's live
 * static state without restarting the game. All game classes are touched reflectively from
 * the agent side (unrestricted); the game's script-classloader sandbox is never involved.
 *
 * What gets reloaded and by whom (verified with javap against starfarer_obf.jar, 0.98a-RC8):
 *   StarfarerStrings (com.fs.starfarer.loading.StarfarerStrings):
 *     public static void super() throws IOException, JSONException
 *     - the yGuard-renamed load entry point is literally named "super", no args, ()V
 *     - re-reads data/strings/tooltips.json into static field Ó00000 and
 *       data/strings/strings.json into static field o00000 (fresh disk read every call,
 *       via LoadingUtils -> the game virtual FS, so mod overrides apply), then calls
 *       com.fs.starfarer.title.String.loadTips().
 *   com.fs.starfarer.title.String:
 *     public static WeightedRandomPicker<String> picker;
 *     public static void loadTips() throws IOException, JSONException
 *     - reads data/strings/tips.json, key "tips", and APPENDS every entry to picker
 *       (no clear), so a reload must clear the picker first or tips pile up.
 *
 * Title tip carousel read path: the "下一个提示" button handler (actionPerformed) calls
 * pickTipText() -> picker.pick() on EVERY click, so after a successful reload the next
 * button click picks from the new pool. The currently displayed tip stays until then;
 * no UI rebuild needed. (See datareload/datareload.md for the full write-up.)
 *
 * Failure handling: the picker content is snapshotted before the reload attempt and
 * restored (best-effort, items+weights) if every loader path fails, so a broken JSON
 * file never leaves the title screen with an empty tip pool.
 *
 * API: POST /api/reload-strings (dispatched on the game thread when the pump is injected,
 * else run on the HTTP thread as fallback).
 */
public final class DataReload {
    private DataReload() {}

    private static final String STARFARER_STRINGS = "com.fs.starfarer.loading.StarfarerStrings";
    private static final String TITLE_STRING = "com.fs.starfarer.title.String";
    private static final String TIPS_JSON = "data/strings/tips.json";

    /**
     * Reload strings data; never throws. Returns
     * {ok, reloaded:[...], titlePickerCleared, tipCount, restoredFromSnapshot, error?}.
     */
    public static Map<String, Object> reloadStrings() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<String> reloaded = new ArrayList<>();
        List<String> errors = new ArrayList<>();

        // snapshot the current title tip pool so a total failure can restore it
        Object picker = getPicker();
        List<Object> snapItems = new ArrayList<>();
        List<Float> snapWeights = new ArrayList<>();
        if (picker != null) snapshotPicker(picker, snapItems, snapWeights, errors);

        // loadTips() appends without clearing; wipe the pool BEFORE re-adding
        boolean cleared = false;
        if (picker != null) {
            try {
                picker.getClass().getMethod("clear").invoke(picker);
                cleared = true;
            } catch (Throwable t) {
                errors.add("picker.clear: " + rootMsg(t));
            }
        }
        out.put("titlePickerCleared", cleared);

        // primary path: the game's own strings loader (tooltips.json + strings.json + tips)
        boolean coreOk = false;
        try {
            Class<?> ss = findClass(STARFARER_STRINGS);
            if (ss == null) {
                errors.add("class not loaded/found: " + STARFARER_STRINGS);
            } else {
                Method m = ss.getMethod("super"); // real bytecode name, see class javadoc
                m.invoke(null);
                coreOk = true;
                reloaded.add("data/strings/tooltips.json + data/strings/strings.json (StarfarerStrings)");
                Log.log("DataReload: StarfarerStrings loader invoked (tooltips+strings re-read from disk)");
            }
        } catch (Throwable t) {
            errors.add("StarfarerStrings loader: " + rootMsg(t));
        }

        // title tips: already re-added by the StarfarerStrings loader; call loadTips()
        // directly only when that path was unavailable/failed
        boolean tipsOk = coreOk;
        if (coreOk) {
            reloaded.add(TIPS_JSON + " (title.String.loadTips, via StarfarerStrings)");
        } else {
            try {
                Class<?> ts = findClass(TITLE_STRING);
                if (ts == null) {
                    errors.add("class not loaded/found: " + TITLE_STRING);
                } else {
                    Method m = ts.getMethod("loadTips");
                    m.invoke(null);
                    tipsOk = true;
                    reloaded.add(TIPS_JSON + " (title.String.loadTips, direct)");
                }
            } catch (Throwable t) {
                errors.add("title.String.loadTips: " + rootMsg(t));
            }
        }

        // total failure: put the old tips back so the title screen never shows an empty pool
        boolean restored = false;
        if (picker != null && !coreOk && !tipsOk && !snapItems.isEmpty()) {
            restored = restorePicker(picker, snapItems, snapWeights, errors);
        }

        Integer tipCount = picker != null ? pickerSize(picker, errors) : null;
        if (tipCount != null) out.put("tipCount", tipCount);
        out.put("restoredFromSnapshot", restored);
        out.put("reloaded", reloaded);
        boolean ok = coreOk || tipsOk;
        out.put("ok", ok);
        if (!errors.isEmpty()) out.put("error", join(errors));
        Log.log("DataReload: ok=" + ok + " reloaded=" + reloaded + " tipCount=" + tipCount
                + (errors.isEmpty() ? "" : " errors=" + errors));
        return out;
    }

    // ---------------- reflective helpers (all null-safe, never throw) ----------------

    /** Find a game class: prefer the exact loaded instance, else the app classloader. */
    private static Class<?> findClass(String dottedName) {
        try {
            Class<?> loaded = Redefiner.findLoaded(dottedName);
            if (loaded != null) return loaded;
        } catch (Throwable ignore) {}
        try {
            return Class.forName(dottedName); // core jars live on the app classloader
        } catch (Throwable t) {
            return null;
        }
    }

    /** com.fs.starfarer.title.String.picker (public static); null if class/field unavailable. */
    private static Object getPicker() {
        try {
            Class<?> ts = findClass(TITLE_STRING);
            if (ts == null) return null;
            Field f = ts.getField("picker");
            return f.get(null);
        } catch (Throwable t) {
            Log.log("DataReload: picker unavailable: " + rootMsg(t));
            return null;
        }
    }

    private static void snapshotPicker(Object picker, List<Object> items, List<Float> weights,
                                       List<String> errors) {
        try {
            Method getItems = picker.getClass().getMethod("getItems");
            Method getWeight = picker.getClass().getMethod("getWeight", Object.class);
            List<?> cur = (List<?>) getItems.invoke(picker);
            for (Object it : cur) {
                items.add(it);
                weights.add((Float) getWeight.invoke(picker, it));
            }
        } catch (Throwable t) {
            errors.add("picker snapshot: " + rootMsg(t));
        }
    }

    private static boolean restorePicker(Object picker, List<Object> items, List<Float> weights,
                                         List<String> errors) {
        try {
            Method clear = picker.getClass().getMethod("clear");
            Method add = picker.getClass().getMethod("add", Object.class, float.class);
            clear.invoke(picker);
            for (int i = 0; i < items.size(); i++) {
                add.invoke(picker, items.get(i), weights.get(i));
            }
            Log.log("DataReload: picker restored from snapshot (" + items.size() + " tips)");
            return true;
        } catch (Throwable t) {
            errors.add("picker restore: " + rootMsg(t));
            return false;
        }
    }

    private static Integer pickerSize(Object picker, List<String> errors) {
        try {
            Method getItems = picker.getClass().getMethod("getItems");
            return ((List<?>) getItems.invoke(picker)).size();
        } catch (Throwable t) {
            errors.add("picker size: " + rootMsg(t));
            return null;
        }
    }

    private static String rootMsg(Throwable t) {
        return t + (t.getCause() != null ? " <- " + t.getCause() : "");
    }

    private static String join(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(p);
        }
        return sb.toString();
    }
}

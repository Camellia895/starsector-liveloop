package liveloop.agent;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Ring-buffer log served over /api/log; also mirrored to stdout. */
public final class Log {
    private static final Deque<String> RING = new ArrayDeque<>();
    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final int CAP = 800;

    private Log() {}

    public static synchronized void log(String msg) {
        String line = LocalTime.now().format(TF) + " [liveloop] " + msg;
        if (RING.size() >= CAP) RING.pollFirst();
        RING.addLast(line);
        System.out.println(line);
    }

    public static synchronized List<String> tail() {
        return new ArrayList<>(RING);
    }
}

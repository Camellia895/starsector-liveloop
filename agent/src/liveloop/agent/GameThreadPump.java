package liveloop.agent;

/** Injection target: called once per frame on the game main thread. Must never throw or allocate heavily. */
public final class GameThreadPump {
    private GameThreadPump() {}

    public static void pump() {
        AgentBridge.pump();
    }
}

package com.grunclepug.tether;

/** Shared flags the mixins read. */
public final class Agent {
    /** How long after the last API call the agent counts as driving the client. */
    private static final long ACTIVE_MS = 120_000;

    private static volatile long lastCall;
    /** While true, Screen.hasShiftDown() reports true so shift-clicks work without a real keyboard. */
    public static volatile boolean forceShift;

    private Agent() {
    }

    static void touch() {
        lastCall = System.currentTimeMillis();
    }

    /** True while an agent has called the API recently; the game then doesn't pause when the window loses focus. */
    public static boolean active() {
        return System.currentTimeMillis() - lastCall < ACTIVE_MS;
    }
}

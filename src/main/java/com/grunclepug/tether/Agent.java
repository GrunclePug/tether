package com.grunclepug.tether;

/** Shared flags the mixins and the overlay read. */
public final class Agent {
    /** How long after the last API call the agent counts as driving the client (no pause on focus loss). */
    private static final long ACTIVE_MS = 120_000;
    /** How long after the last control action the red border stays up. */
    private static final long CONTROL_MS = 8_000;

    private static volatile long lastCall;
    private static volatile long lastControl;
    /** Set each client tick: a build, pillar, held keys or Baritone path is running. */
    static volatile boolean jobRunning;
    /** While true, Screen.hasShiftDown() reports true so shift-clicks work without a real keyboard. */
    public static volatile boolean forceShift;

    private Agent() {
    }

    static void touch() {
        lastCall = System.currentTimeMillis();
    }

    /** Called for routes that act on the game (as opposed to reading it). */
    static void control() {
        lastControl = System.currentTimeMillis();
    }

    /** True while an agent has called the API recently; the game then doesn't pause when the window loses focus. */
    public static boolean active() {
        return System.currentTimeMillis() - lastCall < ACTIVE_MS;
    }

    /** True while the agent is acting on the game right now; the overlay draws the red border. */
    static boolean controlling() {
        return jobRunning || System.currentTimeMillis() - lastControl < CONTROL_MS;
    }
}

package com.grunclepug.tether;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;

/** The last few hundred chat and system messages, numbered so a caller can ask for only the new ones. */
final class ChatLog {
    private static final int MAX = 300;

    private record Line(long seq, long time, String text) {
    }

    private final Deque<Line> lines = new ArrayDeque<>();
    private long seq;

    synchronized void add(String text) {
        lines.addLast(new Line(++seq, System.currentTimeMillis(), text));
        while (lines.size() > MAX) {
            lines.removeFirst();
        }
    }

    synchronized JsonObject since(long after) {
        JsonArray out = new JsonArray();
        for (Line l : lines) {
            if (l.seq() > after) {
                JsonObject o = new JsonObject();
                o.addProperty("seq", l.seq());
                o.addProperty("time", l.time());
                o.addProperty("text", l.text());
                out.add(o);
            }
        }
        JsonObject o = new JsonObject();
        o.add("messages", out);
        o.addProperty("last", seq);
        return o;
    }
}

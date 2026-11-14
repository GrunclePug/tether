package com.grunclepug.tether;

import com.grunclepug.tether.mixin.GameNarratorAccessor;
import com.mojang.blaze3d.platform.Window;
import com.mojang.text2speech.Narrator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * What viewers see: a status line above the hotbar saying what the agent is doing, spoken aloud when TTS is on, and a
 * 2 px red frame around the game while the agent is acting on it.
 */
final class Overlay {
    /** A status line stays up this long after its last update, or as long as the agent keeps acting. */
    private static final long STATUS_MS = 180_000;
    private static final int MAX_CHARS = 600;
    private static final int MAX_LINES = 5;
    /** A chat message said just before the agent starts acting is shown if it starts within this long. */
    private static final long PENDING_MS = 30_000;
    private static final int BORDER_PX = 2;
    private static final int BORDER_COLOR = 0xFFFF2020;
    private static final int LABEL_COLOR = 0xFFE0875F;

    private static volatile String status;
    private static volatile long statusTime;
    private static String pending;
    private static long pendingTime;

    private Overlay() {
    }

    /** Sets the status line (client thread) and speaks it if TTS is on. Null or blank clears it. */
    static void setStatus(String text) {
        if (text == null || text.isBlank()) {
            status = null;
            return;
        }
        String clean = text.replaceAll("\\p{Cntrl}+", " ").strip();
        if (clean.length() > MAX_CHARS) {
            clean = clean.substring(0, MAX_CHARS - 1) + "…";
        }
        boolean changed = !clean.equals(status);
        status = clean;
        statusTime = System.currentTimeMillis();
        if (changed && Settings.tts) {
            speak(clean);
        }
    }

    /** Announces a pause (the status line shows the paused notice until resume). */
    static void paused() {
        if (Settings.tts) {
            speak("Paused. Tell Claude what's up.");
        }
    }

    /**
     * Mirrors one of the agent's chat messages (client thread). Shown and spoken while the agent is working in the
     * game; otherwise held briefly in case it is about to start, then dropped. Returns whether it was shown.
     */
    static boolean say(String markdown) {
        String text = plain(markdown);
        if (text.isEmpty() || Agent.paused) {
            return false;
        }
        if (Agent.sayActive()) {
            pending = null;
            setStatus(text);
            return true;
        }
        pending = text;
        pendingTime = System.currentTimeMillis();
        return false;
    }

    /** Called when the agent acts: shows a message it said just before starting. */
    static void flushPending() {
        if (pending != null && System.currentTimeMillis() - pendingTime < PENDING_MS) {
            setStatus(pending);
        }
        pending = null;
    }

    /** Markdown to one line of plain text: no code fences, emphasis, link targets, headings or table rules. */
    static String plain(String md) {
        StringBuilder out = new StringBuilder();
        boolean fence = false;
        for (String line : md.split("\\R")) {
            String l = line.strip();
            if (l.startsWith("```")) {
                fence = !fence;
                continue;
            }
            if (fence || l.isEmpty() || l.matches("\\|?\\s*:?-{3,}.*")) {
                continue;
            }
            if (l.matches("^#{1,6}\\s+.*") && !l.matches(".*[.:!?]$")) {
                l = l + ":";
            }
            l = l.replaceFirst("^#{1,6}\\s+", "")
                    .replaceFirst("^[-*+]\\s+", "• ")
                    .replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1")
                    .replaceAll("\\*\\*|__|`", "")
                    .replaceAll("^\\|\\s*|\\s*\\|$", "")
                    .replaceAll("\\s*\\|\\s*", " | ");
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(l);
        }
        return out.toString().replaceAll("\\s+", " ").strip();
    }

    static String status() {
        return status;
    }

    /** Whether the game's speech engine works on this machine (it needs the system's TTS library). */
    static boolean ttsAvailable() {
        Narrator n = narrator();
        return n != null && n.active();
    }

    static void speak(String text) {
        Narrator n = narrator();
        if (n != null && n.active()) {
            n.say(text, true);
        }
    }

    static void stopSpeaking() {
        Narrator n = narrator();
        if (n != null && n.active()) {
            n.clear();
        }
    }

    private static Narrator narrator() {
        return ((GameNarratorAccessor) Minecraft.getInstance().getNarrator()).tether$narrator();
    }

    /** Status line and border, drawn with the HUD. */
    static void renderHud(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        String text = status;
        if (Agent.paused) {
            drawStatus(g, mc.font, "Paused (Del). Tell Claude what's up; it carries on when you're ready.");
        } else if (text != null && (Agent.controlling() || System.currentTimeMillis() - statusTime < STATUS_MS)) {
            drawStatus(g, mc.font, text);
        }
        renderBorder(g);
    }

    /** Border only, drawn over open screens (the HUD sits underneath them). */
    static void renderBorder(GuiGraphics g) {
        if (!Agent.controlling()) {
            return;
        }
        Window w = Minecraft.getInstance().getWindow();
        float scale = (float) w.getGuiScale();
        int width = w.getWidth();
        int height = w.getHeight();
        g.pose().pushPose();
        // Draw in real pixels so the frame is 2 px whatever the GUI scale.
        g.pose().translate(0, 0, 400);
        g.pose().scale(1f / scale, 1f / scale, 1f);
        g.fill(0, 0, width, BORDER_PX, BORDER_COLOR);
        g.fill(0, height - BORDER_PX, width, height, BORDER_COLOR);
        g.fill(0, BORDER_PX, BORDER_PX, height - BORDER_PX, BORDER_COLOR);
        g.fill(width - BORDER_PX, BORDER_PX, width, height - BORDER_PX, BORDER_COLOR);
        g.pose().popPose();
    }

    private static void drawStatus(GuiGraphics g, Font font, String text) {
        int screenW = g.guiWidth();
        int screenH = g.guiHeight();
        int maxW = Math.min(380, screenW - 40);
        Component line = Component.literal("Claude: ").withStyle(Style.EMPTY.withColor(LABEL_COLOR))
                .append(Component.literal(text).withStyle(Style.EMPTY.withColor(0xFFFFFFFF)));
        List<FormattedCharSequence> lines = font.split(line, maxW);
        if (lines.size() > MAX_LINES) {
            lines = lines.subList(0, MAX_LINES);
        }
        int textW = 0;
        for (FormattedCharSequence l : lines) {
            textW = Math.max(textW, font.width(l));
        }
        int lineH = font.lineHeight + 1;
        int boxH = lines.size() * lineH + 4;
        // Above the action-bar message (which sits 68 px up) so neither covers the other.
        int bottom = screenH - 72;
        int top = bottom - boxH;
        int left = (screenW - textW) / 2 - 4;
        g.fill(left, top, left + textW + 8, bottom, 0xA0000000);
        int y = top + 3;
        for (FormattedCharSequence l : lines) {
            g.drawString(font, l, (screenW - font.width(l)) / 2, y, 0xFFFFFFFF, true);
            y += lineH;
        }
    }
}

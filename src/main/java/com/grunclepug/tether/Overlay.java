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
    private static final int MAX_CHARS = 200;
    private static final int MAX_LINES = 2;
    private static final int BORDER_PX = 2;
    private static final int BORDER_COLOR = 0xFFFF2020;
    private static final int LABEL_COLOR = 0xFFE0875F;

    private static volatile String status;
    private static volatile long statusTime;

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
        int maxW = Math.min(320, screenW - 40);
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

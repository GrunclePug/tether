package com.grunclepug.tether;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class Tether implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("tether");

    @Override
    public void onInitializeClient() {
        Settings.load();
        // The player's emergency brake: Delete by default, rebindable under Controls > Tether.
        KeyMapping pauseKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.tether.pause", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DELETE, "category.tether"));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (pauseKey.consumeClick()) {
                Actions.pause(client);
            }
        });
        // Key mappings don't fire while a screen is open (the agent may have a terminal open), so catch it there too.
        // Only while the agent is acting, so Delete still works in text boxes the rest of the time.
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> ScreenKeyboardEvents.allowKeyPress(screen)
                .register((s, key, scancode, mods) -> {
                    if (pauseKey.matches(key, scancode) && Agent.controlling()) {
                        Actions.pause(client);
                        return false;
                    }
                    return true;
                }));
        HudRenderCallback.EVENT.register((graphics, delta) -> Overlay.renderHud(graphics));
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> ScreenEvents.afterRender(screen)
                .register((s, graphics, mouseX, mouseY, delta) -> Overlay.renderBorder(graphics)));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registries) -> dispatcher.register(
                ClientCommandManager.literal("tether")
                        .then(ClientCommandManager.literal("pause").executes(ctx -> {
                            Actions.pause(ctx.getSource().getClient());
                            ctx.getSource().sendFeedback(Component.literal("Tether paused"));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("resume").executes(ctx -> {
                            Actions.resume();
                            ctx.getSource().sendFeedback(Component.literal("Tether resumed"));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("tts")
                                .executes(ctx -> setTts(ctx, !Settings.tts))
                                .then(ClientCommandManager.literal("on").executes(ctx -> setTts(ctx, true)))
                                .then(ClientCommandManager.literal("off").executes(ctx -> setTts(ctx, false))))));

        ChatLog chat = new ChatLog();
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) {
                chat.add(message.getString());
            }
        });
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, time) ->
                chat.add((sender == null ? "" : "<" + sender.getName() + "> ") + message.getString()));
        ClientTickEvents.START_CLIENT_TICK.register(Actions::tick);

        FabricLoader loader = FabricLoader.getInstance();
        Actions.baritone = loader.isModLoaded("baritone");
        Routes routes = new Routes(chat, Actions.baritone, loader.isModLoaded("toms_storage"));
        try {
            new ApiServer(routes.all()).start(sessionFile());
        } catch (Exception e) {
            LOG.error("Tether API failed to start", e);
        }
    }

    /** /tether tts [on|off]: toggles speaking status lines, and remembers the choice. */
    private static int setTts(CommandContext<FabricClientCommandSource> ctx, boolean on) {
        Settings.tts = on;
        Settings.save();
        if (!on) {
            Overlay.stopSpeaking();
        }
        String msg = "Tether TTS " + (on ? "on" : "off");
        if (on && !Overlay.ttsAvailable()) {
            msg += " (but Minecraft's speech engine isn't available on this system, so nothing will be heard)";
        }
        ctx.getSource().sendFeedback(Component.literal(msg));
        return 1;
    }

    /** $TETHER_SESSION, else $XDG_STATE_HOME/tether/session, else ~/.local/state/tether/session. */
    private static Path sessionFile() {
        String explicit = System.getenv("TETHER_SESSION");
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String state = System.getenv("XDG_STATE_HOME");
        Path base = state != null && !state.isBlank()
                ? Path.of(state)
                : Path.of(System.getProperty("user.home"), ".local", "state");
        return base.resolve("tether").resolve("session");
    }
}

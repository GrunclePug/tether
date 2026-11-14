package com.grunclepug.tether;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class Tether implements ClientModInitializer {
    public static final Logger LOG = LoggerFactory.getLogger("tether");

    @Override
    public void onInitializeClient() {
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

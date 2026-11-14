package com.grunclepug.tether;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;

/** Typed reads from a request body. */
final class Args {
    private Args() {
    }

    static boolean has(JsonObject a, String key) {
        return a.has(key) && !a.get(key).isJsonNull();
    }

    static int i(JsonObject a, String key) {
        return need(a, key).getAsInt();
    }

    static int i(JsonObject a, String key, int def) {
        return has(a, key) ? a.get(key).getAsInt() : def;
    }

    static double d(JsonObject a, String key) {
        return need(a, key).getAsDouble();
    }

    static String s(JsonObject a, String key) {
        return need(a, key).getAsString();
    }

    static String s(JsonObject a, String key, String def) {
        return has(a, key) ? a.get(key).getAsString() : def;
    }

    static boolean b(JsonObject a, String key, boolean def) {
        return has(a, key) ? a.get(key).getAsBoolean() : def;
    }

    static BlockPos pos(JsonObject a) {
        return new BlockPos(i(a, "x"), i(a, "y"), i(a, "z"));
    }

    private static JsonElement need(JsonObject a, String key) {
        if (!has(a, key)) {
            throw new ApiException("missing argument '" + key + "'");
        }
        return a.get(key);
    }
}

package com.grunclepug.tether;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.grunclepug.tether.compat.BaritoneCompat;
import com.grunclepug.tether.compat.TomsCompat;
import com.grunclepug.tether.mixin.AbstractContainerScreenAccessor;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** The API's routes. Handlers run on an HTTP thread and hop onto the client thread for anything touching the game. */
final class Routes {
    private final Minecraft mc = Minecraft.getInstance();
    private final ChatLog chat;
    private final boolean baritone;
    private final boolean toms;

    Routes(ChatLog chat, boolean baritone, boolean toms) {
        this.chat = chat;
        this.baritone = baritone;
        this.toms = toms;
    }

    Map<String, ApiServer.Route> all() {
        Map<String, ApiServer.Route> r = new LinkedHashMap<>();
        r.put("help", a -> help(r));
        r.put("state", a -> client(this::state));
        r.put("inventory", a -> client(this::inventory));
        r.put("status", a -> client(() -> status(a)));
        r.put("say", a -> client(() -> {
            JsonObject o = new JsonObject();
            o.addProperty("shown", Overlay.say(Args.s(a, "text")));
            return o;
        }));
        r.put("resume", a -> client(() -> {
            Actions.resume();
            JsonObject o = Actions.jobProgress();
            o.addProperty("paused", false);
            return o;
        }));
        r.put("status/tts", a -> client(() -> statusTts(a)));
        r.put("select", ctl(a -> client(() -> select(a))));
        r.put("look", ctl(a -> client(() -> look(a))));
        r.put("move", ctl(this::move));
        r.put("stop", a -> client(this::stop));
        r.put("block", a -> client(() -> block(a)));
        r.put("blocks", a -> client(() -> blocks(a)));
        r.put("entities", a -> client(() -> entities(a)));
        r.put("use", ctl(a -> client(() -> use(a))));
        r.put("region", a -> client(() -> region(a)));
        r.put("place", ctl(this::place));
        r.put("place/options", ctl(a -> client(() -> placeOptions(a))));
        r.put("break", ctl(this::breakBlock));
        r.put("build", ctl(this::build));
        r.put("build/status", a -> client(() -> {
            JsonObject o = Actions.jobProgress();
            o.addProperty("paused", Agent.paused);
            if (mc.player != null) {
                o.add("player", pos(mc.player.blockPosition()));
            }
            return o;
        }));
        r.put("pillar", ctl(this::pillar));
        r.put("view", a -> client(() -> view(a)));
        r.put("attack", ctl(a -> client(() -> attack(a))));
        r.put("screen", a -> client(this::screen));
        r.put("screen/click", ctl(a -> client(() -> screenClick(a))));
        r.put("screen/mouse", ctl(a -> client(() -> screenMouse(a))));
        r.put("screen/type", ctl(a -> client(() -> screenType(a))));
        r.put("screen/key", ctl(a -> client(() -> screenKey(a))));
        r.put("screen/close", ctl(a -> client(this::screenClose)));
        r.put("storage", a -> client(() -> storage(a)));
        r.put("storage/pull", ctl(a -> client(() -> storagePull(a))));
        r.put("storage/push", ctl(a -> client(() -> storagePush(a))));
        r.put("chat", ctl(a -> client(() -> sendChat(a))));
        r.put("chat/log", a -> chatLog(a));
        r.put("baritone", ctl(a -> client(() -> baritoneRun(a))));
        r.put("baritone/status", a -> client(() -> baritoneStatus()));
        r.put("baritone/wait", this::baritoneWait);
        return r;
    }

    // --- plumbing ---

    /** Marks a route as acting on the game, which keeps the red border up. */
    private static ApiServer.Route ctl(ApiServer.Route route) {
        return a -> {
            if (Agent.paused) {
                throw new ApiException("paused by the player (pause key): ask them what's up, then call resume");
            }
            Agent.control();
            try {
                return route.handle(a);
            } finally {
                Agent.control();
            }
        };
    }

    private <T> T client(Supplier<T> task) throws Exception {
        try {
            return mc.submit(task).get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    private static JsonObject await(CompletableFuture<JsonObject> f, long seconds) throws Exception {
        return f.get(seconds, TimeUnit.SECONDS);
    }

    private LocalPlayer player() {
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            throw new ApiException("not in a world");
        }
        return mc.player;
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static String id(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static JsonObject stack(ItemStack stack) {
        JsonObject o = new JsonObject();
        o.addProperty("item", id(stack));
        o.addProperty("count", stack.getCount());
        o.addProperty("name", stack.getHoverName().getString());
        return o;
    }

    private static JsonObject pos(BlockPos p) {
        JsonObject o = new JsonObject();
        o.addProperty("x", p.getX());
        o.addProperty("y", p.getY());
        o.addProperty("z", p.getZ());
        return o;
    }

    private JsonObject help(Map<String, ApiServer.Route> r) {
        JsonObject o = new JsonObject();
        JsonArray names = new JsonArray();
        r.keySet().forEach(names::add);
        o.add("routes", names);
        o.addProperty("baritone", baritone);
        o.addProperty("toms_storage", toms);
        return o;
    }

    // --- overlay ---

    /** Sets the status line above the hotbar ("text"), or clears it ("clear": true). */
    private JsonObject status(JsonObject a) {
        if (Args.b(a, "clear", false)) {
            Overlay.setStatus(null);
        } else if (Args.has(a, "text")) {
            Overlay.setStatus(Args.s(a, "text"));
        }
        JsonObject o = new JsonObject();
        o.addProperty("text", Overlay.status());
        o.addProperty("tts", Settings.tts);
        o.addProperty("controlling", Agent.controlling());
        return o;
    }

    /** Reads or sets ("enabled") whether status lines are spoken. */
    private JsonObject statusTts(JsonObject a) {
        if (Args.has(a, "enabled")) {
            Settings.tts = Args.b(a, "enabled", false);
            Settings.save();
            if (!Settings.tts) {
                Overlay.stopSpeaking();
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("tts", Settings.tts);
        o.addProperty("available", Overlay.ttsAvailable());
        return o;
    }

    // --- player and world ---

    private JsonObject state() {
        LocalPlayer p = player();
        JsonObject o = new JsonObject();
        o.addProperty("x", p.getX());
        o.addProperty("y", p.getY());
        o.addProperty("z", p.getZ());
        o.add("block", pos(p.blockPosition()));
        o.addProperty("yaw", Mth.wrapDegrees(p.getYRot()));
        o.addProperty("pitch", p.getXRot());
        o.addProperty("facing", p.getDirection().getName());
        o.addProperty("health", p.getHealth());
        o.addProperty("food", p.getFoodData().getFoodLevel());
        o.addProperty("dimension", mc.level.dimension().location().toString());
        o.addProperty("selected", p.getInventory().selected);
        o.add("held", stack(p.getMainHandItem()));
        o.addProperty("screen", mc.screen == null ? null : mc.screen.getClass().getName());
        o.addProperty("windowActive", mc.isWindowActive());
        o.addProperty("paused", Agent.paused);
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            JsonObject t = pos(bh.getBlockPos());
            t.addProperty("block", id(mc.level.getBlockState(bh.getBlockPos())));
            t.addProperty("face", bh.getDirection().getName());
            o.add("target", t);
        } else if (hit instanceof EntityHitResult eh) {
            o.add("target", entity(eh.getEntity()));
        }
        return o;
    }

    private JsonObject inventory() {
        Inventory inv = player().getInventory();
        JsonObject o = new JsonObject();
        JsonArray slots = new JsonArray();
        int free = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty()) {
                free++;
                continue;
            }
            JsonObject e = stack(s);
            e.addProperty("slot", i);
            slots.add(e);
        }
        o.add("items", slots);
        o.addProperty("free", free);
        o.addProperty("selected", inv.selected);
        o.addProperty("note", "slots 0-8 are the hotbar, 9-35 the main inventory");
        return o;
    }

    private JsonObject select(JsonObject a) {
        int slot = Args.i(a, "slot");
        if (slot < 0 || slot > 8) {
            throw new ApiException("slot must be 0-8");
        }
        player().getInventory().selected = slot;
        return stack(player().getMainHandItem());
    }

    private JsonObject look(JsonObject a) {
        LocalPlayer p = player();
        if (Args.has(a, "x")) {
            Actions.lookAt(p, new Vec3(Args.d(a, "x"), Args.d(a, "y"), Args.d(a, "z")));
        } else {
            p.setYRot((float) Args.d(a, "yaw"));
            p.setXRot(Mth.clamp((float) Args.d(a, "pitch"), -90f, 90f));
        }
        JsonObject o = ok();
        o.addProperty("yaw", Mth.wrapDegrees(p.getYRot()));
        o.addProperty("pitch", p.getXRot());
        return o;
    }

    private JsonObject move(JsonObject a) throws Exception {
        int ticks = Args.i(a, "ticks", 10);
        if (ticks < 1 || ticks > 400) {
            throw new ApiException("ticks must be 1-400");
        }
        CompletableFuture<JsonObject> done = client(() -> {
            player();
            Map<KeyMapping, Integer> keys = new LinkedHashMap<>();
            if (!Args.has(a, "keys")) {
                throw new ApiException("missing 'keys': any of forward, back, left, right, jump, sneak, sprint");
            }
            for (var k : a.getAsJsonArray("keys")) {
                keys.put(key(k.getAsString()), ticks);
            }
            return Actions.hold(keys);
        });
        return await(done, ticks / 20 + 10);
    }

    private KeyMapping key(String name) {
        return switch (name) {
            case "forward" -> mc.options.keyUp;
            case "back" -> mc.options.keyDown;
            case "left" -> mc.options.keyLeft;
            case "right" -> mc.options.keyRight;
            case "jump" -> mc.options.keyJump;
            case "sneak" -> mc.options.keyShift;
            case "sprint" -> mc.options.keySprint;
            default -> throw new ApiException("unknown key '" + name + "'");
        };
    }

    private JsonObject stop() {
        Actions.stopAll(mc);
        if (baritone) {
            BaritoneCompat.cancel();
        }
        return ok();
    }

    private JsonObject block(JsonObject a) {
        player();
        BlockPos p = Args.pos(a);
        BlockState s = mc.level.getBlockState(p);
        JsonObject o = pos(p);
        o.addProperty("block", id(s));
        o.addProperty("state", s.toString());
        o.addProperty("hasBlockEntity", mc.level.getBlockEntity(p) != null);
        return o;
    }

    private JsonObject blocks(JsonObject a) {
        LocalPlayer p = player();
        int r = Args.i(a, "radius", 8);
        if (r < 1 || r > 32) {
            throw new ApiException("radius must be 1-32");
        }
        String match = Args.s(a, "match", null);
        int limit = Args.i(a, "limit", 200);
        BlockPos c = p.blockPosition();
        List<BlockPos> found = new ArrayList<>();
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-r, -r, -r), c.offset(r, r, r))) {
            BlockState s = mc.level.getBlockState(q);
            if (s.isAir() || (match != null && !id(s).contains(match))) {
                continue;
            }
            found.add(q.immutable());
        }
        found.sort((x, y) -> Double.compare(x.distSqr(c), y.distSqr(c)));
        JsonArray out = new JsonArray();
        for (BlockPos q : found.subList(0, Math.min(limit, found.size()))) {
            JsonObject o = pos(q);
            o.addProperty("block", id(mc.level.getBlockState(q)));
            out.add(o);
        }
        JsonObject o = new JsonObject();
        o.add("blocks", out);
        o.addProperty("total", found.size());
        return o;
    }

    private JsonObject entity(Entity e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.getId());
        o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        o.addProperty("name", e.getName().getString());
        o.addProperty("x", e.getX());
        o.addProperty("y", e.getY());
        o.addProperty("z", e.getZ());
        return o;
    }

    private JsonObject entities(JsonObject a) {
        LocalPlayer p = player();
        double r = Args.i(a, "radius", 16);
        String match = Args.s(a, "match", null);
        List<Entity> found = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p || e.distanceTo(p) > r) {
                continue;
            }
            String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
            if (match != null && !type.contains(match) && !e.getName().getString().toLowerCase(Locale.ROOT).contains(match)) {
                continue;
            }
            found.add(e);
        }
        found.sort((x, y) -> Float.compare(x.distanceTo(p), y.distanceTo(p)));
        JsonArray out = new JsonArray();
        for (Entity e : found) {
            JsonObject o = entity(e);
            o.addProperty("distance", e.distanceTo(p));
            out.add(o);
        }
        JsonObject o = new JsonObject();
        o.add("entities", out);
        return o;
    }

    // --- interaction ---

    private void requireReach(LocalPlayer p, BlockPos pos) {
        if (!p.canInteractWithBlock(pos, 1.0)) {
            throw new ApiException("block " + pos.toShortString() + " is out of reach");
        }
    }

    private JsonObject result(InteractionResult r, InteractionHand hand) {
        if (r.shouldSwing()) {
            player().swing(hand);
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", r.consumesAction());
        o.addProperty("result", r.name());
        return o;
    }

    /** Right-click a block (open it, flip it, use the held item on it), or use the held item in the air. */
    private JsonObject use(JsonObject a) {
        LocalPlayer p = player();
        if (!Args.has(a, "x")) {
            return result(mc.gameMode.useItem(p, InteractionHand.MAIN_HAND), InteractionHand.MAIN_HAND);
        }
        BlockPos pos = Args.pos(a);
        requireReach(p, pos);
        Vec3 center = Vec3.atCenterOf(pos);
        Direction face = Args.has(a, "face")
                ? Direction.byName(Args.s(a, "face"))
                : Direction.getNearest(p.getEyePosition().subtract(center));
        if (face == null) {
            throw new ApiException("face must be one of down, up, north, south, west, east");
        }
        Vec3 hit = center.relative(face, 0.5);
        Actions.lookAt(p, hit);
        return result(mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false)),
                InteractionHand.MAIN_HAND);
    }

    /** Place one block (by default whatever's in hand) at a position; a one-step build. */
    private JsonObject place(JsonObject a) throws Exception {
        CompletableFuture<JsonObject> done = client(() -> {
            LocalPlayer p = player();
            String spec = Args.s(a, "block", null);
            if (spec == null) {
                if (!(p.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem bi)) {
                    throw new ApiException("nothing placeable in hand; pass block=<id[state]>");
                }
                spec = BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString();
            }
            return Actions.startJob(mc, new BuildJob(List.of(BuildJob.step(Args.pos(a), spec)), Args.b(a, "walk", false), false, 1));
        });
        return await(done, 60);
    }

    /** Jump-places a column of blocks under the player (to reach a roof); returns the positions to clear later. */
    private JsonObject pillar(JsonObject a) throws Exception {
        int height = Args.i(a, "height");
        if (height < 1 || height > 16) {
            throw new ApiException("height must be 1-16");
        }
        CompletableFuture<JsonObject> done = client(() -> {
            player();
            String spec = Args.s(a, "block", "minecraft:dirt");
            net.minecraft.world.item.Item item = BuildJob.step(BlockPos.ZERO, spec).want().getBlock().asItem();
            return Actions.startPillar(mc, new PillarJob(item, height));
        });
        return await(done, 10 + height * 3L);
    }

    /** Which states the given block could be placed as at a position, from where the player stands now. */
    private JsonObject placeOptions(JsonObject a) {
        LocalPlayer p = player();
        BlockPos pos = Args.pos(a);
        net.minecraft.world.item.Item item = BuildJob.step(pos, Args.s(a, "block")).want().getBlock().asItem();
        if (!Hotbar.hold(mc, p, item)) {
            throw new ApiException("no " + BuiltInRegistries.ITEM.getKey(item) + " in inventory");
        }
        JsonArray states = new JsonArray();
        Placement.options(mc, p, pos).forEach(states::add);
        JsonObject o = new JsonObject();
        o.add("states", states);
        return o;
    }

    private JsonObject breakBlock(JsonObject a) throws Exception {
        CompletableFuture<JsonObject> done = client(() -> {
            player();
            return Actions.startJob(mc, new BuildJob(List.of(BuildJob.step(Args.pos(a), "air")), Args.b(a, "walk", false), Args.b(a, "pickup", false), 1));
        });
        return await(done, 60);
    }

    /**
     * Applies a list of {x, y, z, block} (block "air" to clear) in order: skips what already matches, mines what's
     * wrong, places the rest with the right orientation. Positions can also be given relative to an origin.
     */
    private JsonObject build(JsonObject a) throws Exception {
        if (!Args.has(a, "blocks")) {
            throw new ApiException("missing 'blocks': [{x, y, z, block}, ...]");
        }
        int ox = 0, oy = 0, oz = 0;
        if (Args.has(a, "origin")) {
            JsonObject o = a.getAsJsonObject("origin");
            ox = Args.i(o, "x");
            oy = Args.i(o, "y");
            oz = Args.i(o, "z");
        }
        List<BuildJob.Step> steps = new ArrayList<>();
        for (var e : a.getAsJsonArray("blocks")) {
            JsonObject b = e.getAsJsonObject();
            steps.add(BuildJob.step(new BlockPos(ox + Args.i(b, "x"), oy + Args.i(b, "y"), oz + Args.i(b, "z")),
                    Args.s(b, "block")));
        }
        if (steps.isEmpty() || steps.size() > 4096) {
            throw new ApiException("blocks must have 1-4096 entries");
        }
        int perTick = Math.max(1, Math.min(Args.i(a, "perTick", 1), 4));
        boolean walk = Args.b(a, "walk", false);
        boolean pickup = Args.b(a, "pickup", false);
        CompletableFuture<JsonObject> done = client(() -> {
            player();
            if ((walk || pickup) && !baritone) {
                throw new ApiException("walk and pickup need Baritone");
            }
            return Actions.startJob(mc, new BuildJob(steps, walk, pickup, perTick));
        });
        if (!Args.b(a, "wait", true)) {
            return client(Actions::jobProgress);
        }
        int timeout = Math.min(Args.i(a, "timeout", 300), 590);
        try {
            return await(done, timeout);
        } catch (java.util.concurrent.TimeoutException e) {
            JsonObject o = client(Actions::jobProgress);
            o.addProperty("note", "still running; poll build/status, or stop");
            return o;
        }
    }

    /**
     * A box of blocks as text: one entry per y layer, one string per z row, one character per x column, decoded by
     * the palette ('.' is air). Full block states by default; states=false lists bare ids for a smaller palette.
     */
    private JsonObject region(JsonObject a) {
        player();
        int x1 = Math.min(Args.i(a, "x1"), Args.i(a, "x2")), x2 = Math.max(Args.i(a, "x1"), Args.i(a, "x2"));
        int y1 = Math.min(Args.i(a, "y1"), Args.i(a, "y2")), y2 = Math.max(Args.i(a, "y1"), Args.i(a, "y2"));
        int z1 = Math.min(Args.i(a, "z1"), Args.i(a, "z2")), z2 = Math.max(Args.i(a, "z1"), Args.i(a, "z2"));
        long volume = (long) (x2 - x1 + 1) * (y2 - y1 + 1) * (z2 - z1 + 1);
        if (volume > 65536) {
            throw new ApiException("region too large (" + volume + " blocks, max 65536)");
        }
        boolean states = Args.b(a, "states", true);
        String symbols = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789#$%&*+=?@^~<>!;:/|";
        Map<String, Character> palette = new LinkedHashMap<>();
        JsonArray layers = new JsonArray();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int y = y1; y <= y2; y++) {
            JsonArray rows = new JsonArray();
            for (int z = z1; z <= z2; z++) {
                StringBuilder row = new StringBuilder();
                for (int x = x1; x <= x2; x++) {
                    BlockState s = mc.level.getBlockState(m.set(x, y, z));
                    if (s.isAir()) {
                        row.append('.');
                        continue;
                    }
                    String key = states
                            ? net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(s)
                            : id(s);
                    Character c = palette.get(key);
                    if (c == null) {
                        if (palette.size() >= symbols.length()) {
                            throw new ApiException("more than " + symbols.length()
                                    + " distinct blocks; shrink the region or pass states=false");
                        }
                        c = symbols.charAt(palette.size());
                        palette.put(key, c);
                    }
                    row.append(c);
                }
                rows.add(row.toString());
            }
            JsonObject layer = new JsonObject();
            layer.addProperty("y", y);
            layer.add("rows", rows);
            layers.add(layer);
        }
        JsonObject pal = new JsonObject();
        palette.forEach((k, v) -> pal.addProperty(String.valueOf(v), k));
        JsonObject o = new JsonObject();
        JsonObject origin = new JsonObject();
        origin.addProperty("x", x1);
        origin.addProperty("y", y1);
        origin.addProperty("z", z1);
        o.add("origin", origin);
        o.addProperty("layout", "layers[y].rows[z - origin.z].charAt(x - origin.x)");
        o.add("palette", pal);
        o.add("layers", layers);
        return o;
    }

    /** Hide the HUD and switch camera perspective, for taking a look at a build. */
    private JsonObject view(JsonObject a) {
        if (Args.has(a, "hideGui")) {
            mc.options.hideGui = Args.b(a, "hideGui", false);
        }
        if (Args.has(a, "camera")) {
            mc.options.setCameraType(switch (Args.s(a, "camera")) {
                case "first" -> net.minecraft.client.CameraType.FIRST_PERSON;
                case "back" -> net.minecraft.client.CameraType.THIRD_PERSON_BACK;
                case "front" -> net.minecraft.client.CameraType.THIRD_PERSON_FRONT;
                default -> throw new ApiException("camera must be first, back or front");
            });
        }
        JsonObject o = ok();
        o.addProperty("hideGui", mc.options.hideGui);
        o.addProperty("camera", mc.options.getCameraType().name().toLowerCase(Locale.ROOT));
        return o;
    }

    private JsonObject attack(JsonObject a) {
        LocalPlayer p = player();
        Entity e = mc.level.getEntity(Args.i(a, "id"));
        if (e == null) {
            throw new ApiException("no entity with that id");
        }
        Actions.lookAt(p, e.getBoundingBox().getCenter());
        mc.gameMode.attack(p, e);
        p.swing(InteractionHand.MAIN_HAND);
        return ok();
    }

    // --- screens ---

    private Screen requireScreen() {
        if (mc.screen == null) {
            throw new ApiException("no screen is open");
        }
        return mc.screen;
    }

    private JsonObject screen() {
        Screen s = mc.screen;
        JsonObject o = new JsonObject();
        o.addProperty("open", s != null);
        if (s == null) {
            return o;
        }
        o.addProperty("class", s.getClass().getName());
        o.addProperty("title", s.getTitle().getString());
        o.addProperty("width", s.width);
        o.addProperty("height", s.height);
        JsonArray widgets = new JsonArray();
        List<? extends GuiEventListener> children = s.children();
        for (int i = 0; i < children.size(); i++) {
            if (!(children.get(i) instanceof AbstractWidget w)) {
                continue;
            }
            JsonObject e = new JsonObject();
            e.addProperty("index", i);
            e.addProperty("type", w.getClass().getSimpleName());
            e.addProperty("x", w.getX());
            e.addProperty("y", w.getY());
            e.addProperty("w", w.getWidth());
            e.addProperty("h", w.getHeight());
            e.addProperty("text", w.getMessage().getString());
            e.addProperty("focused", w.isFocused());
            if (w instanceof EditBox box) {
                e.addProperty("value", box.getValue());
            }
            widgets.add(e);
        }
        o.add("widgets", widgets);
        if (s instanceof AbstractContainerScreen<?> cs) {
            AbstractContainerMenu menu = cs.getMenu();
            AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) cs;
            JsonArray slots = new JsonArray();
            for (Slot slot : menu.slots) {
                ItemStack st = slot.getItem();
                JsonObject e = st.isEmpty() ? new JsonObject() : stack(st);
                e.addProperty("slot", slot.index);
                e.addProperty("owner", slot.container instanceof Inventory ? "player" : "container");
                e.addProperty("containerSlot", slot.getContainerSlot());
                e.addProperty("x", acc.tether$leftPos() + slot.x + 8);
                e.addProperty("y", acc.tether$topPos() + slot.y + 8);
                slots.add(e);
            }
            o.add("slots", slots);
            if (!menu.getCarried().isEmpty()) {
                o.add("carried", stack(menu.getCarried()));
            }
            o.addProperty("storageTerminal", toms && TomsCompat.isTerminal(menu));
        }
        return o;
    }

    /** Click a menu slot by index; works with no screen open too, against the player's own inventory. */
    private JsonObject screenClick(JsonObject a) {
        LocalPlayer p = player();
        AbstractContainerMenu menu = p.containerMenu;
        int slot = Args.i(a, "slot");
        if (slot != -999 && (slot < 0 || slot >= menu.slots.size())) {
            throw new ApiException("slot out of range 0-" + (menu.slots.size() - 1));
        }
        ClickType type;
        try {
            type = ClickType.valueOf(Args.s(a, "type", "pickup").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException("type must be one of pickup, quick_move, swap, clone, throw, quick_craft, pickup_all");
        }
        mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, Args.i(a, "button", 0), type, p);
        JsonObject o = ok();
        if (!menu.getCarried().isEmpty()) {
            o.add("carried", stack(menu.getCarried()));
        }
        return o;
    }

    /** A raw mouse click at screen coordinates, for widgets and mod screens that don't use menu slots. */
    private JsonObject screenMouse(JsonObject a) {
        Screen s = requireScreen();
        double x = Args.d(a, "x");
        double y = Args.d(a, "y");
        int button = Args.i(a, "button", 0);
        Agent.forceShift = Args.b(a, "shift", false);
        try {
            s.mouseClicked(x, y, button);
            s.mouseReleased(x, y, button);
        } finally {
            Agent.forceShift = false;
        }
        return ok();
    }

    private JsonObject screenType(JsonObject a) {
        Screen s = requireScreen();
        String text = Args.s(a, "text");
        if (Args.has(a, "widget")) {
            int i = Args.i(a, "widget");
            List<? extends GuiEventListener> children = s.children();
            if (i < 0 || i >= children.size() || !(children.get(i) instanceof EditBox box)) {
                throw new ApiException("widget " + i + " is not a text box");
            }
            s.setFocused(box);
            box.setValue(text);
            JsonObject o = ok();
            o.addProperty("value", box.getValue());
            return o;
        }
        for (char c : text.toCharArray()) {
            s.charTyped(c, 0);
        }
        return ok();
    }

    private JsonObject screenKey(JsonObject a) {
        Screen s = requireScreen();
        String name = Args.s(a, "key").toLowerCase(Locale.ROOT);
        int code = switch (name) {
            case "escape" -> GLFW.GLFW_KEY_ESCAPE;
            case "enter" -> GLFW.GLFW_KEY_ENTER;
            case "tab" -> GLFW.GLFW_KEY_TAB;
            case "backspace" -> GLFW.GLFW_KEY_BACKSPACE;
            case "delete" -> GLFW.GLFW_KEY_DELETE;
            case "up" -> GLFW.GLFW_KEY_UP;
            case "down" -> GLFW.GLFW_KEY_DOWN;
            case "left" -> GLFW.GLFW_KEY_LEFT;
            case "right" -> GLFW.GLFW_KEY_RIGHT;
            default -> {
                if (name.length() == 1 && Character.isLetterOrDigit(name.charAt(0))) {
                    yield Character.toUpperCase(name.charAt(0));
                }
                throw new ApiException("unknown key '" + name + "'");
            }
        };
        int mods = Args.b(a, "shift", false) ? GLFW.GLFW_MOD_SHIFT : 0;
        boolean handled = s.keyPressed(code, 0, mods);
        if (mc.screen == s) {
            s.keyReleased(code, 0, mods);
        }
        JsonObject o = ok();
        o.addProperty("handled", handled);
        return o;
    }

    private JsonObject screenClose() {
        LocalPlayer p = player();
        if (mc.screen instanceof AbstractContainerScreen<?>) {
            p.closeContainer();
        } else if (mc.screen != null) {
            mc.setScreen(null);
        }
        return ok();
    }

    // --- Tom's Simple Storage ---

    private AbstractContainerMenu terminal() {
        if (!toms) {
            throw new ApiException("Tom's Simple Storage isn't installed");
        }
        AbstractContainerMenu menu = player().containerMenu;
        if (!TomsCompat.isTerminal(menu)) {
            throw new ApiException("no storage terminal is open; 'use' the terminal block first");
        }
        return menu;
    }

    private JsonObject storage(JsonObject a) {
        JsonObject o = new JsonObject();
        String match = Args.s(a, "match", null);
        o.add("items", TomsCompat.list(terminal(), match == null ? null : match.toLowerCase(Locale.ROOT)));
        return o;
    }

    private JsonObject storagePull(JsonObject a) {
        int stacks = Args.i(a, "stacks", 1);
        if (stacks < 1 || stacks > 36) {
            throw new ApiException("stacks must be 1-36");
        }
        int n = TomsCompat.pull(terminal(), Args.s(a, "item", ""), Args.i(a, "index", -1), stacks);
        if (n < 0) {
            throw new ApiException("that item isn't in the terminal");
        }
        JsonObject o = ok();
        o.addProperty("requested", n);
        return o;
    }

    /**
     * Shift-clicks player inventory slots into the open container (a Tom's terminal stores them). Without arguments
     * it moves everything except the hotbar; item=<id> moves only that item, hotbar=true includes the hotbar.
     */
    private JsonObject storagePush(JsonObject a) {
        LocalPlayer p = player();
        AbstractContainerMenu menu = p.containerMenu;
        if (menu == p.inventoryMenu) {
            throw new ApiException("no container is open; 'use' the terminal or chest first");
        }
        String item = Args.s(a, "item", null);
        boolean hotbar = Args.b(a, "hotbar", false);
        int moved = 0;
        for (Slot slot : menu.slots) {
            if (!(slot.container instanceof Inventory) || slot.getItem().isEmpty()) {
                continue;
            }
            int invSlot = slot.getContainerSlot();
            if (invSlot >= 36 || (!hotbar && invSlot < 9)) {
                continue;
            }
            if (item != null && !id(slot.getItem()).equals(item)) {
                continue;
            }
            mc.gameMode.handleInventoryMouseClick(menu.containerId, slot.index, 0, ClickType.QUICK_MOVE, p);
            moved++;
        }
        JsonObject o = ok();
        o.addProperty("slotsMoved", moved);
        return o;
    }

    // --- chat ---

    private JsonObject sendChat(JsonObject a) {
        player();
        String msg = Args.s(a, "message").strip();
        if (msg.isEmpty()) {
            throw new ApiException("empty message");
        }
        if (msg.startsWith("/")) {
            mc.getConnection().sendCommand(msg.substring(1));
        } else {
            mc.getConnection().sendChat(msg);
        }
        return ok();
    }

    private JsonObject chatLog(JsonObject a) {
        return chat.since(Args.i(a, "since", 0));
    }

    // --- Baritone ---

    private void requireBaritone() {
        if (!baritone) {
            throw new ApiException("Baritone isn't installed");
        }
    }

    private JsonObject baritoneRun(JsonObject a) {
        requireBaritone();
        player();
        return BaritoneCompat.run(Args.s(a, "command"));
    }

    private JsonObject baritoneStatus() {
        requireBaritone();
        player();
        return BaritoneCompat.status();
    }

    /**
     * Blocks until Baritone has nothing to do, or the timeout (seconds, default 60, max 300) passes. If the player
     * hasn't moved for 3 seconds while Baritone is busy, it cancels the path and returns with stuck=true.
     */
    private JsonObject baritoneWait(JsonObject a) throws Exception {
        int timeout = Math.min(Args.i(a, "timeout", 60), 300);
        long end = System.currentTimeMillis() + timeout * 1000L;
        // Give a just-issued command a moment to start before treating "not busy" as finished.
        Thread.sleep(500);
        Vec3 last = null;
        long lastMoved = System.currentTimeMillis();
        while (true) {
            JsonObject s = client(this::baritoneStatus);
            if (!s.get("busy").getAsBoolean()) {
                s.addProperty("finished", true);
                return s;
            }
            Vec3 now = client(() -> player().position());
            if (last == null || now.distanceTo(last) > 0.3) {
                last = now;
                lastMoved = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - lastMoved > 3000) {
                client(() -> {
                    BaritoneCompat.cancel();
                    return null;
                });
                s.addProperty("finished", false);
                s.addProperty("stuck", true);
                s.add("at", client(() -> pos(player().blockPosition())));
                return s;
            }
            if (System.currentTimeMillis() > end) {
                s.addProperty("finished", false);
                return s;
            }
            Agent.touch();
            Thread.sleep(250);
        }
    }
}

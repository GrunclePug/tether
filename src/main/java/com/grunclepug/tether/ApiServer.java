package com.grunclepug.tether;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Token-protected JSON API on the loopback interface. Every request is a POST (or GET) to /&lt;route&gt; with an
 * optional JSON object body; every response is a JSON object. The port and token are written to the session file
 * so the CLI can find them.
 */
final class ApiServer {
    interface Route {
        JsonObject handle(JsonObject args) throws Exception;
    }

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Map<String, Route> routes;
    private final byte[] expectedAuth;
    private final String token;
    private final HttpServer server;

    ApiServer(Map<String, Route> routes) throws IOException {
        this.routes = routes;
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        this.token = HexFormat.of().formatHex(raw);
        this.expectedAuth = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "tether-http");
            t.setDaemon(true);
            return t;
        }));
    }

    void start(Path sessionFile) throws IOException {
        server.start();
        Files.createDirectories(sessionFile.getParent());
        Path tmp = sessionFile.resolveSibling(sessionFile.getFileName() + ".tmp");
        Files.writeString(tmp, "port=" + server.getAddress().getPort() + "\ntoken=" + token + "\n");
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        Files.move(tmp, sessionFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        Tether.LOG.info("Tether API listening on 127.0.0.1:{} (session file {})",
                server.getAddress().getPort(), sessionFile);
    }

    private void handle(HttpExchange ex) throws IOException {
        int status = 200;
        JsonObject out;
        try {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(expectedAuth, auth.getBytes(StandardCharsets.UTF_8))) {
                status = 401;
                out = error("missing or wrong token");
            } else {
                String path = ex.getRequestURI().getPath().replaceAll("^/+|/+$", "");
                Route route = routes.get(path);
                if (route == null) {
                    status = 404;
                    out = error("unknown route '" + path + "'; GET /help lists them");
                } else {
                    String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).strip();
                    JsonObject args = body.isEmpty() ? new JsonObject() : JsonParser.parseString(body).getAsJsonObject();
                    Agent.touch();
                    out = route.handle(args);
                }
            }
        } catch (ApiException e) {
            status = 400;
            out = error(e.getMessage());
        } catch (Exception e) {
            status = 500;
            out = error(e.toString());
            Tether.LOG.warn("Tether route failed", e);
        }
        byte[] bytes = (GSON.toJson(out) + "\n").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static JsonObject error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return o;
    }
}

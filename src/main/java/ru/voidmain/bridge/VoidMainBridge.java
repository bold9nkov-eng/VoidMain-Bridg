package ru.voidmain.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.ChatColor;
import org.bukkit.Statistic;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class VoidMainBridge extends JavaPlugin {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private PlayerPointsAPI pointsApi;
    private Connection localDb;
    private String baseUrl;
    private String secret;
    private boolean warned;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var rootCommand = getCommand("voidmain");
        if (rootCommand != null) {
            rootCommand.setExecutor(this);
            rootCommand.setTabCompleter((sender, command, alias, args) -> {
                if (args.length != 1) return java.util.List.of();
                String input = args[0].toLowerCase();
                return java.util.List.of("link", "balance", "help").stream().filter(s -> s.startsWith(input)).toList();
            });
        } else {
            getLogger().severe("Command 'voidmain' is missing from plugin.yml; /voidmain cannot be registered.");
        }
        baseUrl = getConfig().getString("website.url", "https://voidmain-website.onrender.com").replaceAll("/+$", "");
        secret = getConfig().getString("website.shared-secret", "");
        try {
            Class.forName("org.sqlite.JDBC");
            localDb = DriverManager.getConnection("jdbc:sqlite:" + Path.of(getDataFolder().getAbsolutePath(), "bridge.sqlite"));
            try (var st = localDb.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS claim_receipts (claim_id INTEGER PRIMARY KEY, state TEXT NOT NULL, updated_at TEXT DEFAULT CURRENT_TIMESTAMP)");
                st.executeUpdate("CREATE TABLE IF NOT EXISTS nickname_claim_receipts (claim_id INTEGER PRIMARY KEY, state TEXT NOT NULL, updated_at TEXT DEFAULT CURRENT_TIMESTAMP)");
            }
        } catch (Exception e) {
            getLogger().severe("Could not initialize local claim database: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        var dependency = getServer().getPluginManager().getPlugin("PlayerPoints");
        if (dependency instanceof PlayerPoints playerPoints && dependency.isEnabled()) {
            pointsApi = playerPoints.getAPI();
            getLogger().info("PlayerPoints API connected.");
        } else {
            getLogger().severe("PlayerPoints not found. Install PlayerPoints before using VoidCoin rewards.");
        }
        if (secret.isBlank() || secret.startsWith("CHANGE_") || secret.startsWith("SET_")) {
            getLogger().warning("Set website.shared-secret to the same private value as Render's VOIDMAIN_BRIDGE_SECRET.");
            warned = true;
        }
        long interval = Math.max(15, getConfig().getLong("website.poll-seconds", 30));
        getServer().getScheduler().runTaskTimer(this, this::sendHeartbeat, 40L, interval * 20L);
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::pollClaims, 80L, interval * 20L);
        getLogger().info("VoidMain-Bridge enabled. Website: " + baseUrl);
    }

    @Override
    public void onDisable() {
        try { if (localDb != null) localDb.close(); } catch (Exception ignored) {}
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(color("&8&m----------&r &5VoidMain &dBridge &8&m----------"));
            sender.sendMessage(color("&d/voidmain link &7— привязать Minecraft к сайту"));
            sender.sendMessage(color("&d/voidmain balance &7— посмотреть баланс VoidCoin"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Эта команда доступна только игроку.");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "link" -> createLinkCode(player);
            case "balance" -> {
                if (pointsApi == null) {
                    player.sendMessage(color("&cДля работы VoidCoin нужен установленный PlayerPoints."));
                    return true;
                }
                int balance = pointsApi.look(player.getUniqueId());
                player.sendMessage(color(getConfig().getString("messages.prefix", "&8[&5VoidMain&8] ")
                        + getConfig().getString("messages.balance", "&dТвой баланс VoidCoin: &f%balance%")
                        .replace("%balance%", Integer.toString(balance))));
            }
            default -> player.sendMessage(color("&cИспользование: /voidmain <link|balance|help>"));
        }
        return true;
    }

    private void createLinkCode(Player player) {
        if (!bridgeConfigured()) {
            player.sendMessage(color("&cСвязь с сайтом ещё не настроена. Сообщи администрации."));
            return;
        }
        String code = String.format("%06d", new java.security.SecureRandom().nextInt(1_000_000));
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject payload = new JsonObject();
                payload.addProperty("code", code);
                payload.addProperty("uuid", uuid.toString());
                payload.addProperty("username", name);
                post("/api/bridge/link-code", payload);
                getServer().getScheduler().runTask(this, () -> {
                    if (player.isOnline()) player.sendMessage(color(getConfig().getString("messages.prefix", "&8[&5VoidMain&8] ")
                            + getConfig().getString("messages.link-created", "&aКод привязки: &f%code%")
                            .replace("%code%", code)));
                });
            } catch (Exception e) {
                getLogger().warning("Could not create link code: " + e.getMessage());
                getServer().getScheduler().runTask(this, () -> {
                    if (player.isOnline()) player.sendMessage(color("&cНе удалось связаться с сайтом. Попробуй позже."));
                });
            }
        });
    }

    private void sendHeartbeat() {
        if (!bridgeConfigured()) return;
        JsonObject body = new JsonObject();
        body.addProperty("online", getServer().getOnlinePlayers().size());
        body.addProperty("maxPlayers", getServer().getMaxPlayers());
        body.addProperty("version", "Paper 1.21.11");
        JsonArray players = new JsonArray();
        for (Player p : getServer().getOnlinePlayers()) {
            JsonObject item = new JsonObject();
            item.addProperty("uuid", p.getUniqueId().toString());
            item.addProperty("name", p.getName());
            item.addProperty("playtimeTicks", p.getStatistic(Statistic.PLAY_ONE_MINUTE));
            item.addProperty("kills", p.getStatistic(Statistic.PLAYER_KILLS));
            item.addProperty("deaths", p.getStatistic(Statistic.DEATHS));
            item.addProperty("points", pointsApi == null ? 0 : pointsApi.look(p.getUniqueId()));
            players.add(item);
        }
        body.add("players", players);
        CompletableFuture.runAsync(() -> {
            try { post("/api/bridge/heartbeat", body); }
            catch (Exception e) { getLogger().fine("Heartbeat failed: " + e.getMessage()); }
        });
    }

    private void pollClaims() {
        if (!bridgeConfigured() || pointsApi == null) return;
        try {
            String response = request("GET", "/api/bridge/claims", null);
            JsonArray claims = JsonParser.parseString(response).getAsJsonObject().getAsJsonArray("claims");
            for (var element : claims) {
                JsonObject claim = element.getAsJsonObject();
                long id = claim.get("id").getAsLong();
                UUID uuid = UUID.fromString(claim.get("uuid").getAsString());
                int amount = claim.get("amount").getAsInt();
                getServer().getScheduler().runTask(this, () -> processClaim(id, uuid, amount));
            }
            String nicknameResponse = request("GET", "/api/bridge/nickname-claims", null);
            JsonArray nicknameClaims = JsonParser.parseString(nicknameResponse).getAsJsonObject().getAsJsonArray("claims");
            for (var element : nicknameClaims) {
                JsonObject claim = element.getAsJsonObject();
                long id = claim.get("id").getAsLong();
                String minecraftName = claim.get("minecraft_name").getAsString();
                int amount = claim.get("amount").getAsInt();
                getServer().getScheduler().runTask(this, () -> processNicknameClaim(id, minecraftName, amount));
            }
        } catch (Exception e) {
            getLogger().fine("Claim polling failed: " + e.getMessage());
        }
    }

    private void processClaim(long id, UUID uuid, int amount) {
        if (pointsApi == null || amount < 1) return;
        try {
            synchronized (localDb) {
                try (PreparedStatement check = localDb.prepareStatement("SELECT state FROM claim_receipts WHERE claim_id=?")) {
                    check.setLong(1, id);
                    try (var rs = check.executeQuery()) {
                        if (rs.next()) {
                            if ("paid".equals(rs.getString("state"))) completeClaim(id, uuid);
                            return;
                        }
                    }
                }
                try (PreparedStatement insert = localDb.prepareStatement("INSERT INTO claim_receipts(claim_id,state) VALUES(?,'processing')")) {
                    insert.setLong(1, id);
                    insert.executeUpdate();
                }
            }
            boolean paid = pointsApi.give(uuid, amount);
            if (!paid) {
                synchronized (localDb) {
                    try (PreparedStatement delete = localDb.prepareStatement("DELETE FROM claim_receipts WHERE claim_id=? AND state='processing'")) {
                        delete.setLong(1, id);
                        delete.executeUpdate();
                    }
                }
                return;
            }
            synchronized (localDb) {
                try (PreparedStatement update = localDb.prepareStatement("UPDATE claim_receipts SET state='paid',updated_at=CURRENT_TIMESTAMP WHERE claim_id=?")) {
                    update.setLong(1, id);
                    update.executeUpdate();
                }
            }
            completeClaim(id, uuid);
        } catch (Exception e) {
            getLogger().warning("Could not process VoidCoin claim #" + id + ": " + e.getMessage());
        }
    }

    private void processNicknameClaim(long id, String minecraftName, int amount) {
        if (pointsApi == null || amount < 1 || minecraftName == null || !minecraftName.matches("[A-Za-z0-9_]{3,16}")) return;
        Player player = getServer().getOnlinePlayers().stream().filter(p -> p.getName().equalsIgnoreCase(minecraftName)).findFirst().orElse(null);
        if (player == null || !player.isOnline()) return; // Keep the request pending until this player joins.
        UUID uuid = player.getUniqueId();
        try {
            synchronized (localDb) {
                try (PreparedStatement check = localDb.prepareStatement("SELECT state FROM nickname_claim_receipts WHERE claim_id=?")) {
                    check.setLong(1, id);
                    try (var rs = check.executeQuery()) {
                        if (rs.next()) {
                            if ("paid".equals(rs.getString("state"))) completeNicknameClaim(id, uuid);
                            return;
                        }
                    }
                }
                try (PreparedStatement insert = localDb.prepareStatement("INSERT INTO nickname_claim_receipts(claim_id,state) VALUES(?,'processing')")) {
                    insert.setLong(1, id);
                    insert.executeUpdate();
                }
            }
            boolean paid = pointsApi.give(uuid, amount);
            if (!paid) {
                synchronized (localDb) {
                    try (PreparedStatement delete = localDb.prepareStatement("DELETE FROM nickname_claim_receipts WHERE claim_id=? AND state='processing'")) {
                        delete.setLong(1, id);
                        delete.executeUpdate();
                    }
                }
                return;
            }
            synchronized (localDb) {
                try (PreparedStatement update = localDb.prepareStatement("UPDATE nickname_claim_receipts SET state='paid',updated_at=CURRENT_TIMESTAMP WHERE claim_id=?")) {
                    update.setLong(1, id);
                    update.executeUpdate();
                }
            }
            player.sendMessage(color(getConfig().getString("messages.prefix", "&8[&5VoidMain&8] ")
                    + "&aТебе начислено &d" + amount + " VoidCoin&a за награду сайта!"));
            completeNicknameClaim(id, uuid);
        } catch (Exception e) {
            getLogger().warning("Could not process nickname VoidCoin claim #" + id + ": " + e.getMessage());
        }
    }

    private void completeNicknameClaim(long id, UUID uuid) {
        int balance = pointsApi.look(uuid);
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("balance", balance);
                post("/api/bridge/nickname-claims/" + id + "/complete", body);
            } catch (Exception e) {
                getLogger().warning("Nickname claim #" + id + " was paid locally but website confirmation failed; it will be retried: " + e.getMessage());
            }
        });
    }

    private void completeClaim(long id, UUID uuid) {
        int balance = pointsApi.look(uuid);
        CompletableFuture.runAsync(() -> {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("balance", balance);
                post("/api/bridge/claims/" + id + "/complete", body);
            } catch (Exception e) {
                getLogger().warning("Claim #" + id + " was paid locally but website confirmation failed; it will be retried: " + e.getMessage());
            }
        });
    }

    private void post(String path, JsonObject body) throws Exception { request("POST", path, body); }

    private String request(String method, String path, JsonObject body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(45)).header("X-Bridge-Secret", secret);
        if ("POST".equals(method)) builder.header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body.toString()));
        else builder.GET();
        HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("Website returned HTTP " + response.statusCode() + ": " + response.body());
        return response.body();
    }

    private boolean bridgeConfigured() {
        return !warned && secret != null && !secret.isBlank() && !secret.startsWith("CHANGE_") && !secret.startsWith("SET_");
    }

    private String color(String input) { return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input); }
}

package ru.voidmain.bridge;

import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class VoidMainBridge extends JavaPlugin {
    private final SecureRandom random = new SecureRandom();
    private final Map<String, UUID> pendingLinks = new HashMap<>();
    private PlayerPointsAPI pointsApi;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        var dependency = getServer().getPluginManager().getPlugin("PlayerPoints");
        if (dependency instanceof PlayerPoints playerPoints && dependency.isEnabled()) {
            pointsApi = playerPoints.getAPI();
            getLogger().info("PlayerPoints API connected.");
        } else {
            getLogger().severe("PlayerPoints not found. Install PlayerPoints before using VoidMain-Bridge.");
        }
        getLogger().info("VoidMain-Bridge enabled. Website: " + getConfig().getString("website.url"));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(color("&8&m----------&r &5VoidMain &dBridge &8&m----------"));
            sender.sendMessage(color("&d/voidmain link &7— получить код привязки сайта"));
            sender.sendMessage(color("&d/voidmain balance &7— посмотреть баланс VoidCoin"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Эта команда доступна только игроку.");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "link" -> {
                if (pendingLinks.containsValue(player.getUniqueId())) {
                    sender.sendMessage(color("&cУ тебя уже есть активный код. Обратись к администратору, если он потерян."));
                    return true;
                }
                String code;
                do {
                    code = String.format("%06d", random.nextInt(1_000_000));
                } while (pendingLinks.containsKey(code));
                pendingLinks.put(code, player.getUniqueId());
                sender.sendMessage(color(getConfig().getString("messages.prefix", "&8[&5VoidMain&8] ")
                        + getConfig().getString("messages.link-created", "&aКод привязки: &f%code%")
                        .replace("%code%", code)));
                getLogger().info("Link code created for " + player.getName() + " (" + player.getUniqueId() + ").");
            }
            case "balance" -> {
                if (pointsApi == null) {
                    player.sendMessage(color(getConfig().getString("messages.playerpoints-missing")));
                    return true;
                }
                int balance = pointsApi.look(player.getUniqueId());
                player.sendMessage(color(getConfig().getString("messages.prefix") +
                        getConfig().getString("messages.balance").replace("%balance%", Integer.toString(balance))));
            }
            default -> player.sendMessage(color("&cИспользование: /voidmain <link|balance|help>"));
        }
        return true;
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }
}

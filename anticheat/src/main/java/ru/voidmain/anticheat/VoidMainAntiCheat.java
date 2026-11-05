package ru.voidmain.anticheat;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VoidMainAntiCheat extends JavaPlugin implements Listener, CommandExecutor {
    private final Map<UUID, PunishmentSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, PermissionAttachment> grimAttachments = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, ConcurrentLinkedDeque<Long>>> grimFlagWindows = new ConcurrentHashMap<>();
    private volatile boolean grimHooked;
    private volatile boolean vulcanHooked;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        PluginManager manager = Bukkit.getPluginManager();
        Plugin grim = manager.getPlugin("GrimAC");
        Plugin vulcan = manager.getPlugin("Vulcan");
        Plugin packetEvents = manager.getPlugin("packetevents");

        if (grim == null || !grim.isEnabled() || vulcan == null || !vulcan.isEnabled()
                || packetEvents == null || !packetEvents.isEnabled()) {
            getLogger().severe("Не найдены зависимости GrimAC, Vulcan или PacketEvents. Плагин отключён.");
            manager.disablePlugin(this);
            return;
        }

        manager.registerEvents(this, this);
        if (getCommand("voidac") != null) {
            getCommand("voidac").setExecutor(this);
        }

        registerVulcanEvents(vulcan);
        registerGrimEvents(grim);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                refreshGrimExemption(player);
            }
        }, 10L, 40L);

        getLogger().info("§5§lVOIDMAIN §dAntiCheat §fзапущен. GrimAC="
                + (grimHooked ? "OK" : "ERROR") + ", Vulcan=" + (vulcanHooked ? "OK" : "ERROR"));
        if (!grimHooked || !vulcanHooked) {
            getLogger().severe("Не удалось подключить API одного из античитов. Проверь версии и первые ошибки выше.");
        }
    }

    @Override
    public void onDisable() {
        for (PunishmentSession session : sessions.values()) {
            if (session.task != null) {
                session.task.cancel();
            }
            Player player = session.player;
            if (player != null && player.isOnline() && !session.finished.get()) {
                player.setInvulnerable(session.wasInvulnerable);
                player.removePotionEffect(PotionEffectType.GLOWING);
            }
        }
        sessions.clear();
        for (Map.Entry<UUID, PermissionAttachment> entry : grimAttachments.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                try {
                    player.removeAttachment(entry.getValue());
                } catch (IllegalArgumentException ignored) {
                }
            }
        }
        grimAttachments.clear();
    }

    private void registerVulcanEvents(Plugin vulcan) {
        try {
            registerReflectiveBukkitEvent(vulcan, "me.frep.vulcan.api.event.VulcanFlagEvent",
                    this::handleVulcanFlag);
            registerReflectiveBukkitEvent(vulcan, "me.frep.vulcan.api.event.VulcanPunishEvent",
                    this::handleVulcanPunish);
            vulcanHooked = true;
        } catch (Throwable error) {
            getLogger().severe("Vulcan API hook failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void registerReflectiveBukkitEvent(Plugin owner, String eventClassName,
                                                java.util.function.Consumer<Event> handler) throws Exception {
        Class<?> loaded = Class.forName(eventClassName, true, owner.getClass().getClassLoader());
        Class<? extends Event> eventClass = (Class<? extends Event>) loaded.asSubclass(Event.class);
        EventExecutor executor = (listener, event) -> handler.accept(event);
        Bukkit.getPluginManager().registerEvent(eventClass, this, EventPriority.HIGHEST, executor, this, false);
    }

    private void handleVulcanFlag(Event event) {
        Player player = (Player) invoke(event, "getPlayer");
        if (player != null && isExempt(player)) {
            invoke(event, "setCancelled", true);
        }
    }

    private void handleVulcanPunish(Event event) {
        Player player = (Player) invoke(event, "getPlayer");
        Object check = invoke(event, "getCheck");
        if (player == null || check == null) {
            return;
        }
        if (isExempt(player)) {
            invoke(event, "setCancelled", true);
            return;
        }

        String name = stringValue(invoke(check, "getName"), "Unknown");
        String category = stringValue(invoke(check, "getCategory"), "");
        if (!getConfig().getBoolean("vulcan.enabled", true)
                || !getConfig().getBoolean("vulcan.intercept-combat-punishments", true)
                || !isCombatCheck(category, name)) {
            return;
        }

        // Cancel Vulcan's configured kick/ban command. The bridge runs one staged punishment instead.
        invoke(event, "setCancelled", true);
        startPunishment(player, "Vulcan", name);
    }

    private boolean isCombatCheck(String category, String name) {
        String c = category.toLowerCase(Locale.ROOT);
        String n = name.toLowerCase(Locale.ROOT);
        return c.contains("combat") || n.contains("killaura") || n.contains("autoclicker")
                || n.contains("aim") || n.contains("reach") || n.contains("hitbox")
                || n.contains("velocity") || n.contains("critical") || n.contains("autoblock")
                || n.contains("triggerbot");
    }

    private void registerGrimEvents(Plugin grim) {
        if (!getConfig().getBoolean("grim.enabled", true)) {
            grimHooked = true;
            return;
        }
        try {
            ClassLoader loader = grim.getClass().getClassLoader();
            Class<?> provider = Class.forName("ac.grim.grimac.api.GrimAPIProvider", true, loader);
            Object api = provider.getMethod("get").invoke(null);
            if (api == null) {
                throw new IllegalStateException("GrimAPIProvider.get() returned null");
            }
            Object context = api.getClass().getMethod("getGrimPlugin", Object.class).invoke(api, this);
            Object bus = api.getClass().getMethod("getEventBus").invoke(api);
            Class<?> flagClass = Class.forName("ac.grim.grimac.api.event.events.FlagEvent", true, loader);
            Class<?> listenerType = Class.forName("ac.grim.grimac.api.event.GrimEventListener", true, loader);

            Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(),
                    new Class<?>[]{listenerType}, new GrimListenerHandler());
            Method subscribe = null;
            for (Method method : bus.getClass().getMethods()) {
                if (method.getName().equals("subscribe") && method.getParameterCount() == 3
                        && method.getParameterTypes()[0].isInstance(context)
                        && method.getParameterTypes()[1] == Class.class
                        && method.getParameterTypes()[2].isInstance(listener)) {
                    subscribe = method;
                    break;
                }
            }
            if (subscribe == null) {
                throw new NoSuchMethodException("Grim EventBus.subscribe(context, FlagEvent, listener)");
            }
            subscribe.invoke(bus, context, flagClass, listener);
            grimHooked = true;
        } catch (Throwable error) {
            getLogger().severe("GrimAC API hook failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
    }

    private final class GrimListenerHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            if (method.getName().equals("handle") && args != null && args.length == 1) {
                handleGrimFlag(args[0]);
                return null;
            }
            if (method.getName().equals("toString")) return "VoidMainGrimFlagListener";
            if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
            if (method.getName().equals("equals")) return proxy == (args == null ? null : args[0]);
            return null;
        }
    }

    private void handleGrimFlag(Object event) {
        try {
            Object user = invoke(event, "getUser");
            Object check = invoke(event, "getCheck");
            if (user == null || check == null) return;
            UUID uuid = (UUID) invoke(user, "getUniqueId");
            if (uuid == null) return;
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) return;

            if (isExempt(player)) {
                invoke(event, "setCancelled", true);
                refreshGrimExemption(player);
                return;
            }

            String checkName = stringValue(invoke(check, "getCheckName"), "");
            ConfigurationSection thresholds = getConfig().getConfigurationSection("grim.flag-thresholds");
            if (thresholds == null || !thresholds.contains(checkName)) return;

            int required = Math.max(2, thresholds.getInt(checkName, 4));
            long now = System.currentTimeMillis();
            long window = Math.max(5, getConfig().getLong("grim.window-seconds", 15)) * 1000L;
            Map<String, ConcurrentLinkedDeque<Long>> playerFlags =
                    grimFlagWindows.computeIfAbsent(uuid, ignored -> new ConcurrentHashMap<>());
            ConcurrentLinkedDeque<Long> flags =
                    playerFlags.computeIfAbsent(checkName, ignored -> new ConcurrentLinkedDeque<>());
            flags.addLast(now);
            while (true) {
                Long first = flags.peekFirst();
                if (first == null || now - first <= window) break;
                flags.pollFirst();
            }

            if (flags.size() >= required) {
                flags.clear();
                invoke(event, "setCancelled", true);
                startPunishment(player, "GrimAC", checkName);
            }
        } catch (Throwable error) {
            getLogger().warning("Ошибка обработки события GrimAC: " + error.getMessage());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> refreshGrimExemption(event.getPlayer()), 2L);
    }

    private void refreshGrimExemption(Player player) {
        boolean exempt = isExempt(player);
        PermissionAttachment attachment = grimAttachments.get(player.getUniqueId());
        if (exempt && attachment == null) {
            attachment = player.addAttachment(this);
            attachment.setPermission("grim.exempt", true);
            grimAttachments.put(player.getUniqueId(), attachment);
        } else if (!exempt && attachment != null) {
            grimAttachments.remove(player.getUniqueId());
            try {
                player.removeAttachment(attachment);
            } catch (IllegalArgumentException ignored) {
            }
        }
        try {
            Plugin grim = Bukkit.getPluginManager().getPlugin("GrimAC");
            if (grim != null) {
                Class<?> provider = Class.forName("ac.grim.grimac.api.GrimAPIProvider", true,
                        grim.getClass().getClassLoader());
                Object api = provider.getMethod("get").invoke(null);
                Object user = api.getClass().getMethod("getGrimUser", Player.class).invoke(api, player);
                if (user != null) {
                    user.getClass().getMethod("updatePermissions").invoke(user);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private boolean isExempt(Player player) {
        return (getConfig().getBoolean("exempt.operators", true) && player.isOp())
                || player.hasPermission(getConfig().getString("exempt.permission",
                "voidmain.anticheat.bypass"));
    }

    private void startPunishment(Player player, String source, String check) {
        if (!getConfig().getBoolean("punishment.enabled", true) || isExempt(player)) return;
        UUID uuid = player.getUniqueId();
        PunishmentSession session = new PunishmentSession(player, source, check);
        if (sessions.putIfAbsent(uuid, session) != null) return;

        player.closeInventory();
        player.setInvulnerable(true);
        int ticks = Math.max(10, getConfig().getInt("punishment.animation-ticks", 30));
        int repeats = Math.max(5, (int) Math.ceil(ticks / 2.0));
        player.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, repeats * 2 + 10,
                0, false, false, false));
        player.sendTitle(gradient(getConfig().getString("messages.title", "VOIDMAIN")),
                gradient(getConfig().getString("messages.subtitle", "НАРУШЕНИЕ ОБНАРУЖЕНО")),
                0, repeats * 2, 5);
        player.playSound(player.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 0.65f);
        session.task = Bukkit.getScheduler().runTaskTimer(this, new Runnable() {
            private int step;

            @Override
            public void run() {
                if (session.finished.get()) {
                    if (session.task != null) session.task.cancel();
                    return;
                }
                Player current = session.player;
                if (current == null || !current.isOnline()) {
                    finishPunishment(session, true);
                    if (session.task != null) session.task.cancel();
                    return;
                }
                animate(current, step++);
                if (step >= repeats) {
                    if (session.task != null) session.task.cancel();
                    finishPunishment(session, false);
                }
            }
        }, 0L, 2L);
    }

    private void animate(Player player, int step) {
        Location center = player.getLocation();
        World world = center.getWorld();
        if (world == null) return;
        double progress = Math.min(1.0, step / 15.0);
        double radius = 0.55 + progress * 0.8;
        double y = center.getY() + 0.15 + progress * 1.6;
        Color purple = Color.fromRGB(138, 43, 226);
        Color lavender = Color.fromRGB(201, 160, 255);
        Color white = Color.fromRGB(255, 255, 255);
        for (int i = 0; i < 18; i++) {
            double angle = (Math.PI * 2.0 * i / 18.0) + step * 0.22;
            double x = center.getX() + Math.cos(angle) * radius;
            double z = center.getZ() + Math.sin(angle) * radius;
            Color shade = (i % 3 == 0) ? white : ((i % 2 == 0) ? lavender : purple);
            world.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0,
                    new Particle.DustOptions(shade, 1.2f));
        }
        world.spawnParticle(Particle.END_ROD, center.clone().add(0, 0.8 + progress, 0),
                2, 0.12, 0.25, 0.12, 0.01);
        if (step % 4 == 0) {
            player.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.8f, 0.7f + (step * 0.02f));
        }
    }

    private void finishPunishment(PunishmentSession session, boolean disconnected) {
        if (!session.finished.compareAndSet(false, true)) return;
        Player player = session.player;
        Location dropLocation = session.lastLocation.clone();
        boolean dropInventory = getConfig().getBoolean("punishment.drop-inventory", true);
        if (player != null) {
            if (player.isOnline()) {
                dropLocation = player.getLocation().clone();
            }
            // Clear even during PlayerQuitEvent to prevent the saved inventory from reappearing on rejoin.
            if (dropInventory) {
                player.getInventory().clear();
            }
        }

        if (dropInventory) {
            World world = dropLocation.getWorld();
            if (world != null) {
                for (ItemStack item : session.items) {
                    if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                        world.dropItemNaturally(dropLocation, item.clone());
                    }
                }
            }
        }

        String reason = getConfig().getString("punishment.reason-prefix", "VoidMain AntiCheat")
                + " | " + session.source + " | " + session.check;
        int days = Math.max(0, getConfig().getInt("punishment.ban-days", 0));
        Date expiry = days == 0 ? null : new Date(System.currentTimeMillis() + days * 86_400_000L);
        Bukkit.getBanList(BanList.Type.NAME).addBan(session.playerName, reason, expiry, getName());

        if (player != null && player.isOnline() && !disconnected) {
            player.setInvulnerable(session.wasInvulnerable);
            player.removePotionEffect(PotionEffectType.GLOWING);
            player.setHealth(0.0);
            final String kick = getConfig().getString("messages.kick",
                    "&5&lVOIDMAIN &8» &fВы заблокированы античитом VoidMain.");
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (player.isOnline()) {
                    player.kickPlayer(kick.replace('&', '§'));
                }
            }, 1L);
        }
        broadcastPunishment(session.playerName, session.check);
        getLogger().warning("Punished " + session.playerName + " for " + reason);
    }

    private void broadcastPunishment(String player, String check) {
        if (!getConfig().getBoolean("punishment.broadcast", true)) return;
        String message = getConfig().getString("messages.broadcast",
                "&5&lVOIDMAIN &8» &fИгрок &d%player% &fзаблокирован за &d%check%&f.");
        message = message.replace("%player%", player).replace("%check%", check);
        Bukkit.broadcastMessage(gradient(message));
    }

    private String gradient(String text) {
        if (text == null || text.isEmpty()) return "";
        // Strip legacy formatting codes before painting the VoidMain hex gradient.
        String value = text.replaceAll("(?i)[&§][0-9A-FK-OR]", "");
        List<Integer> points = value.codePoints().boxed().toList();
        StringBuilder out = new StringBuilder();
        int purple = parseColor(getConfig().getString("punishment.purple-rgb", "8A2BE2"), 0x8A2BE2);
        int lavender = parseColor(getConfig().getString("punishment.lavender-rgb", "C9A0FF"), 0xC9A0FF);
        int white = parseColor(getConfig().getString("punishment.white-rgb", "FFFFFF"), 0xFFFFFF);
        for (int i = 0; i < points.size(); i++) {
            int cp = points.get(i);
            double t = points.size() <= 1 ? 0 : (double) i / (points.size() - 1);
            int color = t < 0.5 ? mix(purple, lavender, t * 2.0) : mix(lavender, white, (t - 0.5) * 2.0);
            out.append(hex(color)).appendCodePoint(cp);
        }
        return out.toString();
    }

    private int parseColor(String value, int fallback) {
        if (value == null) return fallback;
        try {
            return Integer.parseInt(value.replace("#", ""), 16) & 0xFFFFFF;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private int mix(int a, int b, double t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private String hex(int rgb) {
        String h = String.format("%06X", rgb);
        return "§x§" + h.charAt(0) + "§" + h.charAt(1) + "§" + h.charAt(2)
                + "§" + h.charAt(3) + "§" + h.charAt(4) + "§" + h.charAt(5);
    }

    private void dropAndBanOnQuit(PunishmentSession session) {
        if (session.finished.get()) return;
        finishPunishment(session, true);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        PunishmentSession session = sessions.get(uuid);
        if (session != null) {
            dropAndBanOnQuit(session);
            sessions.remove(uuid, session);
        }
        grimFlagWindows.remove(uuid);
        PermissionAttachment attachment = grimAttachments.remove(uuid);
        if (attachment != null) {
            try {
                event.getPlayer().removeAttachment(attachment);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!sessions.containsKey(event.getPlayer().getUniqueId()) || event.getTo() == null) return;
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location locked = from.clone();
            locked.setYaw(to.getYaw());
            locked.setPitch(to.getPitch());
            event.setTo(locked);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && sessions.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (sessions.containsKey(event.getEntity().getUniqueId())) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(gradient("&5&lVOIDMAIN &8» &fGrimAC: "
                    + (grimHooked ? "&aOK" : "&cERROR") + " &8| &fVulcan: "
                    + (vulcanHooked ? "&aOK" : "&cERROR")));
            sender.sendMessage(gradient("&7OP bypass: &f" + getConfig().getBoolean("exempt.operators", true)
                    + " &8| &7Grim thresholds: &f" + getConfig().getConfigurationSection("grim.flag-thresholds").getKeys(false).size()));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("voidmain.anticheat.admin")) {
                sender.sendMessage("§cНет прав.");
                return true;
            }
            reloadConfig();
            sender.sendMessage(gradient("&5&lVOIDMAIN &8» &fКонфигурация перезагружена."));
            return true;
        }
        sender.sendMessage("§dИспользование: /voidac <status|reload>");
        return true;
    }

    private Object invoke(Object target, String method, Object... args) {
        if (target == null) return null;
        try {
            for (Method candidate : target.getClass().getMethods()) {
                if (!candidate.getName().equals(method) || candidate.getParameterCount() != args.length) continue;
                Class<?>[] types = candidate.getParameterTypes();
                boolean compatible = true;
                for (int i = 0; i < types.length; i++) {
                    if (args[i] != null && !wrap(types[i]).isInstance(args[i])) {
                        compatible = false;
                        break;
                    }
                }
                if (compatible) return candidate.invoke(target, args);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Class<?> wrap(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        return type;
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : value.toString();
    }

    private final class PunishmentSession {
        private final Player player;
        private final UUID uuid;
        private final String playerName;
        private final String source;
        private final String check;
        private final List<ItemStack> items = new ArrayList<>();
        private final Location lastLocation;
        private final boolean wasInvulnerable;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private BukkitTask task;

        private PunishmentSession(Player player, String source, String check) {
            this.player = player;
            this.uuid = player.getUniqueId();
            this.playerName = player.getName();
            this.source = source;
            this.check = check;
            this.lastLocation = player.getLocation().clone();
            this.wasInvulnerable = player.isInvulnerable();
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                    this.items.add(item.clone());
                }
            }
        }
    }
}
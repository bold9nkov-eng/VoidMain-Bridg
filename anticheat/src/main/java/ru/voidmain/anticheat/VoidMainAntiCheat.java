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
import org.bukkit.configuration.file.YamlConfiguration;
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

import java.io.File;
import java.io.IOException;
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
    private final Map<UUID, ManualCheckSession> manualChecks = new ConcurrentHashMap<>();
    private File violationsFile;
    private YamlConfiguration violationsData;
    private final Map<UUID, PermissionAttachment> grimAttachments = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, ConcurrentLinkedDeque<Long>>> grimFlagWindows = new ConcurrentHashMap<>();
    private volatile boolean grimHooked;
    private volatile boolean vulcanHooked;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadViolations();
        PluginManager manager = Bukkit.getPluginManager();
        Plugin grim = findPlugin("GrimAC");
        Plugin vulcan = findPlugin("Vulcan");
        Plugin packetEvents = findPlugin("packetevents", "PacketEvents");

        if ((grim == null || !grim.isEnabled()) && (vulcan == null || !vulcan.isEnabled())) {
            getLogger().severe("Не найден ни GrimAC, ни Vulcan. VoidMain AntiCheat не может работать без хотя бы одного движка проверки.");
            manager.disablePlugin(this);
            return;
        }

        manager.registerEvents(this, this);
        if (getCommand("voidac") != null) {
            getCommand("voidac").setExecutor(this);
        }

        if (vulcan != null && vulcan.isEnabled()) {
            if (packetEvents == null || !packetEvents.isEnabled()) {
                getLogger().severe("Vulcan найден, но PacketEvents не обнаружен. Проверки Vulcan недоступны.");
            }
            registerVulcanEvents(vulcan);
        } else {
            getLogger().warning("Vulcan не найден — подключение к Vulcan пропущено; GrimAC продолжит работать.");
        }
        if (grim != null && grim.isEnabled()) {
            registerGrimEvents(grim);
        } else {
            getLogger().warning("GrimAC не найден — подключение к GrimAC пропущено; Vulcan продолжит работать.");
        }

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

    private Plugin findPlugin(String... names) {
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            for (String name : names) {
                if (plugin.getName().equalsIgnoreCase(name)) {
                    return plugin;
                }
            }
        }
        return null;
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
        for (ManualCheckSession check : manualChecks.values()) {
            if (check.timeoutTask != null) check.timeoutTask.cancel();
            if (check.player != null && check.player.isOnline()) {
                check.player.setInvulnerable(check.wasInvulnerable);
                check.player.removePotionEffect(PotionEffectType.GLOWING);
                if (check.previousGlowing != null) check.player.addPotionEffect(check.previousGlowing);
            }
        }
        manualChecks.clear();
        saveViolations();
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
        if (player != null && isExempt(player)
                && !manualChecks.containsKey(player.getUniqueId())) {
            invoke(event, "setCancelled", true);
        }
    }

    private void handleVulcanPunish(Event event) {
        Player player = (Player) invoke(event, "getPlayer");
        Object check = invoke(event, "getCheck");
        if (player == null || check == null) return;

        String name = stringValue(invoke(check, "getName"), "Unknown");
        String category = stringValue(invoke(check, "getCategory"), "");
        if (!getConfig().getBoolean("vulcan.enabled", true)
                || !getConfig().getBoolean("vulcan.intercept-combat-punishments", true)
                || !isCombatCheck(category, name)) {
            return;
        }

        // Exempt accounts remain immune to automatic punishment. During a manually opened check,
        // however, their Vulcan alerts are visible to staff for inspection.
        if (isExempt(player)) {
            invoke(event, "setCancelled", true);
            if (manualChecks.containsKey(player.getUniqueId())) {
                notifyStaff(gradient("&d" + player.getName() + " &fполучил PunishEvent Vulcan по проверке &d" + name
                        + "&f, но автоматическое наказание отменено (ручная проверка)."));
            }
            return;
        }

        // Replace Vulcan's own action with persistent staged punishment.
        invoke(event, "setCancelled", true);
        int count = incrementViolation(player.getUniqueId());
        applyVulcanPunishmentStage(player, name, count);
    }

    private void applyVulcanPunishmentStage(Player player, String check, int count) {
        switch (count) {
            case 1 -> {
                getLogger().info("Vulcan stage 1: ignored " + player.getName() + " (" + check + ")");
                notifyStaff(gradient("&7Vulcan: первое срабатывание &d" + player.getName()
                        + " &8(&f" + check + "&8) &7— проигнорировано."));
            }
            case 2 -> {
                player.sendMessage(gradient("&5&lVOIDMAIN &8» &fПредупреждение: повторное нарушение &d"
                        + check + "&f. Следующее подтверждённое нарушение приведёт к кику."));
                notifyStaff(gradient("&eПредупреждение &d" + player.getName()
                        + " &8(&f" + check + "&8) &7— 2/5."));
            }
            case 3 -> {
                notifyStaff(gradient("&cКик &d" + player.getName() + " &8(&f" + check + "&8) &7— 3/5."));
                player.kickPlayer(gradient("&5&lVOIDMAIN\n&fВы отключены за повторное нарушение: &d" + check));
            }
            case 4 -> {
                notifyStaff(gradient("&cБан на 2 дня &d" + player.getName()
                        + " &8(&f" + check + "&8) &7— 4/5."));
                startPunishment(player, "Vulcan", check + " (4/5)", 2);
            }
            default -> {
                notifyStaff(gradient("&4Постоянный бан &d" + player.getName()
                        + " &8(&f" + check + "&8) &7— " + count + "/5."));
                startPunishment(player, "Vulcan", check + " (5/5)", 0);
            }
        }
    }

    private void notifyStaff(String message) {
        Bukkit.getConsoleSender().sendMessage(message);
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("voidmain.anticheat.admin")) {
                online.sendMessage(message);
            }
        }
    }

    private void loadViolations() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Не удалось создать папку данных для счётчиков нарушений.");
        }
        violationsFile = new File(getDataFolder(), "violations.yml");
        violationsData = YamlConfiguration.loadConfiguration(violationsFile);
    }

    private synchronized int incrementViolation(UUID uuid) {
        int count = violationsData.getInt(uuid.toString(), 0) + 1;
        violationsData.set(uuid.toString(), count);
        saveViolations();
        return count;
    }

    private synchronized int getViolationCount(UUID uuid) {
        return violationsData == null ? 0 : violationsData.getInt(uuid.toString(), 0);
    }

    private synchronized void resetViolationCount(UUID uuid) {
        if (violationsData == null) loadViolations();
        violationsData.set(uuid.toString(), 0);
        saveViolations();
    }

    private synchronized void saveViolations() {
        if (violationsData == null || violationsFile == null) return;
        try {
            violationsData.save(violationsFile);
        } catch (IOException error) {
            getLogger().severe("Не удалось сохранить violations.yml: " + error.getMessage());
        }
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

            boolean manualCheck = manualChecks.containsKey(player.getUniqueId());
            if (isExempt(player) && !manualCheck) {
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
                if (manualCheck) {
                    notifyStaff(gradient("&d" + player.getName() + " &fполучил повторные флаги GrimAC: &d"
                            + checkName + "&f. Автоматическое наказание не применяется во время ручной проверки."));
                } else {
                    invoke(event, "setCancelled", true);
                    startPunishment(player, "GrimAC", checkName);
                }
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
        startPunishment(player, source, check, getConfig().getInt("punishment.ban-days", 0));
    }

    private void startPunishment(Player player, String source, String check, int banDays) {
        if (!getConfig().getBoolean("punishment.enabled", true) || isExempt(player)) return;
        UUID uuid = player.getUniqueId();
        PunishmentSession session = new PunishmentSession(player, source, check, banDays);
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
        int days = Math.max(0, session.banDays);
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
        ManualCheckSession manual = manualChecks.remove(uuid);
        if (manual != null) {
            if (manual.timeoutTask != null) manual.timeoutTask.cancel();
            if (manual.player.isOnline()) {
                manual.player.setInvulnerable(manual.wasInvulnerable);
                manual.player.removePotionEffect(PotionEffectType.GLOWING);
                if (manual.previousGlowing != null) manual.player.addPotionEffect(manual.previousGlowing);
            }
            notifyStaff(gradient("&e" + event.getPlayer().getName() + " вышел во время ручной проверки."));
        }
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
        if (!isLocked(event.getPlayer()) || event.getTo() == null) return;
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
        if (event.getEntity() instanceof Player player && isLocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (isLocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isLocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isLocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        if (isLocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && isLocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && isLocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && isLocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (isLocked(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (sessions.containsKey(event.getEntity().getUniqueId())) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    private boolean isLocked(Player player) {
        UUID uuid = player.getUniqueId();
        return sessions.containsKey(uuid) || manualChecks.containsKey(uuid);
    }

    private void beginManualCheck(Player target, CommandSender caller) {
        UUID uuid = target.getUniqueId();
        if (sessions.containsKey(uuid)) {
            caller.sendMessage(gradient("&cИгрок уже проходит процедуру наказания."));
            return;
        }
        ManualCheckSession check = new ManualCheckSession(target, caller.getName());
        if (manualChecks.putIfAbsent(uuid, check) != null) {
            caller.sendMessage(gradient("&cИгрок уже вызван на проверку."));
            return;
        }

        int timeoutSeconds = Math.max(30, getConfig().getInt("manual-check.timeout-seconds", 300));
        target.closeInventory();
        target.setInvulnerable(true);
        target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,
                timeoutSeconds * 20, 0, false, false, false));
        target.sendTitle(gradient("&5&lVOIDMAIN"), gradient("&fВЫЗОВ НА ПРОВЕРКУ"),
                5, 80, 10);
        target.sendMessage(gradient("&5&lVOIDMAIN &8» &fАдминистратор вызвал вас на проверку. Не выходите с сервера."));
        target.sendMessage(gradient("&7Проверку проводит: &d" + caller.getName()));
        caller.sendMessage(gradient("&aИгрок &d" + target.getName() + " &aвызван на проверку."));
        notifyStaff(gradient("&5&lVOIDMAIN &8» &d" + target.getName()
                + " &fвызван на ручную проверку администратором &d" + caller.getName() + "&f."));

        check.timeoutTask = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (manualChecks.containsKey(uuid)) {
                releaseManualCheck(uuid, "Время проверки истекло", true);
            }
        }, timeoutSeconds * 20L);
    }

    private void releaseManualCheck(UUID uuid, String reason, boolean notifyTarget) {
        ManualCheckSession check = manualChecks.remove(uuid);
        if (check == null) return;
        if (check.timeoutTask != null) check.timeoutTask.cancel();
        Player target = check.player;
        if (target != null && target.isOnline()) {
            target.setInvulnerable(check.wasInvulnerable);
            target.removePotionEffect(PotionEffectType.GLOWING);
            if (check.previousGlowing != null) target.addPotionEffect(check.previousGlowing);
            if (notifyTarget) {
                target.sendMessage(gradient("&5&lVOIDMAIN &8» &fПроверка завершена: &d" + reason + "&f."));
            }
        }
        notifyStaff(gradient("&aПроверка игрока &d" + check.playerName + " &aзавершена. &7" + reason));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            ConfigurationSection thresholds = getConfig().getConfigurationSection("grim.flag-thresholds");
            int checkCount = thresholds == null ? 0 : thresholds.getKeys(false).size();
            sender.sendMessage(gradient("&5&lVOIDMAIN &8» &fGrimAC: "
                    + (grimHooked ? "&aOK" : "&cNOT CONNECTED") + " &8| &fVulcan: "
                    + (vulcanHooked ? "&aOK" : "&cNOT CONNECTED")));
            sender.sendMessage(gradient("&7OP bypass: &f" + getConfig().getBoolean("exempt.operators", true)
                    + " &8| &7Grim checks: &f" + checkCount));
            sender.sendMessage(gradient("&7Ручных проверок: &f" + manualChecks.size()));
            return true;
        }

        if (!sender.hasPermission("voidmain.anticheat.admin")) {
            sender.sendMessage(gradient("&cНедостаточно прав."));
            return true;
        }

        if (args[0].equalsIgnoreCase("check")) {
            if (args.length < 2) {
                sender.sendMessage(gradient("&cИспользование: /voidac check <ник>"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(gradient("&cИгрок должен быть онлайн."));
                return true;
            }
            beginManualCheck(target, sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("release")) {
            if (args.length < 2) {
                sender.sendMessage(gradient("&cИспользование: /voidac release <ник>"));
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            UUID uuid = target != null ? target.getUniqueId() : Bukkit.getOfflinePlayer(args[1]).getUniqueId();
            if (!manualChecks.containsKey(uuid)) {
                sender.sendMessage(gradient("&cИгрок не находится на проверке."));
                return true;
            }
            releaseManualCheck(uuid, "Отпущен администратором " + sender.getName(), true);
            sender.sendMessage(gradient("&aИгрок освобождён."));
            return true;
        }

        if (args[0].equalsIgnoreCase("violations")) {
            if (args.length < 2) {
                sender.sendMessage(gradient("&cИспользование: /voidac violations <ник>"));
                return true;
            }
            UUID uuid = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
            sender.sendMessage(gradient("&5&lVOIDMAIN &8» &fНарушений Vulcan у &d"
                    + args[1] + "&f: &d" + getViolationCount(uuid) + "/5"));
            return true;
        }

        if (args[0].equalsIgnoreCase("reset")) {
            if (args.length < 2) {
                sender.sendMessage(gradient("&cИспользование: /voidac reset <ник>"));
                return true;
            }
            UUID uuid = Bukkit.getOfflinePlayer(args[1]).getUniqueId();
            resetViolationCount(uuid);
            sender.sendMessage(gradient("&aСчётчик нарушений игрока &d" + args[1] + " &aсброшен."));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            loadViolations();
            sender.sendMessage(gradient("&5&lVOIDMAIN &8» &fКонфигурация перезагружена."));
            return true;
        }

        sender.sendMessage(gradient("&dИспользование: /voidac <status|check|release|violations|reset|reload>"));
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
        private final int banDays;
        private final boolean wasInvulnerable;
        private final AtomicBoolean finished = new AtomicBoolean(false);
        private BukkitTask task;

        private PunishmentSession(Player player, String source, String check, int banDays) {
            this.player = player;
            this.uuid = player.getUniqueId();
            this.playerName = player.getName();
            this.source = source;
            this.check = check;
            this.banDays = banDays;
            this.lastLocation = player.getLocation().clone();
            this.wasInvulnerable = player.isInvulnerable();
            for (ItemStack item : player.getInventory().getContents()) {
                if (item != null && !item.getType().isAir() && item.getAmount() > 0) {
                    this.items.add(item.clone());
                }
            }
        }
    }

    private final class ManualCheckSession {
        private final Player player;
        private final String playerName;
        private final String caller;
        private final boolean wasInvulnerable;
        private final PotionEffect previousGlowing;
        private BukkitTask timeoutTask;

        private ManualCheckSession(Player player, String caller) {
            this.player = player;
            this.playerName = player.getName();
            this.caller = caller;
            this.wasInvulnerable = player.isInvulnerable();
            this.previousGlowing = player.getPotionEffect(PotionEffectType.GLOWING);
        }
    }
}
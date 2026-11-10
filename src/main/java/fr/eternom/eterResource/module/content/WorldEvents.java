package fr.eternom.eterResource.module.content;

import fr.eternom.eterLib.EterLib;
import fr.eternom.eterLib.helper.message.Messages;
import fr.eternom.eterLib.module.quest.QuestBridge;
import fr.eternom.eterResource.module.bonus.JobBonuses;
import fr.eternom.eterResource.module.theme.Themes.Theme;
import fr.eternom.eterResource.module.world.Camp;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * La vie d'un monde ressource :
 * - les événements, un à la fois, toutes les events.interval-minutes (÷ event-boost du thème) : ruée vers l'or (minerais
 *   doublés), forêt dorée (bûches : pommes, parfois dorées), pluie de météorite (un cratère plein de minerais rares),
 *   trésor caché (indices chaud / froid), défi chronométré (casser N blocs à temps), ouverture de l'End (le portail ne
 *   mène à l'End que pendant cette fenêtre, places limitées) ;
 * - la nuit plus dangereuse (monstres plus solides) mais un meilleur butin ;
 * - la ressource du jour (la même partout, change à minuit) : récoltée, elle donne plus ;
 * - le bonus de groupe (à plusieurs, une chance de récolte en plus) et le bonus de métier du thème.
 * Chaque événement prévient les quêtes (eterresource:event {event}, eterresource:meteor, eterresource:treasure,
 * eterresource:challenge). Pas de boss ici (plus tard, avec EterMob).
 */
public class WorldEvents implements Listener {

    public enum Kind { GOLD_RUSH, GOLDEN_FOREST, METEOR, TREASURE, CHALLENGE, END_WINDOW }

    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final List<Material> DAILY = List.of(Material.IRON_ORE, Material.COAL_ORE, Material.COPPER_ORE, Material.GOLD_ORE,
            Material.REDSTONE_ORE, Material.LAPIS_ORE, Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.DARK_OAK_LOG);

    private final JavaPlugin plugin;
    private final Messages messages;
    private final Camp camp;
    private final Theme theme;
    private final JobBonuses jobs;
    private Kind current;
    private long endsAt;
    private Location target;
    private int challengeGoal;
    private final Map<UUID, Integer> challengeProgress = new HashMap<>();
    private int endVisitors;

    public WorldEvents(JavaPlugin plugin, Messages messages, Camp camp, Theme theme, JobBonuses jobs) {
        this.plugin = plugin;
        this.messages = messages;
        this.camp = camp;
        this.theme = theme;
        this.jobs = jobs;
    }

    public void start() {
        long minutes = Math.max(5, Math.round(plugin.getConfig().getLong("events.interval-minutes", 30) / Math.max(0.1, theme.eventBoost())));
        Bukkit.getScheduler().runTaskTimer(plugin, this::next, minutes * 60 * 20 / 2, minutes * 60 * 20);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20, 20);
    }

    // ---------- Les événements ----------

    private void next() {
        if (current != null || Bukkit.getOnlinePlayers().isEmpty() || camp.center() == null) {
            return;
        }
        Kind[] kinds = Kind.values();
        Kind kind = kinds[ThreadLocalRandom.current().nextInt(kinds.length)];
        if (!plugin.getConfig().getBoolean("events.enabled." + kind.name().toLowerCase(Locale.ROOT).replace('_', '-'), true)) {
            return;
        }
        begin(kind);
    }

    /** Lancer un événement (aussi pour le staff : /eterresource event <type>). */
    public boolean begin(Kind kind) {
        if (current != null || camp.center() == null) {
            return false;
        }
        current = kind;
        endsAt = System.currentTimeMillis() + Math.max(1, plugin.getConfig().getLong("events.duration-minutes", 10)) * 60_000L;
        target = null;
        challengeProgress.clear();
        endVisitors = 0;
        String key = kind.name().toLowerCase(Locale.ROOT).replace('_', '-');
        switch (kind) {
            case METEOR -> target = meteor();
            case TREASURE -> target = treasure();
            case CHALLENGE -> challengeGoal = Math.max(10, plugin.getConfig().getInt("events.challenge-blocks", 200));
            default -> {
            }
        }
        if ((kind == Kind.METEOR || kind == Kind.TREASURE) && target == null) {
            plugin.getLogger().warning("Événement " + kind + " annulé : pas de place trouvée autour du camp");
            current = null;
            return false;
        }
        plugin.getLogger().info("Événement " + kind + (target == null ? "" : " en " + target.getBlockX() + " " + target.getBlockY() + " "
                + target.getBlockZ()));
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location where = target == null ? null : target;
            messages.send(player, "events." + key + ".start", "x", where == null ? "" : String.valueOf(round(where.getBlockX())),
                    "z", where == null ? "" : String.valueOf(round(where.getBlockZ())), "goal", String.valueOf(challengeGoal),
                    "direction", where == null ? "" : messages.plain(player, "places.direction." + Places.direction(
                            where.getX() - player.getLocation().getX(), where.getZ() - player.getLocation().getZ())));
            player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 0.7f);
            QuestBridge.signal(player, "eterresource:event", Map.of("event", key, "theme", theme.id()), 1);
        }
        return true;
    }

    private static long round(int coordinate) {
        return Math.round(coordinate / 50.0) * 50;
    }

    private void end(boolean silently) {
        if (current == null) {
            return;
        }
        String key = current.name().toLowerCase(Locale.ROOT).replace('_', '-');
        if (!silently) {
            Bukkit.getOnlinePlayers().forEach(player -> messages.send(player, "events." + key + ".end"));
        }
        current = null;
        target = null;
    }

    /** Ligne de la sidebar : l'événement en cours et son temps restant. */
    public Component sidebarLine(Player player) {
        if (current == null) {
            return null;
        }
        long left = Math.max(0, endsAt - System.currentTimeMillis()) / 1000;
        String key = current.name().toLowerCase(Locale.ROOT).replace('_', '-');
        return messages.get(player, "events." + key + ".sidebar", "time", EterLib.get().formatDuration(player, left),
                "progress", String.valueOf(challengeProgress.getOrDefault(player.getUniqueId(), 0)), "goal", String.valueOf(challengeGoal));
    }

    private void tick() {
        if (current == null) {
            return;
        }
        if (System.currentTimeMillis() >= endsAt) {
            end(false);
            return;
        }
        if (current == Kind.TREASURE && target != null) {
            // Chaud / froid pour ceux qui cherchent (à moins de 300 blocs)
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getWorld() == target.getWorld()) {
                    double distance = player.getLocation().distance(target);
                    if (distance < 300) {
                        String heat = distance < 15 ? "burning" : distance < 50 ? "hot" : distance < 120 ? "warm" : "cold";
                        messages.actionBar(player, "events.treasure.heat." + heat);
                    }
                }
            }
        }
        if (current == Kind.METEOR && target != null && ThreadLocalRandom.current().nextInt(3) == 0) {
            target.getWorld().spawnParticle(Particle.LAVA, target.clone().add(0.5, 2, 0.5), 6, 1.5, 1, 1.5, 0);
        }
    }

    /** Un cratère fumant, plein de minerais rares, entre 150 et 500 blocs du camp. */
    private Location meteor() {
        Location at = surface(150, 500);
        if (at == null) {
            return null;
        }
        World world = at.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int radius = 4;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    Block block = world.getBlockAt(at.getBlockX() + dx, at.getBlockY() + dy, at.getBlockZ() + dz);
                    if (d <= radius - 1 && dy >= -1) {
                        block.setType(Material.AIR, false);
                    } else if (d <= radius) {
                        double roll = random.nextDouble();
                        block.setType(roll < 0.05 ? Material.DIAMOND_ORE : roll < 0.15 ? Material.GOLD_ORE : roll < 0.25 ? Material.EMERALD_ORE
                                : roll < 0.6 ? Material.MAGMA_BLOCK : Material.BLACKSTONE, false);
                    }
                }
            }
        }
        world.getBlockAt(at.getBlockX(), at.getBlockY() - radius + 1, at.getBlockZ()).setType(Material.ANCIENT_DEBRIS, false);
        world.strikeLightningEffect(at);
        world.playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 4f, 0.6f);
        return at;
    }

    /** Un coffre enterré entre 100 et 400 blocs du camp. */
    private Location treasure() {
        Location at = surface(100, 400);
        if (at == null) {
            return null;
        }
        Block block = at.getWorld().getBlockAt(at.getBlockX(), at.getBlockY() - 3, at.getBlockZ());
        block.setType(Material.CHEST, false);
        if (block.getState() instanceof Chest chest) {
            for (String line : plugin.getConfig().getStringList("events.treasure-loot")) {
                String[] parts = line.trim().split("\\s+");
                Material material = Material.matchMaterial(parts[0]);
                if (material != null && material.isItem()) {
                    int amount = parts.length > 1 ? parseInt(parts[1], 1) : 1;
                    chest.getBlockInventory().addItem(ItemStack.of(material, Math.clamp(amount, 1, material.getMaxStackSize())));
                }
            }
        }
        return block.getLocation();
    }

    private Location surface(int min, int max) {
        Location center = camp.center();
        World world = center.getWorld();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 15; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = min + random.nextDouble() * (max - min);
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * distance);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * distance);
            Location probe = new Location(world, x, 64, z);
            if (!world.getWorldBorder().isInside(probe)) {
                continue;
            }
            Block top = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (!top.isLiquid()) {
                return top.getLocation();
            }
        }
        return null;
    }

    /** Le trésor ouvert : celui qui l'a trouvé est annoncé, l'événement s'arrête. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (current != Kind.TREASURE || target == null || !(event.getPlayer() instanceof Player player)
                || event.getInventory().getLocation() == null || !event.getInventory().getLocation().getBlock().getLocation().equals(target)) {
            return;
        }
        Bukkit.getOnlinePlayers().forEach(online -> messages.send(online, "events.treasure.found", "player", player.getName()));
        QuestBridge.signal(player, "eterresource:treasure", Map.of("theme", theme.id()), 1);
        end(true);
    }

    // ---------- Récoltes : événements, ressource du jour, groupe, métier ----------

    /** La ressource du jour (la même partout, change à minuit). */
    public Material dailyResource() {
        long day = LocalDate.now(ZONE).toEpochDay();
        return DAILY.get((int) Math.floorMod(day * 31 + 7, DAILY.size()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material type = block.getType();
        if (EterLib.get().getPlacedBlocks().isPlaced(block)) {
            return;
        }
        boolean ore = type.name().endsWith("_ORE");
        boolean log = Tag.LOGS.isTagged(type);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location drop = block.getLocation().add(0.5, 0.5, 0.5);
        int extra = 0;
        if (current == Kind.GOLD_RUSH && ore) {
            extra++;
        }
        if (current == Kind.GOLDEN_FOREST && log) {
            if (random.nextDouble() < 0.15) {
                block.getWorld().dropItemNaturally(drop, ItemStack.of(random.nextDouble() < 0.1 ? Material.GOLDEN_APPLE : Material.APPLE));
            }
        }
        if (oreFamily(type) == oreFamily(dailyResource()) && random.nextDouble() < 0.5) {
            extra++;
        }
        if ((ore || log) && nearbyPlayers(player) && random.nextDouble() < plugin.getConfig().getDouble("bonus.group-chance", 0.1)) {
            extra++;
        }
        double jobChance = jobs.of(player).map(bonus -> theme.jobBonus().getOrDefault(bonus.job(), 0.0)).orElse(0.0);
        if ((ore || log) && jobChance > 0 && random.nextDouble() < jobChance) {
            extra++;
        }
        if (extra > 0) {
            ItemStack tool = player.getInventory().getItemInMainHand();
            List<ItemStack> drops = List.copyOf(block.getDrops(tool, player));
            for (int i = 0; i < extra; i++) {
                drops.forEach(item -> block.getWorld().dropItemNaturally(drop, item.clone()));
            }
            player.spawnParticle(Particle.WAX_ON, drop, 6, 0.3, 0.3, 0.3, 0);
        }
        if (current == Kind.CHALLENGE) {
            int done = challengeProgress.merge(player.getUniqueId(), 1, Integer::sum);
            if (done == challengeGoal) {
                messages.send(player, "events.challenge.done");
                player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1f);
                EterLib.get().getActions().run(player, plugin.getConfig().getStringList("events.challenge-reward"));
                QuestBridge.signal(player, "eterresource:challenge", Map.of("theme", theme.id()), 1);
            }
        }
        if (current == Kind.METEOR && target != null && block.getWorld() == target.getWorld()
                && block.getLocation().distanceSquared(target) < 36 && ore) {
            QuestBridge.signal(player, "eterresource:meteor", Map.of("block", type.name().toLowerCase(Locale.ROOT)), 1);
        }
    }

    private static Material oreFamily(Material material) {
        String name = material.name();
        return name.startsWith("DEEPSLATE_") && name.endsWith("_ORE") ? Material.getMaterial(name.substring(10)) : material;
    }

    private static boolean nearbyPlayers(Player player) {
        for (Player other : player.getWorld().getPlayers()) {
            if (other != player && other.getLocation().distanceSquared(player.getLocation()) < 32 * 32) {
                return true;
            }
        }
        return false;
    }

    // ---------- La nuit ----------

    /** La nuit, les monstres de la surface sont plus solides (et donnent plus). */
    @EventHandler(ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.NATURAL || !(event.getEntity() instanceof Monster)
                || event.getEntity().getWorld().getEnvironment() != World.Environment.NORMAL || !night(event.getEntity().getWorld())) {
            return;
        }
        AttributeInstance health = event.getEntity().getAttribute(Attribute.MAX_HEALTH);
        double multiplier = Math.max(1, plugin.getConfig().getDouble("night.health-multiplier", 1.5));
        if (health != null) {
            health.setBaseValue(health.getBaseValue() * multiplier);
            event.getEntity().setHealth(health.getValue());
        }
        event.getEntity().addScoreboardTag("eterresource-night");
    }

    @EventHandler
    public void onDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (entity.getScoreboardTags().contains("eterresource-night") && entity.getKiller() != null) {
            double bonus = plugin.getConfig().getDouble("night.loot-bonus", 0.5);
            if (ThreadLocalRandom.current().nextDouble() < bonus) {
                List.copyOf(event.getDrops()).forEach(item -> event.getDrops().add(item.clone()));
            }
            event.setDroppedExp((int) Math.round(event.getDroppedExp() * (1 + bonus)));
        }
    }

    private static boolean night(World world) {
        long time = world.getTime();
        return time >= 13000 && time < 23000;
    }

    // ---------- L'End ----------

    /** L'End n'est ouvert que pendant sa fenêtre (événement), avec des places limitées. */
    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (event.getTo() == null || event.getTo().getWorld() == null
                || event.getTo().getWorld().getEnvironment() != World.Environment.THE_END || event.getPlayer().hasPermission("eterresource.admin")) {
            return;
        }
        int places = Math.max(1, plugin.getConfig().getInt("events.end-places", 10));
        if (current != Kind.END_WINDOW || endVisitors >= places) {
            event.setCancelled(true);
            messages.actionBar(event.getPlayer(), current == Kind.END_WINDOW ? "events.end-window.full" : "events.end-window.closed");
            return;
        }
        endVisitors++;
    }

    private static int parseInt(String text, int fallback) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Le lieu de l'événement en cours (météorite, trésor), null sinon. */
    public Location target() {
        return target;
    }

    public long endsAt() {
        return endsAt;
    }

    /** Arrêter l'événement en cours (staff). */
    public boolean stop() {
        if (current == null) {
            return false;
        }
        end(false);
        return true;
    }

    public Kind current() {
        return current;
    }
}
